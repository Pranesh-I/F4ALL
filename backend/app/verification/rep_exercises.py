"""Server-side scorers for squat, push-up, bicep curl and lunge.

Ports of ``RepCycleTracker.kt``, ``RepExerciseAnalyzer.kt`` and the four
exercise analyzers. As with the sit-up and jump scorers, the rules are
deliberately identical — the server re-derives landmarks from the video and
must reach the same answer from them. ``test_parity.py`` holds the two
implementations to that, including *why* each rep was refused.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from . import thresholds
from .analyzers import AnalyzerEvent, AnalyzerResult, AttemptStatus, TestType
from .pose import (
    BodySide,
    FrameQualityGate,
    LandmarkIndex,
    PoseFrame,
    PosePoint,
    PoseSmoother,
    angle,
    angle_from_horizontal,
    angle_from_vertical,
    cross_product,
    distance,
    select_more_visible_side,
)

_MAX_ANGLE = 180.0


class FormIssue(Enum):
    """A form fault. Declaration order is report order, as in Kotlin."""

    TORSO_LEAN = ("torso_lean", False)
    HIPS_SAGGING = ("hips_sagging", True)
    HIPS_PIKED = ("hips_piked", True)
    NOT_IN_PLANK = ("not_in_plank", True)
    ELBOW_FLARE = ("elbow_flare", True)
    STANCE_TOO_NARROW = ("stance_too_narrow", True)
    KNEE_PAST_TOES = ("knee_past_toes", False)
    FEET_MOVED = ("feet_moved", False)
    BODY_SWING = ("body_swing", False)

    @property
    def code(self) -> str:
        return self.value[0]

    @property
    def rejects_rep(self) -> bool:
        """True when the fault means the movement was not the exercise at all."""
        return self.value[1]


_ISSUE_ORDER = list(FormIssue)


@dataclass(frozen=True)
class RepThresholds:
    extended_angle: float
    depth_angle: float
    partial_angle: float
    min_rep_duration_ms: int


@dataclass(frozen=True)
class RepSample:
    angle: float
    issues: frozenset[FormIssue] = field(default_factory=frozenset)
    in_start_pose: bool = True


class RepEventKind(str, Enum):
    READY = "READY"
    COUNTED = "COUNTED"
    PARTIAL = "PARTIAL"
    TOO_FAST = "TOO_FAST"
    FORM_REJECTED = "FORM_REJECTED"


@dataclass(frozen=True)
class RepEvent:
    kind: RepEventKind
    timestamp_ms: int
    duration_ms: int = 0
    deepest_angle: float = 0.0
    issues: tuple[FormIssue, ...] = ()

    @property
    def reached_depth(self) -> bool:
        return self.kind in (
            RepEventKind.COUNTED,
            RepEventKind.TOO_FAST,
            RepEventKind.FORM_REJECTED,
        )


class RepPhase(str, Enum):
    WAITING_FOR_START = "WAITING_FOR_START"
    TOP = "TOP"
    IN_REP = "IN_REP"


class RepCycleTracker:
    """Hysteresis rep machine on one joint angle; a rep is judged on return to the top."""

    def __init__(self, rep_thresholds: RepThresholds) -> None:
        self._t = rep_thresholds
        self.reset()

    def reset(self) -> None:
        self.phase = RepPhase.WAITING_FOR_START
        self._start_hold_frames = 0
        self._reset_rep()

    def _reset_rep(self) -> None:
        self._rep_start_ms: int | None = None
        self._deepest_angle = _MAX_ANGLE
        self._reached_depth = False
        self._fault_runs = {issue: 0 for issue in _ISSUE_ORDER}
        self._rep_faults: set[FormIssue] = set()

    def update(self, sample: RepSample, timestamp_ms: int) -> RepEvent | None:
        if self.phase is RepPhase.WAITING_FOR_START:
            if sample.angle >= self._t.extended_angle and sample.in_start_pose:
                self._start_hold_frames += 1
                if self._start_hold_frames >= thresholds.REP_START_HOLD_FRAMES:
                    self.phase = RepPhase.TOP
                    return RepEvent(RepEventKind.READY, timestamp_ms)
            else:
                self._start_hold_frames = 0
            return None

        if self.phase is RepPhase.TOP:
            if sample.angle < self._t.extended_angle:
                self.phase = RepPhase.IN_REP
                self._rep_start_ms = timestamp_ms
                self._track(sample)
            return None

        if sample.angle >= self._t.extended_angle:
            return self._finish_rep(timestamp_ms)

        self._track(sample)
        return None

    def _track(self, sample: RepSample) -> None:
        if sample.angle < self._deepest_angle:
            self._deepest_angle = sample.angle
        if sample.angle <= self._t.depth_angle:
            self._reached_depth = True

        for issue in _ISSUE_ORDER:
            if issue in sample.issues:
                self._fault_runs[issue] += 1
                if self._fault_runs[issue] >= thresholds.FORM_FAULT_MIN_FRAMES:
                    self._rep_faults.add(issue)
            else:
                self._fault_runs[issue] = 0

    def _finish_rep(self, timestamp_ms: int) -> RepEvent | None:
        start = self._rep_start_ms if self._rep_start_ms is not None else timestamp_ms
        duration_ms = timestamp_ms - start
        faults = tuple(issue for issue in _ISSUE_ORDER if issue in self._rep_faults)

        event: RepEvent | None
        if not self._reached_depth:
            event = (
                RepEvent(
                    RepEventKind.PARTIAL,
                    timestamp_ms,
                    deepest_angle=self._deepest_angle,
                )
                if self._deepest_angle <= self._t.partial_angle
                else None
            )
        elif duration_ms < self._t.min_rep_duration_ms:
            event = RepEvent(RepEventKind.TOO_FAST, timestamp_ms, duration_ms=duration_ms)
        elif any(issue.rejects_rep for issue in faults):
            event = RepEvent(RepEventKind.FORM_REJECTED, timestamp_ms, issues=faults)
        else:
            event = RepEvent(
                RepEventKind.COUNTED, timestamp_ms, duration_ms=duration_ms, issues=faults
            )

        self.phase = RepPhase.TOP
        self._reset_rep()
        return event


def _index(side: BodySide, left: int, right: int) -> int:
    return left if side is BodySide.LEFT else right


def _midpoint(first: PosePoint, second: PosePoint) -> PosePoint:
    return PosePoint(
        x=(first.x + second.x) / 2.0,
        y=(first.y + second.y) / 2.0,
        z=(first.z + second.z) / 2.0,
        visibility=min(first.visibility, second.visibility),
    )


class RepExerciseAnalyzer:
    """Gate, smoothing, side lock, events and result shared by the rep exercises."""

    test_type: TestType
    rep_thresholds: RepThresholds
    left_indices: tuple[int, ...]
    right_indices: tuple[int, ...]
    locks_one_side: bool
    no_start_reason: str

    def __init__(self) -> None:
        self._left_gate = FrameQualityGate(self.left_indices)
        self._right_gate = FrameQualityGate(self.right_indices)
        self._both_gate = FrameQualityGate(self.left_indices + self.right_indices)
        self._smoother = PoseSmoother()
        self._tracker = RepCycleTracker(self.rep_thresholds)
        self.reset()

    def reset(self) -> None:
        self._tracker.reset()
        self._rep_count = 0
        self._partial_reps = 0
        self._too_fast_reps = 0
        self._form_rejected_reps = 0
        self._frames_analyzed = 0
        self._frames_rejected = 0
        self._consecutive_rejected = 0
        self._visibility_sum = 0.0
        self._tracking_lost = False
        self._ready = False
        self.locked_side: BodySide | None = None
        self._events: list[AnalyzerEvent] = []
        self._smoother.reset()

    def sample(self, frame: PoseFrame, side: BodySide | None) -> RepSample | None:
        raise NotImplementedError

    def on_frame(self, frame: PoseFrame) -> None:
        verdict = self._evaluate(frame)

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

        self._process(self._smoother.smooth(frame))

    def _evaluate(self, frame: PoseFrame):
        if not self.locks_one_side:
            return self._both_gate.evaluate(frame)

        if self.locked_side is not None:
            gate = (
                self._left_gate if self.locked_side is BodySide.LEFT else self._right_gate
            )
            return gate.evaluate(frame)

        left = self._left_gate.evaluate(frame)
        right = self._right_gate.evaluate(frame)

        if left.accepted and right.accepted:
            side = select_more_visible_side(frame, self.left_indices, self.right_indices)
        elif left.accepted:
            side = BodySide.LEFT
        elif right.accepted:
            side = BodySide.RIGHT
        else:
            return left

        self.locked_side = side
        self._events.append(AnalyzerEvent(frame.timestamp_ms, "side_locked", side.value))
        return left if side is BodySide.LEFT else right

    def _process(self, frame: PoseFrame) -> None:
        sample = self.sample(frame, self.locked_side)
        if sample is None:
            return
        event = self._tracker.update(sample, frame.timestamp_ms)
        if event is not None:
            self._record(event)

    def _record(self, event: RepEvent) -> None:
        if event.kind is RepEventKind.READY:
            if not self._ready:
                self._ready = True
                self._events.append(
                    AnalyzerEvent(event.timestamp_ms, "ready", "Start position detected")
                )
            return

        if event.kind is RepEventKind.COUNTED:
            self._rep_count += 1
            self._events.append(
                AnalyzerEvent(
                    event.timestamp_ms,
                    "rep_counted",
                    f"Rep {self._rep_count} in {event.duration_ms}ms",
                )
            )
            for issue in event.issues:
                self._events.append(
                    AnalyzerEvent(event.timestamp_ms, "form_warning", issue.code)
                )
            return

        if event.kind is RepEventKind.PARTIAL:
            self._partial_reps += 1
            self._events.append(
                AnalyzerEvent(
                    event.timestamp_ms,
                    "rep_rejected_partial",
                    f"Reached {event.deepest_angle:.0f} deg, "
                    f"needs {int(self.rep_thresholds.depth_angle)} deg",
                )
            )
            return

        if event.kind is RepEventKind.TOO_FAST:
            self._too_fast_reps += 1
            self._events.append(
                AnalyzerEvent(
                    event.timestamp_ms,
                    "rep_rejected_too_fast",
                    f"{event.duration_ms}ms is below the "
                    f"{self.rep_thresholds.min_rep_duration_ms}ms minimum",
                )
            )
            return

        self._form_rejected_reps += 1
        self._events.append(
            AnalyzerEvent(
                event.timestamp_ms,
                "rep_rejected_form",
                ",".join(issue.code for issue in event.issues),
            )
        )

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

        if not self._ready:
            return AnalyzerResult.invalid(
                self.test_type,
                self.no_start_reason,
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
# Squat
# ---------------------------------------------------------------------------


class SquatAnalyzer(RepExerciseAnalyzer):
    """Knee angle, side-on. Torso lean is a warning, not a rejection."""

    test_type = TestType.SQUATS
    rep_thresholds = RepThresholds(
        thresholds.SQUAT_EXTENDED_ANGLE,
        thresholds.SQUAT_DEPTH_ANGLE,
        thresholds.SQUAT_PARTIAL_ANGLE,
        thresholds.SQUAT_MIN_REP_DURATION_MS,
    )
    left_indices = (
        LandmarkIndex.LEFT_SHOULDER,
        LandmarkIndex.LEFT_HIP,
        LandmarkIndex.LEFT_KNEE,
        LandmarkIndex.LEFT_ANKLE,
    )
    right_indices = (
        LandmarkIndex.RIGHT_SHOULDER,
        LandmarkIndex.RIGHT_HIP,
        LandmarkIndex.RIGHT_KNEE,
        LandmarkIndex.RIGHT_ANKLE,
    )
    locks_one_side = True
    no_start_reason = "Start position never detected — stand tall, side-on to the camera"

    def sample(self, frame: PoseFrame, side: BodySide | None) -> RepSample | None:
        if side is None:
            return None
        shoulder = frame.get(
            _index(side, LandmarkIndex.LEFT_SHOULDER, LandmarkIndex.RIGHT_SHOULDER)
        )
        hip = frame.get(_index(side, LandmarkIndex.LEFT_HIP, LandmarkIndex.RIGHT_HIP))
        knee = frame.get(_index(side, LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE))
        ankle = frame.get(
            _index(side, LandmarkIndex.LEFT_ANKLE, LandmarkIndex.RIGHT_ANKLE)
        )
        if shoulder is None or hip is None or knee is None or ankle is None:
            return None

        knee_angle = angle(hip, knee, ankle)
        shin = distance(knee, ankle)

        # Outside a rep the reference follows the athlete.
        if self._tracker.phase is not RepPhase.IN_REP:
            self._reference_ankle_x = ankle.x
            self._reference_leg_length = distance(hip, knee) + shin

        issues: set[FormIssue] = set()
        if angle_from_vertical(shoulder, hip) > thresholds.SQUAT_MAX_TORSO_LEAN_DEG:
            issues.add(FormIssue.TORSO_LEAN)

        if knee_angle <= thresholds.SQUAT_PARTIAL_ANGLE:
            forward = 1.0 if knee.x >= hip.x else -1.0
            if (knee.x - ankle.x) * forward > (
                thresholds.SQUAT_MAX_KNEE_TRAVEL_RATIO * shin
            ):
                issues.add(FormIssue.KNEE_PAST_TOES)

        if self._reference_ankle_x is not None and abs(
            ankle.x - self._reference_ankle_x
        ) > (thresholds.FOOT_MAX_SHIFT_RATIO * self._reference_leg_length):
            issues.add(FormIssue.FEET_MOVED)

        return RepSample(knee_angle, frozenset(issues))

    def reset(self) -> None:
        super().reset()
        self._reference_ankle_x: float | None = None
        self._reference_leg_length = 0.0


# ---------------------------------------------------------------------------
# Push-up
# ---------------------------------------------------------------------------


class PushUpAnalyzer(RepExerciseAnalyzer):
    """Elbow angle, side-on. A bent body line or no plank rejects the rep."""

    test_type = TestType.PUSH_UPS
    rep_thresholds = RepThresholds(
        thresholds.PUSHUP_EXTENDED_ANGLE,
        thresholds.PUSHUP_DEPTH_ANGLE,
        thresholds.PUSHUP_PARTIAL_ANGLE,
        thresholds.PUSHUP_MIN_REP_DURATION_MS,
    )
    left_indices = (
        LandmarkIndex.LEFT_SHOULDER,
        LandmarkIndex.LEFT_ELBOW,
        LandmarkIndex.LEFT_WRIST,
        LandmarkIndex.LEFT_HIP,
        LandmarkIndex.LEFT_ANKLE,
    )
    right_indices = (
        LandmarkIndex.RIGHT_SHOULDER,
        LandmarkIndex.RIGHT_ELBOW,
        LandmarkIndex.RIGHT_WRIST,
        LandmarkIndex.RIGHT_HIP,
        LandmarkIndex.RIGHT_ANKLE,
    )
    locks_one_side = True
    no_start_reason = "Start position never detected — hold a straight-arm plank"

    def sample(self, frame: PoseFrame, side: BodySide | None) -> RepSample | None:
        if side is None:
            return None
        shoulder = frame.get(
            _index(side, LandmarkIndex.LEFT_SHOULDER, LandmarkIndex.RIGHT_SHOULDER)
        )
        elbow = frame.get(
            _index(side, LandmarkIndex.LEFT_ELBOW, LandmarkIndex.RIGHT_ELBOW)
        )
        wrist = frame.get(
            _index(side, LandmarkIndex.LEFT_WRIST, LandmarkIndex.RIGHT_WRIST)
        )
        hip = frame.get(_index(side, LandmarkIndex.LEFT_HIP, LandmarkIndex.RIGHT_HIP))
        ankle = frame.get(
            _index(side, LandmarkIndex.LEFT_ANKLE, LandmarkIndex.RIGHT_ANKLE)
        )
        if None in (shoulder, elbow, wrist, hip, ankle):
            return None

        in_plank = (
            angle_from_horizontal(shoulder, ankle) <= thresholds.PUSHUP_MAX_BODY_TILT_DEG
        )

        if not in_plank:
            issues = frozenset({FormIssue.NOT_IN_PLANK})
        elif angle(shoulder, hip, ankle) < thresholds.PUSHUP_MIN_BODY_LINE_ANGLE:
            direction = 1.0 if ankle.x >= shoulder.x else -1.0
            below = cross_product(shoulder, ankle, hip) * direction > 0.0
            issues = frozenset(
                {FormIssue.HIPS_SAGGING if below else FormIssue.HIPS_PIKED}
            )
        else:
            issues = frozenset()

        return RepSample(angle(shoulder, elbow, wrist), issues, in_start_pose=in_plank)


# ---------------------------------------------------------------------------
# Lunge
# ---------------------------------------------------------------------------


class LungeAnalyzer(RepExerciseAnalyzer):
    """Mean knee angle, side-on. Feet together rejects; knee past toes warns."""

    test_type = TestType.LUNGES
    rep_thresholds = RepThresholds(
        thresholds.LUNGE_EXTENDED_ANGLE,
        thresholds.LUNGE_DEPTH_ANGLE,
        thresholds.LUNGE_PARTIAL_ANGLE,
        thresholds.LUNGE_MIN_REP_DURATION_MS,
    )
    left_indices = (
        LandmarkIndex.LEFT_SHOULDER,
        LandmarkIndex.LEFT_HIP,
        LandmarkIndex.LEFT_KNEE,
        LandmarkIndex.LEFT_ANKLE,
    )
    right_indices = (
        LandmarkIndex.RIGHT_SHOULDER,
        LandmarkIndex.RIGHT_HIP,
        LandmarkIndex.RIGHT_KNEE,
        LandmarkIndex.RIGHT_ANKLE,
    )
    locks_one_side = False
    no_start_reason = "Start position never detected — stand tall, side-on to the camera"

    def sample(self, frame: PoseFrame, side: BodySide | None) -> RepSample | None:
        points = [
            frame.get(index)
            for index in (
                LandmarkIndex.LEFT_SHOULDER,
                LandmarkIndex.RIGHT_SHOULDER,
                LandmarkIndex.LEFT_HIP,
                LandmarkIndex.LEFT_KNEE,
                LandmarkIndex.LEFT_ANKLE,
                LandmarkIndex.RIGHT_HIP,
                LandmarkIndex.RIGHT_KNEE,
                LandmarkIndex.RIGHT_ANKLE,
            )
        ]
        if any(point is None for point in points):
            return None
        (
            left_shoulder,
            right_shoulder,
            left_hip,
            left_knee,
            left_ankle,
            right_hip,
            right_knee,
            right_ankle,
        ) = points

        mean_knee = (
            angle(left_hip, left_knee, left_ankle)
            + angle(right_hip, right_knee, right_ankle)
        ) / 2.0

        if mean_knee > thresholds.LUNGE_PARTIAL_ANGLE:
            return RepSample(mean_knee)

        # The front knee is the higher one: front thigh near level, back knee dropping.
        left_leads = left_knee.y <= right_knee.y
        front_hip: PosePoint = left_hip if left_leads else right_hip
        front_knee: PosePoint = left_knee if left_leads else right_knee
        front_ankle: PosePoint = left_ankle if left_leads else right_ankle
        back_ankle: PosePoint = right_ankle if left_leads else left_ankle

        shin = distance(front_knee, front_ankle)
        leg_length = distance(front_hip, front_knee) + shin
        stance = abs(front_ankle.x - back_ankle.x)

        issues: set[FormIssue] = set()
        if stance < thresholds.LUNGE_MIN_STANCE_RATIO * leg_length:
            issues.add(FormIssue.STANCE_TOO_NARROW)

        forward = 1.0 if front_knee.x >= front_hip.x else -1.0
        if (front_knee.x - front_ankle.x) * forward > (
            thresholds.LUNGE_MAX_KNEE_TRAVEL_RATIO * shin
        ):
            issues.add(FormIssue.KNEE_PAST_TOES)

        torso_lean = angle_from_vertical(
            _midpoint(left_shoulder, right_shoulder), _midpoint(left_hip, right_hip)
        )
        if torso_lean > thresholds.LUNGE_MAX_TORSO_LEAN_DEG:
            issues.add(FormIssue.TORSO_LEAN)

        return RepSample(mean_knee, frozenset(issues))


# ---------------------------------------------------------------------------
# Bicep curl
# ---------------------------------------------------------------------------


class BicepCurlAnalyzer(RepExerciseAnalyzer):
    """Working-arm elbow angle, facing the camera.

    Both arms get their own rep machine. The working arm is given or locked to
    the first arm to complete a full-range rep; a full curl by the other arm
    while the working arm stayed down is recorded as the wrong arm.
    """

    test_type = TestType.BICEP_CURLS
    rep_thresholds = RepThresholds(
        thresholds.CURL_EXTENDED_ANGLE,
        thresholds.CURL_DEPTH_ANGLE,
        thresholds.CURL_PARTIAL_ANGLE,
        thresholds.CURL_MIN_REP_DURATION_MS,
    )
    left_indices = (
        LandmarkIndex.LEFT_SHOULDER,
        LandmarkIndex.LEFT_ELBOW,
        LandmarkIndex.LEFT_WRIST,
        LandmarkIndex.LEFT_HIP,
    )
    right_indices = (
        LandmarkIndex.RIGHT_SHOULDER,
        LandmarkIndex.RIGHT_ELBOW,
        LandmarkIndex.RIGHT_WRIST,
        LandmarkIndex.RIGHT_HIP,
    )
    locks_one_side = False
    no_start_reason = "Start position never detected — stand facing the camera"

    def __init__(self, working_arm: BodySide | None = None) -> None:
        self._working_arm = working_arm
        self._left_tracker = RepCycleTracker(self.rep_thresholds)
        self._right_tracker = RepCycleTracker(self.rep_thresholds)
        super().__init__()

    def reset(self) -> None:
        super().reset()
        self._left_tracker.reset()
        self._right_tracker.reset()
        self.active_arm = self._working_arm
        self._wrong_arm_reps = 0
        self._working_deepest_during_other_rep = _MAX_ANGLE
        self._reference_shoulders: PosePoint | None = None
        self._reference_shoulder_width = 0.0

    def _body_swinging(self, frame: PoseFrame) -> bool:
        """Shoulders travelled too far since both arms were last at rest."""
        left = frame.get(LandmarkIndex.LEFT_SHOULDER)
        right = frame.get(LandmarkIndex.RIGHT_SHOULDER)
        if left is None or right is None:
            return False
        middle = _midpoint(left, right)

        resting = (
            self._left_tracker.phase is not RepPhase.IN_REP
            and self._right_tracker.phase is not RepPhase.IN_REP
        )
        if resting:
            self._reference_shoulders = middle
            self._reference_shoulder_width = distance(left, right)
            return False

        if self._reference_shoulders is None:
            return False
        return distance(middle, self._reference_shoulders) > (
            thresholds.CURL_MAX_BODY_SWAY_RATIO * self._reference_shoulder_width
        )

    def _arm_sample(
        self, frame: PoseFrame, arm: BodySide, swinging: bool = False
    ) -> RepSample | None:
        shoulder = frame.get(
            _index(arm, LandmarkIndex.LEFT_SHOULDER, LandmarkIndex.RIGHT_SHOULDER)
        )
        elbow = frame.get(
            _index(arm, LandmarkIndex.LEFT_ELBOW, LandmarkIndex.RIGHT_ELBOW)
        )
        wrist = frame.get(
            _index(arm, LandmarkIndex.LEFT_WRIST, LandmarkIndex.RIGHT_WRIST)
        )
        hip = frame.get(_index(arm, LandmarkIndex.LEFT_HIP, LandmarkIndex.RIGHT_HIP))
        if None in (shoulder, elbow, wrist, hip):
            return None

        issues: set[FormIssue] = set()
        if angle(hip, shoulder, elbow) > thresholds.CURL_MAX_ELBOW_FLARE_DEG:
            issues.add(FormIssue.ELBOW_FLARE)
        if swinging:
            issues.add(FormIssue.BODY_SWING)
        return RepSample(angle(shoulder, elbow, wrist), frozenset(issues))

    def sample(self, frame: PoseFrame, side: BodySide | None) -> RepSample | None:
        return self._arm_sample(frame, side) if side is not None else None

    def _tracker_for(self, arm: BodySide) -> RepCycleTracker:
        return self._left_tracker if arm is BodySide.LEFT else self._right_tracker

    @staticmethod
    def _other(arm: BodySide) -> BodySide:
        return BodySide.RIGHT if arm is BodySide.LEFT else BodySide.LEFT

    def _process(self, frame: PoseFrame) -> None:
        swinging = self._body_swinging(frame)
        left = self._arm_sample(frame, BodySide.LEFT, swinging)
        right = self._arm_sample(frame, BodySide.RIGHT, swinging)
        if left is None or right is None:
            return

        arm = self.active_arm
        if arm is not None:
            other_tracker = self._tracker_for(self._other(arm))
            other_was_in_rep = other_tracker.phase is RepPhase.IN_REP
            other_event = other_tracker.update(
                right if arm is BodySide.LEFT else left, frame.timestamp_ms
            )
            working = left if arm is BodySide.LEFT else right

            if not other_was_in_rep and other_tracker.phase is RepPhase.IN_REP:
                self._working_deepest_during_other_rep = _MAX_ANGLE
            self._working_deepest_during_other_rep = min(
                self._working_deepest_during_other_rep, working.angle
            )

            event = self._tracker_for(arm).update(working, frame.timestamp_ms)
            if event is not None:
                self._record(event)
            if other_event is not None:
                self._on_other_arm(other_event)
            return

        left_event = self._left_tracker.update(left, frame.timestamp_ms)
        right_event = self._right_tracker.update(right, frame.timestamp_ms)
        self._on_unassigned(BodySide.LEFT, left_event, frame.timestamp_ms)
        self._on_unassigned(BodySide.RIGHT, right_event, frame.timestamp_ms)

    def _on_unassigned(
        self, arm: BodySide, event: RepEvent | None, timestamp_ms: int
    ) -> None:
        if event is None:
            return

        chosen = self.active_arm
        if chosen is not None:
            if chosen is not arm and event.reached_depth:
                return
            if chosen is arm:
                self._record(event)
            return

        if event.reached_depth:
            self.active_arm = arm
            self._working_deepest_during_other_rep = self.rep_thresholds.depth_angle
            self._events.append(AnalyzerEvent(timestamp_ms, "arm_locked", arm.value))

        self._record(event)

    def _on_other_arm(self, event: RepEvent) -> None:
        arm = self.active_arm
        if arm is None or not event.reached_depth:
            return

        if self._working_deepest_during_other_rep > self.rep_thresholds.partial_angle:
            self._wrong_arm_reps += 1
            self._events.append(
                AnalyzerEvent(
                    event.timestamp_ms,
                    "rep_rejected_wrong_arm",
                    f"Curl with the {self._other(arm).value} arm; "
                    f"working arm is {arm.value}",
                )
            )
