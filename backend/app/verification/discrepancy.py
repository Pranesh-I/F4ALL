"""Compares the device's provisional score with the server's own.

This is where the project's first invariant is enforced: *the on-device score is
provisional, the server score is truth*. The device number is never accepted;
it is only ever compared, and a large disagreement raises a flag for a human.

Deliberately conservative about what it concludes. A discrepancy is evidence
that something needs looking at — a modified client, but equally a phone that
scored a blurry video badly, or a server pass over an over-compressed upload.
The flag says "these disagree and by how much", never "this athlete cheated".
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from .analyzers import AnalyzerResult, AttemptStatus, TestType


class Verdict(str, Enum):
    VERIFIED = "verified"
    FLAGGED = "flagged"
    REJECTED = "rejected"


class FlagReason(str, Enum):
    SCORE_DISCREPANCY = "score_discrepancy"
    SERVER_COULD_NOT_SCORE = "server_could_not_score"
    LOW_TRACKING_QUALITY = "low_tracking_quality"
    DEVICE_SCORE_MISSING = "device_score_missing"


@dataclass(frozen=True)
class DiscrepancyOutcome:
    verdict: Verdict
    server_score: float | None
    device_score: float | None
    difference: float | None
    tolerance: float
    reason: FlagReason | None = None
    detail: str = ""
    severity: str = "medium"


def tolerance_for(test_type: TestType, settings) -> float:
    if test_type is TestType.SIT_UPS:
        return settings.discrepancy_tolerance_reps
    return settings.discrepancy_tolerance_cm


def evaluate(
    *,
    server_result: AnalyzerResult,
    device_score: float | None,
    settings,
) -> DiscrepancyOutcome:
    tolerance = tolerance_for(server_result.test_type, settings)

    # The server could not score the video at all. This is NOT a rejection of
    # the athlete — a video the server cannot read is a video a human should
    # look at, and the recording may be perfectly valid.
    if server_result.status is not AttemptStatus.COMPLETE:
        return DiscrepancyOutcome(
            verdict=Verdict.FLAGGED,
            server_score=None,
            device_score=device_score,
            difference=None,
            tolerance=tolerance,
            reason=FlagReason.SERVER_COULD_NOT_SCORE,
            detail=server_result.invalid_reason or "Server scoring failed",
            severity="high",
        )

    if device_score is None:
        return DiscrepancyOutcome(
            verdict=Verdict.FLAGGED,
            server_score=server_result.score,
            device_score=None,
            difference=None,
            tolerance=tolerance,
            reason=FlagReason.DEVICE_SCORE_MISSING,
            detail="Submission carried no provisional score to compare against",
            severity="medium",
        )

    difference = abs(server_result.score - device_score)

    if difference > tolerance:
        # Severity scales with how far apart they are. A reviewer triaging a
        # queue needs to know which disagreements are worth opening first.
        severity = "high" if difference > tolerance * 2 else "medium"
        return DiscrepancyOutcome(
            verdict=Verdict.FLAGGED,
            server_score=server_result.score,
            device_score=device_score,
            difference=difference,
            tolerance=tolerance,
            reason=FlagReason.SCORE_DISCREPANCY,
            detail=(
                f"Device reported {device_score:g} {server_result.unit}, "
                f"server measured {server_result.score:g} {server_result.unit} "
                f"(difference {difference:g}, tolerance {tolerance:g})"
            ),
            severity=severity,
        )

    # Scores agree, but the server barely saw the athlete. Accepting a
    # confident-looking number derived from mostly-unusable frames is how a bad
    # result reaches an official with nothing marking it as shaky.
    if server_result.confidence < LOW_CONFIDENCE_THRESHOLD:
        return DiscrepancyOutcome(
            verdict=Verdict.FLAGGED,
            server_score=server_result.score,
            device_score=device_score,
            difference=difference,
            tolerance=tolerance,
            reason=FlagReason.LOW_TRACKING_QUALITY,
            detail=(
                f"Scores agree but server tracking quality was "
                f"{server_result.confidence:.0%} "
                f"({server_result.frames_rejected} frames unusable)"
            ),
            severity="low",
        )

    return DiscrepancyOutcome(
        verdict=Verdict.VERIFIED,
        server_score=server_result.score,
        device_score=device_score,
        difference=difference,
        tolerance=tolerance,
    )


LOW_CONFIDENCE_THRESHOLD = 0.55
