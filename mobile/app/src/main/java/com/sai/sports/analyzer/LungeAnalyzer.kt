package com.sai.sports.analyzer

import kotlin.math.abs

/**
 * Counts lunges from both knee angles, filmed side-on.
 *
 * Driving signal: the mean of the two hip-knee-ankle angles. Both knees bend
 * to roughly 90 deg at the bottom of a lunge, so the mean does not care which
 * leg leads — alternating lunges work without the analyzer tracking which is
 * which.
 *
 * Once the knees are bent past [AnalyzerThresholds.LUNGE_PARTIAL_ANGLE], two
 * checks run on the front leg — the one whose knee is higher on screen, since
 * the front thigh is near horizontal and the back knee drops toward the floor:
 *
 *  - Stance: ankles closer than [AnalyzerThresholds.LUNGE_MIN_STANCE_RATIO] of
 *    a leg length apart means the feet are together. That is a squat, and the
 *    rep is rejected.
 *  - Front-knee alignment: the knee travelling past the ankle by more than
 *    [AnalyzerThresholds.LUNGE_MAX_KNEE_TRAVEL_RATIO] of the shin is recorded
 *    as a warning.
 *  - Posture: the torso leaning past [AnalyzerThresholds
 *    .LUNGE_MAX_TORSO_LEAN_DEG] over the front leg is recorded as a warning.
 *
 * The checks wait for depth because stepping into a forward lunge starts with
 * the feet together, and that is not a fault.
 */
class LungeAnalyzer : RepExerciseAnalyzer(
    testType = TestType.LUNGES,
    repThresholds = THRESHOLDS,
    leftIndices = LEFT_INDICES,
    rightIndices = RIGHT_INDICES,
    locksOneSide = false
) {

    override val noStartReason =
        "Start position never detected — stand tall, side-on to the camera, before starting"

    override fun sample(frame: PoseFrame, side: BodySide?): RepSample? {

        val leftShoulder = frame[PoseLandmarkIndex.LEFT_SHOULDER] ?: return null
        val rightShoulder = frame[PoseLandmarkIndex.RIGHT_SHOULDER] ?: return null
        val leftHip = frame[PoseLandmarkIndex.LEFT_HIP] ?: return null
        val leftKnee = frame[PoseLandmarkIndex.LEFT_KNEE] ?: return null
        val leftAnkle = frame[PoseLandmarkIndex.LEFT_ANKLE] ?: return null
        val rightHip = frame[PoseLandmarkIndex.RIGHT_HIP] ?: return null
        val rightKnee = frame[PoseLandmarkIndex.RIGHT_KNEE] ?: return null
        val rightAnkle = frame[PoseLandmarkIndex.RIGHT_ANKLE] ?: return null

        val meanKneeAngle =
            (PoseMath.angle(leftHip, leftKnee, leftAnkle) + PoseMath.angle(rightHip, rightKnee, rightAnkle)) / 2.0

        if (meanKneeAngle > AnalyzerThresholds.LUNGE_PARTIAL_ANGLE) {
            return RepSample(angle = meanKneeAngle)
        }

        val leftLeads = leftKnee.y <= rightKnee.y

        val frontHip = if (leftLeads) leftHip else rightHip
        val frontKnee = if (leftLeads) leftKnee else rightKnee
        val frontAnkle = if (leftLeads) leftAnkle else rightAnkle
        val backAnkle = if (leftLeads) rightAnkle else leftAnkle

        val shin = PoseMath.distance(frontKnee, frontAnkle).toDouble()
        val legLength = PoseMath.distance(frontHip, frontKnee).toDouble() + shin
        val stance = abs((frontAnkle.x - backAnkle.x).toDouble())

        val issues = mutableSetOf<FormIssue>()

        if (stance < AnalyzerThresholds.LUNGE_MIN_STANCE_RATIO * legLength) {
            issues += FormIssue.STANCE_TOO_NARROW
        }

        // "Forward" is the way the front thigh points from the hip.
        val forward = if (frontKnee.x >= frontHip.x) 1.0 else -1.0
        val kneeTravel = (frontKnee.x - frontAnkle.x).toDouble() * forward
        if (kneeTravel > AnalyzerThresholds.LUNGE_MAX_KNEE_TRAVEL_RATIO * shin) {
            issues += FormIssue.KNEE_PAST_TOES
        }

        val torsoLean = PoseMath.angleFromVertical(
            upper = PoseMath.midpoint(leftShoulder, rightShoulder),
            lower = PoseMath.midpoint(leftHip, rightHip)
        )
        if (torsoLean > AnalyzerThresholds.LUNGE_MAX_TORSO_LEAN_DEG) {
            issues += FormIssue.TORSO_LEAN
        }

        return RepSample(angle = meanKneeAngle, issues = issues)
    }

    companion object {

        val THRESHOLDS = RepThresholds(
            extendedAngle = AnalyzerThresholds.LUNGE_EXTENDED_ANGLE,
            depthAngle = AnalyzerThresholds.LUNGE_DEPTH_ANGLE,
            partialAngle = AnalyzerThresholds.LUNGE_PARTIAL_ANGLE,
            minRepDurationMs = AnalyzerThresholds.LUNGE_MIN_REP_DURATION_MS
        )

        private val LEFT_INDICES = listOf(
            PoseLandmarkIndex.LEFT_SHOULDER,
            PoseLandmarkIndex.LEFT_HIP,
            PoseLandmarkIndex.LEFT_KNEE,
            PoseLandmarkIndex.LEFT_ANKLE
        )

        private val RIGHT_INDICES = listOf(
            PoseLandmarkIndex.RIGHT_SHOULDER,
            PoseLandmarkIndex.RIGHT_HIP,
            PoseLandmarkIndex.RIGHT_KNEE,
            PoseLandmarkIndex.RIGHT_ANKLE
        )
    }
}
