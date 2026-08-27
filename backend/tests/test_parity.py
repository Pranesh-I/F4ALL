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
    assert tests_covered == {"SIT_UPS", "VERTICAL_JUMP"}


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
