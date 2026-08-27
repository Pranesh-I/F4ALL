"""Who is in frame, and whether it stays the same person.

Runs on per-frame pose counts and landmark geometry rather than raw pixels, so
it costs nothing beyond the extraction pass the scorer already needs.

Three questions:

* Is anyone there at all?
* Is more than one person there — a coach demonstrating, or someone else doing
  the test while the athlete watches?
* Did the subject change part-way through?
"""

from __future__ import annotations

from ..pose import LandmarkIndex, PoseFrame
from .findings import CheatCheck, CheatFinding, CheatReport, Severity

# A single stray detection is noise — a reflection, a poster, a passer-by at the
# edge of frame. Only a sustained second presence is worth a reviewer's time.
MULTI_PERSON_FRACTION = 0.15

# Below this fraction of frames containing a usable subject, the recording does
# not show a test being performed.
MIN_SUBJECT_FRACTION = 0.35

# Fractional change in apparent body size, between consecutive tracked frames,
# that no real body produces. A jump changes position, not size; a different
# person stepping in changes both.
SUBJECT_SWAP_SCALE_JUMP = 0.45


def check_subject(
    frames: list[PoseFrame],
    pose_counts: list[int] | None = None,
    *,
    report: CheatReport | None = None,
) -> CheatReport:
    report = report or CheatReport()

    if not frames:
        report.add(
            CheatFinding(
                check=CheatCheck.NO_SUBJECT,
                severity=Severity.HIGH,
                detail="No frames could be read from the recording",
            )
        )
        return report

    _check_presence(frames, report)

    if pose_counts:
        _check_multiple_people(frames, pose_counts, report)
    else:
        report.skip(
            CheatCheck.MULTIPLE_PEOPLE,
            "Per-frame pose counts unavailable",
        )

    _check_subject_continuity(frames, report)

    return report


def _has_subject(frame: PoseFrame) -> bool:
    """A torso the model is confident about is enough to call someone present."""
    return (
        frame.is_visible(LandmarkIndex.LEFT_HIP)
        or frame.is_visible(LandmarkIndex.RIGHT_HIP)
    ) and (
        frame.is_visible(LandmarkIndex.LEFT_SHOULDER)
        or frame.is_visible(LandmarkIndex.RIGHT_SHOULDER)
    )


def _check_presence(frames: list[PoseFrame], report: CheatReport) -> None:
    present = sum(1 for frame in frames if _has_subject(frame))
    fraction = present / len(frames)

    if fraction < MIN_SUBJECT_FRACTION:
        report.add(
            CheatFinding(
                check=CheatCheck.NO_SUBJECT,
                severity=Severity.HIGH,
                detail=(
                    f"A person is clearly visible in only {fraction:.0%} of the "
                    "recording, so the test cannot be confirmed from it"
                ),
                evidence={"subject_fraction": fraction},
            )
        )


def _check_multiple_people(
    frames: list[PoseFrame], pose_counts: list[int], report: CheatReport
) -> None:
    multi = [index for index, count in enumerate(pose_counts) if count > 1]

    if not multi:
        return

    fraction = len(multi) / len(pose_counts)

    if fraction < MULTI_PERSON_FRACTION:
        return

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
                "multi_person_fraction": fraction,
                "max_people": float(max(pose_counts)),
            },
        )
    )


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
                        evidence={"scale_change": change},
                    )
                )
                # One report is enough; a spliced video would otherwise produce
                # a finding per splice and bury the reviewer.
                return

        previous_scale = scale
        previous_index = index
