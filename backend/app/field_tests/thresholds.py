"""Field-test constants, mirrored by the phone.

**These values MUST match**
``mobile/app/src/main/java/com/sai/sports/fieldtest/FieldThresholds.kt``.
``tests/test_field_tests.py`` compares the two by name, and the shared fixtures
in ``tests/fixtures/field`` prove both implementations score a trace the same.

Every value here is a starting point, set from the physics of the test rather
than from measured runs: the reference runs that calibrate them are Sprint 22's
job. Each comment says what the number protects against.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Shuttle run: 4 x 10 m, three turns, timed from the phone's GO beep
# ---------------------------------------------------------------------------

SHUTTLE_LEGS = 4
SHUTTLE_TURNS = 3
SHUTTLE_LEG_METRES = 10.0

# Before GO the athlete stands still; more movement than this is a false start.
# Standard deviation of |acceleration| over the second before GO, m/s^2.
SHUTTLE_STILL_STD = 0.6
SHUTTLE_PRESTART_MS = 1000

# A turn is the body rotating at least this far about the vertical within the
# window. 180 degrees in the ideal; running turns are rarely clean.
SHUTTLE_TURN_DEGREES = 120.0
SHUTTLE_TURN_WINDOW_MS = 1500
# Two turns closer than this are one turn seen twice.
SHUTTLE_TURN_GAP_MS = 800

# No 10 m leg, turn included, is quicker than this (world-class is about 1.6 s).
SHUTTLE_MIN_LEG_MS = 1200
# The fastest believable 4 x 10 m; faster is a mistimed or fabricated trace.
SHUTTLE_MIN_TOTAL_MS = 7000
SHUTTLE_MAX_TOTAL_MS = 30000

# The athlete runs through the line and stops within a couple of steps. The
# finish is when they are still for this long, less the time it takes to stop.
# The allowance is fitted to the synthetic traces in tests/field_traces.py,
# which model that stop; it is a placeholder until Sprint 22 measures it
# against hand-timed runs.
SHUTTLE_STOP_HOLD_MS = 1000
SHUTTLE_STOP_WINDOW_MS = 500
SHUTTLE_FINISH_ALLOWANCE_MS = 550

# Gravity follows slow changes in how the phone sits (time constant, ms).
SHUTTLE_GRAVITY_TIME_CONSTANT_MS = 1000

# Sensors must be sampled at least this often (median gap) and never pause long.
SHUTTLE_MAX_MEDIAN_GAP_MS = 25
SHUTTLE_MAX_GAP_MS = 200

# ---------------------------------------------------------------------------
# Endurance run: 600 m, timed by GPS distance
# ---------------------------------------------------------------------------

ENDURANCE_TARGET_METRES = 600.0

# A fix less accurate than this is ignored.
ENDURANCE_MAX_ACCURACY_M = 25.0
# A jump faster than this between two fixes is GPS error, not the athlete.
ENDURANCE_JUMP_SPEED_MPS = 12.0
# More jumps than this (or this share of fixes) and the track is not trusted.
ENDURANCE_MAX_JUMPS = 5
ENDURANCE_MAX_JUMP_SHARE = 0.10
# Movement smaller than this between counted points is GPS jitter.
ENDURANCE_MIN_SEGMENT_M = 3.0
# There must be a good fix this close to GO, or the start is unknown.
ENDURANCE_START_FIX_MS = 5000
ENDURANCE_MAX_FIX_GAP_MS = 15000

# 600 m faster than this average (about 70 s) is not on foot.
ENDURANCE_MAX_AVERAGE_SPEED_MPS = 8.5

# Steps: a peak in |acceleration| this far above gravity, at least this far apart.
ENDURANCE_STEP_PEAK = 2.0
ENDURANCE_STEP_MIN_GAP_MS = 250
# Running or walking: at least this cadence (steps/min) in this share of
# 10-second windows. A bicycle or a vehicle has no steps.
ENDURANCE_MIN_CADENCE = 90.0
ENDURANCE_CADENCE_WINDOW_MS = 10000
ENDURANCE_MIN_STEPPING_SHARE = 0.7

GRAVITY = 9.80665
