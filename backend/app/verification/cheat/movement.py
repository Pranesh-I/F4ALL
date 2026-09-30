"""Movement no human body produces.

Consumes the scorer's own output rather than re-scoring:

* **Reps faster than the physical minimum.** Each rep scorer already refuses
  a rep quicker than its exercise's minimum duration
  (``thresholds.*_MIN_REP_DURATION_MS``) and records it as
  ``rep_rejected_too_fast``. One such rep is jitter; a large share of them is
  what sped-up footage, or a video of someone else's fast movement, produces.
* **Landmarks that teleport and stay.** Hips moving faster than any hip moves
  in these tests. A pose-model glitch jumps and snaps straight back; a splice
  or a different person jumps and stays, which is what is reported.
"""

from __future__ import annotations

import math

from .. import thresholds as scoring
from ..analyzers import AnalyzerResult, TestType
from ..pose import LandmarkIndex, PoseFrame
from .findings import CheatCheck, CheatFinding, CheatReport, Severity

# Too-fast reps before it is worth a reviewer's time: at least this many, and
# at least this share of every rep attempted.
MIN_FAST_REPS = 3
FAST_REP_FRACTION = 0.3

# Hip speed, in torso lengths per second, that no athlete reaches here. A
# vertical jump leaves the ground at ~3 m/s, the fastest hip movement in any of
# these tests; 15 torso lengths/s is 6-7.5 m/s for a 0.4-0.5 m torso. As a
# speed rather than a per-frame distance, it holds at any frame rate.
# Measured: a real squat recording peaks at 5.6; splicing the athlete a third
# of the frame sideways reaches 24 (0.8 torso lengths in one 33 ms frame).
# The synthetic jump fixtures reach 27 — they are not physically realistic.
MAX_HIP_SPEED = 15.0

# ...and does not undo itself within this many frames. A tracking glitch jumps
# out and straight back: the hips return (within half the jump) to where they
# were, or the jump lands where they just came from. A splice or a swapped
# subject stays away, while the athlete keeps moving normally.
TELEPORT_RETURN_FRAMES = 5

# Frames further apart than this are not "consecutive" for speed purposes; a
# gap in the recording is the timestamp check's business.
MAX_FRAME_GAP_MS = 100

_MIN_REP_MS = {
    TestType.SIT_UPS: scoring.SITUP_MIN_REP_DURATION_MS,
    TestType.SQUATS: scoring.SQUAT_MIN_REP_DURATION_MS,
    TestType.PUSH_UPS: scoring.PUSHUP_MIN_REP_DURATION_MS,
    TestType.BICEP_CURLS: scoring.CURL_MIN_REP_DURATION_MS,
    TestType.LUNGES: scoring.LUNGE_MIN_REP_DURATION_MS,
}

_REFUSED = (
    "rep_rejected_partial",
    "rep_rejected_too_fast",
    "rep_rejected_form",
    "rep_rejected_wrong_arm",
)


def check_movement(
    frames: list[PoseFrame],
    server_result: AnalyzerResult | None,
    *,
    aspect_ratio: float = 1.0,
    report: CheatReport | None = None,
    fast_rep_fraction: float = FAST_REP_FRACTION,
    max_hip_speed: float = MAX_HIP_SPEED,
) -> CheatReport:
    """``aspect_ratio`` is width / height, so x and y distances are comparable."""
    report = report or CheatReport()
    summary: dict = {}

    if server_result is not None:
        _check_rep_speed(server_result, report, summary, fast_rep_fraction)
    _check_teleports(frames, report, summary, aspect_ratio, max_hip_speed)

    report.summarise("movement", summary)
    return report


def _check_rep_speed(
    server_result: AnalyzerResult, report: CheatReport, summary: dict, fraction: float
) -> None:
    if not server_result.test_type.counts_reps:
        return
    labels = [event.label for event in server_result.events]
    too_fast = labels.count("rep_rejected_too_fast")
    refused = sum(labels.count(label) for label in _REFUSED)
    attempted = labels.count("rep_counted") + refused
    summary["too_fast_reps"] = too_fast
    summary["attempted_reps"] = attempted

    if too_fast < MIN_FAST_REPS or attempted == 0 or too_fast / attempted < fraction:
        return

    minimum = _MIN_REP_MS.get(server_result.test_type)
    first = next(e for e in server_result.events if e.label == "rep_rejected_too_fast")
    report.add(
        CheatFinding(
            check=CheatCheck.IMPOSSIBLE_MOVEMENT,
            severity=Severity.MEDIUM,
            detail=(
                f"{too_fast} of {attempted} reps were completed faster than a "
                "person can do this exercise, and were not counted. That can "
                "mean sped-up footage, or poor tracking — watch those reps"
            ),
            at_ms=first.timestamp_ms,
            evidence={
                "signal": "rep_speed",
                "too_fast_reps": too_fast,
                "attempted_reps": attempted,
                "fraction": round(too_fast / attempted, 3),
                "min_rep_duration_ms": minimum,
                "threshold_fraction": fraction,
            },
        )
    )


_TORSO_VIEWS = {
    # Facing or backing the camera: both sides, averaged.
    "both": (
        (LandmarkIndex.LEFT_HIP, LandmarkIndex.RIGHT_HIP),
        (LandmarkIndex.LEFT_SHOULDER, LandmarkIndex.RIGHT_SHOULDER),
    ),
    # Side-on, as most tests are filmed: only the near side is reliable.
    "left": ((LandmarkIndex.LEFT_HIP,), (LandmarkIndex.LEFT_SHOULDER,)),
    "right": ((LandmarkIndex.RIGHT_HIP,), (LandmarkIndex.RIGHT_SHOULDER,)),
}


def _hips_and_torso(
    frame: PoseFrame, aspect_ratio: float
) -> tuple[str, tuple[float, float], float] | None:
    """Which view, the hip position and the shoulder-to-hip length.

    None when no side of the torso is tracked.
    """
    for view, (hips, shoulders) in _TORSO_VIEWS.items():
        if not all(frame.is_visible(index) for index in (*hips, *shoulders)):
            continue
        hip = _mean_point(frame, hips, aspect_ratio)
        shoulder = _mean_point(frame, shoulders, aspect_ratio)
        torso = math.dist(hip, shoulder)
        if torso > 0.02:
            return view, hip, torso
    return None


def _mean_point(
    frame: PoseFrame, indices: tuple[int, ...], aspect_ratio: float
) -> tuple[float, float]:
    points = [frame.get(index) for index in indices]
    return (
        sum(point.x for point in points) / len(points) * aspect_ratio,
        sum(point.y for point in points) / len(points),
    )


def _tracked_at(tracked: list, index: int, view: str):
    if not 0 <= index < len(tracked):
        return None
    entry = tracked[index]
    return entry if entry is not None and entry[0] == view else None


def _near(point, entries: list, distance: float) -> bool:
    return any(
        entry is not None and math.dist(point, entry[1]) < distance
        for entry in entries
    )


def _check_teleports(
    frames: list[PoseFrame],
    report: CheatReport,
    summary: dict,
    aspect_ratio: float,
    limit: float,
) -> None:
    tracked = [_hips_and_torso(frame, aspect_ratio) for frame in frames]
    teleports: list[tuple[int, float, float]] = []
    fastest = 0.0

    for index in range(1, len(frames)):
        before, now = tracked[index - 1], tracked[index]
        # Positions are only comparable when measured from the same view.
        if before is None or now is None or before[0] != now[0]:
            continue
        dt_ms = frames[index].timestamp_ms - frames[index - 1].timestamp_ms
        if dt_ms <= 0 or dt_ms > MAX_FRAME_GAP_MS:
            continue

        _, start, torso = before
        moved = math.dist(start, now[1]) / torso
        speed = moved * 1000.0 / dt_ms
        fastest = max(fastest, speed)
        if speed <= limit:
            continue

        window = range(1, TELEPORT_RETURN_FRAMES + 1)
        after = [_tracked_at(tracked, index + step, before[0]) for step in window]
        prior = [_tracked_at(tracked, index - 1 - step, before[0]) for step in window]
        if any(entry is None for entry in after):
            continue  # cannot tell a glitch from a splice without what follows

        # Back where it started, or landing where it just was: a glitch.
        reach = moved / 2 * torso
        if _near(start, after, reach) or _near(now[1], prior, reach):
            continue
        teleports.append((index, moved, speed))

    summary["landmark_jumps"] = len(teleports)
    summary["fastest_hip_speed_torso_lengths_per_s"] = round(fastest, 2)

    if not teleports:
        return

    index, moved, speed = teleports[0]
    frame = frames[index]
    report.add(
        CheatFinding(
            check=CheatCheck.IMPOSSIBLE_MOVEMENT,
            severity=Severity.MEDIUM,
            detail=(
                f"The athlete's hips jump {moved:.1f} body-lengths between two "
                f"frames at {frame.timestamp_ms / 1000:.1f}s and stay there — no "
                "one moves that fast. It can mean the footage was spliced or the "
                "tracker switched to someone else"
            ),
            at_ms=frame.timestamp_ms,
            evidence={
                "signal": "landmark_jump",
                "frame_index": index,
                "displacement_torso_lengths": round(moved, 2),
                "speed_torso_lengths_per_s": round(speed, 1),
                "jumps": len(teleports),
                "max_hip_speed": limit,
            },
        )
    )
