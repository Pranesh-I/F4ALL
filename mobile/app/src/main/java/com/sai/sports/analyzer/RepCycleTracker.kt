package com.sai.sports.analyzer

/**
 * A form fault an analyzer can detect during a rep.
 *
 * [code] is the stable key stored in events, sent to the server and translated
 * on the phone — never shown to the athlete as-is.
 *
 * [rejectsRep] separates two kinds of fault. Some mean the movement was not the
 * exercise at all — sagging hips turn a push-up into a hip bounce — and the rep
 * must not count. Others are coaching points: the rep is still a squat, just
 * not a textbook one, and deleting it would punish honest athletes for their
 * proportions. Those are counted and recorded as warnings.
 *
 * Declaration order is the order issues are reported in; the Python port lists
 * them in the same order so event details match byte for byte.
 */
enum class FormIssue(
    val code: String,
    val rejectsRep: Boolean
) {
    TORSO_LEAN("torso_lean", rejectsRep = false),
    HIPS_SAGGING("hips_sagging", rejectsRep = true),
    HIPS_PIKED("hips_piked", rejectsRep = true),
    NOT_IN_PLANK("not_in_plank", rejectsRep = true),
    ELBOW_FLARE("elbow_flare", rejectsRep = true),
    STANCE_TOO_NARROW("stance_too_narrow", rejectsRep = true),
    KNEE_PAST_TOES("knee_past_toes", rejectsRep = false),
    FEET_MOVED("feet_moved", rejectsRep = false),
    BODY_SWING("body_swing", rejectsRep = false);

    companion object {
        fun fromCode(code: String): FormIssue? = entries.firstOrNull { it.code == code }
    }
}

/** The three angle bands and duration floor one exercise is scored with. */
data class RepThresholds(
    val extendedAngle: Double,
    val depthAngle: Double,
    val partialAngle: Double,
    val minRepDurationMs: Long
)

/** What an analyzer measured on one frame, reduced to what the rep machine needs. */
data class RepSample(
    /** The driving joint angle: wide at the start position, closing at the bottom. */
    val angle: Double,
    /** Faults visible on this frame. */
    val issues: Set<FormIssue> = emptySet(),
    /**
     * Whether the body is in a valid start position at all, beyond the angle —
     * a push-up needs a plank, not just straight arms.
     */
    val inStartPose: Boolean = true
)

/** Something the rep machine decided on a frame. */
sealed interface RepEvent {

    val timestampMs: Long

    /** True when the rep reached depth, whether or not it then counted. */
    val reachedDepth: Boolean

    /** The start position has been held long enough; reps may now count. */
    data class Ready(override val timestampMs: Long) : RepEvent {
        override val reachedDepth = false
    }

    data class Counted(
        override val timestampMs: Long,
        val durationMs: Long,
        /** Non-rejecting faults seen during the rep, in [FormIssue] order. */
        val warnings: List<FormIssue>
    ) : RepEvent {
        override val reachedDepth = true
    }

    data class Partial(
        override val timestampMs: Long,
        val deepestAngle: Double
    ) : RepEvent {
        override val reachedDepth = false
    }

    data class TooFast(
        override val timestampMs: Long,
        val durationMs: Long
    ) : RepEvent {
        override val reachedDepth = true
    }

    data class FormRejected(
        override val timestampMs: Long,
        /** Every fault seen during the rep, rejecting or not, in [FormIssue] order. */
        val issues: List<FormIssue>
    ) : RepEvent {
        override val reachedDepth = true
    }
}

/**
 * The rep state machine shared by squat, push-up, bicep curl and lunge.
 *
 * Same idea as the sit-up counter — hysteresis bands on one joint angle — with
 * two differences that matter for these exercises:
 *
 *  - A rep is counted on the return to the start position, not on reaching
 *    depth. Form is judged across the whole rep, and a squat that reaches depth
 *    but never stands back up is not a squat.
 *  - The start position must be held for [AnalyzerThresholds
 *    .REP_START_HOLD_FRAMES] frames before anything counts.
 *
 *     angle >= EXTENDED            angle < EXTENDED
 *   +-------------------+       +--------------------+
 *   |        TOP        | ----> |       IN_REP       |  track depth + faults
 *   |  (start position) | <---- |                    |
 *   +-------------------+       +--------------------+
 *                 angle >= EXTENDED: rep judged here
 *
 * Pure: no gating, smoothing or landmarks — the analyzer feeds it angles. That
 * keeps it trivially portable to the server's Python verifier.
 */
class RepCycleTracker(
    private val thresholds: RepThresholds
) {

    enum class Phase {
        WAITING_FOR_START,
        TOP,
        IN_REP
    }

    var phase = Phase.WAITING_FOR_START
        private set

    private var startHoldFrames = 0
    private var repStartMs: Long? = null
    private var deepestAngle = MAX_ANGLE
    private var reachedDepth = false

    /** Consecutive frames each fault has been seen, indexed by ordinal. */
    private val faultRuns = IntArray(FormIssue.entries.size)

    /** Faults that persisted long enough during the current rep to count. */
    private val repFaults = BooleanArray(FormIssue.entries.size)

    fun update(sample: RepSample, timestampMs: Long): RepEvent? =
        when (phase) {

            Phase.WAITING_FOR_START -> {
                if (sample.angle >= thresholds.extendedAngle && sample.inStartPose) {
                    startHoldFrames++
                    if (startHoldFrames >= AnalyzerThresholds.REP_START_HOLD_FRAMES) {
                        phase = Phase.TOP
                        RepEvent.Ready(timestampMs)
                    } else {
                        null
                    }
                } else {
                    startHoldFrames = 0
                    null
                }
            }

            Phase.TOP -> {
                if (sample.angle < thresholds.extendedAngle) {
                    phase = Phase.IN_REP
                    repStartMs = timestampMs
                    track(sample)
                }
                null
            }

            Phase.IN_REP -> {
                if (sample.angle >= thresholds.extendedAngle) {
                    finishRep(timestampMs)
                } else {
                    track(sample)
                    null
                }
            }
        }

    private fun track(sample: RepSample) {

        if (sample.angle < deepestAngle) {
            deepestAngle = sample.angle
        }
        if (sample.angle <= thresholds.depthAngle) {
            reachedDepth = true
        }

        for (issue in FormIssue.entries) {
            if (issue in sample.issues) {
                faultRuns[issue.ordinal]++
                if (faultRuns[issue.ordinal] >= AnalyzerThresholds.FORM_FAULT_MIN_FRAMES) {
                    repFaults[issue.ordinal] = true
                }
            } else {
                faultRuns[issue.ordinal] = 0
            }
        }
    }

    private fun finishRep(timestampMs: Long): RepEvent? {

        val durationMs = timestampMs - (repStartMs ?: timestampMs)
        val faults = FormIssue.entries.filter { repFaults[it.ordinal] }

        val event = when {
            !reachedDepth ->
                if (deepestAngle <= thresholds.partialAngle) {
                    RepEvent.Partial(timestampMs, deepestAngle)
                } else {
                    // A small dip that never looked like a rep attempt. Saying
                    // nothing is right: it is the athlete settling, not trying.
                    null
                }

            durationMs < thresholds.minRepDurationMs ->
                RepEvent.TooFast(timestampMs, durationMs)

            faults.any { it.rejectsRep } ->
                RepEvent.FormRejected(timestampMs, faults)

            else ->
                RepEvent.Counted(timestampMs, durationMs, faults)
        }

        phase = Phase.TOP
        resetRep()
        return event
    }

    private fun resetRep() {
        repStartMs = null
        deepestAngle = MAX_ANGLE
        reachedDepth = false
        faultRuns.fill(0)
        repFaults.fill(false)
    }

    fun reset() {
        phase = Phase.WAITING_FOR_START
        startHoldFrames = 0
        resetRep()
    }

    private companion object {
        const val MAX_ANGLE = 180.0
    }
}
