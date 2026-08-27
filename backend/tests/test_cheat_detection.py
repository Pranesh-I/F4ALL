"""Unit tests for the integrity checks.

These use hand-built hash sequences and synthetic pose frames, so they pin the
detection logic without needing video or models. `test_tampered_videos.py`
covers the end-to-end Definition of Done against real rendered footage.

A recurring theme: every test that asserts something IS detected has a sibling
asserting an honest recording is NOT. False positives here are not cosmetic —
each one puts a real athlete in a review queue and makes the queue less useful
for the cases that matter.
"""

from __future__ import annotations

import random

from app.verification.cheat.findings import CheatCheck, CheatReport, Severity
from app.verification.cheat.frames import (
    MIN_MEAN_ADJACENT_MOTION,
    check_frames,
    find_abrupt_cuts,
    find_looped_runs,
    hamming,
    mean_adjacent_motion,
)
from app.verification.cheat.metadata import VideoMetadata, check_metadata
from app.verification.cheat.subject import check_subject
from app.verification.pose import LandmarkIndex, PoseFrame, PosePoint

LANDMARK_COUNT = 33

# Hashes are 256-bit; test fixtures must span the same width or distances are
# not comparable to the production thresholds.
HASH_BITS = 256


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def timestamps(count: int, interval_ms: int = 33) -> list[int]:
    return [index * interval_ms for index in range(count)]


def drifting_hashes(count: int, seed: int = 7) -> list[int]:
    """Hashes that change a little each frame, like a real moving scene.

    A random walk, not a deterministic bit rotation — an earlier version of this
    helper XOR-ed bit `index % 64` and cycled with period 64, manufacturing
    genuine repetition and making the loop detector look wrong when it was
    correctly reporting what the fixture actually contained.
    """
    generator = random.Random(seed)
    values = []
    current = generator.getrandbits(HASH_BITS)
    for _ in range(count):
        # ~2% of bits per frame: the scale of real frame-to-frame motion.
        for _ in range(generator.randint(4, 8)):
            current ^= 1 << generator.randrange(HASH_BITS)
        values.append(current)
    return values


def repetitive_exercise_hashes(
    reps: int = 12, seed: int = 11, rest_frames: int = 10
) -> list[int]:
    """A sit-up test: the athlete returns to the same rest position every rep.

    This is the shape of an HONEST recording and the most dangerous false
    positive in the whole check. The rest frames between reps are near-identical
    across the entire video against an unchanging background.
    """
    generator = random.Random(seed)
    rest = generator.getrandbits(HASH_BITS)
    values: list[int] = []

    for _ in range(reps):
        # Rest position: nearly identical every time, give or take sensor noise.
        for _ in range(rest_frames):
            noisy = rest
            if generator.random() < 0.4:
                noisy ^= 1 << generator.randrange(HASH_BITS)
            values.append(noisy)

        # The rep itself: the athlete moves, so the picture genuinely changes.
        current = rest
        for _ in range(20):
            for _ in range(6):
                current ^= 1 << generator.randrange(HASH_BITS)
            values.append(current)

    return values


def pose_frame(
    timestamp_ms: int,
    *,
    visible: bool = True,
    torso: float = 0.2,
    hip_y: float = 0.6,
) -> PoseFrame:
    visibility = 0.9 if visible else 0.1
    points = [PosePoint(0.5, 0.5, 0.0, visibility) for _ in range(LANDMARK_COUNT)]

    shoulder = PosePoint(0.5, hip_y - torso, 0.0, visibility)
    hip = PosePoint(0.5, hip_y, 0.0, visibility)

    points[LandmarkIndex.LEFT_SHOULDER] = shoulder
    points[LandmarkIndex.RIGHT_SHOULDER] = shoulder
    points[LandmarkIndex.LEFT_HIP] = hip
    points[LandmarkIndex.RIGHT_HIP] = hip

    return PoseFrame(timestamp_ms=timestamp_ms, points=points)


# ---------------------------------------------------------------------------
# Frame consistency
# ---------------------------------------------------------------------------


def test_hamming_distance():
    assert hamming(0b0000, 0b0000) == 0
    assert hamming(0b1010, 0b0000) == 2
    assert hamming(0b1111, 0b0000) == 4


def test_a_copied_segment_is_detected():
    original = drifting_hashes(60)
    # Frames 0..34 pasted in again later — roughly a second of copied footage.
    tampered = original + drifting_hashes(20, seed=0xAAAA) + original[:35]

    matches = find_looped_runs(tampered)

    assert matches, "A repeated 35-frame run should be detected"
    assert matches[0].length >= 20


def test_an_honest_recording_is_not_flagged_as_looped():
    """The false positive that would matter most.

    A sit-up test returns to the same position twenty times against an
    unchanging background. If that reads as a loop, every honest submission gets
    flagged and the signal becomes worthless.
    """
    assert find_looped_runs(drifting_hashes(300)) == []


def test_a_real_sit_up_test_is_not_flagged_as_looped():
    """The most dangerous false positive in the whole system.

    A sit-up athlete returns to the same rest position on every rep against an
    unchanging background, so runs of near-identical frames genuinely repeat
    throughout an honest recording. Flagging that would put every legitimate
    submission in the review queue and make the queue useless for the cases
    that matter.

    The guard is that a repeated run must contain internal MOVEMENT. A repeat of
    a motionless scene is a motionless scene, reported separately.
    """
    assert find_looped_runs(repetitive_exercise_hashes()) == []


def test_near_still_frames_are_not_a_loop_of_themselves():
    """An athlete standing still during jump calibration."""
    still = [0x0F0F0F0F0F0F0F0F] * 40
    moving = drifting_hashes(100, seed=0x1234)

    # Consecutive identical frames must not count as a repeat, or every
    # calibration period would be flagged.
    matches = find_looped_runs(still + moving)

    assert all(match.repeat_index - match.source_index >= 15 for match in matches)


def test_abrupt_cut_is_detected():
    before = [0] * 20
    after = [(1 << HASH_BITS) - 1] * 20  # every bit flipped: a scene change

    cuts = find_abrupt_cuts(before + after)

    assert cuts == [20]


def test_smooth_motion_is_not_a_cut():
    assert find_abrupt_cuts(drifting_hashes(200)) == []


def test_static_video_is_detected():
    report = check_frames([0x1234567812345678] * 120, timestamps(120))

    assert any(f.check is CheatCheck.STATIC_VIDEO for f in report.findings)


def test_motion_measure_separates_still_from_moving():
    """Asserted against the flagging threshold, not an invented number.

    What matters is the decision the value drives: a frozen scene must fall
    below the threshold and a moving one must sit clearly above it.
    """
    frozen = mean_adjacent_motion([0x1111111111111111] * 50)
    moving = mean_adjacent_motion(drifting_hashes(50))

    assert frozen < MIN_MEAN_ADJACENT_MOTION
    assert moving > MIN_MEAN_ADJACENT_MOTION * 3


def test_a_moving_recording_is_not_flagged_as_static():
    report = check_frames(drifting_hashes(200), timestamps(200))
    assert not any(f.check is CheatCheck.STATIC_VIDEO for f in report.findings)


def test_loop_finding_names_where_to_look():
    original = drifting_hashes(60)
    tampered = original + drifting_hashes(20, seed=0xBEEF) + original[:35]

    report = check_frames(tampered, timestamps(len(tampered)))
    looped = [f for f in report.findings if f.check is CheatCheck.LOOPED_FRAMES]

    assert looped
    # A reviewer handed a 60-second video and the word "looped" has to watch all
    # of it. A timestamp is the difference between usable and not.
    assert looped[0].at_ms is not None
    assert looped[0].severity is Severity.HIGH


def test_too_few_frames_is_skipped_not_passed():
    report = check_frames([1, 2, 3, 4, 5], timestamps(5))

    assert report.is_clean
    # "We could not look" must never be recorded as "we looked and it was fine".
    assert CheatCheck.LOOPED_FRAMES.value in report.skipped


# ---------------------------------------------------------------------------
# Metadata
# ---------------------------------------------------------------------------


def ordinary_metadata(**overrides) -> VideoMetadata:
    base = {
        "duration_seconds": 45.0,
        "width": 480,
        "height": 854,
        "fps": 30.0,
        "file_size_bytes": 2_400_000,
    }
    base.update(overrides)
    return VideoMetadata(**base)


def test_ordinary_recording_passes_metadata_checks():
    assert check_metadata(ordinary_metadata(), "SIT_UPS").is_clean


def test_video_too_short_for_the_test_is_flagged():
    report = check_metadata(ordinary_metadata(duration_seconds=2.0), "SIT_UPS")

    findings = [f for f in report.findings if f.check is CheatCheck.DURATION_IMPLAUSIBLE]
    assert findings
    assert findings[0].severity is Severity.HIGH


def test_overlong_video_is_low_severity():
    """Usually someone forgot to press stop, not fraud."""
    report = check_metadata(ordinary_metadata(duration_seconds=900.0), "SIT_UPS")

    findings = [f for f in report.findings if f.check is CheatCheck.DURATION_IMPLAUSIBLE]
    assert findings
    assert findings[0].severity is Severity.LOW


def test_duration_bounds_differ_per_test():
    # 5s is too short for sit-ups but fine for a jump.
    assert not check_metadata(ordinary_metadata(duration_seconds=5.0), "SIT_UPS").is_clean
    assert check_metadata(
        ordinary_metadata(duration_seconds=5.0), "VERTICAL_JUMP"
    ).is_clean


def test_unexpected_resolution_is_flagged():
    report = check_metadata(ordinary_metadata(width=64, height=64), "SIT_UPS")
    assert any(f.check is CheatCheck.RESOLUTION_UNEXPECTED for f in report.findings)


def test_generous_resolution_bounds_accept_real_devices():
    """Encoders round dimensions; older phones report odd sizes.

    Being strict here would flag honest athletes on cheap hardware — precisely
    the users this platform exists for.
    """
    for width, height in [(480, 854), (480, 640), (486, 864), (720, 1280)]:
        report = check_metadata(
            ordinary_metadata(width=width, height=height), "SIT_UPS"
        )
        assert not any(
            f.check is CheatCheck.RESOLUTION_UNEXPECTED for f in report.findings
        ), f"{width}x{height} should be accepted"


def test_implausible_framerate_is_flagged():
    assert not check_metadata(ordinary_metadata(fps=240.0), "SIT_UPS").is_clean
    assert not check_metadata(ordinary_metadata(fps=3.0), "SIT_UPS").is_clean


def test_low_bitrate_suggests_re_encoding():
    report = check_metadata(
        ordinary_metadata(file_size_bytes=20_000, duration_seconds=45.0), "SIT_UPS"
    )
    assert not report.is_clean


def test_missing_metadata_is_skipped_not_passed():
    report = check_metadata(
        VideoMetadata(None, None, None, None, None), "SIT_UPS"
    )
    assert report.is_clean
    assert CheatCheck.DURATION_IMPLAUSIBLE.value in report.skipped
    assert CheatCheck.RESOLUTION_UNEXPECTED.value in report.skipped


# ---------------------------------------------------------------------------
# Subject
# ---------------------------------------------------------------------------


def test_single_athlete_recording_is_clean():
    frames = [pose_frame(index * 33) for index in range(120)]
    report = check_subject(frames, [1] * 120)
    assert report.is_clean


def test_sustained_second_person_is_flagged():
    frames = [pose_frame(index * 33) for index in range(120)]
    counts = [1] * 60 + [2] * 60

    report = check_subject(frames, counts)
    findings = [f for f in report.findings if f.check is CheatCheck.MULTIPLE_PEOPLE]

    assert findings
    # Medium, not high: a parent holding a child's feet during sit-ups is
    # completely legitimate and extremely common.
    assert findings[0].severity is Severity.MEDIUM


def test_a_brief_passer_by_is_not_flagged():
    frames = [pose_frame(index * 33) for index in range(120)]
    counts = [1] * 118 + [2, 2]

    report = check_subject(frames, counts)

    assert not any(f.check is CheatCheck.MULTIPLE_PEOPLE for f in report.findings)


def test_video_with_no_visible_person_is_flagged():
    frames = [pose_frame(index * 33, visible=False) for index in range(60)]

    report = check_subject(frames, [0] * 60)

    assert any(f.check is CheatCheck.NO_SUBJECT for f in report.findings)


def test_subject_swap_is_detected():
    """A different person stepping in changes apparent body size discontinuously."""
    first = [pose_frame(index * 33, torso=0.20) for index in range(40)]
    second = [pose_frame((40 + index) * 33, torso=0.34) for index in range(40)]

    report = check_subject(first + second, [1] * 80)

    assert any(f.check is CheatCheck.SUBJECT_SWAPPED for f in report.findings)


def test_normal_movement_is_not_a_subject_swap():
    """A jump changes position, not apparent size."""
    frames = [
        pose_frame(index * 33, torso=0.20, hip_y=0.6 - 0.15 * (index / 60))
        for index in range(60)
    ]

    report = check_subject(frames, [1] * 60)

    assert not any(f.check is CheatCheck.SUBJECT_SWAPPED for f in report.findings)


def test_tracking_gap_is_not_a_subject_swap():
    """Losing the athlete briefly is not evidence they were replaced."""
    before = [pose_frame(index * 33, torso=0.20) for index in range(30)]
    gap = [pose_frame((30 + index) * 33, visible=False) for index in range(10)]
    after = [pose_frame((40 + index) * 33, torso=0.34) for index in range(30)]

    report = check_subject(before + gap + after, [1] * 70)

    assert not any(f.check is CheatCheck.SUBJECT_SWAPPED for f in report.findings)


def test_missing_pose_counts_are_skipped_not_passed():
    frames = [pose_frame(index * 33) for index in range(60)]

    report = check_subject(frames, None)

    assert CheatCheck.MULTIPLE_PEOPLE.value in report.skipped


# ---------------------------------------------------------------------------
# Report aggregation
# ---------------------------------------------------------------------------


def test_report_tracks_highest_severity():
    from app.verification.cheat.findings import CheatFinding

    report = CheatReport()
    report.add(CheatFinding(CheatCheck.ABRUPT_CUT, Severity.LOW, "minor"))
    report.add(CheatFinding(CheatCheck.LOOPED_FRAMES, Severity.HIGH, "major"))

    assert report.highest_severity is Severity.HIGH


def test_clean_report_says_so_plainly():
    assert CheatReport().is_clean
    assert "No integrity concerns" in CheatReport().summary()


def test_skipped_checks_are_distinguishable_from_passed_ones():
    report = CheatReport()
    report.skip(CheatCheck.FACE_MISMATCH, "No registration photo on file")

    # The report is "clean" in the sense that nothing was found, but a reviewer
    # can still see that identity was never actually checked.
    assert report.is_clean
    assert report.skipped[CheatCheck.FACE_MISMATCH.value]
