package com.sai.sports.analyzer

/**
 * Every tunable number the scoring algorithms use, in one place.
 *
 * Why one object instead of constants scattered across the analyzers:
 * Sprint 5 re-implements this logic server-side in Python, and the server
 * auto-flags a submission when the on-device and server scores disagree.
 * If the two implementations drift apart, that flag fires on honest athletes.
 * A single table makes the port mechanical and the drift visible in a diff.
 *
 * These values are STARTING POINTS calibrated against synthetic sequences.
 * Sprint 3.4 replaces them with values measured against real reference videos;
 * Sprint 13-14 re-tunes them against real pilot data.
 */
object AnalyzerThresholds {

    // ---------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------

    /** Landmarks below this visibility are treated as unusable. */
    const val MIN_LANDMARK_VISIBILITY = 0.5f

    /**
     * Consecutive rejected frames tolerated mid-attempt before the attempt is
     * abandoned. At ~15 fps this is roughly one second of lost tracking.
     */
    const val MAX_CONSECUTIVE_REJECTED_FRAMES = 15

    // ---------------------------------------------------------------
    // One-Euro smoothing
    // ---------------------------------------------------------------

    /**
     * A moving average would be simpler, but it lags — and lag blunts exactly
     * the peak frame a vertical jump is measured from. One-Euro widens its
     * cutoff as movement speeds up, so it kills resting jitter without
     * flattening fast motion.
     *
     * These values are tied to the scale of the signal being filtered:
     * normalized landmark coordinates in 0..1, sampled at 15-30 fps. A standing
     * athlete's landmarks jitter at well under 0.05 units/s, while a shoulder
     * during a sit-up moves at roughly 1 unit/s. [SMOOTHING_BETA] is large
     * because it multiplies those small velocities — it is what pushes the
     * cutoff from ~1 Hz at rest to ~25 Hz mid-rep. Copying beta from an
     * implementation that filters pixels instead of normalized units would
     * produce a filter that never adapts.
     */
    const val SMOOTHING_MIN_CUTOFF = 0.8
    const val SMOOTHING_BETA = 25.0
    const val SMOOTHING_DERIVATIVE_CUTOFF = 3.0

    // ---------------------------------------------------------------
    // Sit-ups
    // ---------------------------------------------------------------

    /**
     * Torso angle (shoulder-hip-knee) at the hip vertex.
     *
     * Lying flat with knees bent gives a wide angle; a completed sit-up closes
     * it. The two thresholds are deliberately far apart — that gap IS the
     * hysteresis band, and it is what stops a trembling torso at a single
     * threshold from counting twenty reps.
     */
    const val SITUP_DOWN_ENTER_ANGLE = 135.0
    const val SITUP_UP_ENTER_ANGLE = 70.0

    /**
     * An ascent that gets past this angle but never reaches [SITUP_UP_ENTER_ANGLE]
     * is recorded as a rejected partial rep. It does not count, but the athlete
     * is told about it — silent non-counting reads as a broken app.
     */
    const val SITUP_PARTIAL_REP_ANGLE = 110.0

    /**
     * Floor on how long the torso may take to travel from the DOWN band to the
     * UP band. Anything quicker is the phone being shaken or the pose estimator
     * snapping between people.
     *
     * Kept deliberately low. The SAI sit-up test scores maximum reps in a fixed
     * window, so athletes go FAST — and this guard silently deletes reps rather
     * than flagging them, which makes an over-strict value actively dangerous:
     * it would penalise precisely the strongest athletes. At 600ms the counter
     * scored zero on a 660ms ascent, a completely ordinary pace. 200ms
     * corresponds to a full rep of roughly 0.6s, at the edge of human
     * capability, while still rejecting the sub-100ms transitions that shaking
     * the phone produces.
     */
    const val SITUP_MIN_REP_DURATION_MS = 200L

    // ---------------------------------------------------------------
    // Vertical jump
    // ---------------------------------------------------------------

    /** Consecutive stable frames required before the standing baseline is accepted. */
    const val JUMP_CALIBRATION_FRAMES = 12

    /**
     * Max standard deviation of normalized hip-y across the calibration window
     * for the athlete to count as "standing still".
     */
    const val JUMP_CALIBRATION_STABILITY = 0.008

    /**
     * Nose height as a fraction of full stature.
     *
     * Calibration needs full stature, but the pose model's topmost reliable
     * landmark is the nose, not the crown of the head. This anthropometric
     * constant bridges the gap. It is an approximation and a real source of
     * error — Sprint 3.4 measures how much.
     */
    const val NOSE_HEIGHT_STATURE_RATIO = 0.936

    /** Upward hip displacement that counts as takeoff. */
    const val JUMP_TAKEOFF_CM = 6.0

    /** Falling back below this displacement counts as landing. */
    const val JUMP_LANDING_CM = 3.0

    /** Flight times outside this window are not human jumps. */
    const val JUMP_MIN_FLIGHT_MS = 150L
    const val JUMP_MAX_FLIGHT_MS = 2_000L

    /** Sanity bounds on a human vertical jump. Outside this, something is wrong. */
    const val JUMP_MIN_PLAUSIBLE_CM = 2.0
    const val JUMP_MAX_PLAUSIBLE_CM = 120.0

    // ---------------------------------------------------------------
    // Accuracy targets (Sprint 3 Definition of Done)
    // ---------------------------------------------------------------

    /** Sit-up count must land within this many reps of manual ground truth. */
    const val TARGET_SITUP_TOLERANCE_REPS = 1

    /** Jump height must land within this many cm of manual ground truth. */
    const val TARGET_JUMP_TOLERANCE_CM = 3.0
}
