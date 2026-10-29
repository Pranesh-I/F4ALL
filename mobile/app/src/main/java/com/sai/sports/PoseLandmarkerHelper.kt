package com.sai.sports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
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

    private val perf = PosePerfMeter()

    /** False when the model failed to load; the screen shows an error instead of a dead preview. */
    @Volatile
    var isReady = false
        private set

    /** Which processor runs the model on this phone: "GPU", or "CPU" when the GPU could not. */
    var delegate: String = "none"
        private set

    // Set by MediaPipe's error callback; acted on by the next frame, on the
    // camera thread, because the landmarker cannot be closed from inside its
    // own callback.
    @Volatile
    private var gpuFailed = false

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        // The GPU first (Sprint 21: on the first phone measured it was the
        // difference between dropping a third of the frames and keeping up),
        // the CPU when this phone's GPU driver cannot run the model.
        for (choice in listOf(Delegate.GPU, Delegate.CPU)) {
            if (tryCreate(choice)) return
        }
    }

    private fun tryCreate(choice: Delegate): Boolean {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(choice)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, input ->
                perf.onResult(result.timestampMs(), SystemClock.uptimeMillis())
                    ?.let { Log.i(PERF_TAG, it.logLine()) }
                listener.onResults(result, input.width, input.height)
            }
            .setErrorListener { error ->
                if (choice == Delegate.GPU) {
                    // Some GPU drivers load the model and then fail on the
                    // first frames. Fall back rather than show an error.
                    Log.w(TAG, "Pose model failed on the GPU; switching to the CPU", error)
                    gpuFailed = true
                } else {
                    listener.onError(error.message ?: "Unknown MediaPipe error")
                }
            }
            .build()

        return try {
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            isReady = true
            delegate = choice.name
            Log.i(PERF_TAG, "delegate=${choice.name}")
            true
        } catch (e: Exception) {
            if (choice == Delegate.CPU) {
                Log.e(TAG, "Failed to initialize Pose Landmarker", e)
                listener.onError(e.message ?: "Failed to initialize Pose Landmarker")
            } else {
                Log.w(TAG, "Pose model cannot run on the ${choice.name}; trying the next", e)
            }
            false
        }
    }

    /** Runs pose detection on one camera frame. Always closes [imageProxy]. */
    fun detectLiveStream(imageProxy: ImageProxy) {

        try {
            synchronized(lock) {

                if (gpuFailed && delegate == Delegate.GPU.name) {
                    gpuFailed = false
                    poseLandmarker?.close()
                    poseLandmarker = null
                    isReady = false
                    tryCreate(Delegate.CPU)
                }

                val landmarker = poseLandmarker ?: return

                val frameTime = maxOf(SystemClock.uptimeMillis(), lastTimestampMs + 1)
                lastTimestampMs = frameTime
                perf.onSent(frameTime)

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
        // Read by the device-matrix measurements: adb logcat -s PosePerf
        private const val PERF_TAG = "PosePerf"
        private const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
}
