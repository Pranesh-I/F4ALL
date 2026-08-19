package com.sai.sports.ui.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max

class PoseOverlayView(
    context: Context
) : View(context) {

    private var landmarks: List<NormalizedLandmark> = emptyList()

    private var imageWidth = 1
    private var imageHeight = 1

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    fun updatePose(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.landmarks = landmarks
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)

        postInvalidateOnAnimation()
    }

    fun clearPose() {
        landmarks = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (landmarks.isEmpty()) {
            return
        }

        /*
         * PreviewView uses FILL_CENTER by default.
         *
         * Therefore the source image is scaled until it
         * completely fills the view and the excess is cropped.
         */
        val scale = max(
            width.toFloat() / imageWidth,
            height.toFloat() / imageHeight
        )

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        val offsetX = (width - scaledWidth) / 2f
        val offsetY = (height - scaledHeight) / 2f

        for (connection in POSE_CONNECTIONS) {

            val start = landmarks.getOrNull(connection.first)
            val end = landmarks.getOrNull(connection.second)

            if (start == null || end == null) {
                continue
            }

            val startX =
                offsetX + start.x() * scaledWidth

            val startY =
                offsetY + start.y() * scaledHeight

            val endX =
                offsetX + end.x() * scaledWidth

            val endY =
                offsetY + end.y() * scaledHeight

            canvas.drawLine(
                startX,
                startY,
                endX,
                endY,
                linePaint
            )
        }

        for (landmark in landmarks) {

            val x =
                offsetX + landmark.x() * scaledWidth

            val y =
                offsetY + landmark.y() * scaledHeight

            canvas.drawCircle(
                x,
                y,
                8f,
                pointPaint
            )
        }
    }

    companion object {

        /*
         * MediaPipe Pose has 33 landmarks.
         *
         * These connections create the visible human skeleton.
         */
        private val POSE_CONNECTIONS = listOf(

            // Face
            0 to 1,
            1 to 2,
            2 to 3,
            3 to 7,
            0 to 4,
            4 to 5,
            5 to 6,
            6 to 8,

            // Shoulders
            11 to 12,

            // Left arm
            11 to 13,
            13 to 15,
            15 to 17,
            15 to 19,
            15 to 21,

            // Right arm
            12 to 14,
            14 to 16,
            16 to 18,
            16 to 20,
            16 to 22,

            // Torso
            11 to 23,
            12 to 24,
            23 to 24,

            // Left leg
            23 to 25,
            25 to 27,
            27 to 29,
            29 to 31,

            // Right leg
            24 to 26,
            26 to 28,
            28 to 30,
            30 to 32
        )
    }
}