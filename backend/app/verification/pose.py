"""Pose primitives, geometry, smoothing and the frame-quality gate.

A direct port of the mobile analyzer's shared layer. Kept free of mediapipe,
numpy and opencv so the scoring logic is importable and testable on its own —
the same property that makes the Kotlin analyzers JVM-testable.
"""

from __future__ import annotations

import csv
import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from enum import Enum
from io import StringIO

from . import thresholds

LANDMARK_COUNT = 33


class LandmarkIndex:
    """MediaPipe Pose landmark indices."""

    NOSE = 0
    LEFT_EYE_INNER = 1
    LEFT_EYE = 2
    LEFT_EYE_OUTER = 3
    RIGHT_EYE_INNER = 4
    RIGHT_EYE = 5
    RIGHT_EYE_OUTER = 6
    LEFT_EAR = 7
    RIGHT_EAR = 8
    LEFT_SHOULDER = 11
    RIGHT_SHOULDER = 12
    LEFT_ELBOW = 13
    RIGHT_ELBOW = 14
    LEFT_WRIST = 15
    RIGHT_WRIST = 16
    LEFT_HIP = 23
    RIGHT_HIP = 24
    LEFT_KNEE = 25
    RIGHT_KNEE = 26
    LEFT_ANKLE = 27
    RIGHT_ANKLE = 28
    LEFT_HEEL = 29
    RIGHT_HEEL = 30
    LEFT_FOOT_INDEX = 31
    RIGHT_FOOT_INDEX = 32


class BodySide(str, Enum):
    LEFT = "LEFT"
    RIGHT = "RIGHT"


@dataclass(frozen=True)
class PosePoint:
    """One landmark, normalized to the frame (0..1), origin top-left.

    y grows DOWNWARD, so upward movement means y decreases.
    """

    x: float
    y: float
    z: float = 0.0
    visibility: float = 0.0


@dataclass(frozen=True)
class PoseFrame:
    timestamp_ms: int
    points: Sequence[PosePoint]

    def get(self, index: int) -> PosePoint | None:
        if 0 <= index < len(self.points):
            return self.points[index]
        return None

    def is_visible(
        self, index: int, minimum: float = thresholds.MIN_LANDMARK_VISIBILITY
    ) -> bool:
        point = self.get(index)
        return point is not None and point.visibility >= minimum

    def mean_visibility(self, indices: Iterable[int]) -> float:
        indices = list(indices)
        if not indices:
            return 0.0
        total = 0.0
        for index in indices:
            point = self.get(index)
            if point is None:
                return 0.0
            total += point.visibility
        return total / len(indices)


# ---------------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------------


def angle(first: PosePoint, vertex: PosePoint, second: PosePoint) -> float:
    """Angle in degrees at ``vertex``, in the image plane only.

    z is a relative depth estimate, not a metric one; folding it in at this
    resolution adds more noise than signal.
    """
    first_x = first.x - vertex.x
    first_y = first.y - vertex.y
    second_x = second.x - vertex.x
    second_y = second.y - vertex.y

    dot = first_x * second_x + first_y * second_y
    first_magnitude = math.sqrt(first_x * first_x + first_y * first_y)
    second_magnitude = math.sqrt(second_x * second_x + second_y * second_y)

    if first_magnitude == 0.0 or second_magnitude == 0.0:
        return 0.0

    cosine = dot / (first_magnitude * second_magnitude)
    cosine = max(-1.0, min(1.0, cosine))
    return math.degrees(math.acos(cosine))


def distance(first: PosePoint, second: PosePoint) -> float:
    """Euclidean distance in the image plane, in normalized units."""
    return math.hypot(first.x - second.x, first.y - second.y)


def angle_from_vertical(upper: PosePoint, lower: PosePoint) -> float:
    """Angle between ``lower`` -> ``upper`` and straight up: 0 upright, 90 level."""
    return math.degrees(math.atan2(abs(upper.x - lower.x), -(upper.y - lower.y)))


def angle_from_horizontal(first: PosePoint, second: PosePoint) -> float:
    """Angle between ``first`` -> ``second`` and the horizontal, ignoring direction."""
    return math.degrees(
        math.atan2(abs(second.y - first.y), abs(second.x - first.x))
    )


def cross_product(start: PosePoint, end: PosePoint, point: PosePoint) -> float:
    """Which side of ``start`` -> ``end`` the ``point`` lies on (2D cross product)."""
    return (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (
        point.x - start.x
    )


def mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def standard_deviation(values: Sequence[float]) -> float:
    """Population standard deviation, matching the Kotlin implementation."""
    if len(values) < 2:
        return 0.0
    average = mean(values)
    variance = sum((value - average) ** 2 for value in values) / len(values)
    return math.sqrt(variance)


def select_more_visible_side(
    frame: PoseFrame, left_indices: Sequence[int], right_indices: Sequence[int]
) -> BodySide:
    """Pick whichever half of the body the model can actually see.

    These tests are filmed from the side, so one half is always partly occluded.
    """
    if frame.mean_visibility(right_indices) > frame.mean_visibility(left_indices):
        return BodySide.RIGHT
    return BodySide.LEFT


# ---------------------------------------------------------------------------
# One-Euro smoothing
# ---------------------------------------------------------------------------


class OneEuroFilter:
    """Adaptive low-pass filter.

    Heavier smoothing at rest, lighter during fast motion. A moving average
    would lag, and lag blunts exactly the peak frame a vertical jump is measured
    from — producing a consistent under-estimate that looks plausible.
    """

    def __init__(
        self,
        min_cutoff: float = thresholds.SMOOTHING_MIN_CUTOFF,
        beta: float = thresholds.SMOOTHING_BETA,
        derivative_cutoff: float = thresholds.SMOOTHING_DERIVATIVE_CUTOFF,
    ) -> None:
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.derivative_cutoff = derivative_cutoff
        self.reset()

    def reset(self) -> None:
        self._previous_value: float | None = None
        self._previous_derivative = 0.0
        self._previous_timestamp_ms: int | None = None

    @staticmethod
    def _alpha(cutoff: float, sample_rate: float) -> float:
        time_constant = 1.0 / (2.0 * math.pi * cutoff)
        sample_period = 1.0 / sample_rate
        return 1.0 / (1.0 + time_constant / sample_period)

    @staticmethod
    def _low_pass(value: float, previous: float, alpha: float) -> float:
        return alpha * value + (1.0 - alpha) * previous

    def filter(self, value: float, timestamp_ms: int) -> float:
        last_value = self._previous_value
        last_timestamp = self._previous_timestamp_ms

        if last_value is None or last_timestamp is None:
            self._previous_value = value
            self._previous_timestamp_ms = timestamp_ms
            self._previous_derivative = 0.0
            return value

        elapsed_seconds = (timestamp_ms - last_timestamp) / 1000.0

        # Duplicate or out-of-order timestamps imply an infinite rate.
        if elapsed_seconds <= 0.0:
            return last_value

        sample_rate = 1.0 / elapsed_seconds

        raw_derivative = (value - last_value) * sample_rate
        smoothed_derivative = self._low_pass(
            raw_derivative,
            self._previous_derivative,
            self._alpha(self.derivative_cutoff, sample_rate),
        )

        cutoff = self.min_cutoff + self.beta * abs(smoothed_derivative)
        smoothed_value = self._low_pass(
            value, last_value, self._alpha(cutoff, sample_rate)
        )

        self._previous_value = smoothed_value
        self._previous_derivative = smoothed_derivative
        self._previous_timestamp_ms = timestamp_ms
        return smoothed_value


class PoseSmoother:
    def __init__(self, landmark_count: int = LANDMARK_COUNT) -> None:
        self._x = [OneEuroFilter() for _ in range(landmark_count)]
        self._y = [OneEuroFilter() for _ in range(landmark_count)]
        self._z = [OneEuroFilter() for _ in range(landmark_count)]

    def reset(self) -> None:
        for filters in (self._x, self._y, self._z):
            for one_euro in filters:
                one_euro.reset()

    def smooth(self, frame: PoseFrame) -> PoseFrame:
        smoothed: list[PosePoint] = []

        for index, point in enumerate(frame.points):
            if index >= len(self._x):
                smoothed.append(point)
                continue

            # An invisible landmark's coordinates are meaningless; feeding them
            # to the filter poisons its state for the next real samples.
            if point.visibility < thresholds.MIN_LANDMARK_VISIBILITY:
                smoothed.append(point)
                continue

            smoothed.append(
                PosePoint(
                    x=self._x[index].filter(point.x, frame.timestamp_ms),
                    y=self._y[index].filter(point.y, frame.timestamp_ms),
                    z=self._z[index].filter(point.z, frame.timestamp_ms),
                    visibility=point.visibility,
                )
            )

        return PoseFrame(timestamp_ms=frame.timestamp_ms, points=smoothed)


# ---------------------------------------------------------------------------
# Frame quality
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class QualityVerdict:
    accepted: bool
    mean_visibility: float
    missing_indices: tuple[int, ...] = field(default=())


class FrameQualityGate:
    """Drops frames whose required landmarks are not reliably visible.

    A frame where the model guessed at an occluded knee yields a plausible-
    looking wrong angle, and a plausible-looking wrong angle is what makes a rep
    counter miscount. Dropping it costs one sample; trusting it costs a rep.
    """

    def __init__(
        self,
        required_indices: Sequence[int],
        minimum_visibility: float = thresholds.MIN_LANDMARK_VISIBILITY,
    ) -> None:
        self.required_indices = list(required_indices)
        self.minimum_visibility = minimum_visibility

    def evaluate(self, frame: PoseFrame) -> QualityVerdict:
        if not frame.points:
            return QualityVerdict(
                accepted=False,
                mean_visibility=0.0,
                missing_indices=tuple(self.required_indices),
            )

        missing = tuple(
            index
            for index in self.required_indices
            if not frame.is_visible(index, self.minimum_visibility)
        )

        return QualityVerdict(
            accepted=not missing,
            mean_visibility=frame.mean_visibility(self.required_indices),
            missing_indices=missing,
        )


# ---------------------------------------------------------------------------
# Sequence serialization (mirrors PoseSequenceCodec.kt)
# ---------------------------------------------------------------------------


def decode_sequence(text: str) -> list[PoseFrame]:
    """Parse a pose sequence CSV written by the mobile app.

    Malformed rows are skipped rather than raising: a recording truncated by a
    killed process should still yield the frames it managed to write.
    """
    frames: list[PoseFrame] = []

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("timestamp_ms"):
            continue

        cells = line.split(",")
        if len(cells) < 5:
            continue

        try:
            timestamp_ms = int(cells[0])
        except ValueError:
            continue

        points: list[PosePoint] = []
        cursor = 1
        while cursor + 3 < len(cells):
            try:
                points.append(
                    PosePoint(
                        x=float(cells[cursor]),
                        y=float(cells[cursor + 1]),
                        z=float(cells[cursor + 2]),
                        visibility=float(cells[cursor + 3]),
                    )
                )
            except ValueError:
                points.append(PosePoint(0.0, 0.0, 0.0, 0.0))
            cursor += 4

        frames.append(PoseFrame(timestamp_ms=timestamp_ms, points=points))

    return frames


def encode_sequence(frames: Sequence[PoseFrame], test_name: str) -> str:
    buffer = StringIO()
    buffer.write(
        f"# f4all-pose-sequence v1 test={test_name} landmarks={LANDMARK_COUNT}\n"
    )

    writer = csv.writer(buffer, lineterminator="\n")
    header = ["timestamp_ms"]
    for index in range(LANDMARK_COUNT):
        header += [f"l{index}_x", f"l{index}_y", f"l{index}_z", f"l{index}_v"]
    writer.writerow(header)

    for frame in frames:
        row: list[object] = [frame.timestamp_ms]
        for index in range(LANDMARK_COUNT):
            point = frame.get(index)
            if point is None:
                row += [0, 0, 0, 0]
            else:
                row += [
                    f"{point.x:.5f}",
                    f"{point.y:.5f}",
                    f"{point.z:.5f}",
                    f"{point.visibility:.5f}",
                ]
        writer.writerow(row)

    return buffer.getvalue()
