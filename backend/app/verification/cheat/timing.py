"""Playback-speed sanity: does the footage run at the speed it claims?

Sped-up footage makes an athlete look faster (more sit-ups in the window);
slowed footage makes a jump look higher-flying. Three independent signals, from
cheapest to most physical:

* **Container frame rate** — outside the range a phone records at
  (``metadata.py``, Sprint 6).
* **Frame timing** — the frame rate the container declares against the rate
  the frames' own timestamps imply. A file re-timed by an editor can carry one
  and not the other.
* **Jump physics** — a body in free flight obeys gravity, so the time an
  athlete spends in the air fixes how high they can have gone. Footage played
  slower than real time stretches the flight without raising the jump.

Rep tests have no equivalent physical constant: a fast honest athlete and a
sped-up slow one look alike. Reps quicker than the scorer's physical minimum
are reported by ``movement.py`` instead.
"""

from __future__ import annotations

import math

from .. import thresholds as scoring
from ..analyzers import AnalyzerResult, AttemptStatus, TestType
from .findings import CheatCheck, CheatFinding, CheatReport, Severity
from .metadata import VideoMetadata

# Declared and timestamp-measured frame rates further apart than this share.
# Variable-frame-rate phone footage still averages out to its declared rate;
# 25% is far outside what a phone's own container writes.
FRAME_RATE_MISMATCH = 0.25
MIN_FRAMES_FOR_RATE = 30

# Gravity, in cm/s^2.
GRAVITY_CM_S2 = 981.0

# The scorer times a flight from the hip rising past JUMP_TAKEOFF_CM to falling
# back below JUMP_LANDING_CM, so the measured height includes roughly the mean
# of the two above the timed parabola.
FLIGHT_OFFSET_CM = (scoring.JUMP_TAKEOFF_CM + scoring.JUMP_LANDING_CM) / 2

# Below this height the offset above dominates the comparison and small timing
# errors swing the estimate wildly, so the physics check is not attempted.
MIN_JUMP_FOR_PHYSICS_CM = 25.0

# Estimated time scale beyond which the footage is reported: 1.6 means the
# flight lasted as long as a jump 1.6^2 = 2.6x higher would. Deliberately
# wide — the ballistic model ignores push-off before the feet leave the ground
# and has not been measured against real jumps (only synthetic fixtures, where
# 40 and 60 cm jumps estimate 1.05 and 0.94).
JUMP_TIME_SCALE_LIMIT = 1.6


def jump_time_scale(height_cm: float, flight_ms: int) -> float | None:
    """How much slower than real time the footage appears to run.

    1.0 is consistent with gravity; 2.0 means the flight took as long as it
    would at half speed; 0.5 as if sped up twice. None when not measurable.
    """
    rise = height_cm - FLIGHT_OFFSET_CM
    if rise <= 0 or flight_ms <= 0:
        return None
    seconds = flight_ms / 1000.0
    ballistic_cm = GRAVITY_CM_S2 * seconds * seconds / 8.0
    return math.sqrt(ballistic_cm / rise)


def check_timing(
    timestamps_ms: list[int],
    metadata: VideoMetadata | None,
    server_result: AnalyzerResult | None,
    *,
    report: CheatReport | None = None,
    jump_time_scale_limit: float = JUMP_TIME_SCALE_LIMIT,
) -> CheatReport:
    report = report or CheatReport()
    summary: dict = {
        "declared_fps": metadata.fps if metadata else None,
        "measured_fps": None,
        "duration_seconds": (
            round(metadata.duration_seconds, 2)
            if metadata and metadata.duration_seconds is not None
            else None
        ),
        "jump_time_scale": None,
    }

    _check_frame_rate(timestamps_ms, metadata, report, summary)
    if server_result is not None:
        _check_jump_physics(server_result, report, summary, jump_time_scale_limit)

    report.summarise("timing", summary)
    return report


def _check_frame_rate(
    timestamps_ms: list[int],
    metadata: VideoMetadata | None,
    report: CheatReport,
    summary: dict,
) -> None:
    if len(timestamps_ms) < MIN_FRAMES_FOR_RATE:
        return
    span_ms = timestamps_ms[-1] - timestamps_ms[0]
    if span_ms <= 0:
        return
    measured = (len(timestamps_ms) - 1) * 1000.0 / span_ms
    summary["measured_fps"] = round(measured, 2)

    declared = metadata.fps if metadata else None
    if not declared:
        report.skip(CheatCheck.PLAYBACK_SPEED_SUSPICIOUS, "Declared frame rate unknown")
        return

    ratio = measured / declared
    if abs(ratio - 1.0) <= FRAME_RATE_MISMATCH:
        return

    report.add(
        CheatFinding(
            check=CheatCheck.PLAYBACK_SPEED_SUSPICIOUS,
            severity=Severity.LOW,
            detail=(
                f"The file declares {declared:.0f} frames per second but its frames "
                f"are timed at {measured:.0f} per second, so it may not play back at "
                "the speed it was recorded"
            ),
            evidence={
                "signal": "frame_timing",
                "declared_fps": round(declared, 2),
                "measured_fps": round(measured, 2),
                "ratio": round(ratio, 3),
                "tolerance": FRAME_RATE_MISMATCH,
            },
        )
    )


def _check_jump_physics(
    server_result: AnalyzerResult,
    report: CheatReport,
    summary: dict,
    limit: float,
) -> None:
    if server_result.test_type is not TestType.VERTICAL_JUMP:
        return
    if server_result.status is not AttemptStatus.COMPLETE or not server_result.jumps:
        return

    height_cm, flight_ms = max(server_result.jumps, key=lambda jump: jump[0])
    if height_cm < MIN_JUMP_FOR_PHYSICS_CM:
        summary["jump_time_scale"] = None
        summary["jump_physics"] = "not attempted: jump below the reliable range"
        return

    scale = jump_time_scale(height_cm, flight_ms)
    if scale is None:
        return
    summary["jump_time_scale"] = round(scale, 3)

    if 1.0 / limit < scale < limit:
        return

    seconds = flight_ms / 1000.0
    ballistic_cm = GRAVITY_CM_S2 * seconds * seconds / 8.0 + FLIGHT_OFFSET_CM
    direction = "slower" if scale > 1.0 else "faster"
    report.add(
        CheatFinding(
            check=CheatCheck.PLAYBACK_SPEED_SUSPICIOUS,
            # Low: the model is simple and unmeasured on real jumps.
            severity=Severity.LOW,
            detail=(
                f"The athlete is in the air for {flight_ms} ms, which under gravity "
                f"fits a jump of about {ballistic_cm:.0f} cm, but the video measures "
                f"{height_cm:.0f} cm. The footage may be playing {direction} than "
                "real time"
            ),
            evidence={
                "signal": "jump_physics",
                "jump_cm": round(height_cm, 1),
                "flight_ms": flight_ms,
                "ballistic_cm": round(ballistic_cm, 1),
                "time_scale": round(scale, 3),
                "limit": limit,
            },
        )
    )
