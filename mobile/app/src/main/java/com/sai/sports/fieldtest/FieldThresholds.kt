package com.sai.sports.fieldtest

/**
 * Field-test constants, mirrored by the server.
 *
 * **These values MUST match** `backend/app/field_tests/thresholds.py`. The
 * backend's `test_every_threshold_matches_the_phone` compares them by name, and
 * `FieldParityTest` scores the shared fixtures to prove the two implementations
 * agree. Every value is a starting point set from the physics of the test; the
 * reference runs that calibrate them are Sprint 22's job.
 */
object FieldThresholds {

    // -- Shuttle run: 4 x 10 m, three turns, timed from the GO beep ---------

    const val SHUTTLE_LEGS = 4
    const val SHUTTLE_TURNS = 3
    const val SHUTTLE_LEG_METRES = 10.0

    /** Spread of |acceleration| in the second before GO above which it is a false start. */
    const val SHUTTLE_STILL_STD = 0.6
    const val SHUTTLE_PRESTART_MS = 1000L

    /** Rotation about the vertical within the window that counts as a turn. */
    const val SHUTTLE_TURN_DEGREES = 120.0
    const val SHUTTLE_TURN_WINDOW_MS = 1500L
    const val SHUTTLE_TURN_GAP_MS = 800L

    const val SHUTTLE_MIN_LEG_MS = 1200L
    const val SHUTTLE_MIN_TOTAL_MS = 7000L
    const val SHUTTLE_MAX_TOTAL_MS = 30000L

    /** Still for this long after the last leg ends the run; the allowance is the stopping steps. */
    const val SHUTTLE_STOP_HOLD_MS = 1000L
    const val SHUTTLE_STOP_WINDOW_MS = 500L
    const val SHUTTLE_FINISH_ALLOWANCE_MS = 550L

    const val SHUTTLE_GRAVITY_TIME_CONSTANT_MS = 1000L

    const val SHUTTLE_MAX_MEDIAN_GAP_MS = 25L
    const val SHUTTLE_MAX_GAP_MS = 200L

    // -- Endurance run: 600 m, timed by GPS distance ------------------------

    const val ENDURANCE_TARGET_METRES = 600.0

    const val ENDURANCE_MAX_ACCURACY_M = 25.0
    const val ENDURANCE_JUMP_SPEED_MPS = 12.0
    const val ENDURANCE_MAX_JUMPS = 5
    const val ENDURANCE_MAX_JUMP_SHARE = 0.10
    const val ENDURANCE_MIN_SEGMENT_M = 3.0
    const val ENDURANCE_START_FIX_MS = 5000L
    const val ENDURANCE_MAX_FIX_GAP_MS = 15000L

    const val ENDURANCE_MAX_AVERAGE_SPEED_MPS = 8.5

    const val ENDURANCE_STEP_PEAK = 2.0
    const val ENDURANCE_STEP_MIN_GAP_MS = 250L
    const val ENDURANCE_MIN_CADENCE = 90.0
    const val ENDURANCE_CADENCE_WINDOW_MS = 10000L
    const val ENDURANCE_MIN_STEPPING_SHARE = 0.7

    const val GRAVITY = 9.80665
}
