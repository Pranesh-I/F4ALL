"""Shuttle run, 4 x 10 m, from the phone's motion sensors.

Ported line for line to ``ShuttleRunAnalyzer.kt``; the fixtures in
``tests/fixtures/field`` hold both to the same answers.

The protocol, which the app's instructions state and this code assumes:

1. Two lines 10 m apart. The athlete wears the phone at the waist (pocket,
   pouch or waistband) or holds it firmly.
2. They stand still behind the start line; the phone counts down and beeps GO.
   The time runs from GO, as with a stopwatch and a whistle.
3. Four lengths, turning three times, then they run through the start line,
   stop within a couple of steps and stand still.

What the sensors give:

* **Turns** are the body rotating about the vertical. The gyroscope's rate is
  projected onto gravity (tracked by a slow low-pass of the accelerometer), so
  it does not matter which way up or round the phone is carried.
* **The finish** is when the athlete becomes still after the third turn, less a
  fixed allowance for the steps it takes to stop. That allowance is a starting
  value; Sprint 22's hand-timed reference runs calibrate it.
"""

from __future__ import annotations

import math
from itertools import pairwise

from . import thresholds as T
from .models import FieldResult, FieldTest, Invalid, Motion, Outcome

RADIANS_TO_DEGREES = 180.0 / math.pi


def score_shuttle(samples: list[Motion]) -> FieldResult:
    samples = sorted(samples, key=lambda s: s.t_ms)
    test = FieldTest.SHUTTLE_RUN
    if len(samples) < 50 or samples[0].t_ms >= 0 or samples[-1].t_ms <= 0:
        return FieldResult.invalid_result(test, Invalid.NOT_ENOUGH_DATA)

    gaps = sorted(b.t_ms - a.t_ms for a, b in pairwise(samples))
    if gaps[len(gaps) // 2] > T.SHUTTLE_MAX_MEDIAN_GAP_MS:
        return FieldResult.invalid_result(test, Invalid.LOW_SAMPLE_RATE)
    if gaps[-1] > T.SHUTTLE_MAX_GAP_MS:
        return FieldResult.invalid_result(test, Invalid.SENSOR_GAP)

    before = [s for s in samples if -T.SHUTTLE_PRESTART_MS <= s.t_ms < 0]
    if len(before) < 10:
        return FieldResult.invalid_result(test, Invalid.NOT_ENOUGH_DATA)
    if _std([magnitude(s) for s in before]) > T.SHUTTLE_STILL_STD:
        return FieldResult.invalid_result(test, Invalid.MOVED_BEFORE_START)

    after = [s for s in samples if s.t_ms >= 0]
    turns = _turns(before, after)
    events: list[tuple[int, str, str]] = [(0, "start", "")]
    moving = _first_moving(after)
    if moving is not None:
        events.append((moving, "moving", ""))
    metrics: dict = {"turns": len(turns)}

    if len(turns) < T.SHUTTLE_TURNS:
        return FieldResult.invalid_result(
            test, Invalid.WRONG_TURN_COUNT, events + _turn_events(turns), metrics
        )

    stop = _stillness_after(after, turns[T.SHUTTLE_TURNS - 1] + T.SHUTTLE_MIN_LEG_MS)
    if stop is None:
        return FieldResult.invalid_result(
            test, Invalid.NO_FINISH, events + _turn_events(turns), metrics
        )
    finish = stop - T.SHUTTLE_FINISH_ALLOWANCE_MS

    counted = [t for t in turns if t < stop]
    metrics["turns"] = len(counted)
    events += _turn_events(counted)
    if len(counted) != T.SHUTTLE_TURNS:
        return FieldResult.invalid_result(test, Invalid.WRONG_TURN_COUNT, events, metrics)

    marks = [0, *counted, finish]
    legs = [b - a for a, b in pairwise(marks)]
    metrics["leg_ms"] = legs
    events.append((finish, "finish", ""))

    if finish > T.SHUTTLE_MAX_TOTAL_MS:
        return FieldResult.invalid_result(test, Invalid.NO_FINISH, events, metrics)
    if any(leg < T.SHUTTLE_MIN_LEG_MS for leg in legs):
        return FieldResult.invalid_result(test, Invalid.LEG_TOO_FAST, events, metrics)
    if finish < T.SHUTTLE_MIN_TOTAL_MS:
        return FieldResult.invalid_result(test, Invalid.TOO_FAST, events, metrics)

    return FieldResult(
        test=test,
        outcome=Outcome.COMPLETE,
        score=((finish + 5) // 10) / 100.0,
        events=events,
        metrics=metrics,
    )


def magnitude(sample: Motion) -> float:
    return math.sqrt(
        sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az
    )


def _turns(before: list[Motion], after: list[Motion]) -> list[int]:
    """Times (ms) at which the body had turned far enough to count a turn."""
    count = float(len(before))
    gravity = [
        sum(s.ax for s in before) / count,
        sum(s.ay for s in before) / count,
        sum(s.az for s in before) / count,
    ]
    window: list[tuple[int, float]] = []
    turned = 0.0
    turns: list[int] = []
    previous = after[0].t_ms
    for sample in after:
        dt = float(sample.t_ms - previous)
        previous = sample.t_ms
        alpha = dt / (T.SHUTTLE_GRAVITY_TIME_CONSTANT_MS + dt)
        gravity[0] += alpha * (sample.ax - gravity[0])
        gravity[1] += alpha * (sample.ay - gravity[1])
        gravity[2] += alpha * (sample.az - gravity[2])
        size = math.sqrt(
            gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]
        )
        if size == 0.0:
            continue
        yaw = (
            sample.gx * gravity[0] + sample.gy * gravity[1] + sample.gz * gravity[2]
        ) / size
        step = yaw * (dt / 1000.0) * RADIANS_TO_DEGREES

        # The rest of a turn just counted is not the start of the next one:
        # left in the window, it would cancel part of a turn the other way.
        if turns and sample.t_ms - turns[-1] < T.SHUTTLE_TURN_GAP_MS:
            continue

        window.append((sample.t_ms, step))
        turned += step
        while window and window[0][0] < sample.t_ms - T.SHUTTLE_TURN_WINDOW_MS:
            turned -= window.pop(0)[1]

        if abs(turned) >= T.SHUTTLE_TURN_DEGREES:
            turns.append(sample.t_ms)
            window.clear()
            turned = 0.0
    return turns


def _windowed_std(after: list[Motion], index: int) -> float | None:
    """|acceleration| spread over the STOP window ending at ``index``; None if
    the trace does not reach that far back."""
    end = after[index].t_ms
    start = end - T.SHUTTLE_STOP_WINDOW_MS
    if after[0].t_ms > start:
        return None
    values = []
    j = index
    while j >= 0 and after[j].t_ms >= start:
        values.append(magnitude(after[j]))
        j -= 1
    return _std(values)


def _first_moving(after: list[Motion]) -> int | None:
    for index, sample in enumerate(after):
        spread = _windowed_std(after, index)
        if spread is not None and spread > T.SHUTTLE_STILL_STD:
            return sample.t_ms
    return None


def _stillness_after(after: list[Motion], earliest: int) -> int | None:
    """When the athlete became still (and stayed still), at or after ``earliest``."""
    for index, sample in enumerate(after):
        if sample.t_ms < earliest:
            continue
        spread = _windowed_std(after, index)
        if spread is None or spread >= T.SHUTTLE_STILL_STD:
            continue
        onset = sample.t_ms - T.SHUTTLE_STOP_WINDOW_MS
        hold_until = sample.t_ms + T.SHUTTLE_STOP_HOLD_MS
        if after[-1].t_ms < hold_until:
            return None
        still = True
        j = index + 1
        while j < len(after) and after[j].t_ms <= hold_until:
            later = _windowed_std(after, j)
            if later is None or later >= T.SHUTTLE_STILL_STD:
                still = False
                break
            j += 1
        if still:
            return onset
    return None


def _turn_events(turns: list[int]) -> list[tuple[int, str, str]]:
    return [(t, "turn", str(number)) for number, t in enumerate(turns, start=1)]


def _std(values: list[float]) -> float:
    count = float(len(values))
    mean = sum(values) / count
    return math.sqrt(sum((v - mean) * (v - mean) for v in values) / count)
