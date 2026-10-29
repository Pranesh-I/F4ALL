"""Endurance run, 600 m, from GPS — the distance in the Khelo India battery.

Ported line for line to ``EnduranceRunAnalyzer.kt``; the fixtures in
``tests/fixtures/field`` hold both to the same answers.

The protocol, which the app's instructions state:

1. Outdoors, on a track or a flat open route of at least 600 m, with the GPS
   ready (the app waits for a good fix before it lets the run start).
2. The phone counts down and beeps GO; the athlete carries it (hand, pocket,
   armband) and runs — walking is allowed, as in the official run/walk.
3. The time stops itself when the GPS distance reaches 600 m.

The track is the evidence (plan: "telemetry is primary"). What makes it
trustworthy:

* fixes worse than 25 m are ignored; a jump faster than a human can move is
  GPS error and skipped; a track with many jumps is not trusted at all;
* jitter smaller than 3 m is not counted as distance, so standing still does
  not add metres;
* the finish is interpolated between the two fixes either side of 600 m;
* the accelerometer must show steps — a bicycle, a scooter or a bus carries
  the phone the distance without any.
"""

from __future__ import annotations

import math

from . import thresholds as T
from .models import FieldResult, FieldTest, Fix, Invalid, Motion, Outcome
from .shuttle import magnitude

EARTH_RADIUS_M = 6371000.0
SPLIT_METRES = 100.0


def score_endurance(fixes: list[Fix], motion: list[Motion]) -> FieldResult:
    test = FieldTest.ENDURANCE_RUN
    fixes = sorted(fixes, key=lambda f: f.t_ms)
    motion = sorted(motion, key=lambda m: m.t_ms)

    accepted: list[Fix] = []
    considered = 0
    jumps = 0
    for fix in fixes:
        if fix.accuracy_m > T.ENDURANCE_MAX_ACCURACY_M:
            continue
        considered += 1
        if not accepted:
            accepted.append(fix)
            continue
        last = accepted[-1]
        seconds = (fix.t_ms - last.t_ms) / 1000.0
        if seconds <= 0.0:
            continue
        if distance_m(last, fix) / seconds > T.ENDURANCE_JUMP_SPEED_MPS:
            jumps += 1
            continue
        accepted.append(fix)

    metrics: dict = {"gps_jumps": jumps, "fixes_used": len(accepted)}
    if considered < 10:
        # Fixes arrived but too few were accurate enough: indoors, or no sky.
        reason = Invalid.GPS_WEAK if len(fixes) >= 10 else Invalid.NOT_ENOUGH_DATA
        return FieldResult.invalid_result(test, reason, metrics=metrics)
    if jumps > T.ENDURANCE_MAX_JUMPS or jumps / considered > T.ENDURANCE_MAX_JUMP_SHARE:
        return FieldResult.invalid_result(test, Invalid.GPS_UNRELIABLE, metrics=metrics)

    anchor_index = next(
        (i for i, f in enumerate(accepted) if f.t_ms >= -T.ENDURANCE_START_FIX_MS), None
    )
    if anchor_index is None or accepted[anchor_index].t_ms > T.ENDURANCE_START_FIX_MS:
        return FieldResult.invalid_result(test, Invalid.NO_START_FIX, metrics=metrics)

    events: list[tuple[int, str, str]] = [(0, "start", "")]
    pivot = accepted[anchor_index]
    total = 0.0
    next_split = SPLIT_METRES
    finish = None
    previous_t = pivot.t_ms
    for fix in accepted[anchor_index + 1 :]:
        if fix.t_ms - previous_t > T.ENDURANCE_MAX_FIX_GAP_MS:
            metrics["distance_m"] = round(total, 1)
            return FieldResult.invalid_result(test, Invalid.GPS_GAP, events, metrics)
        previous_t = fix.t_ms
        segment = distance_m(pivot, fix)
        if segment < T.ENDURANCE_MIN_SEGMENT_M:
            continue
        before = total
        total += segment
        while next_split <= total and next_split <= T.ENDURANCE_TARGET_METRES:
            at = pivot.t_ms + (fix.t_ms - pivot.t_ms) * (next_split - before) / segment
            if next_split < T.ENDURANCE_TARGET_METRES:
                events.append((_round_ms(at), "split", str(int(next_split))))
            next_split += SPLIT_METRES
        if total >= T.ENDURANCE_TARGET_METRES:
            finish = _round_ms(
                pivot.t_ms
                + (fix.t_ms - pivot.t_ms) * (T.ENDURANCE_TARGET_METRES - before) / segment
            )
            break
        pivot = fix

    metrics["distance_m"] = round(min(total, T.ENDURANCE_TARGET_METRES), 1)
    if finish is None:
        return FieldResult.invalid_result(
            test, Invalid.DISTANCE_NOT_REACHED, events, metrics
        )
    events.append((finish, "finish", ""))

    seconds = finish / 1000.0
    metrics["pace_s_per_km"] = round(seconds * 1000.0 / T.ENDURANCE_TARGET_METRES, 1)
    if (
        seconds <= 0.0
        or T.ENDURANCE_TARGET_METRES / seconds > T.ENDURANCE_MAX_AVERAGE_SPEED_MPS
    ):
        return FieldResult.invalid_result(test, Invalid.TOO_FAST, events, metrics)

    stepping, cadence = _stepping(motion, finish)
    metrics["cadence_spm"] = cadence
    if stepping < T.ENDURANCE_MIN_STEPPING_SHARE:
        return FieldResult.invalid_result(test, Invalid.NOT_ON_FOOT, events, metrics)

    return FieldResult(
        test=test,
        outcome=Outcome.COMPLETE,
        score=((finish + 50) // 100) / 10.0,
        events=events,
        metrics=metrics,
    )


def distance_m(a: Fix, b: Fix) -> float:
    """Great-circle distance (haversine)."""
    lat1, lat2 = math.radians(a.lat), math.radians(b.lat)
    d_lat = lat2 - lat1
    d_lon = math.radians(b.lon - a.lon)
    # Squares written out, not `** 2`, so the phone's port does the same arithmetic.
    half_lat = math.sin(d_lat / 2.0)
    half_lon = math.sin(d_lon / 2.0)
    h = half_lat * half_lat + math.cos(lat1) * math.cos(lat2) * half_lon * half_lon
    return 2.0 * EARTH_RADIUS_M * math.asin(math.sqrt(min(1.0, h)))


def _stepping(motion: list[Motion], finish: int) -> tuple[float, float]:
    """(share of windows with a stepping cadence, average cadence in steps/min)."""
    during = [m for m in motion if 0 <= m.t_ms <= finish]
    if len(during) < 3:
        return 0.0, 0.0
    sizes = [magnitude(m) for m in during]
    steps: list[int] = []
    for i in range(1, len(during) - 1):
        peak = sizes[i] > sizes[i - 1] and sizes[i] >= sizes[i + 1]
        if not peak or sizes[i] <= T.GRAVITY + T.ENDURANCE_STEP_PEAK:
            continue
        if steps and during[i].t_ms - steps[-1] < T.ENDURANCE_STEP_MIN_GAP_MS:
            continue
        steps.append(during[i].t_ms)

    windows = 0
    stepping = 0
    start = 0
    while start + T.ENDURANCE_CADENCE_WINDOW_MS <= finish:
        end = start + T.ENDURANCE_CADENCE_WINDOW_MS
        count = sum(1 for t in steps if start <= t < end)
        cadence = count * 60000.0 / T.ENDURANCE_CADENCE_WINDOW_MS
        windows += 1
        if cadence >= T.ENDURANCE_MIN_CADENCE:
            stepping += 1
        start = end
    if windows == 0:
        return 0.0, 0.0
    average = round(len(steps) * 60000.0 / finish, 1)
    return stepping / windows, average


def _round_ms(value: float) -> int:
    return math.floor(value + 0.5)
