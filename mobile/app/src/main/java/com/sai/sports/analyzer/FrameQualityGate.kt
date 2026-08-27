package com.sai.sports.analyzer

/**
 * Rejects frames whose required landmarks are not reliably visible.
 *
 * Without this gate, a frame where the pose model guessed at an occluded knee
 * produces a plausible-looking torso angle, and a plausible-looking wrong angle
 * is exactly what makes a rep counter miscount. Dropping the frame costs one
 * sample; trusting it costs a rep.
 */
class FrameQualityGate(
    private val requiredIndices: List<Int>,
    private val minimumVisibility: Float = AnalyzerThresholds.MIN_LANDMARK_VISIBILITY
) {

    data class Verdict(
        val accepted: Boolean,
        val meanVisibility: Float,
        val missingIndices: List<Int> = emptyList()
    )

    fun evaluate(frame: PoseFrame): Verdict {

        if (frame.points.isEmpty()) {
            return Verdict(
                accepted = false,
                meanVisibility = 0f,
                missingIndices = requiredIndices
            )
        }

        val missing = requiredIndices.filterNot {
            frame.isVisible(it, minimumVisibility)
        }

        return Verdict(
            accepted = missing.isEmpty(),
            meanVisibility = frame.meanVisibility(requiredIndices),
            missingIndices = missing
        )
    }
}
