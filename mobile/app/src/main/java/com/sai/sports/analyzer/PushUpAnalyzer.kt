package com.sai.sports.analyzer

/**
 * Counts push-ups from the elbow angle, filmed side-on.
 *
 * Driving signal: shoulder-elbow-wrist, ~170 deg at the top and ~80 deg with
 * the chest near the ground.
 *
 * What makes a push-up a push-up is the straight body, so two checks run on
 * every frame and either one rejects the rep:
 *
 *  - Body line: shoulder-hip-ankle below [AnalyzerThresholds
 *    .PUSHUP_MIN_BODY_LINE_ANGLE] means the hips have sagged or piked. Which
 *    one is decided by the side of the shoulder-ankle line the hip sits on,
 *    because the athlete needs to be told which way to fix it.
 *  - Plank: the shoulder-ankle line tilted more than [AnalyzerThresholds
 *    .PUSHUP_MAX_BODY_TILT_DEG] from horizontal is not a plank at all. Without
 *    this, standing up and bending the arms would score.
 *
 * The plank check also gates the start position, so the counter never arms on
 * an athlete who is still standing.
 */
class PushUpAnalyzer : RepExerciseAnalyzer(
    testType = TestType.PUSH_UPS,
    repThresholds = THRESHOLDS,
    leftIndices = LEFT_INDICES,
    rightIndices = RIGHT_INDICES,
    locksOneSide = true
) {

    override val noStartReason =
        "Start position never detected — hold a straight-arm plank, side-on to the camera"

    override fun sample(frame: PoseFrame, side: BodySide?): RepSample? {

        val lockedSide = side ?: return null

        val shoulder = frame[index(lockedSide, PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER)] ?: return null
        val elbow = frame[index(lockedSide, PoseLandmarkIndex.LEFT_ELBOW, PoseLandmarkIndex.RIGHT_ELBOW)] ?: return null
        val wrist = frame[index(lockedSide, PoseLandmarkIndex.LEFT_WRIST, PoseLandmarkIndex.RIGHT_WRIST)] ?: return null
        val hip = frame[index(lockedSide, PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP)] ?: return null
        val ankle = frame[index(lockedSide, PoseLandmarkIndex.LEFT_ANKLE, PoseLandmarkIndex.RIGHT_ANKLE)] ?: return null

        val elbowAngle = PoseMath.angle(shoulder, elbow, wrist)
        val inPlank =
            PoseMath.angleFromHorizontal(shoulder, ankle) <= AnalyzerThresholds.PUSHUP_MAX_BODY_TILT_DEG

        val issues = when {
            !inPlank -> setOf(FormIssue.NOT_IN_PLANK)

            PoseMath.angle(shoulder, hip, ankle) < AnalyzerThresholds.PUSHUP_MIN_BODY_LINE_ANGLE -> {
                // Normalise the cross product by the line's direction so "below
                // the line" means the same whichever way the athlete faces.
                val direction = if (ankle.x >= shoulder.x) 1.0 else -1.0
                val below = PoseMath.crossProduct(shoulder, ankle, hip) * direction > 0.0
                setOf(if (below) FormIssue.HIPS_SAGGING else FormIssue.HIPS_PIKED)
            }

            else -> emptySet()
        }

        return RepSample(angle = elbowAngle, issues = issues, inStartPose = inPlank)
    }

    companion object {

        val THRESHOLDS = RepThresholds(
            extendedAngle = AnalyzerThresholds.PUSHUP_EXTENDED_ANGLE,
            depthAngle = AnalyzerThresholds.PUSHUP_DEPTH_ANGLE,
            partialAngle = AnalyzerThresholds.PUSHUP_PARTIAL_ANGLE,
            minRepDurationMs = AnalyzerThresholds.PUSHUP_MIN_REP_DURATION_MS
        )

        private val LEFT_INDICES = listOf(
            PoseLandmarkIndex.LEFT_SHOULDER,
            PoseLandmarkIndex.LEFT_ELBOW,
            PoseLandmarkIndex.LEFT_WRIST,
            PoseLandmarkIndex.LEFT_HIP,
            PoseLandmarkIndex.LEFT_ANKLE
        )

        private val RIGHT_INDICES = listOf(
            PoseLandmarkIndex.RIGHT_SHOULDER,
            PoseLandmarkIndex.RIGHT_ELBOW,
            PoseLandmarkIndex.RIGHT_WRIST,
            PoseLandmarkIndex.RIGHT_HIP,
            PoseLandmarkIndex.RIGHT_ANKLE
        )
    }
}
