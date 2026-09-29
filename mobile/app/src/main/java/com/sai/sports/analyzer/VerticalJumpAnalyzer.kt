package com.sai.sports.analyzer

import kotlin.math.abs
import kotlin.math.max

/**
 * Measures vertical jump height from hip displacement against a calibrated
 * standing reference.
 *
 * ## How the centimetres are obtained
 *
 * Pose landmarks are normalized to the image (0..1), so displacement is
 * unitless until something ties it to the real world. That something is the
 * athlete's registered standing height:
 *
 *   1. While the athlete stands still, measure the nose-to-ankle span in
 *      normalized units.
 *   2. Divide by [AnalyzerThresholds.NOSE_HEIGHT_STATURE_RATIO] to estimate
 *      full stature — the pose model's topmost dependable landmark is the nose,
 *      not the crown of the head.
 *   3. cmPerUnit = registeredHeightCm / estimatedStatureInUnits.
 *   4. Jump height = peak upward hip displacement * cmPerUnit.
 *
 * ## What this measurement assumes
 *
 * The athlete must stay roughly the same distance from the camera. Normalized
 * units map to centimetres only at the depth where calibration happened; step
 * toward the lens and everything grows, jump height included. The analyzer
 * cannot see depth directly, so it watches the athlete's apparent stature
 * instead — if that changes between calibration and landing, the ratio is stale
 * and the attempt is downgraded or rejected.
 *
 * The self-reported height in step 3 is another error source. Both of these are
 * why the server re-scores the video in Sprint 5 and why the on-device number
 * is only ever provisional.
 *
 * ## Phases
 *
 *   CALIBRATING -> READY -> AIRBORNE -> READY (repeatable) -> result
 *
 * Multiple jumps in one recording are allowed; the best one is reported.
 */
class VerticalJumpAnalyzer(
    /** Standing height from the athlete's profile. Sprint 7 supplies this at registration. */
    private val athleteHeightCm: Double
) : TestAnalyzer {

    override val testType = TestType.VERTICAL_JUMP

    enum class Phase {
        CALIBRATING,
        READY,
        AIRBORNE,
        INVALID
    }

    private val qualityGate = FrameQualityGate(
        requiredIndices = REQUIRED_INDICES
    )

    private val smoother = PoseSmoother()

    private var phase = Phase.CALIBRATING

    /** Sliding window of hip-y used to decide the athlete is standing still. */
    private val calibrationHipY = ArrayDeque<Double>()
    private val calibrationStature = ArrayDeque<Double>()

    private var baselineHipY: Double? = null
    private var calibratedStatureUnits: Double? = null
    private var cmPerUnit: Double? = null

    private var takeoffMs: Long? = null
    private var currentPeakUnits = 0.0
    private var bestJumpCm = 0.0
    private var jumpCount = 0

    private var framesAnalyzed = 0
    private var framesRejected = 0
    private var consecutiveRejectedFrames = 0
    private var visibilitySum = 0.0

    private var scaleDrift = 0.0
    private var invalidReason: String? = null
    private var lastDisplacementCm: Double? = null

    private val events = mutableListOf<AnalyzerEvent>()

    override fun onFrame(frame: PoseFrame) {

        if (phase == Phase.INVALID) return

        val verdict = qualityGate.evaluate(frame)

        if (!verdict.accepted) {
            framesRejected++
            consecutiveRejectedFrames++

            if (consecutiveRejectedFrames >= AnalyzerThresholds.MAX_CONSECUTIVE_REJECTED_FRAMES) {
                // Losing the athlete mid-flight means the peak was very likely
                // missed. A jump measured from a partial arc is worse than no
                // measurement, because it looks like a real result.
                if (phase == Phase.AIRBORNE) {
                    fail(
                        frame.timestampMs,
                        "Lost track of you mid-jump — keep your whole body in frame"
                    )
                } else if (phase == Phase.CALIBRATING) {
                    calibrationHipY.clear()
                    calibrationStature.clear()
                }
            }
            return
        }

        consecutiveRejectedFrames = 0
        framesAnalyzed++
        visibilitySum += verdict.meanVisibility

        val smoothed = smoother.smooth(frame)

        val hipY = hipY(smoothed) ?: return
        val stature = statureUnits(smoothed) ?: return

        when (phase) {
            Phase.CALIBRATING -> calibrate(hipY, stature, frame.timestampMs)
            Phase.READY -> watchForTakeoff(hipY, stature, frame.timestampMs)
            Phase.AIRBORNE -> trackFlight(hipY, frame.timestampMs)
            Phase.INVALID -> Unit
        }
    }

    /**
     * Accepts the standing reference only after the hips have held still for a
     * full window. Calibrating off a moving athlete bakes an error into every
     * measurement that follows, so it is worth waiting for.
     */
    private fun calibrate(hipY: Double, stature: Double, timestampMs: Long) {

        calibrationHipY.addLast(hipY)
        calibrationStature.addLast(stature)

        while (calibrationHipY.size > AnalyzerThresholds.JUMP_CALIBRATION_FRAMES) {
            calibrationHipY.removeFirst()
            calibrationStature.removeFirst()
        }

        if (calibrationHipY.size < AnalyzerThresholds.JUMP_CALIBRATION_FRAMES) {
            return
        }

        val spread = PoseMath.standardDeviation(calibrationHipY.toList())

        if (spread > AnalyzerThresholds.JUMP_CALIBRATION_STABILITY) {
            // Still moving. The window slides forward one frame and tries again.
            return
        }

        val meanStature = PoseMath.mean(calibrationStature.toList())

        if (meanStature <= 0.0) {
            return
        }

        val estimatedStature = meanStature / AnalyzerThresholds.NOSE_HEIGHT_STATURE_RATIO

        baselineHipY = PoseMath.mean(calibrationHipY.toList())
        calibratedStatureUnits = meanStature
        cmPerUnit = athleteHeightCm / estimatedStature

        phase = Phase.READY

        events += AnalyzerEvent(
            timestampMs = timestampMs,
            label = "calibrated",
            detail = "Standing reference set — " +
                "1.0 unit = ${"%.1f".format(cmPerUnit)}cm at ${athleteHeightCm.toInt()}cm height"
        )
    }

    private fun watchForTakeoff(hipY: Double, stature: Double, timestampMs: Long) {

        val displacementCm = displacementCm(hipY) ?: return
        lastDisplacementCm = displacementCm

        // Between jumps, check the athlete has not drifted toward or away from
        // the camera. Apparent stature is the only depth cue available here.
        val calibrated = calibratedStatureUnits
        if (calibrated != null && calibrated > 0.0) {
            scaleDrift = max(scaleDrift, abs(stature / calibrated - 1.0))
        }

        if (displacementCm >= AnalyzerThresholds.JUMP_TAKEOFF_CM) {
            phase = Phase.AIRBORNE
            takeoffMs = timestampMs
            currentPeakUnits = displacementUnits(hipY) ?: 0.0

            events += AnalyzerEvent(
                timestampMs = timestampMs,
                label = "takeoff",
                detail = "Rose past ${AnalyzerThresholds.JUMP_TAKEOFF_CM.toInt()}cm"
            )
        }
    }

    private fun trackFlight(hipY: Double, timestampMs: Long) {

        val displacementUnits = displacementUnits(hipY) ?: return
        val displacementCm = displacementCm(hipY) ?: return
        lastDisplacementCm = displacementCm

        currentPeakUnits = max(currentPeakUnits, displacementUnits)

        val startMs = takeoffMs ?: timestampMs
        val elapsedMs = timestampMs - startMs

        if (elapsedMs > AnalyzerThresholds.JUMP_MAX_FLIGHT_MS) {
            fail(
                timestampMs,
                "No clean landing detected — land in the same spot you started"
            )
            return
        }

        if (displacementCm > AnalyzerThresholds.JUMP_LANDING_CM) {
            return
        }

        completeJump(elapsedMs, timestampMs)
    }

    private fun completeJump(elapsedMs: Long, timestampMs: Long) {

        val ratio = cmPerUnit ?: return
        val jumpCm = currentPeakUnits * ratio

        phase = Phase.READY
        takeoffMs = null
        currentPeakUnits = 0.0

        if (elapsedMs < AnalyzerThresholds.JUMP_MIN_FLIGHT_MS) {
            events += AnalyzerEvent(
                timestampMs = timestampMs,
                label = "jump_rejected",
                detail = "Only ${elapsedMs}ms off the ground — too brief to be a jump"
            )
            return
        }

        if (jumpCm > AnalyzerThresholds.JUMP_MAX_PLAUSIBLE_CM) {
            events += AnalyzerEvent(
                timestampMs = timestampMs,
                label = "jump_rejected",
                detail = "${"%.1f".format(jumpCm)}cm exceeds the plausible range"
            )
            return
        }

        jumpCount++
        bestJumpCm = max(bestJumpCm, jumpCm)

        events += AnalyzerEvent(
            timestampMs = timestampMs,
            label = "jump_measured",
            detail = "Jump $jumpCount: ${"%.1f".format(jumpCm)}cm over ${elapsedMs}ms"
        )
    }

    private fun fail(timestampMs: Long, reason: String) {
        phase = Phase.INVALID
        invalidReason = reason
        events += AnalyzerEvent(
            timestampMs = timestampMs,
            label = "attempt_failed",
            detail = reason
        )
    }

    /** Mean of both hips — steadier than either one alone. */
    private fun hipY(frame: PoseFrame): Double? {
        val left = frame[PoseLandmarkIndex.LEFT_HIP] ?: return null
        val right = frame[PoseLandmarkIndex.RIGHT_HIP] ?: return null
        return ((left.y + right.y) / 2f).toDouble()
    }

    /** Nose-to-ankle span in normalized units. */
    private fun statureUnits(frame: PoseFrame): Double? {
        val nose = frame[PoseLandmarkIndex.NOSE] ?: return null
        val leftAnkle = frame[PoseLandmarkIndex.LEFT_ANKLE] ?: return null
        val rightAnkle = frame[PoseLandmarkIndex.RIGHT_ANKLE] ?: return null

        val ankleY = (leftAnkle.y + rightAnkle.y) / 2f
        val span = (ankleY - nose.y).toDouble()

        return if (span <= 0.0) null else span
    }

    /** Upward displacement in normalized units. Screen y grows downward, hence the subtraction order. */
    private fun displacementUnits(hipY: Double): Double? {
        val baseline = baselineHipY ?: return null
        return baseline - hipY
    }

    private fun displacementCm(hipY: Double): Double? {
        val units = displacementUnits(hipY) ?: return null
        val ratio = cmPerUnit ?: return null
        return units * ratio
    }

    override fun currentScore(): Double = bestJumpCm

    override fun isReady(): Boolean = phase == Phase.READY || phase == Phase.AIRBORNE

    override fun eventsSince(fromIndex: Int): List<AnalyzerEvent> =
        if (fromIndex >= events.size) emptyList() else events.subList(fromIndex, events.size).toList()

    fun currentPhase(): Phase = phase

    /** Live height above the standing reference, for the on-screen readout. */
    fun currentDisplacementCm(): Double? = lastDisplacementCm

    override fun result(): AnalyzerResult {

        invalidReason?.let {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = it,
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        if (framesAnalyzed == 0) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "No usable pose data — make sure your whole body is in frame",
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        if (phase == Phase.CALIBRATING) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "Could not set a standing reference — stand still for a moment before jumping",
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        if (phase == Phase.AIRBORNE) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "Recording stopped mid-jump — land before pressing stop",
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        if (jumpCount == 0 || bestJumpCm < AnalyzerThresholds.JUMP_MIN_PLAUSIBLE_CM) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "No jump detected",
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        // Enough apparent size change that the pixel-to-cm ratio can no longer
        // be trusted — the number would be confidently wrong.
        if (scaleDrift > MAX_SCALE_DRIFT) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "You or the camera moved during the test — keep both still and retry",
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        return AnalyzerResult(
            testType = testType,
            score = bestJumpCm,
            unit = testType.unit,
            status = AttemptStatus.COMPLETE,
            confidence = confidence(),
            framesAnalyzed = framesAnalyzed,
            framesRejected = framesRejected,
            events = events.toList()
        )
    }

    private fun confidence(): Double {

        val meanVisibility = visibilitySum / framesAnalyzed
        val totalFrames = framesAnalyzed + framesRejected
        val acceptanceRatio =
            if (totalFrames == 0) 0.0 else framesAnalyzed.toDouble() / totalFrames

        // Small scale drift does not invalidate the attempt but it does widen
        // the error bar, and the reviewer should see that reflected.
        val driftPenalty = (1.0 - (scaleDrift / MAX_SCALE_DRIFT)).coerceIn(0.0, 1.0)

        return (meanVisibility * acceptanceRatio * driftPenalty).coerceIn(0.0, 1.0)
    }

    override fun reset() {
        phase = Phase.CALIBRATING
        calibrationHipY.clear()
        calibrationStature.clear()
        baselineHipY = null
        calibratedStatureUnits = null
        cmPerUnit = null
        takeoffMs = null
        currentPeakUnits = 0.0
        bestJumpCm = 0.0
        jumpCount = 0
        framesAnalyzed = 0
        framesRejected = 0
        consecutiveRejectedFrames = 0
        visibilitySum = 0.0
        scaleDrift = 0.0
        invalidReason = null
        lastDisplacementCm = null
        events.clear()
        smoother.reset()
    }

    companion object {

        /** Fractional change in apparent stature beyond which the attempt is rejected. */
        private const val MAX_SCALE_DRIFT = 0.15

        private val REQUIRED_INDICES = listOf(
            PoseLandmarkIndex.NOSE,
            PoseLandmarkIndex.LEFT_HIP,
            PoseLandmarkIndex.RIGHT_HIP,
            PoseLandmarkIndex.LEFT_ANKLE,
            PoseLandmarkIndex.RIGHT_ANKLE
        )
    }
}
