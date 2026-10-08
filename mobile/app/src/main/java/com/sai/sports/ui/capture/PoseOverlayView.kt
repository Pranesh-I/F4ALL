package com.sai.sports.ui.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.sai.sports.analyzer.AnalyzerThresholds
import com.sai.sports.analyzer.PosePoint
import kotlin.math.max

/**
 * Draws the pose skeleton.
 *
 * Takes [PosePoint] rather than MediaPipe landmarks so the same view serves the
 * live camera overlay and the results screen's replay of a recorded sequence.
 */
class PoseOverlayView(
    context: Context
) : View(context) {

    private var points: List<PosePoint> = emptyList()

    private var imageWidth = 1
    private var imageHeight = 1

    /**
     * FILL_CENTER matches PreviewView's default and is right for the live
     * overlay. Replay has no camera preview underneath, so it uses FIT_CENTER
     * to keep the whole skeleton on screen instead of cropping it.
     */
    var scaleMode: ScaleMode = ScaleMode.FILL_CENTER

    enum class ScaleMode {
        FILL_CENTER,
        FIT_CENTER
    }

    /*
     * Trials palette: chalk-white limbs over an ink edge, so the skeleton reads
     * on a sunlit wall and in a dim room alike; marigold joints; pencil grey
     * for a joint the model is unsure of.
     */
    private val density = resources.displayMetrics.density

    private val pointPaint = Paint().apply {
        color = MARIGOLD
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointEdgePaint = Paint().apply {
        color = INK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        isAntiAlias = true
    }

    private val lowConfidencePointPaint = Paint().apply {
        color = PENCIL
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val lineEdgePaint = Paint().apply {
        color = INK
        alpha = 150
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    fun updatePose(
        points: List<PosePoint>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.points = points
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)

        postInvalidateOnAnimation()
    }

    fun clearPose() {
        points = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.isEmpty()) {
            return
        }

        val widthScale = width.toFloat() / imageWidth
        val heightScale = height.toFloat() / imageHeight

        val scale = when (scaleMode) {
            ScaleMode.FILL_CENTER -> max(widthScale, heightScale)
            ScaleMode.FIT_CENTER -> minOf(widthScale, heightScale)
        }

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        val offsetX = (width - scaledWidth) / 2f
        val offsetY = (height - scaledHeight) / 2f

        for (connection in POSE_CONNECTIONS) {

            val start = points.getOrNull(connection.first) ?: continue
            val end = points.getOrNull(connection.second) ?: continue

            // Drawing a limb between landmarks the model could not see produces
            // a skeleton that looks confident about a guess.
            if (start.visibility < AnalyzerThresholds.MIN_LANDMARK_VISIBILITY) continue
            if (end.visibility < AnalyzerThresholds.MIN_LANDMARK_VISIBILITY) continue

            val x1 = offsetX + start.x * scaledWidth
            val y1 = offsetY + start.y * scaledHeight
            val x2 = offsetX + end.x * scaledWidth
            val y2 = offsetY + end.y * scaledHeight
            canvas.drawLine(x1, y1, x2, y2, lineEdgePaint)
            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }

        for (point in points) {

            val confident = point.visibility >= AnalyzerThresholds.MIN_LANDMARK_VISIBILITY
            val x = offsetX + point.x * scaledWidth
            val y = offsetY + point.y * scaledHeight
            val radius = 4f * density

            canvas.drawCircle(x, y, radius, if (confident) pointPaint else lowConfidencePointPaint)
            canvas.drawCircle(x, y, radius, pointEdgePaint)
        }
    }

    companion object {

        // TrialsColors, as ARGB ints for the Android canvas.
        private const val INK = 0xFF171512.toInt()
        private const val MARIGOLD = 0xFFF2A900.toInt()
        private const val PENCIL = 0xFF9A9A96.toInt()

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
