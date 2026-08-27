package com.sai.sports.analyzer

/**
 * Framework-independent pose model.
 *
 * The analyzers deliberately do NOT depend on MediaPipe types. Two reasons:
 *
 *  1. Unit tests run on the plain JVM with no Android/MediaPipe classpath.
 *  2. Sprint 5 has to re-implement these same algorithms server-side in Python.
 *     Keeping the input model this small makes that port a transcription
 *     rather than a redesign.
 *
 * Conversion from MediaPipe lives in [MediaPipeMapper] and nowhere else.
 */

/**
 * A single pose landmark.
 *
 * [x] and [y] are normalized to the source image: 0.0..1.0, origin top-left.
 * y therefore grows DOWNWARD — upward movement means y decreases.
 */
data class PosePoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 0f
)

/**
 * One frame of pose data.
 *
 * [points] is expected to hold all 33 MediaPipe Pose landmarks, indexed by
 * [PoseLandmarkIndex]. A frame with fewer points is not an error — the frame
 * quality gate rejects it before any analyzer sees it.
 */
data class PoseFrame(
    val timestampMs: Long,
    val points: List<PosePoint>
) {

    operator fun get(index: Int): PosePoint? = points.getOrNull(index)

    fun isVisible(
        index: Int,
        minimumVisibility: Float = AnalyzerThresholds.MIN_LANDMARK_VISIBILITY
    ): Boolean {
        val point = points.getOrNull(index) ?: return false
        return point.visibility >= minimumVisibility
    }

    /** Mean visibility across [indices]; 0 if any are missing. */
    fun meanVisibility(indices: List<Int>): Float {
        if (indices.isEmpty()) return 0f
        var total = 0f
        for (index in indices) {
            val point = points.getOrNull(index) ?: return 0f
            total += point.visibility
        }
        return total / indices.size
    }

    companion object {
        const val LANDMARK_COUNT = 33
    }
}

/**
 * MediaPipe Pose landmark indices.
 *
 * Named constants because `landmarks[24]` in a state machine is how off-by-one
 * bugs get shipped.
 */
object PoseLandmarkIndex {

    const val NOSE = 0

    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8

    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16

    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32
}

/** Which side of the body an analyzer locked onto. */
enum class BodySide {
    LEFT,
    RIGHT
}
