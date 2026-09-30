"""Who is in frame, and whether it stays the same person.

Runs on per-frame pose counts and landmark geometry rather than raw pixels, so
it costs nothing beyond the extraction pass the scorer already needs.

Three questions:

* Is anyone there at all?
* Is more than one person there — a coach demonstrating, or someone else doing
  the test while the athlete watches?
* Did the subject change part-way through?

The answers are aggregated over every frame into one person outcome (Sprint
12), recorded whether or not anything was flagged. No single frame decides it:
a flag needs a sustained share of the recording.
"""

from __future__ import annotations

from enum import Enum

from ..pose import LandmarkIndex, PoseFrame
from .findings import CheatCheck, CheatFinding, CheatReport, Severity

# A single stray detection is noise — a reflection, a poster, a passer-by at the
# edge of frame. Only a sustained second presence is worth a reviewer's time.
MULTI_PERSON_FRACTION = 0.15

# Below this fraction of frames containing a usable subject, the recording does
# not show a test being performed.
MIN_SUBJECT_FRACTION = 0.35

# At or above MIN_SUBJECT_FRACTION but below this, someone is there but too
# intermittently to call the recording clearly of one athlete. Reported as
# UNCERTAIN in the person summary, not flagged: poor tracking already lowers
# the scorer's confidence, which has its own flag.
CONFIDENT_SUBJECT_FRACTION = 0.6

# Fractional change in apparent body size, between consecutive tracked frames,
# that no real body produces. A jump changes position, not size; a different
# person stepping in changes both.
SUBJECT_SWAP_SCALE_JUMP = 0.45

_TORSO = [
    LandmarkIndex.LEFT_SHOULDER,
    LandmarkIndex.RIGHT_SHOULDER,
    LandmarkIndex.LEFT_HIP,
    LandmarkIndex.RIGHT_HIP,
]


class PersonOutcome(str, Enum):
    VALID_PERSON = "VALID_PERSON"
    NO_PERSON = "NO_PERSON"
    MULTIPLE_PEOPLE = "MULTIPLE_PEOPLE"
    UNCERTAIN = "UNCERTAIN"


def check_subject(
    frames: list[PoseFrame],
    pose_counts: list[int] | None = None,
    *,
    report: CheatReport | None = None,
    multi_person_fraction: float = MULTI_PERSON_FRACTION,
    min_subject_fraction: float = MIN_SUBJECT_FRACTION,
    confident_subject_fraction: float = CONFIDENT_SUBJECT_FRACTION,
) -> CheatReport:
    report = report or CheatReport()

    if not frames:
        report.add(
            CheatFinding(
                check=CheatCheck.NO_SUBJECT,
                severity=Severity.HIGH,
                detail="No frames could be read from the recording",
                evidence={"frames_sampled": 0},
            )
        )
        report.summarise(
            "person", {"outcome": PersonOutcome.NO_PERSON.value, "frames_sampled": 0}
        )
        return report

    present = _check_presence(frames, report, min_subject_fraction)

    multi: list[int] = []
    if pose_counts:
        multi = _check_multiple_people(
            frames, pose_counts, report, multi_person_fraction
        )
    else:
        report.skip(
            CheatCheck.MULTIPLE_PEOPLE,
            "Per-frame pose counts unavailable",
        )

    _check_subject_continuity(frames, report)

    report.summarise(
        "person",
        person_summary(
            frames,
            present,
            multi,
            counts_available=bool(pose_counts),
            multi_person_fraction=multi_person_fraction,
            min_subject_fraction=min_subject_fraction,
            confident_subject_fraction=confident_subject_fraction,
        ),
    )
    return report


def person_summary(
    frames: list[PoseFrame],
    present: list[int],
    multi: list[int],
    *,
    counts_available: bool,
    multi_person_fraction: float = MULTI_PERSON_FRACTION,
    min_subject_fraction: float = MIN_SUBJECT_FRACTION,
    confident_subject_fraction: float = CONFIDENT_SUBJECT_FRACTION,
) -> dict:
    """The aggregate person outcome and the counts behind it.

    ``present`` and ``multi`` are frame indices: frames with a usable torso,
    and frames where the model found more than one pose.
    """
    sampled = len(frames)
    present_fraction = len(present) / sampled if sampled else 0.0
    multi_fraction = len(multi) / sampled if sampled else 0.0

    if present_fraction < min_subject_fraction:
        outcome = PersonOutcome.NO_PERSON
    elif counts_available and multi_fraction >= multi_person_fraction:
        outcome = PersonOutcome.MULTIPLE_PEOPLE
    elif not counts_available or present_fraction < confident_subject_fraction:
        outcome = PersonOutcome.UNCERTAIN
    else:
        outcome = PersonOutcome.VALID_PERSON

    # How sure the pose model was of the torso, over the frames it found one.
    confidence = (
        sum(frames[index].mean_visibility(_TORSO) for index in present) / len(present)
        if present
        else None
    )

    return {
        "outcome": outcome.value,
        "frames_sampled": sampled,
        "frames_with_person": len(present),
        "frames_without_person": sampled - len(present),
        "frames_with_multiple_people": len(multi) if counts_available else None,
        "person_fraction": round(present_fraction, 3),
        "no_person_fraction": round(1.0 - present_fraction, 3),
        "multiple_people_fraction": (
            round(multi_fraction, 3) if counts_available else None
        ),
        "mean_torso_confidence": (
            round(confidence, 3) if confidence is not None else None
        ),
    }


def _has_subject(frame: PoseFrame) -> bool:
    """A torso the model is confident about is enough to call someone present."""
    return (
        frame.is_visible(LandmarkIndex.LEFT_HIP)
        or frame.is_visible(LandmarkIndex.RIGHT_HIP)
    ) and (
        frame.is_visible(LandmarkIndex.LEFT_SHOULDER)
        or frame.is_visible(LandmarkIndex.RIGHT_SHOULDER)
    )


def _check_presence(
    frames: list[PoseFrame], report: CheatReport, minimum: float
) -> list[int]:
    present = [index for index, frame in enumerate(frames) if _has_subject(frame)]
    fraction = len(present) / len(frames)

    if fraction < minimum:
        report.add(
            CheatFinding(
                check=CheatCheck.NO_SUBJECT,
                severity=Severity.HIGH,
                detail=(
                    f"A person is clearly visible in only {fraction:.0%} of the "
                    "recording, so the test cannot be confirmed from it"
                ),
                evidence={
                    "subject_fraction": round(fraction, 3),
                    "frames_sampled": len(frames),
                    "frames_without_person": len(frames) - len(present),
                    "threshold": minimum,
                },
            )
        )
    return present


def _check_multiple_people(
    frames: list[PoseFrame],
    pose_counts: list[int],
    report: CheatReport,
    minimum: float,
) -> list[int]:
    multi = [index for index, count in enumerate(pose_counts) if count > 1]

    if not multi:
        return multi

    fraction = len(multi) / len(pose_counts)

    if fraction < minimum:
        return multi

    first_index = multi[0]
    at_ms = frames[first_index].timestamp_ms if first_index < len(frames) else None

    report.add(
        CheatFinding(
            check=CheatCheck.MULTIPLE_PEOPLE,
            # Medium, not high. A parent holding a child's feet during sit-ups
            # is completely legitimate and extremely common — this needs a human
            # to look, not an automatic penalty.
            severity=Severity.MEDIUM,
            detail=(
                f"More than one person is visible in {fraction:.0%} of the "
                "recording; confirm the right athlete was measured"
            ),
            at_ms=at_ms,
            evidence={
                "multi_person_fraction": round(fraction, 3),
                "frames_sampled": len(pose_counts),
                "frames_with_multiple_people": len(multi),
                "max_people": max(pose_counts),
                "first_frame_index": first_index,
                "threshold": minimum,
            },
        )
    )
    return multi


def _torso_scale(frame: PoseFrame) -> float | None:
    """Shoulder-to-hip distance: a proxy for apparent body size."""
    shoulder = frame.get(LandmarkIndex.LEFT_SHOULDER)
    hip = frame.get(LandmarkIndex.LEFT_HIP)

    if shoulder is None or hip is None:
        return None
    if not frame.is_visible(LandmarkIndex.LEFT_SHOULDER):
        return None
    if not frame.is_visible(LandmarkIndex.LEFT_HIP):
        return None

    scale = abs(hip.y - shoulder.y)
    return scale if scale > 0.01 else None


def _check_subject_continuity(frames: list[PoseFrame], report: CheatReport) -> None:
    """Detects the subject being swapped mid-recording.

    Compares apparent torso size between consecutive tracked frames. A person
    moving changes where they are; a different person stepping in changes how
    big they appear, discontinuously.

    Deliberately compares only frames where tracking was continuous — a gap in
    tracking is not evidence of a swap, and treating it as such would flag every
    recording where the athlete briefly left frame.
    """
    previous_scale: float | None = None
    previous_index: int | None = None

    for index, frame in enumerate(frames):
        scale = _torso_scale(frame)

        if scale is None:
            previous_scale = None
            previous_index = None
            continue

        if (
            previous_scale is not None
            and previous_index is not None
            and index == previous_index + 1
        ):
            change = abs(scale - previous_scale) / previous_scale

            if change > SUBJECT_SWAP_SCALE_JUMP:
                report.add(
                    CheatFinding(
                        check=CheatCheck.SUBJECT_SWAPPED,
                        severity=Severity.HIGH,
                        detail=(
                            f"The person in frame changes size by {change:.0%} "
                            f"in a single frame at "
                            f"{frame.timestamp_ms / 1000:.1f}s, which can mean "
                            "the subject was swapped or the footage was spliced"
                        ),
                        at_ms=frame.timestamp_ms,
                        evidence={
                            "scale_change": round(change, 3),
                            "frame_index": index,
                            "threshold": SUBJECT_SWAP_SCALE_JUMP,
                        },
                    )
                )
                # One report is enough; a spliced video would otherwise produce
                # a finding per splice and bury the reviewer.
                return

        previous_scale = scale
        previous_index = index
