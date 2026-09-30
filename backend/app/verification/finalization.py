"""The machine verdict on a submission, and the record that explains it.

One function decides VERIFIED, FLAGGED or REJECTED, from every input the
decision may depend on: validation, the server's own score, the comparison
with the device, the integrity checks, and processing errors. It is pure — no
database, no video — so each rule is tested directly, and the worker only
persists what it returns.

The decision is stored with the list of checks it was built from. "Flagged"
alone tells a reviewer nothing; "comparison failed: device 14, server 9,
tolerance 2" and "integrity passed" is what makes the verdict reproducible.

The verdict never sets the official score. VERIFIED means the server re-scored
the video and nothing needs a human; the number becomes official only when an
official approves it (routers/dashboard.py).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from .analyzers import AnalyzerResult, AttemptStatus, TestType
from .cheat.findings import CheatReport
from .discrepancy import DiscrepancyOutcome, FlagReason, Verdict
from .validation import ValidationCode, ValidationFailure

# Bump when extraction, scoring or decision rules change, so a stored verdict
# can be traced to the rules that produced it. 1.0 was the unversioned Sprint
# 5-6 pipeline; 1.1 adds validation, the lifecycle and this module; 1.2 adds
# the Sprint 12 integrity checks (duplicated frames, timestamps, playback
# speed, impossible movement, duplicate submissions) and flag evidence.
PIPELINE_VERSION = "f4all-verify/1.2"

# Primary reason when every check passed.
REASON_AGREED = "server_and_device_agree"
REASON_PROCESSING_ERROR = "processing_error"
REASON_INTEGRITY_CHECK_FAILED = "integrity_check_failed"

# Severity of the flag recorded for a failed validation.
_VALIDATION_SEVERITY = {
    ValidationCode.VIDEO_MISSING_FROM_STORAGE: "high",
    ValidationCode.TEST_TYPE_NOT_VERIFIABLE: "medium",
    ValidationCode.ATHLETE_HEIGHT_UNKNOWN: "medium",
}


class CheckName(str, Enum):
    VALIDATION = "validation"
    SERVER_SCORING = "server_scoring"
    COMPARISON = "comparison"
    INTEGRITY = "integrity"
    PROCESSING = "processing"


class CheckOutcome(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    # Not run, because an earlier check already decided the verdict.
    SKIPPED = "skipped"


@dataclass(frozen=True)
class Check:
    name: CheckName
    outcome: CheckOutcome
    code: str | None = None
    detail: str | None = None

    def as_dict(self) -> dict:
        return {
            "name": self.name.value,
            "outcome": self.outcome.value,
            "code": self.code,
            "detail": self.detail,
        }


@dataclass(frozen=True)
class FlagSpec:
    """A flag the verdict asks to be recorded: why, in detail, how urgent.

    ``evidence`` is the measurements behind it (Sprint 12), persisted in
    ``flags.evidence`` so the flag can be audited and thresholds tuned.
    """

    reason: str
    detail: str
    severity: str
    evidence: dict | None = None


@dataclass
class FinalDecision:
    verdict: Verdict
    reason: str
    checks: list[Check] = field(default_factory=list)
    flags: list[FlagSpec] = field(default_factory=list)

    @property
    def status_value(self) -> str:
        # Verdict values are TestResultStatus values by construction.
        return self.verdict.value

    def checks_record(self) -> list[dict]:
        return [check.as_dict() for check in self.checks]


def decide(
    *,
    validation_failure: ValidationFailure | None = None,
    processing_error: str | None = None,
    server_result: AnalyzerResult | None = None,
    comparison: DiscrepancyOutcome | None = None,
    cheat_report: CheatReport | None = None,
) -> FinalDecision:
    """The verdict, in order of precedence.

    1. A failed validation decides alone: REJECTED when nobody could verify
       the submission, FLAGGED when a human still can.
    2. A processing error that survived its retries: FLAGGED.
    3. Otherwise the server scored the video: VERIFIED only if scoring
       succeeded, the device agreed within tolerance, tracking was good
       enough, and every integrity check passed. Anything else is FLAGGED,
       with one flag per failing check.
    """
    if validation_failure is not None:
        return _from_validation(validation_failure)

    if processing_error is not None:
        return FinalDecision(
            verdict=Verdict.FLAGGED,
            reason=REASON_PROCESSING_ERROR,
            checks=[
                Check(CheckName.VALIDATION, CheckOutcome.PASSED),
                Check(
                    CheckName.PROCESSING,
                    CheckOutcome.FAILED,
                    FlagReason.SERVER_COULD_NOT_SCORE.value,
                    processing_error,
                ),
                *_skipped(
                    CheckName.SERVER_SCORING, CheckName.COMPARISON, CheckName.INTEGRITY
                ),
            ],
            flags=[
                FlagSpec(
                    FlagReason.SERVER_COULD_NOT_SCORE.value,
                    processing_error,
                    "high",
                    {"signal": "processing_error"},
                )
            ],
        )

    if server_result is None or comparison is None:
        raise ValueError("A scored decision needs the server result and comparison")

    cheat_report = cheat_report or CheatReport()
    checks = [
        Check(CheckName.VALIDATION, CheckOutcome.PASSED),
        Check(CheckName.PROCESSING, CheckOutcome.PASSED),
    ]
    flags: list[FlagSpec] = []

    scored = server_result.status is AttemptStatus.COMPLETE
    checks.append(
        Check(CheckName.SERVER_SCORING, CheckOutcome.PASSED)
        if scored
        else Check(
            CheckName.SERVER_SCORING,
            CheckOutcome.FAILED,
            FlagReason.SERVER_COULD_NOT_SCORE.value,
            server_result.invalid_reason,
        )
    )

    compared_ok = comparison.verdict is Verdict.VERIFIED
    if not scored:
        # Nothing to compare against; the scoring failure is the flag.
        checks.append(Check(CheckName.COMPARISON, CheckOutcome.SKIPPED))
    else:
        checks.append(
            Check(CheckName.COMPARISON, CheckOutcome.PASSED)
            if compared_ok
            else Check(
                CheckName.COMPARISON,
                CheckOutcome.FAILED,
                comparison.reason.value if comparison.reason else None,
                comparison.detail,
            )
        )

    if not compared_ok:
        # discrepancy.evaluate folds a scoring failure into its own outcome,
        # so this one flag covers both the scoring and the comparison checks.
        flags.append(
            FlagSpec(
                comparison.reason.value if comparison.reason else "unknown",
                comparison.detail,
                comparison.severity,
                comparison_evidence(server_result, comparison),
            )
        )

    # Integrity is independent of the score: a tampered video can be scored
    # perfectly and agree with the device exactly, because both measured the
    # same fake.
    integrity_ok = cheat_report.is_clean and not cheat_report.errors
    if integrity_ok:
        checks.append(Check(CheckName.INTEGRITY, CheckOutcome.PASSED))
    else:
        codes = [finding.check.value for finding in cheat_report.findings]
        if cheat_report.errors:
            codes.append(REASON_INTEGRITY_CHECK_FAILED)
        checks.append(
            Check(
                CheckName.INTEGRITY,
                CheckOutcome.FAILED,
                ",".join(codes),
                cheat_report.summary(),
            )
        )
        for finding in cheat_report.findings:
            detail = finding.detail
            if finding.at_ms is not None:
                detail = f"{detail} (at {finding.at_ms / 1000:.1f}s)"
            flags.append(
                FlagSpec(
                    finding.check.value,
                    detail,
                    finding.severity.value,
                    {**finding.evidence, "at_ms": finding.at_ms},
                )
            )
        if cheat_report.errors:
            # Crashed, so never examined: "we did not look" is not "clean".
            flags.append(
                FlagSpec(
                    REASON_INTEGRITY_CHECK_FAILED,
                    "Some integrity checks could not run on this video ("
                    + ", ".join(sorted(cheat_report.errors))
                    + "), so it was not examined for those problems",
                    "low",
                    {"signal": "processing_error", "checks": cheat_report.errors},
                )
            )

    if compared_ok and integrity_ok:
        return FinalDecision(Verdict.VERIFIED, REASON_AGREED, checks, flags)

    return FinalDecision(Verdict.FLAGGED, primary_reason(flags), checks, flags)


_SEVERITY_RANK = {"low": 0, "medium": 1, "high": 2}


def primary_reason(flags: list[FlagSpec]) -> str:
    """The most severe flag's reason; the earliest one when severities tie.

    Every flag is kept — this only chooses which one names the verdict, so a
    LOW flag found first cannot stand in front of a HIGH one found later.
    """
    return max(
        enumerate(flags),
        key=lambda item: (_SEVERITY_RANK.get(item[1].severity, 0), -item[0]),
    )[1].reason


def _from_validation(failure: ValidationFailure) -> FinalDecision:
    verdict = Verdict.REJECTED if failure.rejects else Verdict.FLAGGED
    return FinalDecision(
        verdict=verdict,
        reason=failure.code.value,
        checks=[
            Check(
                CheckName.VALIDATION,
                CheckOutcome.FAILED,
                failure.code.value,
                failure.detail,
            ),
            *_skipped(
                CheckName.PROCESSING,
                CheckName.SERVER_SCORING,
                CheckName.COMPARISON,
                CheckName.INTEGRITY,
            ),
        ],
        # Recorded as a flag either way, so the reviewer's screen — which
        # lists flags — shows why the machine stopped.
        flags=[
            FlagSpec(
                failure.code.value,
                failure.detail,
                _VALIDATION_SEVERITY.get(failure.code, "high"),
                {"signal": "validation", "disposition": failure.disposition.value},
            )
        ],
    )


def _skipped(*names: CheckName) -> list[Check]:
    return [Check(name, CheckOutcome.SKIPPED) for name in names]


def comparison_evidence(
    server_result: AnalyzerResult, comparison: DiscrepancyOutcome
) -> dict:
    """The mobile/server mismatch as numbers: what each side said, and the limit."""
    return {
        "signal": "mobile_server_comparison",
        "mobile_value": comparison.device_score,
        "server_value": comparison.server_score,
        "difference": comparison.difference,
        "tolerance": comparison.tolerance,
        "unit": server_result.unit,
        "server_confidence": round(server_result.confidence, 3),
    }


# ---------------------------------------------------------------------------
# Snapshots persisted with the verdict
# ---------------------------------------------------------------------------


def _split_score(test_type: TestType | None, score: float | None) -> dict:
    """The same number under the name that means something for this test."""
    counts_reps = test_type.counts_reps if test_type is not None else None
    return {
        "rep_count": int(score) if score is not None and counts_reps else None,
        "measurement": score if score is not None and counts_reps is False else None,
    }


def mobile_snapshot(
    *,
    test_code: str,
    unit: str,
    provisional_score: float | None,
    form_score: int | None,
) -> dict:
    """What the phone claimed. Recorded, compared, never trusted."""
    try:
        test_type = TestType(test_code)
    except ValueError:
        test_type = None
    return {
        "authoritative": False,
        "score": provisional_score,
        "unit": unit,
        **_split_score(test_type, provisional_score),
        "form_score": form_score,
    }


def server_snapshot(
    server_result: AnalyzerResult,
    comparison: DiscrepancyOutcome,
    *,
    video: dict | None = None,
    pipeline: dict | None = None,
) -> dict:
    """What the server measured, and how it compared."""
    scored = server_result.status is AttemptStatus.COMPLETE
    score = server_result.score if scored else None
    return {
        "authoritative": True,
        "status": server_result.status.value,
        "score": score,
        "unit": server_result.unit,
        **_split_score(server_result.test_type, score),
        "form_score": server_result.form_score,
        "confidence": round(server_result.confidence, 3),
        "frames_analyzed": server_result.frames_analyzed,
        "frames_rejected": server_result.frames_rejected,
        "invalid_reason": server_result.invalid_reason,
        "rep_attempts": rep_attempts(server_result),
        "comparison": {
            "verdict": comparison.verdict.value,
            "reason": comparison.reason.value if comparison.reason else None,
            "difference": comparison.difference,
            "tolerance": comparison.tolerance,
        },
        "video": video,
        "pipeline": pipeline,
    }


def rep_attempts(server_result: AnalyzerResult) -> dict | None:
    if not server_result.test_type.counts_reps:
        return None
    labels = [event.label for event in server_result.events]
    return {
        "counted": labels.count("rep_counted"),
        "rejected_partial": labels.count("rep_rejected_partial"),
        "rejected_too_fast": labels.count("rep_rejected_too_fast"),
        "rejected_form": labels.count("rep_rejected_form"),
        "rejected_wrong_arm": labels.count("rep_rejected_wrong_arm"),
    }

