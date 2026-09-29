package com.sai.sports.analyzer

import kotlin.math.min

/**
 * Counts bicep curls on one working arm, filmed facing the camera.
 *
 * Driving signal: shoulder-elbow-wrist of the working arm, ~170 deg hanging and
 * ~40 deg at the top of the curl.
 *
 * ## Working arm
 *
 * Both arms are tracked with their own rep machine. The working arm is either
 * given, or locked to whichever arm first completes a full-range rep. After
 * that:
 *
 *  - Working-arm reps are judged normally.
 *  - A full curl by the other arm while the working arm stayed down is the
 *    wrong arm: recorded, not counted.
 *  - The other arm curling alongside the working arm (a two-arm curl) is
 *    ignored — the rep is counted once, on the working arm.
 *
 * ## Form
 *
 * Hip-shoulder-elbow above [AnalyzerThresholds.CURL_MAX_ELBOW_FLARE_DEG] means
 * the elbow has left the side of the body — a raise or a swing, not a curl —
 * and the rep is rejected.
 *
 * The shoulders travelling more than [AnalyzerThresholds
 * .CURL_MAX_BODY_SWAY_RATIO] of shoulder width from where they were between
 * reps is the body helping the weight up: recorded as a warning.
 */
class BicepCurlAnalyzer(
    /** Null to lock onto whichever arm curls first. */
    private val workingArm: BodySide? = null
) : RepExerciseAnalyzer(
    testType = TestType.BICEP_CURLS,
    repThresholds = THRESHOLDS,
    leftIndices = LEFT_INDICES,
    rightIndices = RIGHT_INDICES,
    locksOneSide = false
) {

    override val noStartReason =
        "Start position never detected — stand facing the camera with your arms straight"

    private val leftTracker = RepCycleTracker(THRESHOLDS)
    private val rightTracker = RepCycleTracker(THRESHOLDS)

    private var activeArm: BodySide? = workingArm
    private var wrongArmReps = 0

    /**
     * Tightest working-arm angle since the other arm last began a rep. If it
     * never passed the partial band, the working arm sat still while the other
     * one did the work.
     */
    private var workingDeepestDuringOtherRep = MAX_ANGLE

    /** Shoulder midpoint and width while neither arm is mid-rep. */
    private var referenceShoulders: PosePoint? = null
    private var referenceShoulderWidth = 0.0

    override fun sample(frame: PoseFrame, side: BodySide?): RepSample? =
        side?.let { armSample(frame, it, swinging = false) }

    /**
     * Whether the shoulders have travelled too far since the last moment both
     * arms were at rest. The reference follows the athlete between reps.
     */
    private fun bodySwinging(frame: PoseFrame): Boolean {

        val left = frame[PoseLandmarkIndex.LEFT_SHOULDER] ?: return false
        val right = frame[PoseLandmarkIndex.RIGHT_SHOULDER] ?: return false
        val middle = PoseMath.midpoint(left, right)

        val resting = leftTracker.phase != RepCycleTracker.Phase.IN_REP &&
            rightTracker.phase != RepCycleTracker.Phase.IN_REP

        if (resting) {
            referenceShoulders = middle
            referenceShoulderWidth = PoseMath.distance(left, right).toDouble()
            return false
        }

        val reference = referenceShoulders ?: return false
        return PoseMath.distance(middle, reference) >
            AnalyzerThresholds.CURL_MAX_BODY_SWAY_RATIO * referenceShoulderWidth
    }

    private fun armSample(frame: PoseFrame, arm: BodySide, swinging: Boolean): RepSample? {

        val shoulder = frame[index(arm, PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER)] ?: return null
        val elbow = frame[index(arm, PoseLandmarkIndex.LEFT_ELBOW, PoseLandmarkIndex.RIGHT_ELBOW)] ?: return null
        val wrist = frame[index(arm, PoseLandmarkIndex.LEFT_WRIST, PoseLandmarkIndex.RIGHT_WRIST)] ?: return null
        val hip = frame[index(arm, PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP)] ?: return null

        val issues = mutableSetOf<FormIssue>()
        if (PoseMath.angle(hip, shoulder, elbow) > AnalyzerThresholds.CURL_MAX_ELBOW_FLARE_DEG) {
            issues += FormIssue.ELBOW_FLARE
        }
        if (swinging) {
            issues += FormIssue.BODY_SWING
        }

        return RepSample(angle = PoseMath.angle(shoulder, elbow, wrist), issues = issues)
    }

    override fun process(frame: PoseFrame) {

        val swinging = bodySwinging(frame)
        val left = armSample(frame, BodySide.LEFT, swinging) ?: return
        val right = armSample(frame, BodySide.RIGHT, swinging) ?: return

        val arm = activeArm
        if (arm != null) {
            val otherTracker = trackerFor(other(arm))
            val otherWasInRep = otherTracker.phase == RepCycleTracker.Phase.IN_REP
            val otherEvent = otherTracker.update(if (arm == BodySide.LEFT) right else left, frame.timestampMs)
            val working = if (arm == BodySide.LEFT) left else right

            if (!otherWasInRep && otherTracker.phase == RepCycleTracker.Phase.IN_REP) {
                workingDeepestDuringOtherRep = MAX_ANGLE
            }
            workingDeepestDuringOtherRep = min(workingDeepestDuringOtherRep, working.angle)

            showLive(working)
            trackerFor(arm).update(working, frame.timestampMs)?.let(::record)
            otherEvent?.let { onOtherArm(it) }
            return
        }

        // No working arm yet: whichever completes a full-range rep first
        // becomes it. Before that, the live readout follows the more bent arm.
        showLive(if (left.angle <= right.angle) left else right)

        val leftEvent = leftTracker.update(left, frame.timestampMs)
        val rightEvent = rightTracker.update(right, frame.timestampMs)

        onUnassigned(BodySide.LEFT, leftEvent, frame.timestampMs)
        onUnassigned(BodySide.RIGHT, rightEvent, frame.timestampMs)
    }

    private fun onUnassigned(arm: BodySide, event: RepEvent?, timestampMs: Long) {

        event ?: return

        val chosen = activeArm
        if (chosen != null) {
            // Both arms finished on the same frame and the other one was
            // chosen first: this arm curled alongside it.
            if (chosen != arm && event.reachedDepth) return
            if (chosen == arm) record(event)
            return
        }

        if (event.reachedDepth) {
            activeArm = arm
            // Whatever the other arm is doing now overlaps a working-arm rep.
            workingDeepestDuringOtherRep = repThresholds.depthAngle
            events += AnalyzerEvent(timestampMs, "arm_locked", arm.name)
        }

        record(event)
    }

    private fun onOtherArm(event: RepEvent) {

        val arm = activeArm ?: return
        if (!event.reachedDepth) return

        if (workingDeepestDuringOtherRep > repThresholds.partialAngle) {
            wrongArmReps++
            events += AnalyzerEvent(
                event.timestampMs,
                "rep_rejected_wrong_arm",
                "Curl with the ${other(arm).name} arm; working arm is ${arm.name}"
            )
        }
    }

    private fun trackerFor(arm: BodySide) =
        if (arm == BodySide.LEFT) leftTracker else rightTracker

    private fun other(arm: BodySide) =
        if (arm == BodySide.LEFT) BodySide.RIGHT else BodySide.LEFT

    fun rejectedWrongArmReps(): Int = wrongArmReps

    /** The arm being scored, once chosen. */
    fun activeArm(): BodySide? = activeArm

    override fun reset() {
        super.reset()
        leftTracker.reset()
        rightTracker.reset()
        activeArm = workingArm
        wrongArmReps = 0
        workingDeepestDuringOtherRep = MAX_ANGLE
        referenceShoulders = null
        referenceShoulderWidth = 0.0
    }

    companion object {

        private const val MAX_ANGLE = 180.0

        val THRESHOLDS = RepThresholds(
            extendedAngle = AnalyzerThresholds.CURL_EXTENDED_ANGLE,
            depthAngle = AnalyzerThresholds.CURL_DEPTH_ANGLE,
            partialAngle = AnalyzerThresholds.CURL_PARTIAL_ANGLE,
            minRepDurationMs = AnalyzerThresholds.CURL_MIN_REP_DURATION_MS
        )

        private val LEFT_INDICES = listOf(
            PoseLandmarkIndex.LEFT_SHOULDER,
            PoseLandmarkIndex.LEFT_ELBOW,
            PoseLandmarkIndex.LEFT_WRIST,
            PoseLandmarkIndex.LEFT_HIP
        )

        private val RIGHT_INDICES = listOf(
            PoseLandmarkIndex.RIGHT_SHOULDER,
            PoseLandmarkIndex.RIGHT_ELBOW,
            PoseLandmarkIndex.RIGHT_WRIST,
            PoseLandmarkIndex.RIGHT_HIP
        )
    }
}
