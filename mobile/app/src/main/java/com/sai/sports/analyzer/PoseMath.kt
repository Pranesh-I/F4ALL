package com.sai.sports.analyzer

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.acos
import kotlin.math.sqrt

object PoseMath {

    fun calculateAngle(
        first: NormalizedLandmark,
        vertex: NormalizedLandmark,
        second: NormalizedLandmark
    ): Double {

        val vector1X = first.x() - vertex.x()
        val vector1Y = first.y() - vertex.y()

        val vector2X = second.x() - vertex.x()
        val vector2Y = second.y() - vertex.y()

        val dotProduct =
            vector1X * vector2X +
                    vector1Y * vector2Y

        val magnitude1 =
            sqrt(
                vector1X * vector1X +
                        vector1Y * vector1Y
            )

        val magnitude2 =
            sqrt(
                vector2X * vector2X +
                        vector2Y * vector2Y
            )

        if (magnitude1 == 0f || magnitude2 == 0f) {
            return 0.0
        }

        val cosine =
            (dotProduct / (magnitude1 * magnitude2))
                .coerceIn(-1f, 1f)

        return Math.toDegrees(
            acos(cosine.toDouble())
        )
    }

    fun isLandmarkVisible(
        landmark: NormalizedLandmark,
        minimumVisibility: Float = 0.5f
    ): Boolean {

        return landmark.visibility()
            .orElse(0f) >= minimumVisibility
    }
}