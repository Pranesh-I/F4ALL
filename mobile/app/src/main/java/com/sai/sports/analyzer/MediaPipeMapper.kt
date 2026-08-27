package com.sai.sports.analyzer

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * The single boundary between MediaPipe and the scoring code.
 *
 * Everything downstream of this file works on [PoseFrame], which keeps the
 * analyzers unit-testable on a plain JVM and portable to the Python server-side
 * pipeline in Sprint 5. If a MediaPipe import ever appears in an analyzer, that
 * property is gone.
 */
object MediaPipeMapper {

    fun toPoseFrame(
        landmarks: List<NormalizedLandmark>,
        timestampMs: Long
    ): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        points = landmarks.map { landmark ->
            PosePoint(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                // MediaPipe reports visibility as an Optional; absent means the
                // model made no claim, which we treat as "do not trust it".
                visibility = landmark.visibility().orElse(0f)
            )
        }
    )
}
