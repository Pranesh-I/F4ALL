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
    // Angle-driven rep exercises (squat, push-up, bicep curl, lunge)
    // ---------------------------------------------------------------

    /**
     * Consecutive frames the athlete must hold the start position before reps
     * can count. Stops the counter arming on a single frame of the athlete
     * walking into position.
     */
    const val REP_START_HOLD_FRAMES = 5

    /**
     * A form fault must persist this many consecutive frames inside a rep to be
     * recorded. One frame of a mis-placed hip is the pose model, not the athlete.
     */
    const val FORM_FAULT_MIN_FRAMES = 3

    /**
     * Each exercise uses the same three-band machine as sit-ups, on a joint
     * angle that is wide at the start position and closes at the bottom:
     *
     *   EXTENDED: at or above this the athlete is at the start/top position.
     *   DEPTH:    at or below this the rep went deep enough to count.
     *   PARTIAL:  a rep that turned back after passing this but never reached
     *             DEPTH is recorded as a rejected partial rep.
     *
     * Rep duration floors are deliberately low, for the same reason as
     * [SITUP_MIN_REP_DURATION_MS]: they delete reps silently, so an over-strict
     * value punishes the fastest honest athletes.
     */

    /** Squat: hip-knee-ankle angle, filmed side-on. ~90 deg is thighs parallel. */
    const val SQUAT_EXTENDED_ANGLE = 160.0
    const val SQUAT_DEPTH_ANGLE = 100.0
    const val SQUAT_PARTIAL_ANGLE = 135.0
    const val SQUAT_MIN_REP_DURATION_MS = 400L

    /**
     * Torso angle from vertical above which the athlete is folding forward
     * rather than sitting down. A warning, not a rejection: long-legged
     * athletes lean more at depth and the rep is still a squat.
     */
    const val SQUAT_MAX_TORSO_LEAN_DEG = 55.0

    /**
     * How far the knee may travel past the ankle at depth, as a fraction of
     * shin length. Knees do go forward in a good squat, so this is generous
     * and a warning only — it catches the athlete tipping onto their toes.
     */
    const val SQUAT_MAX_KNEE_TRAVEL_RATIO = 0.5

    /**
     * How far the near ankle may slide during one rep, as a fraction of leg
     * length, before the feet count as having moved. Shuffling the feet
     * mid-squat is an unstable base, not a different exercise: a warning.
     */
    const val FOOT_MAX_SHIFT_RATIO = 0.25

    /** Push-up: shoulder-elbow-wrist angle, filmed side-on. */
    const val PUSHUP_EXTENDED_ANGLE = 150.0
    const val PUSHUP_DEPTH_ANGLE = 95.0
    const val PUSHUP_PARTIAL_ANGLE = 125.0
    const val PUSHUP_MIN_REP_DURATION_MS = 300L

    /**
     * Shoulder-hip-ankle angle below which the body is no longer a straight
     * line — hips sagging or piked. A rep done that way is not a push-up.
     */
    const val PUSHUP_MIN_BODY_LINE_ANGLE = 155.0

    /**
     * Shoulder-to-ankle line's angle from horizontal above which the athlete is
     * not in a plank at all. This is what stops an athlete standing up and
     * bending their arms from scoring push-ups.
     */
    const val PUSHUP_MAX_BODY_TILT_DEG = 40.0

    /** Bicep curl: shoulder-elbow-wrist angle, filmed facing the camera. */
    const val CURL_EXTENDED_ANGLE = 150.0
    const val CURL_DEPTH_ANGLE = 60.0
    const val CURL_PARTIAL_ANGLE = 100.0
    const val CURL_MIN_REP_DURATION_MS = 300L

    /**
     * Hip-shoulder-elbow angle above which the elbow has left the side of the
     * body. The biceps are no longer doing the work — it is a raise or a swing.
     */
    const val CURL_MAX_ELBOW_FLARE_DEG = 35.0

    /**
     * How far the shoulders may travel during a curl, as a fraction of
     * shoulder width, before the athlete is swinging the weight up with their
     * body. A warning: the arm still did a curl, just with help.
     */
    const val CURL_MAX_BODY_SWAY_RATIO = 0.25

    /** Lunge: mean of both knee angles, filmed side-on. */
    const val LUNGE_EXTENDED_ANGLE = 160.0
    const val LUNGE_DEPTH_ANGLE = 110.0
    const val LUNGE_PARTIAL_ANGLE = 140.0
    const val LUNGE_MIN_REP_DURATION_MS = 400L

    /**
     * Horizontal ankle separation, as a fraction of leg length, below which the
     * feet are together — a squat, not a lunge. Checked only once the knees
     * are bent past [LUNGE_PARTIAL_ANGLE], so stepping into the lunge is fine.
     */
    const val LUNGE_MIN_STANCE_RATIO = 0.5

    /**
     * How far the front knee may travel past the front ankle, as a fraction of
     * shin length, before it is flagged. A warning only.
     */
    const val LUNGE_MAX_KNEE_TRAVEL_RATIO = 0.35

    /**
     * Torso angle from vertical, at depth, above which the athlete is folding
     * over the front leg. A lunge is done upright; a warning only.
     */
    const val LUNGE_MAX_TORSO_LEAN_DEG = 30.0

    // ---------------------------------------------------------------
    // Accuracy targets (Sprint 3 Definition of Done)
    // ---------------------------------------------------------------

    /** Sit-up count must land within this many reps of manual ground truth. */
    const val TARGET_SITUP_TOLERANCE_REPS = 1

    /** Squat, push-up, curl and lunge counts: same tolerance as sit-ups. */
    const val TARGET_REP_TOLERANCE_REPS = 1

    /** Jump height must land within this many cm of manual ground truth. */
    const val TARGET_JUMP_TOLERANCE_CM = 3.0
}
