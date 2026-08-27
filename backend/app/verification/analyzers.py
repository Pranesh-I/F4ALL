"""Server-side re-verification scorers.

Ports of ``SitUpAnalyzer.kt`` and ``VerticalJumpAnalyzer.kt``. The state
machines, thresholds and rejection rules are intentionally identical — the
server's job is to reach the same answer independently, not a different one.

The independence that matters is in the *input*: the server extracts its own
landmarks from the uploaded video rather than trusting the ones the device
reported. A modified client can lie about landmarks; it cannot make the video
show something it does not show.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from enum import Enum

from . import thresholds
from .pose import (
    BodySide,
    FrameQualityGate,
    LandmarkIndex,
    PoseFrame,
    PoseSmoother,
    angle,
    mean,
    select_more_visible_side,
    standard_deviation,
)


class TestType(str, Enum):
    # Stops pytest trying to collect this as a test class.
    __test__ = False

    SIT_UPS = "SIT_UPS"
    VERTICAL_JUMP = "VERTICAL_JUMP"

    @property
    def unit(self) -> str:
        return "reps" if self is TestType.SIT_UPS else "cm"


class AttemptStatus(str, Enum):
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETE = "COMPLETE"
    INVALID = "INVALID"


@dataclass(frozen=True)
class AnalyzerEvent:
    timestamp_ms: int
    label: str
    detail: str = ""


@dataclass
class AnalyzerResult:
    test_type: TestType
    score: float
    unit: str
    status: AttemptStatus
    confidence: float
    frames_analyzed: int
    frames_rejected: int
    invalid_reason: str | None = None
    events: list[AnalyzerEvent] = field(default_factory=list)

    @property
    def is_usable(self) -> bool:
        return self.status is AttemptStatus.COMPLETE

    @classmethod
    def invalid(
        cls,
        test_type: TestType,
        reason: str,
        frames_analyzed: int = 0,
        frames_rejected: int = 0,
        events: list[AnalyzerEvent] | None = None,
    ) -> AnalyzerResult:
        return cls(
            test_type=test_type,
            score=0.0,
            unit=test_type.unit,
            status=AttemptStatus.INVALID,
            confidence=0.0,
            frames_analyzed=frames_analyzed,
            frames_rejected=frames_rejected,
            invalid_reason=reason,
            events=events or [],
        )


# ---------------------------------------------------------------------------
# Sit-ups
# ---------------------------------------------------------------------------

_LEFT_SITUP = [
    LandmarkIndex.LEFT_SHOULDER,
    LandmarkIndex.LEFT_HIP,
    LandmarkIndex.LEFT_KNEE,
]
_RIGHT_SITUP = [
    LandmarkIndex.RIGHT_SHOULDER,
    LandmarkIndex.RIGHT_HIP,
    LandmarkIndex.RIGHT_KNEE,
]
_SITUP_REQUIRED = _LEFT_SITUP + _RIGHT_SITUP

_MAX_ANGLE = 180.0


class _SitUpState(str, Enum):
    WAITING_FOR_DOWN = "WAITING_FOR_DOWN"
    DOWN = "DOWN"
    UP = "UP"


class SitUpAnalyzer:
    """Counts sit-ups from the torso angle at the hip.

    Two stable states separated by a wide hysteresis band; a rep is counted on
    the DOWN -> UP crossing. The band is what stops a trembling torso at a
    single threshold from counting twenty reps.
    """

    test_type = TestType.SIT_UPS

    def __init__(self) -> None:
        self._gate = FrameQualityGate(_SITUP_REQUIRED)
        self._smoother = PoseSmoother()
        self.reset()

    def reset(self) -> None:
        self._state = _SitUpState.WAITING_FOR_DOWN
        self._rep_count = 0
        self._rejected_partial = 0
        self._rejected_fast = 0
        self._frames_analyzed = 0
        self._frames_rejected = 0
        self._consecutive_rejected = 0
        self._ascent_start_ms: int | None = None
        self._min_angle_since_down = _MAX_ANGLE
        self._visibility_sum = 0.0
        self._locked_side: BodySide | None = None
        self._last_angle: float | None = None
        self._tracking_lost = False
        self._events: list[AnalyzerEvent] = []
        self._smoother.reset()

    @property
    def rejected_partial_reps(self) -> int:
        return self._rejected_partial

    def on_frame(self, frame: PoseFrame) -> None:
        verdict = self._gate.evaluate(frame)

        if not verdict.accepted:
            self._frames_rejected += 1
            self._consecutive_rejected += 1
            if self._consecutive_rejected == thresholds.MAX_CONSECUTIVE_REJECTED_FRAMES:
                self._tracking_lost = True
                self._events.append(
                    AnalyzerEvent(
                        frame.timestamp_ms,
                        "tracking_lost",
                        "Body not fully visible for "
                        f"{thresholds.MAX_CONSECUTIVE_REJECTED_FRAMES} frames",
                    )
                )
            return

        self._consecutive_rejected = 0
        self._frames_analyzed += 1
        self._visibility_sum += verdict.mean_visibility

        smoothed = self._smoother.smooth(frame)

        # Locked once, from the first good frame. Switching mid-attempt puts a
        # discontinuity in the angle signal, which the machine reads as a rep.
        if self._locked_side is None:
            self._locked_side = select_more_visible_side(
                smoothed, _LEFT_SITUP, _RIGHT_SITUP
            )
            self._events.append(
                AnalyzerEvent(
                    frame.timestamp_ms, "side_locked", self._locked_side.value
                )
            )

        left = self._locked_side is BodySide.LEFT
        shoulder = smoothed.get(
            LandmarkIndex.LEFT_SHOULDER if left else LandmarkIndex.RIGHT_SHOULDER
        )
        hip = smoothed.get(LandmarkIndex.LEFT_HIP if left else LandmarkIndex.RIGHT_HIP)
        knee = smoothed.get(
            LandmarkIndex.LEFT_KNEE if left else LandmarkIndex.RIGHT_KNEE
        )

        if shoulder is None or hip is None or knee is None:
            return

        torso_angle = angle(shoulder, hip, knee)
        self._last_angle = torso_angle
        self._advance(torso_angle, frame.timestamp_ms)

    def _advance(self, torso_angle: float, timestamp_ms: int) -> None:
        if self._state is _SitUpState.WAITING_FOR_DOWN:
            if torso_angle >= thresholds.SITUP_DOWN_ENTER_ANGLE:
                self._state = _SitUpState.DOWN
                self._reset_ascent()
                self._events.append(
                    AnalyzerEvent(timestamp_ms, "ready", "Start position detected")
                )
            return

        if self._state is _SitUpState.DOWN:
            if torso_angle < thresholds.SITUP_DOWN_ENTER_ANGLE:
                if self._ascent_start_ms is None:
                    self._ascent_start_ms = timestamp_ms
                self._min_angle_since_down = min(
                    self._min_angle_since_down, torso_angle
                )
                if torso_angle <= thresholds.SITUP_UP_ENTER_ANGLE:
                    self._complete_ascent(timestamp_ms)
            elif self._ascent_start_ms is not None:
                self._abandon_ascent(timestamp_ms)
            return

        # Back to the start position re-arms the counter for the next rep.
        if (
            self._state is _SitUpState.UP
            and torso_angle >= thresholds.SITUP_DOWN_ENTER_ANGLE
        ):
            self._state = _SitUpState.DOWN
            self._reset_ascent()

    def _complete_ascent(self, timestamp_ms: int) -> None:
        start = (
            self._ascent_start_ms
            if self._ascent_start_ms is not None
            else timestamp_ms
        )
        duration_ms = timestamp_ms - start

        if duration_ms < thresholds.SITUP_MIN_REP_DURATION_MS:
            self._rejected_fast += 1
            self._events.append(
                AnalyzerEvent(
                    timestamp_ms,
                    "rep_rejected_too_fast",
                    f"{duration_ms}ms is below the "
                    f"{thresholds.SITUP_MIN_REP_DURATION_MS}ms minimum",
                )
            )
        else:
            self._rep_count += 1
            self._events.append(
                AnalyzerEvent(
                    timestamp_ms,
                    "rep_counted",
                    f"Rep {self._rep_count} in {duration_ms}ms",
                )
            )

        self._state = _SitUpState.UP
        self._reset_ascent()

    def _abandon_ascent(self, timestamp_ms: int) -> None:
        if self._min_angle_since_down <= thresholds.SITUP_PARTIAL_REP_ANGLE:
            self._rejected_partial += 1
            self._events.append(
                AnalyzerEvent(
                    timestamp_ms,
                    "rep_rejected_partial",
                    f"Reached {self._min_angle_since_down:.0f} deg, "
                    f"needs {int(thresholds.SITUP_UP_ENTER_ANGLE)} deg",
                )
            )
        self._reset_ascent()

    def _reset_ascent(self) -> None:
        self._ascent_start_ms = None
        self._min_angle_since_down = _MAX_ANGLE

    def current_score(self) -> float:
        return float(self._rep_count)

    def result(self) -> AnalyzerResult:
        if self._frames_analyzed == 0:
            return AnalyzerResult.invalid(
                self.test_type,
                "No usable pose data in the recording",
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        if self._state is _SitUpState.WAITING_FOR_DOWN:
            return AnalyzerResult.invalid(
                self.test_type,
                "Start position never detected",
                frames_analyzed=self._frames_analyzed,
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        return AnalyzerResult(
            test_type=self.test_type,
            score=float(self._rep_count),
            unit=self.test_type.unit,
            status=AttemptStatus.COMPLETE,
            confidence=self._confidence(),
            frames_analyzed=self._frames_analyzed,
            frames_rejected=self._frames_rejected,
            events=list(self._events),
        )

    def _confidence(self) -> float:
        mean_visibility = self._visibility_sum / self._frames_analyzed
        total = self._frames_analyzed + self._frames_rejected
        acceptance = self._frames_analyzed / total if total else 0.0
        base = mean_visibility * acceptance
        if self._tracking_lost:
            base *= 0.5
        return max(0.0, min(1.0, base))


# ---------------------------------------------------------------------------
# Vertical jump
# ---------------------------------------------------------------------------

_JUMP_REQUIRED = [
    LandmarkIndex.NOSE,
    LandmarkIndex.LEFT_HIP,
    LandmarkIndex.RIGHT_HIP,
    LandmarkIndex.LEFT_ANKLE,
    LandmarkIndex.RIGHT_ANKLE,
]


class JumpPhase(str, Enum):
    CALIBRATING = "CALIBRATING"
    READY = "READY"
    AIRBORNE = "AIRBORNE"
    INVALID = "INVALID"


class VerticalJumpAnalyzer:
    """Measures jump height from hip displacement against a standing reference.

    Normalized coordinates map to centimetres only at the depth where
    calibration happened, so apparent stature is watched as the only available
    depth cue; if it drifts, the pixel-to-cm ratio is stale and the attempt is
    rejected rather than reported as a confidently wrong number.
    """

    test_type = TestType.VERTICAL_JUMP

    def __init__(self, athlete_height_cm: float) -> None:
        self.athlete_height_cm = athlete_height_cm
        self._gate = FrameQualityGate(_JUMP_REQUIRED)
        self._smoother = PoseSmoother()
        self.reset()

    def reset(self) -> None:
        self._phase = JumpPhase.CALIBRATING
        self._calibration_hip_y: list[float] = []
        self._calibration_stature: list[float] = []
        self._baseline_hip_y: float | None = None
        self._calibrated_stature: float | None = None
        self._cm_per_unit: float | None = None
        self._takeoff_ms: int | None = None
        self._current_peak_units = 0.0
        self._best_jump_cm = 0.0
        self._jump_count = 0
        self._frames_analyzed = 0
        self._frames_rejected = 0
        self._consecutive_rejected = 0
        self._visibility_sum = 0.0
        self._scale_drift = 0.0
        self._invalid_reason: str | None = None
        self._events: list[AnalyzerEvent] = []
        self._smoother.reset()

    def on_frame(self, frame: PoseFrame) -> None:
        if self._phase is JumpPhase.INVALID:
            return

        verdict = self._gate.evaluate(frame)

        if not verdict.accepted:
            self._frames_rejected += 1
            self._consecutive_rejected += 1
            lost_tracking = (
                self._consecutive_rejected
                >= thresholds.MAX_CONSECUTIVE_REJECTED_FRAMES
            )

            if lost_tracking and self._phase is JumpPhase.AIRBORNE:
                # A jump measured from a partial arc is worse than no
                # measurement, because it looks like a real result.
                self._fail(frame.timestamp_ms, "Lost track of the athlete mid-jump")
            elif lost_tracking and self._phase is JumpPhase.CALIBRATING:
                self._calibration_hip_y.clear()
                self._calibration_stature.clear()
            return

        self._consecutive_rejected = 0
        self._frames_analyzed += 1
        self._visibility_sum += verdict.mean_visibility

        smoothed = self._smoother.smooth(frame)

        hip_y = self._hip_y(smoothed)
        stature = self._stature_units(smoothed)
        if hip_y is None or stature is None:
            return

        if self._phase is JumpPhase.CALIBRATING:
            self._calibrate(hip_y, stature, frame.timestamp_ms)
        elif self._phase is JumpPhase.READY:
            self._watch_for_takeoff(hip_y, stature, frame.timestamp_ms)
        elif self._phase is JumpPhase.AIRBORNE:
            self._track_flight(hip_y, frame.timestamp_ms)

    def _calibrate(self, hip_y: float, stature: float, timestamp_ms: int) -> None:
        self._calibration_hip_y.append(hip_y)
        self._calibration_stature.append(stature)

        while len(self._calibration_hip_y) > thresholds.JUMP_CALIBRATION_FRAMES:
            self._calibration_hip_y.pop(0)
            self._calibration_stature.pop(0)

        if len(self._calibration_hip_y) < thresholds.JUMP_CALIBRATION_FRAMES:
            return

        if (
            standard_deviation(self._calibration_hip_y)
            > thresholds.JUMP_CALIBRATION_STABILITY
        ):
            return  # Still moving; the window slides forward and tries again.

        mean_stature = mean(self._calibration_stature)
        if mean_stature <= 0.0:
            return

        estimated_stature = mean_stature / thresholds.NOSE_HEIGHT_STATURE_RATIO

        self._baseline_hip_y = mean(self._calibration_hip_y)
        self._calibrated_stature = mean_stature
        self._cm_per_unit = self.athlete_height_cm / estimated_stature
        self._phase = JumpPhase.READY

        self._events.append(
            AnalyzerEvent(
                timestamp_ms,
                "calibrated",
                f"1.0 unit = {self._cm_per_unit:.1f}cm at "
                f"{int(self.athlete_height_cm)}cm height",
            )
        )

    def _watch_for_takeoff(
        self, hip_y: float, stature: float, timestamp_ms: int
    ) -> None:
        displacement_cm = self._displacement_cm(hip_y)
        if displacement_cm is None:
            return

        if self._calibrated_stature:
            self._scale_drift = max(
                self._scale_drift, abs(stature / self._calibrated_stature - 1.0)
            )

        if displacement_cm >= thresholds.JUMP_TAKEOFF_CM:
            self._phase = JumpPhase.AIRBORNE
            self._takeoff_ms = timestamp_ms
            self._current_peak_units = self._displacement_units(hip_y) or 0.0
            self._events.append(
                AnalyzerEvent(
                    timestamp_ms,
                    "takeoff",
                    f"Rose past {int(thresholds.JUMP_TAKEOFF_CM)}cm",
                )
            )

    def _track_flight(self, hip_y: float, timestamp_ms: int) -> None:
        displacement_units = self._displacement_units(hip_y)
        displacement_cm = self._displacement_cm(hip_y)
        if displacement_units is None or displacement_cm is None:
            return

        self._current_peak_units = max(self._current_peak_units, displacement_units)

        start = self._takeoff_ms if self._takeoff_ms is not None else timestamp_ms
        elapsed_ms = timestamp_ms - start

        if elapsed_ms > thresholds.JUMP_MAX_FLIGHT_MS:
            self._fail(timestamp_ms, "No clean landing detected")
            return

        if displacement_cm > thresholds.JUMP_LANDING_CM:
            return

        self._complete_jump(elapsed_ms, timestamp_ms)

    def _complete_jump(self, elapsed_ms: int, timestamp_ms: int) -> None:
        if self._cm_per_unit is None:
            return

        jump_cm = self._current_peak_units * self._cm_per_unit

        self._phase = JumpPhase.READY
        self._takeoff_ms = None
        self._current_peak_units = 0.0

        if elapsed_ms < thresholds.JUMP_MIN_FLIGHT_MS:
            self._events.append(
                AnalyzerEvent(
                    timestamp_ms,
                    "jump_rejected",
                    f"Only {elapsed_ms}ms off the ground",
                )
            )
            return

        if jump_cm > thresholds.JUMP_MAX_PLAUSIBLE_CM:
            self._events.append(
                AnalyzerEvent(
                    timestamp_ms,
                    "jump_rejected",
                    f"{jump_cm:.1f}cm exceeds the plausible range",
                )
            )
            return

        self._jump_count += 1
        self._best_jump_cm = max(self._best_jump_cm, jump_cm)
        self._events.append(
            AnalyzerEvent(
                timestamp_ms,
                "jump_measured",
                f"Jump {self._jump_count}: {jump_cm:.1f}cm over {elapsed_ms}ms",
            )
        )

    def _fail(self, timestamp_ms: int, reason: str) -> None:
        self._phase = JumpPhase.INVALID
        self._invalid_reason = reason
        self._events.append(AnalyzerEvent(timestamp_ms, "attempt_failed", reason))

    @staticmethod
    def _hip_y(frame: PoseFrame) -> float | None:
        left = frame.get(LandmarkIndex.LEFT_HIP)
        right = frame.get(LandmarkIndex.RIGHT_HIP)
        if left is None or right is None:
            return None
        return (left.y + right.y) / 2.0

    @staticmethod
    def _stature_units(frame: PoseFrame) -> float | None:
        nose = frame.get(LandmarkIndex.NOSE)
        left_ankle = frame.get(LandmarkIndex.LEFT_ANKLE)
        right_ankle = frame.get(LandmarkIndex.RIGHT_ANKLE)
        if nose is None or left_ankle is None or right_ankle is None:
            return None
        span = (left_ankle.y + right_ankle.y) / 2.0 - nose.y
        return span if span > 0.0 else None

    def _displacement_units(self, hip_y: float) -> float | None:
        if self._baseline_hip_y is None:
            return None
        return self._baseline_hip_y - hip_y

    def _displacement_cm(self, hip_y: float) -> float | None:
        units = self._displacement_units(hip_y)
        if units is None or self._cm_per_unit is None:
            return None
        return units * self._cm_per_unit

    def current_score(self) -> float:
        return self._best_jump_cm

    def result(self) -> AnalyzerResult:
        if self._invalid_reason:
            return AnalyzerResult.invalid(
                self.test_type,
                self._invalid_reason,
                frames_analyzed=self._frames_analyzed,
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        if self._frames_analyzed == 0:
            return AnalyzerResult.invalid(
                self.test_type,
                "No usable pose data in the recording",
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        if self._phase is JumpPhase.CALIBRATING:
            return AnalyzerResult.invalid(
                self.test_type,
                "Could not establish a standing reference",
                frames_analyzed=self._frames_analyzed,
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        if self._phase is JumpPhase.AIRBORNE:
            return AnalyzerResult.invalid(
                self.test_type,
                "Recording ended mid-jump",
                frames_analyzed=self._frames_analyzed,
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        if (
            self._jump_count == 0
            or self._best_jump_cm < thresholds.JUMP_MIN_PLAUSIBLE_CM
        ):
            return AnalyzerResult.invalid(
                self.test_type,
                "No jump detected",
                frames_analyzed=self._frames_analyzed,
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        if self._scale_drift > thresholds.MAX_SCALE_DRIFT:
            return AnalyzerResult.invalid(
                self.test_type,
                "Athlete or camera moved during the test",
                frames_analyzed=self._frames_analyzed,
                frames_rejected=self._frames_rejected,
                events=list(self._events),
            )

        return AnalyzerResult(
            test_type=self.test_type,
            score=self._best_jump_cm,
            unit=self.test_type.unit,
            status=AttemptStatus.COMPLETE,
            confidence=self._confidence(),
            frames_analyzed=self._frames_analyzed,
            frames_rejected=self._frames_rejected,
            events=list(self._events),
        )

    def _confidence(self) -> float:
        mean_visibility = self._visibility_sum / self._frames_analyzed
        total = self._frames_analyzed + self._frames_rejected
        acceptance = self._frames_analyzed / total if total else 0.0
        drift_penalty = max(
            0.0, min(1.0, 1.0 - (self._scale_drift / thresholds.MAX_SCALE_DRIFT))
        )
        return max(0.0, min(1.0, mean_visibility * acceptance * drift_penalty))


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def analyze_sequence(analyzer, frames: Sequence[PoseFrame]) -> AnalyzerResult:
    """Run a complete recorded sequence through an analyzer."""
    analyzer.reset()
    for frame in frames:
        analyzer.on_frame(frame)
    return analyzer.result()


def build_analyzer(test_type: TestType, athlete_height_cm: float | None):
    if test_type is TestType.SIT_UPS:
        return SitUpAnalyzer()
    if athlete_height_cm is None:
        raise ValueError("Vertical jump scoring requires the athlete's height")
    return VerticalJumpAnalyzer(athlete_height_cm)
