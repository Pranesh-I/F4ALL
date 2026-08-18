package com.sai.sports

import android.content.Context
import android.util.Log

import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder

class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener
) {

    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task")
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, input ->
                listener.onResults(result, input.width, input.height)
            }
            .setErrorListener { error ->
                listener.onError(error.message ?: "Unknown MediaPipe error")
            }
            .build()

        try {
            poseLandmarker =
                PoseLandmarker.createFromOptions(context, options)

            Log.d(TAG, "Pose Landmarker initialized successfully")

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to initialize Pose Landmarker",
                e
            )

            listener.onError(
                e.message ?: "Failed to initialize Pose Landmarker"
            )
        }
    }

    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean = false
    ) {
        val frameTime = SystemClock.uptimeMillis()

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val bitmap = imageProxy.toBitmap()

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        bitmap.width.toFloat(),
                        bitmap.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

            val mpImage =
                BitmapImageBuilder(rotatedBitmap).build()

            poseLandmarker?.detectAsync(
                mpImage,
                frameTime
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Pose detection failed",
                exception
            )

            listener.onError(
                exception.message ?: "Pose detection failed"
            )

        } finally {

            imageProxy.close()
        }
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    interface LandmarkerListener {

        fun onResults(
            result: PoseLandmarkerResult,
            imageWidth: Int,
            imageHeight: Int
        )

        fun onError(error: String)
    }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"
    }
}