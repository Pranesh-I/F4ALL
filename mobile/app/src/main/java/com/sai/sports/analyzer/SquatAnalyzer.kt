package com.sai.sports.analyzer

import kotlin.math.abs

/**
 * Counts squats from the knee angle, filmed side-on.
 *
 * The driving signal is hip-knee-ankle: ~175 deg standing, ~90 deg with the
 * thighs parallel to the ground. A rep counts when the athlete goes below
 * [AnalyzerThresholds.SQUAT_DEPTH_ANGLE] and stands back up.
 *
 * The standing calibration is the held start position: reps only count once
 * the athlete has stood tall for [AnalyzerThresholds.REP_START_HOLD_FRAMES].
 *
 * Three coaching points are recorded as warnings — each is a worse squat, not
 * a different exercise:
 *
 *  - Torso lean past [AnalyzerThresholds.SQUAT_MAX_TORSO_LEAN_DEG].
 *  - Knee alignment: at depth, the knee travelling past the ankle by more than
 *    [AnalyzerThresholds.SQUAT_MAX_KNEE_TRAVEL_RATIO] of the shin.
 *  - Stability: the near ankle sliding more than [AnalyzerThresholds
 *    .FOOT_MAX_SHIFT_RATIO] of a leg length from where it stood at the top.
 */
class SquatAnalyzer : RepExerciseAnalyzer(
    testType = TestType.SQUATS,
    repThresholds = THRESHOLDS,
    leftIndices = LEFT_INDICES,
    rightIndices = RIGHT_INDICES,
    locksOneSide = true
) {

    override val noStartReason =
        "Start position never detected — stand tall, side-on to the camera, before starting"

    /** Where the near ankle stood at the top of the current rep, and the leg length then. */
    private var referenceAnkleX: Double? = null
    private var referenceLegLength = 0.0

    override fun sample(frame: PoseFrame, side: BodySide?): RepSample? {

        val lockedSide = side ?: return null

        val shoulder = frame[index(lockedSide, PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER)] ?: return null
        val hip = frame[index(lockedSide, PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP)] ?: return null
        val knee = frame[index(lockedSide, PoseLandmarkIndex.LEFT_KNEE, PoseLandmarkIndex.RIGHT_KNEE)] ?: return null
        val ankle = frame[index(lockedSide, PoseLandmarkIndex.LEFT_ANKLE, PoseLandmarkIndex.RIGHT_ANKLE)] ?: return null

        val kneeAngle = PoseMath.angle(hip, knee, ankle)
        val shin = PoseMath.distance(knee, ankle).toDouble()

        // Outside a rep the reference follows the athlete, so stepping into
        // position is never "moving the feet".
        if (tracker.phase != RepCycleTracker.Phase.IN_REP) {
            referenceAnkleX = ankle.x.toDouble()
            referenceLegLength = PoseMath.distance(hip, knee).toDouble() + shin
        }

        val issues = mutableSetOf<FormIssue>()

        if (PoseMath.angleFromVertical(upper = shoulder, lower = hip) > AnalyzerThresholds.SQUAT_MAX_TORSO_LEAN_DEG) {
            issues += FormIssue.TORSO_LEAN
        }

        if (kneeAngle <= AnalyzerThresholds.SQUAT_PARTIAL_ANGLE) {
            // The hips sit behind the knees, so the knee points the way the athlete faces.
            val forward = if (knee.x >= hip.x) 1.0 else -1.0
            val kneeTravel = (knee.x - ankle.x).toDouble() * forward
            if (kneeTravel > AnalyzerThresholds.SQUAT_MAX_KNEE_TRAVEL_RATIO * shin) {
                issues += FormIssue.KNEE_PAST_TOES
            }
        }

        referenceAnkleX?.let { reference ->
            if (abs(ankle.x - reference) > AnalyzerThresholds.FOOT_MAX_SHIFT_RATIO * referenceLegLength) {
                issues += FormIssue.FEET_MOVED
            }
        }

        return RepSample(angle = kneeAngle, issues = issues)
    }

    override fun reset() {
        super.reset()
        referenceAnkleX = null
        referenceLegLength = 0.0
    }

    companion object {

        val THRESHOLDS = RepThresholds(
            extendedAngle = AnalyzerThresholds.SQUAT_EXTENDED_ANGLE,
            depthAngle = AnalyzerThresholds.SQUAT_DEPTH_ANGLE,
            partialAngle = AnalyzerThresholds.SQUAT_PARTIAL_ANGLE,
            minRepDurationMs = AnalyzerThresholds.SQUAT_MIN_REP_DURATION_MS
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
