package com.sai.sports.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sai.sports.utils.FrameExtractor
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import androidx.compose.runtime.DisposableEffect
import com.sai.sports.PoseLandmarkerHelper

@Composable
fun CaptureScreen(
    testName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasCameraPermission = isGranted
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreview(
            testName = testName,
            onBack = onBack
        )
    } else {
        PermissionScreen(
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onBack = onBack
        )
    }
}

@Composable
private fun CameraPreview(
    testName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val poseLandmarkerHelper = remember {
        PoseLandmarkerHelper(
            context = context,
            listener = object : PoseLandmarkerHelper.LandmarkerListener {

                override fun onResults(
                    result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
                    imageWidth: Int,
                    imageHeight: Int
                ) {
                    val poseCount = result.landmarks().size

                    println(
                        "MediaPipe pose result: $poseCount pose(s)"
                    )
                }

                override fun onError(error: String) {
                    println(
                        "MediaPipe error: $error"
                    )
                }
            }
        )
    }

    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            poseLandmarkerHelper.close()
        }
    }

    var videoCapture by remember {
        mutableStateOf<VideoCapture<Recorder>?>(null)
    }

    var recording by remember {
        mutableStateOf<Recording?>(null)
    }

    var isRecording by remember {
        mutableStateOf(false)
    }

    var savedFileName by remember {
        mutableStateOf<String?>(null)
    }

    var isExtractingFrames by remember {
        mutableStateOf(false)
    }

    var countdown by remember {
        mutableStateOf(0)
    }

    var shouldStartRecording by remember {
        mutableStateOf(false)
    }

    /*
     * Countdown:
     *
     * 3 → 2 → 1 → start recording
     */
    LaunchedEffect(countdown, shouldStartRecording) {

        if (shouldStartRecording && countdown > 0) {

            delay(1000L)

            countdown--

        } else if (shouldStartRecording && countdown == 0) {

            shouldStartRecording = false

            startRecording(
                context = context,
                videoCapture = videoCapture,
                onRecordingStarted = {
                    isRecording = true
                    savedFileName = null
                },
                onRecordingFinished = { videoFile ->

                    isRecording = false
                    recording = null
                    savedFileName = videoFile.name

                    isExtractingFrames = true

                    coroutineScope.launch {

                        try {

                            val result = withContext(Dispatchers.IO) {

                                val framesDirectory = File(
                                    context.filesDir,
                                    "frames/${videoFile.nameWithoutExtension}"
                                )

                                FrameExtractor.extractFrames(
                                    videoFile = videoFile,
                                    outputDirectory = framesDirectory,
                                    intervalMs = 500L
                                )
                            }

                            println(
                                "Frame extraction complete: " +
                                        "${result.totalFrames} frames"
                            )

                        } catch (exception: Exception) {

                            exception.printStackTrace()

                        } finally {

                            isExtractingFrames = false
                        }
                    }
                },
                onRecordingCreated = { newRecording ->
                    recording = newRecording
                }
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        /*
         * CameraX Preview
         */
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->

                val previewView = PreviewView(ctx)

                previewView.layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                val cameraProviderFuture =
                    ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({

                    val cameraProvider =
                        cameraProviderFuture.get()

                    val preview =
                        Preview.Builder()
                            .build()
                            .also {
                                it.surfaceProvider =
                                    previewView.surfaceProvider
                            }

                    val imageAnalysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                            )
                            .build()

                    imageAnalysis.setAnalyzer(
                        analysisExecutor
                    ) { imageProxy ->

                        poseLandmarkerHelper.detectLiveStream(
                            imageProxy = imageProxy,
                            isFrontCamera = false
                        )
                    }

                    val recorder =
                        Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.from(Quality.HD)
                            )
                            .build()

                    val newVideoCapture =
                        VideoCapture.withOutput(recorder)

                    val cameraSelector =
                        CameraSelector.DEFAULT_BACK_CAMERA

                    try {

                        cameraProvider.unbindAll()

                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            newVideoCapture,
                            imageAnalysis
                        )

                        videoCapture = newVideoCapture

                    } catch (exception: Exception) {

                        exception.printStackTrace()
                    }

                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        /*
         * Top title + instructions
         */
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    top = 32.dp,
                    start = 20.dp,
                    end = 20.dp
                )
                .background(
                    color = Color.Black.copy(alpha = 0.55f)
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = testName,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = getInstructions(testName),
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        /*
         * Countdown overlay
         */
        if (countdown > 0) {

            Text(
                text = countdown.toString(),
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.55f)
                    )
                    .padding(
                        horizontal = 40.dp,
                        vertical = 20.dp
                    )
            )
        }

        /*
         * Recording indicator
         */
        if (isRecording) {

            Text(
                text = "● RECORDING",
                color = Color.Red,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 180.dp)
            )
        }

        /*
         * Bottom controls
         */
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            /*
             * Saved file information
             */
            if (savedFileName != null && !isRecording) {

                Text(
                    text = "Saved: $savedFileName",
                    color = Color.White
                )

                if (isExtractingFrames) {

                    Text(
                        text = "Processing frames...",
                        color = Color.White
                    )

                } else {

                    Text(
                        text = "Frames ready",
                        color = Color.White
                    )
                }
            }

            /*
             * RECORD button
             */
            if (!isRecording && countdown == 0) {

                Button(
                    onClick = {

                        if (videoCapture != null) {

                            savedFileName = null

                            countdown = 3

                            shouldStartRecording = true
                        }
                    },
                    enabled = videoCapture != null
                ) {

                    Text("RECORD")
                }
            }

            /*
             * STOP button
             */
            if (isRecording) {

                Button(
                    onClick = {

                        recording?.stop()
                    }
                ) {

                    Text("STOP")
                }
            }

            /*
             * Back button
             */
            Button(
                onClick = {

                    recording?.stop()

                    recording = null

                    isRecording = false

                    onBack()
                }
            ) {

                Text("Back")
            }
        }
    }
}

/*
 * Starts a CameraX video recording.
 */
private fun startRecording(
    context: android.content.Context,
    videoCapture: VideoCapture<Recorder>?,
    onRecordingStarted: () -> Unit,
    onRecordingFinished: (File) -> Unit,
    onRecordingCreated: (Recording) -> Unit
) {

    if (videoCapture == null) {
        return
    }

    val videosDirectory =
        File(
            context.filesDir,
            "videos"
        )

    if (!videosDirectory.exists()) {
        videosDirectory.mkdirs()
    }

    val fileName =
        "test_${System.currentTimeMillis()}.mp4"

    val videoFile =
        File(
            videosDirectory,
            fileName
        )

    val outputOptions =
        FileOutputOptions.Builder(videoFile)
            .build()

    val pendingRecording =
        videoCapture.output
            .prepareRecording(
                context,
                outputOptions
            )

    val newRecording =
        pendingRecording.start(
            ContextCompat.getMainExecutor(context)
        ) { event ->

            when (event) {

                is VideoRecordEvent.Start -> {

                    onRecordingStarted()
                }

                is VideoRecordEvent.Finalize -> {

                    if (!event.hasError()) {

                        onRecordingFinished(
                            videoFile
                        )

                    } else {

                        event.cause?.printStackTrace()
                    }
                }
            }
        }

    onRecordingCreated(newRecording)
}

private fun getInstructions(
    testName: String
): String {

    return when (testName) {

        "Vertical Jump" ->
            "Stand straight and keep your full body visible."

        "Sit-ups" ->
            "Lie down fully and keep your complete body visible."

        else ->
            "Position yourself so your full body is visible."
    }
}

@Composable
private fun PermissionScreen(
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.headlineSmall
            )

            Button(
                onClick = onRequestPermission
            ) {

                Text("Allow Camera")
            }

            Button(
                onClick = onBack
            ) {

                Text("Back")
            }
        }
    }
}