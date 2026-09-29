"""Cross-language parity between the mobile and server scorers.

## Why this is the most important test in the backend

The server auto-flags a submission when its score disagrees with the device's.
That mechanism is what makes an official result trustworthy — and it is also
the thing that breaks first if the Kotlin and Python implementations drift
apart. When they drift, the flag fires on athletes who did nothing wrong, and
the reviewer looking at the flag cannot tell a real discrepancy from an
implementation gap. The system's credibility degrades quietly.

Two implementations of the same algorithm in two languages *will* drift. The
only question is whether anyone notices before an athlete does.

So: `ParityFixtureExportTest.kt` writes canonical pose sequences plus the scores
the mobile implementation produces. This asserts the Python port reproduces them
exactly. If you change a threshold or an algorithm, regenerate the fixtures and
change both sides — this test is what makes forgetting impossible.

    cd mobile
    gradlew :app:testDebugUnitTest --tests "*ParityFixtureExportTest*"
"""

from __future__ import annotations

import csv
from pathlib import Path

import pytest

from app.verification.analyzers import (
    AttemptStatus,
    TestType,
    analyze_sequence,
    build_analyzer,
)
from app.verification.pose import decode_sequence

FIXTURE_DIR = Path(__file__).parent / "fixtures" / "parity"
MANIFEST = FIXTURE_DIR / "expected_scores.csv"

# Both implementations do the same float arithmetic in the same order, so the
# only expected difference is double formatting. Anything larger than this is a
# genuine behavioural divergence, not rounding.
SCORE_TOLERANCE = 0.001


def load_cases() -> list[dict[str, str]]:
    if not MANIFEST.exists():
        return []

    with MANIFEST.open(encoding="utf-8") as handle:
        rows = [line for line in handle if not line.startswith("#")]

    return list(csv.DictReader(rows))


CASES = load_cases()


@pytest.mark.skipif(not CASES, reason="Parity fixtures not generated")
@pytest.mark.parametrize("case", CASES, ids=[c["sequence"] for c in CASES])
def test_python_scorer_matches_kotlin(case: dict[str, str]) -> None:
    sequence_file = FIXTURE_DIR / case["sequence"]
    assert sequence_file.exists(), f"Missing fixture {sequence_file}"

    frames = decode_sequence(sequence_file.read_text(encoding="utf-8"))
    assert frames, "Fixture decoded to zero frames"

    test_type = TestType(case["test"])
    height = case["athlete_height_cm"]
    athlete_height_cm = float(height) if height else None

    analyzer = build_analyzer(test_type, athlete_height_cm)
    result = analyze_sequence(analyzer, frames)

    expected_status = case["expected_status"]
    expected_score = float(case["expected_score"])

    assert result.status.value == expected_status, (
        f"{case['sequence']}: mobile said {expected_status}, "
        f"server said {result.status.value} ({result.invalid_reason})"
    )

    fault_codes = [
        code
        for event in result.events
        if event.label in ("rep_rejected_form", "form_warning")
        for code in event.detail.split(",")
    ]

    expected_events = case.get("expected_events") or ""
    for pair in filter(None, expected_events.split(";")):
        label, expected_count = pair.split("=")
        if label.startswith("issue."):
            actual_count = fault_codes.count(label.removeprefix("issue."))
        else:
            actual_count = sum(1 for event in result.events if event.label == label)
        assert actual_count == int(expected_count), (
            f"{case['sequence']}: mobile recorded {expected_count} x {label}, "
            f"server recorded {actual_count}. Same score or not, the two "
            f"implementations disagree about why reps were refused."
        )

    if result.status is AttemptStatus.COMPLETE:
        assert result.score == pytest.approx(
            expected_score, abs=SCORE_TOLERANCE
        ), (
            f"{case['sequence']}: mobile scored {expected_score}, "
            f"server scored {result.score}. The two implementations have "
            f"drifted — the discrepancy flag will now fire on honest athletes."
        )


@pytest.mark.skipif(not CASES, reason="Parity fixtures not generated")
def test_fixture_set_covers_the_awkward_cases() -> None:
    """Parity on happy paths proves very little.

    Divergence shows up in the rejection rules — partial reps, too-fast reps,
    lost tracking — because that is where the two state machines have the most
    branching. A fixture set of only clean runs would pass while the
    implementations disagreed about everything that matters.
    """
    names = {case["sequence"] for case in CASES}

    assert any("partial" in name for name in names)
    assert any("tracking_loss" in name for name in names)
    assert any("fast" in name for name in names)
    assert any(case["expected_status"] == "INVALID" for case in CASES)

    tests_covered = {case["test"] for case in CASES}
    assert tests_covered == {test_type.value for test_type in TestType}

    # Every rep-rule rejection path has at least one fixture that exercises it.
    pairs = [
        pair
        for case in CASES
        for pair in (case.get("expected_events") or "").split(";")
        if pair
    ]
    for label in ("rep_rejected_form", "rep_rejected_wrong_arm", "form_warning"):
        assert any(
            pair.startswith(f"{label}=") and not pair.endswith("=0") for pair in pairs
        ), f"No parity fixture exercises {label}"


def test_thresholds_match_the_mobile_implementation() -> None:
    """Reads the Kotlin threshold file and compares the numbers directly.

    The parity fixtures catch drift in behaviour; this catches drift in intent,
    including for thresholds no fixture happens to exercise.
    """
    kotlin_file = (
        Path(__file__).resolve().parents[2]
        / "mobile/app/src/main/java/com/sai/sports/analyzer/AnalyzerThresholds.kt"
    )

    if not kotlin_file.exists():
        pytest.skip("Mobile source not available")

    source = kotlin_file.read_text(encoding="utf-8")

    from app.verification import thresholds

    # name in Kotlin -> value in Python
    checks = {
        "MIN_LANDMARK_VISIBILITY": thresholds.MIN_LANDMARK_VISIBILITY,
        "MAX_CONSECUTIVE_REJECTED_FRAMES": thresholds.MAX_CONSECUTIVE_REJECTED_FRAMES,
        "SMOOTHING_MIN_CUTOFF": thresholds.SMOOTHING_MIN_CUTOFF,
        "SMOOTHING_BETA": thresholds.SMOOTHING_BETA,
        "SMOOTHING_DERIVATIVE_CUTOFF": thresholds.SMOOTHING_DERIVATIVE_CUTOFF,
        "SITUP_DOWN_ENTER_ANGLE": thresholds.SITUP_DOWN_ENTER_ANGLE,
        "SITUP_UP_ENTER_ANGLE": thresholds.SITUP_UP_ENTER_ANGLE,
        "SITUP_PARTIAL_REP_ANGLE": thresholds.SITUP_PARTIAL_REP_ANGLE,
        "SITUP_MIN_REP_DURATION_MS": thresholds.SITUP_MIN_REP_DURATION_MS,
        "JUMP_CALIBRATION_FRAMES": thresholds.JUMP_CALIBRATION_FRAMES,
        "JUMP_CALIBRATION_STABILITY": thresholds.JUMP_CALIBRATION_STABILITY,
        "NOSE_HEIGHT_STATURE_RATIO": thresholds.NOSE_HEIGHT_STATURE_RATIO,
        "JUMP_TAKEOFF_CM": thresholds.JUMP_TAKEOFF_CM,
        "JUMP_LANDING_CM": thresholds.JUMP_LANDING_CM,
        "JUMP_MIN_FLIGHT_MS": thresholds.JUMP_MIN_FLIGHT_MS,
        "JUMP_MAX_FLIGHT_MS": thresholds.JUMP_MAX_FLIGHT_MS,
        "JUMP_MIN_PLAUSIBLE_CM": thresholds.JUMP_MIN_PLAUSIBLE_CM,
        "JUMP_MAX_PLAUSIBLE_CM": thresholds.JUMP_MAX_PLAUSIBLE_CM,
        "REP_START_HOLD_FRAMES": thresholds.REP_START_HOLD_FRAMES,
        "FORM_FAULT_MIN_FRAMES": thresholds.FORM_FAULT_MIN_FRAMES,
        "SQUAT_EXTENDED_ANGLE": thresholds.SQUAT_EXTENDED_ANGLE,
        "SQUAT_DEPTH_ANGLE": thresholds.SQUAT_DEPTH_ANGLE,
        "SQUAT_PARTIAL_ANGLE": thresholds.SQUAT_PARTIAL_ANGLE,
        "SQUAT_MIN_REP_DURATION_MS": thresholds.SQUAT_MIN_REP_DURATION_MS,
        "SQUAT_MAX_TORSO_LEAN_DEG": thresholds.SQUAT_MAX_TORSO_LEAN_DEG,
        "PUSHUP_EXTENDED_ANGLE": thresholds.PUSHUP_EXTENDED_ANGLE,
        "PUSHUP_DEPTH_ANGLE": thresholds.PUSHUP_DEPTH_ANGLE,
        "PUSHUP_PARTIAL_ANGLE": thresholds.PUSHUP_PARTIAL_ANGLE,
        "PUSHUP_MIN_REP_DURATION_MS": thresholds.PUSHUP_MIN_REP_DURATION_MS,
        "PUSHUP_MIN_BODY_LINE_ANGLE": thresholds.PUSHUP_MIN_BODY_LINE_ANGLE,
        "PUSHUP_MAX_BODY_TILT_DEG": thresholds.PUSHUP_MAX_BODY_TILT_DEG,
        "CURL_EXTENDED_ANGLE": thresholds.CURL_EXTENDED_ANGLE,
        "CURL_DEPTH_ANGLE": thresholds.CURL_DEPTH_ANGLE,
        "CURL_PARTIAL_ANGLE": thresholds.CURL_PARTIAL_ANGLE,
        "CURL_MIN_REP_DURATION_MS": thresholds.CURL_MIN_REP_DURATION_MS,
        "CURL_MAX_ELBOW_FLARE_DEG": thresholds.CURL_MAX_ELBOW_FLARE_DEG,
        "LUNGE_EXTENDED_ANGLE": thresholds.LUNGE_EXTENDED_ANGLE,
        "LUNGE_DEPTH_ANGLE": thresholds.LUNGE_DEPTH_ANGLE,
        "LUNGE_PARTIAL_ANGLE": thresholds.LUNGE_PARTIAL_ANGLE,
        "LUNGE_MIN_REP_DURATION_MS": thresholds.LUNGE_MIN_REP_DURATION_MS,
        "LUNGE_MIN_STANCE_RATIO": thresholds.LUNGE_MIN_STANCE_RATIO,
        "LUNGE_MAX_KNEE_TRAVEL_RATIO": thresholds.LUNGE_MAX_KNEE_TRAVEL_RATIO,
        "LUNGE_MAX_TORSO_LEAN_DEG": thresholds.LUNGE_MAX_TORSO_LEAN_DEG,
        "SQUAT_MAX_KNEE_TRAVEL_RATIO": thresholds.SQUAT_MAX_KNEE_TRAVEL_RATIO,
        "FOOT_MAX_SHIFT_RATIO": thresholds.FOOT_MAX_SHIFT_RATIO,
        "CURL_MAX_BODY_SWAY_RATIO": thresholds.CURL_MAX_BODY_SWAY_RATIO,
    }

    import re

    mismatches: list[str] = []

    for name, python_value in checks.items():
        match = re.search(
            rf"const val {name}\s*=\s*([0-9_]+\.?[0-9]*)[fL]?", source
        )
        if match is None:
            mismatches.append(f"{name}: not found in AnalyzerThresholds.kt")
            continue

        kotlin_value = float(match.group(1).replace("_", ""))

        if abs(kotlin_value - float(python_value)) > 1e-9:
            mismatches.append(
                f"{name}: Kotlin={kotlin_value} Python={python_value}"
            )

    assert not mismatches, "Threshold drift between implementations:\n" + "\n".join(
        mismatches
    )
