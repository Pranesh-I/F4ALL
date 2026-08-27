package com.sai.sports.analyzer

import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class SitUpAnalyzer {

    fun analyze(
        landmarks: List<NormalizedLandmark>
    ): Double? {

        if (landmarks.size <= 26) {
            return null
        }

        val leftShoulder = landmarks[11]
        val leftHip = landmarks[23]
        val leftKnee = landmarks[25]

        val rightShoulder = landmarks[12]
        val rightHip = landmarks[24]
        val rightKnee = landmarks[26]

        val leftAngle = calculateSideAngle(
            shoulder = leftShoulder,
            hip = leftHip,
            knee = leftKnee
        )

        val rightAngle = calculateSideAngle(
            shoulder = rightShoulder,
            hip = rightHip,
            knee = rightKnee
        )

        val angle = when {
            leftAngle != null && rightAngle != null ->
                (leftAngle + rightAngle) / 2.0

            leftAngle != null ->
                leftAngle

            rightAngle != null ->
                rightAngle

            else ->
                null
        }

        angle?.let {
            Log.d(
                "SitUpAngle",
                "Torso angle: %.2f°".format(it)
            )
        }

        return angle
    }

    private fun calculateSideAngle(
        shoulder: NormalizedLandmark,
        hip: NormalizedLandmark,
        knee: NormalizedLandmark
    ): Double? {

        val shoulderVisible =
            PoseMath.isLandmarkVisible(shoulder)

        val hipVisible =
            PoseMath.isLandmarkVisible(hip)

        val kneeVisible =
            PoseMath.isLandmarkVisible(knee)

        if (
            !shoulderVisible ||
            !hipVisible ||
            !kneeVisible
        ) {
            return null
        }

        return PoseMath.calculateAngle(
            first = shoulder,
            vertex = hip,
            second = knee
        )
    }

    fun reset() {

        // No state yet.
        // Rep counting starts in Milestone 3.3.
    }
}