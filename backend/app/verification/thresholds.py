"""Server-side mirror of the mobile app's AnalyzerThresholds.

**These values MUST match**
``mobile/app/src/main/java/com/sai/sports/analyzer/AnalyzerThresholds.kt``.

Not a style preference — a correctness requirement. The server auto-flags a
submission when its score disagrees with the device's. If the two threshold
tables drift, that flag fires on honest athletes, and the person reviewing the
flag has no way to tell a real discrepancy from an implementation gap.

``test_parity.py`` runs both implementations over identical recorded landmark
sequences and asserts identical scores. When you change a number here, change it
there, and let the parity test prove it.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Shared
# ---------------------------------------------------------------------------

MIN_LANDMARK_VISIBILITY = 0.5
MAX_CONSECUTIVE_REJECTED_FRAMES = 15

# ---------------------------------------------------------------------------
# One-Euro smoothing
# ---------------------------------------------------------------------------
# Tuned for normalized landmark coordinates (0..1) at 15-30 fps. BETA is large
# because it multiplies small velocities; copying a value tuned for pixel
# coordinates would produce a filter that never adapts.

SMOOTHING_MIN_CUTOFF = 0.8
SMOOTHING_BETA = 25.0
SMOOTHING_DERIVATIVE_CUTOFF = 3.0

# ---------------------------------------------------------------------------
# Sit-ups
# ---------------------------------------------------------------------------

SITUP_DOWN_ENTER_ANGLE = 135.0
SITUP_UP_ENTER_ANGLE = 70.0
SITUP_PARTIAL_REP_ANGLE = 110.0

# 200ms, not 600ms. The SAI sit-up test scores maximum reps in a fixed window,
# so athletes go fast, and this guard deletes reps rather than flagging them —
# an over-strict value silently penalises the strongest athletes.
SITUP_MIN_REP_DURATION_MS = 200

# ---------------------------------------------------------------------------
# Vertical jump
# ---------------------------------------------------------------------------

JUMP_CALIBRATION_FRAMES = 12
JUMP_CALIBRATION_STABILITY = 0.008
NOSE_HEIGHT_STATURE_RATIO = 0.936
JUMP_TAKEOFF_CM = 6.0
JUMP_LANDING_CM = 3.0
JUMP_MIN_FLIGHT_MS = 150
JUMP_MAX_FLIGHT_MS = 2000
JUMP_MIN_PLAUSIBLE_CM = 2.0
JUMP_MAX_PLAUSIBLE_CM = 120.0
MAX_SCALE_DRIFT = 0.15

# ---------------------------------------------------------------------------
# Accuracy targets (Sprint 3 Definition of Done)
# ---------------------------------------------------------------------------

TARGET_SITUP_TOLERANCE_REPS = 1
TARGET_JUMP_TOLERANCE_CM = 3.0
