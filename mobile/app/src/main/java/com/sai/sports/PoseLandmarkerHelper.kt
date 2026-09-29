package com.sai.sports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Owns one MediaPipe pose landmarker for the lifetime of one capture screen.
 *
 * Three rules keep it from going stale or crashing:
 *
 *  - [close] and [detectLiveStream] are serialised on one lock. Closing from
 *    the main thread while the analysis thread is inside `detectAsync` is a
 *    native use-after-free; with the lock, a frame either finishes first or
 *    sees the landmarker already closed and is dropped.
 *  - Timestamps are strictly increasing. Live-stream mode rejects a repeated
 *    timestamp, and two frames can land in the same millisecond.
 *  - The image is never mirrored before inference. MediaPipe labels LEFT and
 *    RIGHT from the person's point of view on the image it is given; a
 *    mirrored image swaps every label, and side-specific scoring and gestures
 *    silently go wrong. Only the on-screen overlay may be mirrored.
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener
) {

    private val lock = Any()

    private var poseLandmarker: PoseLandmarker? = null

    private var lastTimestampMs = 0L

    /** False when the model failed to load; the screen shows an error instead of a dead preview. */
    @Volatile
    var isReady = false
        private set

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
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
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            isReady = true
            Log.d(TAG, "Pose Landmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Pose Landmarker", e)
            listener.onError(e.message ?: "Failed to initialize Pose Landmarker")
        }
    }

    /** Runs pose detection on one camera frame. Always closes [imageProxy]. */
    fun detectLiveStream(imageProxy: ImageProxy) {

        try {
            synchronized(lock) {

                val landmarker = poseLandmarker ?: return

                val frameTime = maxOf(SystemClock.uptimeMillis(), lastTimestampMs + 1)
                lastTimestampMs = frameTime

                val bitmap = imageProxy.toBitmap()
                val rotation = imageProxy.imageInfo.rotationDegrees

                val upright =
                    if (rotation == 0) bitmap
                    else Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height,
                        Matrix().apply { postRotate(rotation.toFloat()) },
                        true
                    )

                landmarker.detectAsync(BitmapImageBuilder(upright).build(), frameTime)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Pose detection failed", exception)
            listener.onError(exception.message ?: "Pose detection failed")
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        synchronized(lock) {
            poseLandmarker?.close()
            poseLandmarker = null
            isReady = false
        }
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
        private const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
}
