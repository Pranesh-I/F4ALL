package com.sai.sports.analyzer

import kotlin.math.min

/**
 * Counts sit-ups from the torso angle at the hip.
 *
 * The signal is the shoulder-hip-knee angle: wide when the athlete is lying
 * back, closing as they sit up. The machine has two stable states separated by
 * a wide hysteresis band, and a rep is counted on the DOWN -> UP crossing.
 *
 *      angle >= 135 deg          angle <= 70 deg
 *   +-------------------+     +-------------------+
 *   |       DOWN        | --> |        UP         |   rep counted here
 *   |   (lying back)    | <-- |   (sat all the    |
 *   +-------------------+     |      way up)      |
 *                             +-------------------+
 *
 * Three ways a rep is refused:
 *
 *  - Partial: the ascent never reaches the UP threshold. Falls out naturally —
 *    the crossing never happens — and is recorded so the athlete is told why.
 *  - Too fast: the DOWN -> UP crossing takes less than [AnalyzerThresholds
 *    .SITUP_MIN_REP_DURATION_MS]. Usually a shaken phone or the pose model
 *    snapping to a different person.
 *  - Poor tracking: the frame never reaches the state machine, because the
 *    quality gate dropped it.
 *
 * Not thread-safe: feed it from one thread. In the app that is the CameraX
 * analysis executor.
 */
class SitUpAnalyzer : TestAnalyzer {

    override val testType = TestType.SIT_UPS

    private enum class State {
        /** Nothing counted yet — waiting to see the athlete lying down. */
        WAITING_FOR_DOWN,

        /** Lying back, ready to ascend. */
        DOWN,

        /** Sat up; must return to DOWN before another rep can count. */
        UP
    }

    private val qualityGate = FrameQualityGate(
        requiredIndices = ALL_REQUIRED_INDICES
    )

    private val smoother = PoseSmoother()

    private var state = State.WAITING_FOR_DOWN

    private var repCount = 0
    private var rejectedPartialReps = 0
    private var rejectedFastReps = 0

    private var framesAnalyzed = 0
    private var framesRejected = 0
    private var consecutiveRejectedFrames = 0

    /** When the torso first left the DOWN band on the current ascent. */
    private var ascentStartMs: Long? = null

    /** Tightest angle seen since leaving DOWN — decides partial vs. no attempt. */
    private var minAngleSinceDown = MAX_ANGLE

    private var visibilitySum = 0.0
    private var lockedSide: BodySide? = null
    private var lastAngle: Double? = null
    private var trackingLost = false

    private val events = mutableListOf<AnalyzerEvent>()

    override fun onFrame(frame: PoseFrame) {

        val verdict = qualityGate.evaluate(frame)

        if (!verdict.accepted) {
            framesRejected++
            consecutiveRejectedFrames++

            if (consecutiveRejectedFrames == AnalyzerThresholds.MAX_CONSECUTIVE_REJECTED_FRAMES) {
                trackingLost = true
                events += AnalyzerEvent(
                    timestampMs = frame.timestampMs,
                    label = "tracking_lost",
                    detail = "Body not fully visible for " +
                        "${AnalyzerThresholds.MAX_CONSECUTIVE_REJECTED_FRAMES} frames"
                )
            }
            return
        }

        consecutiveRejectedFrames = 0
        framesAnalyzed++
        visibilitySum += verdict.meanVisibility

        val smoothed = smoother.smooth(frame)

        // The side is chosen once, from the first good frame, and held. Letting
        // it switch mid-attempt would put a discontinuity in the angle signal
        // and the state machine would read that jump as a rep.
        val side = lockedSide ?: PoseMath.selectMoreVisibleSide(
            frame = smoothed,
            leftIndices = LEFT_INDICES,
            rightIndices = RIGHT_INDICES
        ).also {
            lockedSide = it
            events += AnalyzerEvent(
                timestampMs = frame.timestampMs,
                label = "side_locked",
                detail = it.name
            )
        }

        val shoulder = smoothed[shoulderIndex(side)] ?: return
        val hip = smoothed[hipIndex(side)] ?: return
        val knee = smoothed[kneeIndex(side)] ?: return

        val angle = PoseMath.angle(shoulder, hip, knee)
        lastAngle = angle

        advance(angle, frame.timestampMs)
    }

    private fun advance(angle: Double, timestampMs: Long) {

        when (state) {

            State.WAITING_FOR_DOWN -> {
                if (angle >= AnalyzerThresholds.SITUP_DOWN_ENTER_ANGLE) {
                    state = State.DOWN
                    resetAscentTracking()
                    events += AnalyzerEvent(
                        timestampMs = timestampMs,
                        label = "ready",
                        detail = "Start position detected"
                    )
                }
            }

            State.DOWN -> {
                if (angle < AnalyzerThresholds.SITUP_DOWN_ENTER_ANGLE) {

                    if (ascentStartMs == null) {
                        ascentStartMs = timestampMs
                    }
                    minAngleSinceDown = min(minAngleSinceDown, angle)

                    if (angle <= AnalyzerThresholds.SITUP_UP_ENTER_ANGLE) {
                        completeAscent(timestampMs)
                    }

                } else if (ascentStartMs != null) {
                    // Returned to the start position without ever reaching UP.
                    abandonAscent(timestampMs)
                }
            }

            State.UP -> {
                if (angle >= AnalyzerThresholds.SITUP_DOWN_ENTER_ANGLE) {
                    state = State.DOWN
                    resetAscentTracking()
                }
            }
        }
    }

    private fun completeAscent(timestampMs: Long) {

        val startMs = ascentStartMs ?: timestampMs
        val durationMs = timestampMs - startMs

        if (durationMs < AnalyzerThresholds.SITUP_MIN_REP_DURATION_MS) {
            rejectedFastReps++
            events += AnalyzerEvent(
                timestampMs = timestampMs,
                label = "rep_rejected_too_fast",
                detail = "${durationMs}ms is below the " +
                    "${AnalyzerThresholds.SITUP_MIN_REP_DURATION_MS}ms minimum"
            )
        } else {
            repCount++
            events += AnalyzerEvent(
                timestampMs = timestampMs,
                label = "rep_counted",
                detail = "Rep $repCount in ${durationMs}ms"
            )
        }

        // Either way the athlete is now sat up, so the machine advances. A
        // rejected rep must still return to DOWN before the next one counts.
        state = State.UP
        resetAscentTracking()
    }

    private fun abandonAscent(timestampMs: Long) {

        if (minAngleSinceDown <= AnalyzerThresholds.SITUP_PARTIAL_REP_ANGLE) {
            rejectedPartialReps++
            events += AnalyzerEvent(
                timestampMs = timestampMs,
                label = "rep_rejected_partial",
                detail = "Reached ${"%.0f".format(minAngleSinceDown)} deg, " +
                    "needs ${AnalyzerThresholds.SITUP_UP_ENTER_ANGLE.toInt()} deg"
            )
        }

        resetAscentTracking()
    }

    private fun resetAscentTracking() {
        ascentStartMs = null
        minAngleSinceDown = MAX_ANGLE
    }

    override fun currentScore(): Double = repCount.toDouble()

    override fun isReady(): Boolean = state != State.WAITING_FOR_DOWN

    override fun eventsSince(fromIndex: Int): List<AnalyzerEvent> =
        if (fromIndex >= events.size) emptyList() else events.subList(fromIndex, events.size).toList()

    /** Live torso angle, for the on-screen debug readout. */
    fun currentAngle(): Double? = lastAngle

    fun rejectedPartialReps(): Int = rejectedPartialReps

    override fun result(): AnalyzerResult {

        if (framesAnalyzed == 0) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "No usable pose data — make sure your whole body is in frame",
                framesRejected = framesRejected,
                events = events
            )
        }

        if (state == State.WAITING_FOR_DOWN) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "Start position never detected — lie back fully before starting",
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events
            )
        }

        return AnalyzerResult(
            testType = testType,
            score = repCount.toDouble(),
            unit = testType.unit,
            status = AttemptStatus.COMPLETE,
            confidence = confidence(),
            framesAnalyzed = framesAnalyzed,
            framesRejected = framesRejected,
            events = events.toList()
        )
    }

    /**
     * Confidence blends how clearly the body was seen with how much of the
     * attempt was usable at all. A run with excellent landmarks on the third of
     * frames that survived the gate is not a confident run.
     */
    private fun confidence(): Double {

        val meanVisibility = visibilitySum / framesAnalyzed
        val totalFrames = framesAnalyzed + framesRejected
        val acceptanceRatio =
            if (totalFrames == 0) 0.0 else framesAnalyzed.toDouble() / totalFrames

        val base = meanVisibility * acceptanceRatio

        // Losing tracking mid-attempt means reps may have happened unseen.
        return (if (trackingLost) base * 0.5 else base).coerceIn(0.0, 1.0)
    }

    override fun reset() {
        state = State.WAITING_FOR_DOWN
        repCount = 0
        rejectedPartialReps = 0
        rejectedFastReps = 0
        framesAnalyzed = 0
        framesRejected = 0
        consecutiveRejectedFrames = 0
        visibilitySum = 0.0
        lockedSide = null
        lastAngle = null
        trackingLost = false
        resetAscentTracking()
        events.clear()
        smoother.reset()
    }

    private fun shoulderIndex(side: BodySide) =
        if (side == BodySide.LEFT) PoseLandmarkIndex.LEFT_SHOULDER
        else PoseLandmarkIndex.RIGHT_SHOULDER

    private fun hipIndex(side: BodySide) =
        if (side == BodySide.LEFT) PoseLandmarkIndex.LEFT_HIP
        else PoseLandmarkIndex.RIGHT_HIP

    private fun kneeIndex(side: BodySide) =
        if (side == BodySide.LEFT) PoseLandmarkIndex.LEFT_KNEE
        else PoseLandmarkIndex.RIGHT_KNEE

    companion object {

        private const val MAX_ANGLE = 180.0

        private val LEFT_INDICES = listOf(
            PoseLandmarkIndex.LEFT_SHOULDER,
            PoseLandmarkIndex.LEFT_HIP,
            PoseLandmarkIndex.LEFT_KNEE
        )

        private val RIGHT_INDICES = listOf(
            PoseLandmarkIndex.RIGHT_SHOULDER,
            PoseLandmarkIndex.RIGHT_HIP,
            PoseLandmarkIndex.RIGHT_KNEE
        )

        /**
         * The gate requires BOTH sides visible even though only one is measured.
         * Filmed from the side, a frame where the far shoulder has vanished is a
         * frame where the pose model is struggling, and its estimate of the near
         * shoulder is not to be trusted either.
         */
        private val ALL_REQUIRED_INDICES = LEFT_INDICES + RIGHT_INDICES
    }
}
