package com.sai.sports.ui.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

/**
 * Binds the capture screen's camera use cases, and unbinds exactly those.
 *
 * The black-preview and stale-camera bugs this replaces came from three habits:
 * `unbindAll()` (which also tears down anything another screen still holds),
 * binding against a lifecycle that had already been destroyed by navigation,
 * and never unbinding at all when the screen left. This class binds fresh use
 * cases every time, refuses a dead lifecycle, and [release] undoes precisely
 * what [bind] did — so the next attempt, or the next visit to the screen,
 * always starts from a clean camera.
 *
 * Main thread only. Frames are handed to [analyze] on [analysisExecutor].
 */
class CameraBinding(
    private val context: Context,
    private val analysisExecutor: ExecutorService,
    private val analyze: (ImageProxy) -> Unit
) {

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var bound: List<UseCase> = emptyList()

    @Volatile
    private var released = false

    /** Set once bound; recording needs it. */
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onBound: (VideoCapture<Recorder>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        released = false
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({

            // Navigation may have moved on while the provider was loading.
            if (released || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                return@addListener
            }

            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                unbindOwn()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(analysisExecutor) { image ->
                            // A frame already in flight when the screen left.
                            if (released) image.close() else analyze(image)
                        }
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val capture = VideoCapture.withOutput(recorder)

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                    imageAnalysis
                )

                analysis = imageAnalysis
                bound = listOf(preview, capture, imageAnalysis)
                videoCapture = capture
                onBound(capture)

            } catch (exception: Exception) {
                Log.e(TAG, "Camera binding failed", exception)
                onError(exception)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    /** Stops frames and gives the camera back. Safe to call more than once. */
    fun release() {
        released = true
        unbindOwn()
    }

    private fun unbindOwn() {
        analysis?.clearAnalyzer()
        analysis = null
        if (bound.isNotEmpty()) {
            provider?.unbind(*bound.toTypedArray())
            bound = emptyList()
        }
        videoCapture = null
    }

    private companion object {
        const val TAG = "CameraBinding"
    }
}
