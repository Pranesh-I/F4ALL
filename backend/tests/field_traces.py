"""Synthetic shuttle-run and endurance-run traces, with the truth they encode.

Not a substitute for reference runs (Sprint 22) — a way to know exactly what
the right answer is, so the analyzers can be checked against it, and so the
phone and the server can be held to the same answers on the same input.

Deterministic: every trace takes a seed. Values are rounded to four decimals,
the precision the fixtures store, so both languages read identical numbers.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

from app.field_tests.models import Fix, Motion
from app.field_tests.thresholds import GRAVITY

ROUND = 4


@dataclass
class ShuttleTruth:
    samples: list[Motion]
    # When the athlete crossed the finish line, ms from GO.
    finish_ms: int
    turn_ms: list[int]


def shuttle_trace(
    *,
    seed: int = 1,
    rate_hz: int = 100,
    axis: str = "z",
    reaction_ms: int = 300,
    legs_ms: tuple[int, ...] = (2600, 2400, 2400, 2300),
    run_through_ms: int = 300,
    stop_ms: int = 500,
    still_after_ms: int = 2500,
    moves_before_go: bool = False,
    gap_at_ms: int | None = None,
) -> ShuttleTruth:
    """4 x 10 m: still, GO, legs separated by 180-degree turns, run through, stop.

    ``axis`` is which phone axis gravity lies along — "z" lying flat in a
    waistband pouch, "y" upright in a pocket, "x" sideways — so the analyzer
    can be shown not to care how the phone is carried.
    """
    rng = random.Random(seed)
    step = 1000 // rate_hz
    turn_half = 300  # a turn takes 0.6 s
    crossings = []
    elapsed = 0
    for leg in legs_ms:
        elapsed += leg
        crossings.append(elapsed)
    turn_times = crossings[:-1]
    finish = crossings[-1]
    end = finish + run_through_ms + stop_ms + still_after_ms

    samples = []
    t = -1500
    direction = 1.0
    while t <= end:
        if gap_at_ms is not None and gap_at_ms <= t < gap_at_ms + 400:
            t += step
            continue
        vertical = GRAVITY + rng.gauss(0, 0.05)
        sideways = rng.gauss(0, 0.05)
        forward = rng.gauss(0, 0.05)
        yaw = rng.gauss(0, 0.02)

        running = reaction_ms <= t < finish + run_through_ms
        stopping = finish + run_through_ms <= t < finish + run_through_ms + stop_ms
        if moves_before_go and -900 <= t < 0:
            running = True
        if running:
            stride = math.sin(2 * math.pi * 3.5 * t / 1000.0)
            vertical += 4.0 * stride + rng.gauss(0, 0.3)
            forward += 1.5 * math.cos(2 * math.pi * 3.5 * t / 1000.0) + rng.gauss(0, 0.3)
        elif stopping:
            fade = 1.0 - (t - finish - run_through_ms) / stop_ms
            vertical += 4.0 * fade * math.sin(2 * math.pi * 3.5 * t / 1000.0)
            forward -= 3.0 * fade
        for index, turn in enumerate(turn_times):
            if turn - turn_half <= t < turn + turn_half:
                direction = 1.0 if index % 2 == 0 else -1.0
                yaw += direction * math.pi / (2 * turn_half / 1000.0)

        if axis == "z":
            ax, ay, az, gx, gy, gz = sideways, forward, vertical, 0.0, 0.0, yaw
        elif axis == "y":
            ax, ay, az, gx, gy, gz = sideways, vertical, forward, 0.0, yaw, 0.0
        else:
            ax, ay, az, gx, gy, gz = vertical, forward, sideways, yaw, 0.0, 0.0
        samples.append(Motion(t, *(round(v, ROUND) for v in (ax, ay, az, gx, gy, gz))))
        t += step
    return ShuttleTruth(samples=samples, finish_ms=finish, turn_ms=turn_times)


@dataclass
class EnduranceTruth:
    fixes: list[Fix]
    motion: list[Motion]
    # When the athlete really reached 600 m, ms from GO (None if they never did).
    finish_ms: int | None


START_LAT, START_LON = 12.9716, 77.5946  # an open ground in Bengaluru
METRES_PER_DEGREE_LAT = 111320.0


def endurance_trace(
    *,
    seed: int = 1,
    speed_mps: float = 4.5,
    cadence_spm: float = 170.0,
    step_amplitude: float = 6.0,
    distance_m: float = 650.0,
    accuracy_m: float = 5.0,
    jumps: int = 0,
    start_fix: bool = True,
    walk_from_m: float | None = None,
) -> EnduranceTruth:
    """A straight run north at a steady pace, GPS once a second, motion at 25 Hz.

    GPS noise is correlated from one fix to the next, as real receivers'
    error is; white noise would inflate the distance far more than real GPS
    does. ``walk_from_m`` slows to a walk (and a walking cadence) after that
    distance; ``jumps`` inserts fixes hundreds of metres off the track.
    """
    rng = random.Random(seed)
    duration_ms = int(distance_m / speed_mps * 1000) + 3000
    if walk_from_m is not None:
        duration_ms += int((600.0 - walk_from_m) / 1.6 * 1000) + 10000

    def travelled(t_ms: int) -> float:
        seconds = max(0, t_ms) / 1000.0
        if walk_from_m is None:
            return speed_mps * seconds
        switch = walk_from_m / speed_mps
        if seconds <= switch:
            return speed_mps * seconds
        return walk_from_m + 1.6 * (seconds - switch)

    true_finish = None
    t = 0
    while t <= duration_ms:
        if travelled(t) >= 600.0:
            true_finish = t
            break
        t += 10

    fixes = []
    north = east = 0.0
    jump_at = set(rng.sample(range(20, 90), jumps)) if jumps else set()
    for index, t_ms in enumerate(
        range(-3000 if start_fix else 9000, duration_ms + 1, 1000)
    ):
        north = 0.9 * north + rng.gauss(0, 0.4)
        east = 0.9 * east + rng.gauss(0, 0.4)
        metres = travelled(t_ms) + north
        lat, lon = START_LAT + metres / METRES_PER_DEGREE_LAT, START_LON + east / 108000.0
        if index in jump_at:
            lat += 400.0 / METRES_PER_DEGREE_LAT
        fixes.append(Fix(t_ms, round(lat, 7), round(lon, 7), accuracy_m))

    motion = []
    for t_ms in range(-2000, duration_ms + 1, 40):
        seconds = max(0, t_ms) / 1000.0
        walking = walk_from_m is not None and travelled(t_ms) > walk_from_m
        rate = (110.0 if walking else cadence_spm) / 60.0
        amplitude = (3.5 if walking else step_amplitude) if t_ms >= 0 else 0.0
        vertical = (
            GRAVITY
            + amplitude * math.sin(2 * math.pi * rate * seconds)
            + rng.gauss(0, 0.2)
        )
        motion.append(
            Motion(
                t_ms,
                round(rng.gauss(0, 0.3), ROUND),
                round(vertical, ROUND),
                round(rng.gauss(0, 0.3), ROUND),
            )
        )
    return EnduranceTruth(fixes=fixes, motion=motion, finish_ms=true_finish)
