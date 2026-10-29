"""The data a field test is scored from, and what scoring it produces.

The phone sends the same structure it scored, so the server can score it
again: inertial samples for the shuttle run, GPS fixes plus inertial samples for
the endurance run. Times are milliseconds from the phone's GO beep; samples
before GO have negative times.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class FieldTest(str, Enum):
    __test__ = False

    SHUTTLE_RUN = "SHUTTLE_RUN"
    ENDURANCE_RUN = "ENDURANCE_RUN"

    @property
    def unit(self) -> str:
        return "seconds"


class Outcome(str, Enum):
    COMPLETE = "COMPLETE"
    INVALID = "INVALID"


class Invalid(str, Enum):
    """Why a trace could not be scored. Stable codes; the phone translates them."""

    NOT_ENOUGH_DATA = "not_enough_data"
    LOW_SAMPLE_RATE = "low_sample_rate"
    SENSOR_GAP = "sensor_gap"
    MOVED_BEFORE_START = "moved_before_start"
    WRONG_TURN_COUNT = "wrong_turn_count"
    LEG_TOO_FAST = "leg_too_fast"
    TOO_FAST = "too_fast"
    NO_FINISH = "no_finish"
    NO_START_FIX = "no_start_fix"
    GPS_WEAK = "gps_weak"
    GPS_UNRELIABLE = "gps_unreliable"
    GPS_GAP = "gps_gap"
    DISTANCE_NOT_REACHED = "distance_not_reached"
    NOT_ON_FOOT = "not_on_foot"


@dataclass(frozen=True)
class Motion:
    """One inertial sample: acceleration (m/s^2, gravity included) and,
    where the phone has a gyroscope, rotation rate (rad/s), in the phone's axes."""

    t_ms: int
    ax: float
    ay: float
    az: float
    gx: float = 0.0
    gy: float = 0.0
    gz: float = 0.0


@dataclass(frozen=True)
class Fix:
    t_ms: int
    lat: float
    lon: float
    accuracy_m: float


@dataclass
class FieldResult:
    test: FieldTest
    outcome: Outcome
    # Seconds; 0 when the trace could not be scored.
    score: float
    invalid: Invalid | None = None
    # (t_ms, label, detail) — the trace of what was found, for the athlete and
    # the reviewer: start, moving, turn, finish; or 100 m splits.
    events: list[tuple[int, str, str]] = field(default_factory=list)
    metrics: dict = field(default_factory=dict)

    @property
    def unit(self) -> str:
        return self.test.unit

    @classmethod
    def invalid_result(
        cls, test: FieldTest, reason: Invalid, events=None, metrics=None
    ) -> FieldResult:
        return cls(
            test=test,
            outcome=Outcome.INVALID,
            score=0.0,
            invalid=reason,
            events=events or [],
            metrics=metrics or {},
        )
