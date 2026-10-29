"""Accuracy validation against human ground truth (Sprint 22).

    python -m app.cli accuracy                     # docs/accuracy/dataset.csv
    python -m app.cli accuracy --manifest my.csv --report out.md

Each row of the manifest is one recording, the result a human evaluator
measured from it, and the conditions it was recorded in. For a video, the
command runs the **server's own pipeline** on it — the same extraction,
scorer and integrity checks the verification worker runs, without a database
— and records what it measured next to the human result. A field run (shuttle,
endurance) has no video; its server result is entered in ``ai_result`` from
the dashboard, against a hand-timed ``human_result``.

The report (``docs/accuracy/REPORT.md`` by default) gives, per test, the
plan's numbers: dataset size, mean and maximum error, reps over- and
under-counted, form and cheat-detection agreement where those were labelled,
the device and condition spread, and the cases that missed the target. A row
without a human result is listed with its AI result and **left out of every
number**: no accuracy is claimed for a recording nobody checked.
"""

from __future__ import annotations

import csv
import logging
import statistics
from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path

from ..verification.thresholds import (
    TARGET_JUMP_TOLERANCE_CM,
    TARGET_REP_TOLERANCE_REPS,
    TARGET_SITUP_TOLERANCE_REPS,
)

logger = logging.getLogger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_MANIFEST = REPO_ROOT / "docs" / "accuracy" / "dataset.csv"
DEFAULT_REPORT = REPO_ROOT / "docs" / "accuracy" / "REPORT.md"

REP_TESTS = {"SQUATS", "PUSH_UPS", "BICEP_CURLS", "LUNGES", "SIT_UPS"}
MEASURED_TESTS = {"VERTICAL_JUMP", "SHUTTLE_RUN", "ENDURANCE_RUN"}
VIDEO_TESTS = REP_TESTS | {"VERTICAL_JUMP"}

# The target each test is judged against. Field runs have no agreed target
# yet: the plan leaves it to the calibration runs (docs/SPRINT-18.md).
TARGETS = {
    "SIT_UPS": TARGET_SITUP_TOLERANCE_REPS,
    "SQUATS": TARGET_REP_TOLERANCE_REPS,
    "PUSH_UPS": TARGET_REP_TOLERANCE_REPS,
    "BICEP_CURLS": TARGET_REP_TOLERANCE_REPS,
    "LUNGES": TARGET_REP_TOLERANCE_REPS,
    "VERTICAL_JUMP": TARGET_JUMP_TOLERANCE_CM,
}
UNITS = {"VERTICAL_JUMP": "cm", "SHUTTLE_RUN": "s", "ENDURANCE_RUN": "s"}

COLUMNS = [
    "video",  # path relative to the manifest; blank for a field run
    "test",
    "human_result",  # reps, cm or seconds; blank until an evaluator has measured it
    "evaluator",
    "height_cm",  # required for VERTICAL_JUMP
    "person",  # an anonymous id, to count distinct people
    "device",
    "lighting",  # e.g. indoor, outdoor-sun, dim
    "distance_m",
    "camera_angle",  # side-on, facing, angled
    "clothing",
    "background",
    "human_form",  # optional: good | faulty, the evaluator's judgement
    "cheat_label",  # optional: none, or the trick used (e.g. replayed, sped_up)
    "ai_result",  # filled by the run; enter by hand for a field run
    "notes",
]


@dataclass
class Case:
    row: dict[str, str]
    ai_result: float | None = None
    ai_status: str | None = None
    ai_form_score: int | None = None
    ai_flags: list[str] = field(default_factory=list)
    error: str | None = None

    @property
    def test(self) -> str:
        return self.row["test"].strip().upper()

    @property
    def human(self) -> float | None:
        value = (self.row.get("human_result") or "").strip()
        return float(value) if value else None

    @property
    def labelled(self) -> bool:
        return self.human is not None and self.ai_result is not None

    @property
    def difference(self) -> float | None:
        return self.ai_result - self.human if self.labelled else None


# ---------------------------------------------------------------------------
# Running the pipeline
# ---------------------------------------------------------------------------

Runner = Callable[
    [Path, str, float | None], tuple[float | None, str, int | None, list[str]]
]


def server_pipeline(models_dir: Path) -> Runner:
    """The verification worker's measurement, as a function of one video."""
    from ..verification.analyzers import analyze_sequence, build_analyzer
    from ..verification.cheat import pipeline as cheat_pipeline
    from ..verification.extractor import analyze_video
    from ..verification.validation import verifiable_test_type

    def run(video: Path, test: str, height_cm: float | None):
        analysis = analyze_video(video, models_dir)
        result = analyze_sequence(
            build_analyzer(verifiable_test_type(test), height_cm), analysis.frames
        )
        report = cheat_pipeline.run_checks(
            video_path=video,
            test_code=test,
            pose_frames=analysis.frames,
            frame_hashes=analysis.frame_hashes,
            frame_signatures=analysis.frame_signatures,
            pose_counts=analysis.pose_counts,
            video_metadata=analysis.metadata,
            reference_face_path=None,
            models_dir=models_dir,
            include_face_check=False,  # no registration photo in the dataset
            frame_changes=analysis.frame_changes,
        )
        cheat_pipeline.run_analysis_checks(
            report,
            pose_frames=analysis.frames,
            server_result=result,
            video_metadata=analysis.metadata,
        )
        flags = sorted({finding.check.value for finding in report.findings})
        score = result.score if result.is_usable else None
        status = result.status.value + (
            f" ({result.invalid_reason})" if result.invalid_reason else ""
        )
        return score, status, result.form_score, flags

    return run


def load_manifest(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        lines = [line for line in handle if not line.lstrip().startswith("#")]
    return [
        {key: (value or "").strip() for key, value in row.items()}
        for row in csv.DictReader(lines)
        if any((value or "").strip() for value in row.values())
    ]


def evaluate(
    rows: Iterable[dict[str, str]], base: Path, runner: Runner | None
) -> list[Case]:
    cases = []
    for row in rows:
        case = Case(row=row)
        cases.append(case)
        given = row.get("ai_result", "")
        if case.test not in VIDEO_TESTS or not row.get("video"):
            # A field run: its server result comes from the dashboard.
            case.ai_result = float(given) if given else None
            case.ai_status = "entered" if given else "missing"
            continue
        if runner is None:
            case.ai_result = float(given) if given else None
            case.ai_status = "from manifest" if given else "not run"
            continue
        height = float(row["height_cm"]) if row.get("height_cm") else None
        try:
            score, status, form, flags = runner(base / row["video"], case.test, height)
        except Exception as error:  # one bad file must not stop the dataset
            logger.exception("Could not process %s", row["video"])
            case.error = f"{type(error).__name__}: {error}"[:200]
            continue
        case.ai_result, case.ai_status = score, status
        case.ai_form_score, case.ai_flags = form, flags
    return cases


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------


@dataclass
class TestMetrics:
    test: str
    recordings: int
    labelled: int
    people: int
    mean_abs_error: float | None
    mean_signed_error: float | None
    max_abs_error: float | None
    within_target: int
    target: float | None
    over_counted: int | None  # reps the AI added (false positives)
    under_counted: int | None  # reps the AI missed (false negatives)
    human_total: float | None
    form_agree: int
    form_disagree: int
    cheat_true: int
    cheat_false: int
    cheat_missed: int
    devices: Counter
    conditions: Counter
    misses: list[Case]
    unlabelled: list[Case]
    failed: list[Case]

    @property
    def false_positive_rate(self) -> float | None:
        if not self.human_total or self.over_counted is None:
            return None
        return self.over_counted / self.human_total

    @property
    def false_negative_rate(self) -> float | None:
        if not self.human_total or self.under_counted is None:
            return None
        return self.under_counted / self.human_total


def metrics_for(test: str, cases: list[Case]) -> TestMetrics:
    labelled = [c for c in cases if c.labelled]
    errors = [abs(c.difference) for c in labelled]
    target = TARGETS.get(test)
    reps = test in REP_TESTS
    form_cases = [
        c for c in labelled if c.row.get("human_form") and c.ai_form_score is not None
    ]
    # The app calls a rep set "good form" at 80+ (FormSummary); the evaluator
    # judges the whole recording.
    form_agree = sum(
        1
        for c in form_cases
        if (c.ai_form_score >= 80) == (c.row["human_form"].lower() == "good")
    )
    cheat_cases = [c for c in cases if c.row.get("cheat_label") and c.ai_status]
    cheat_true = cheat_false = cheat_missed = 0
    for case in cheat_cases:
        tricked = case.row["cheat_label"].lower() != "none"
        flagged = bool(case.ai_flags)
        if tricked and flagged:
            cheat_true += 1
        elif tricked:
            cheat_missed += 1
        elif flagged:
            cheat_false += 1

    return TestMetrics(
        test=test,
        recordings=len(cases),
        labelled=len(labelled),
        people=len({c.row.get("person") for c in labelled if c.row.get("person")}),
        mean_abs_error=round(statistics.mean(errors), 2) if errors else None,
        mean_signed_error=round(statistics.mean(c.difference for c in labelled), 2)
        if labelled
        else None,
        max_abs_error=round(max(errors), 2) if errors else None,
        within_target=sum(1 for e in errors if target is not None and e <= target),
        target=target,
        over_counted=int(sum(max(0, c.difference) for c in labelled)) if reps else None,
        under_counted=int(sum(max(0, -c.difference) for c in labelled)) if reps else None,
        human_total=sum(c.human for c in labelled) if reps else None,
        form_agree=form_agree,
        form_disagree=len(form_cases) - form_agree,
        cheat_true=cheat_true,
        cheat_false=cheat_false,
        cheat_missed=cheat_missed,
        devices=Counter(c.row.get("device") or "unknown" for c in cases),
        conditions=Counter(
            " / ".join(filter(None, (c.row.get("lighting"), c.row.get("camera_angle"))))
            or "unrecorded"
            for c in cases
        ),
        misses=[c for c in labelled if target is not None and abs(c.difference) > target],
        unlabelled=[c for c in cases if c.human is None and not c.error],
        failed=[c for c in cases if c.error],
    )


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def _fmt(value, unit: str = "") -> str:
    if value is None:
        return "—"
    if isinstance(value, float):
        value = f"{value:g}"
    return f"{value}{unit}"


def render(cases: list[Case], manifest: Path) -> str:
    by_test: dict[str, list[Case]] = {}
    for case in cases:
        by_test.setdefault(case.test, []).append(case)
    labelled = sum(1 for c in cases if c.labelled)

    lines = [
        "# Accuracy Report",
        "",
        "Generated by `python -m app.cli accuracy` from "
        f"`{manifest.name}`: {len(cases)} recording(s), **{labelled} with a human "
        "result**. Only those count in the numbers below. Regenerate it after "
        "adding recordings; do not edit it by hand.",
        "",
        "| Test | Recordings | With human result | People | Mean error | Max error"
        " | Within target | Target |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    all_metrics = [metrics_for(test, rows) for test, rows in sorted(by_test.items())]
    for m in all_metrics:
        unit = UNITS.get(m.test, " reps")
        unit = unit if unit.startswith(" ") else f" {unit}"
        lines.append(
            f"| {m.test} | {m.recordings} | {m.labelled} | {m.people}"
            f" | {_fmt(m.mean_abs_error, unit)} | {_fmt(m.max_abs_error, unit)}"
            f" | {f'{m.within_target}/{m.labelled}' if m.labelled else '—'}"
            f" | {'±' + _fmt(m.target, unit) if m.target is not None else 'not set'} |"
        )
    if not labelled:
        lines += [
            "",
            "**No accuracy claim can be made yet:** no recording has a human result.",
        ]

    for m in all_metrics:
        unit = UNITS.get(m.test, "reps")
        lines += ["", f"## {m.test}", ""]
        lines.append(f"- Dataset size: {m.recordings} ({m.labelled} with a human result)")
        lines.append(f"- Distinct people: {m.people}")
        lines.append(
            f"- Mean error: {_fmt(m.mean_abs_error)} {unit}"
            f" (signed {_fmt(m.mean_signed_error)}: positive means the AI measured more)"
        )
        lines.append(f"- Max error: {_fmt(m.max_abs_error)} {unit}")
        if m.test in REP_TESTS:
            fp, fn = m.false_positive_rate, m.false_negative_rate
            lines.append(
                f"- Reps over-counted (false positives): {_fmt(m.over_counted)}"
                f"{f' ({fp:.1%} of human reps)' if fp is not None else ''}"
            )
            lines.append(
                f"- Reps missed (false negatives): {_fmt(m.under_counted)}"
                f"{f' ({fn:.1%} of human reps)' if fn is not None else ''}"
            )
        if m.form_agree or m.form_disagree:
            lines.append(
                f"- Form: {m.form_agree} agree, {m.form_disagree} disagree"
                " with the evaluator"
            )
        if m.cheat_true or m.cheat_false or m.cheat_missed:
            lines.append(
                f"- Cheat detection: {m.cheat_true} caught, {m.cheat_false} false,"
                f" {m.cheat_missed} missed"
            )
        lines.append(
            "- Devices: " + ", ".join(f"{k} ({v})" for k, v in m.devices.most_common())
        )
        lines.append(
            "- Conditions: "
            + ", ".join(f"{k} ({v})" for k, v in m.conditions.most_common())
        )
        if m.misses:
            lines += ["", "Outside the target:", ""]
            lines += [
                f"- `{c.row.get('video') or '(field run)'}`: human {c.human:g},"
                f" AI {c.ai_result:g} ({c.row.get('notes') or 'no notes'})"
                for c in m.misses
            ]
        if m.unlabelled:
            lines += [
                "",
                "Waiting for a human result (AI result recorded, not counted):",
                "",
            ]
            lines += [
                f"- `{c.row.get('video')}`: AI {_fmt(c.ai_result)} — {c.ai_status}"
                f"{'; flags: ' + ', '.join(c.ai_flags) if c.ai_flags else ''}"
                for c in m.unlabelled
            ]
        if m.failed:
            lines += ["", "Could not be processed:", ""]
            lines += [f"- `{c.row.get('video')}`: {c.error}" for c in m.failed]
    return "\n".join(lines) + "\n"


def write_results(cases: list[Case], manifest: Path) -> Path:
    """The manifest with the AI columns filled in, next to it."""
    out = manifest.with_name(manifest.stem + ".results.csv")
    with out.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[*COLUMNS, "ai_status", "ai_form_score", "ai_flags", "error"],
            extrasaction="ignore",
        )
        writer.writeheader()
        for case in cases:
            writer.writerow(
                {
                    **case.row,
                    "ai_result": _fmt(case.ai_result)
                    if case.ai_result is not None
                    else "",
                    "ai_status": case.ai_status or "",
                    "ai_form_score": case.ai_form_score
                    if case.ai_form_score is not None
                    else "",
                    "ai_flags": " ".join(case.ai_flags),
                    "error": case.error or "",
                }
            )
    return out
