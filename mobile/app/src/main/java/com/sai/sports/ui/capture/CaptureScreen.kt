package com.sai.sports.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sai.sports.PoseLandmarkerHelper
import com.sai.sports.analyzer.MediaPipeMapper
import com.sai.sports.analyzer.TestType
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.SyncRepository
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.utils.FrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CaptureScreen(
    testName: String,
    onBack: () -> Unit,
    onAttemptComplete: (String) -> Unit
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
            onBack = onBack,
            onAttemptComplete = onAttemptComplete
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
    onBack: () -> Unit,
    onAttemptComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val testType = remember(testName) {
        TestType.fromDisplayName(testName) ?: TestType.SIT_UPS
    }

    val profileStore = remember { AthleteProfileStore(context) }
    val attemptStore = remember { AttemptStore(context) }
    val syncRepository = remember { SyncRepository(context) }
    val session = remember { CaptureSession() }

    var athleteHeightCm by remember {
        mutableStateOf(profileStore.heightCm())
    }

    /*
     * Vertical jump cannot be measured without a real-world reference. Ask once,
     * before the athlete has done anything, rather than after they have jumped.
     */
    var showHeightDialog by remember {
        mutableStateOf(
            testType == TestType.VERTICAL_JUMP && athleteHeightCm == null
        )
    }

    var poseOverlayView by remember {
        mutableStateOf<PoseOverlayView?>(null)
    }

    var fpsFrameCount by remember { mutableIntStateOf(0) }
    var fpsLastTime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var currentFps by remember { mutableIntStateOf(0) }

    val poseLandmarkerHelper = remember {
        PoseLandmarkerHelper(
            context = context,
            listener = object : PoseLandmarkerHelper.LandmarkerListener {

                override fun onResults(
                    result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
                    imageWidth: Int,
                    imageHeight: Int
                ) {
                    fpsFrameCount++

                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - fpsLastTime

                    if (elapsed >= 1000L) {
                        currentFps = (fpsFrameCount * 1000L / elapsed).toInt()
                        fpsFrameCount = 0
                        fpsLastTime = now

                        Log.d("PoseFPS", "Pose inference FPS: $currentFps")
                    }

                    if (result.landmarks().isEmpty()) {
                        poseOverlayView?.clearPose()
                        return
                    }

                    val frame = MediaPipeMapper.toPoseFrame(
                        landmarks = result.landmarks()[0],
                        timestampMs = result.timestampMs()
                    )

                    poseOverlayView?.updatePose(
                        points = frame.points,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )

                    session.onPoseFrame(
                        frame = frame,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                }

                override fun onError(error: String) {
                    Log.e("PoseLandmarker", "MediaPipe error: $error")
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

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isFinishing by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var shouldStartRecording by remember { mutableStateOf(false) }

    /*
     * Countdown: 3 -> 2 -> 1 -> start recording.
     *
     * Scoring is already running by this point. The vertical jump analyzer needs
     * the athlete standing still to set its reference, and standing still for a
     * countdown is exactly what they are doing.
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
                },
                onRecordingFinished = { videoFile ->

                    isRecording = false
                    recording = null
                    isFinishing = true

                    val outcome = session.finish()

                    coroutineScope.launch {

                        val attemptId = videoFile.nameWithoutExtension

                        withContext(Dispatchers.IO) {

                            if (outcome != null) {

                                val attempt = Attempt(
                                    id = attemptId,
                                    testType = testType,
                                    videoFileName = videoFile.name,
                                    recordedAtMs = System.currentTimeMillis(),
                                    result = outcome.result,
                                    imageWidth = session.imageWidth,
                                    imageHeight = session.imageHeight
                                )

                                attemptStore.save(
                                    attempt = attempt,
                                    frames = outcome.frames
                                )

                                // Enqueue for upload. Only scored attempts are
                                // accepted — the repository drops the rest
                                // rather than spending the athlete's data on a
                                // video an official would reject anyway.
                                if (syncRepository.enqueue(attempt)) {
                                    SyncScheduler.syncNow(context)
                                }
                            }

                            // Sprint 1's frame extraction. Scoring no longer needs
                            // these JPEGs, but the pipeline stays until Sprint 4
                            // decides what the upload payload actually contains.
                            try {
                                FrameExtractor.extractFrames(
                                    videoFile = videoFile,
                                    outputDirectory = File(
                                        context.filesDir,
                                        "frames/${videoFile.nameWithoutExtension}"
                                    ),
                                    intervalMs = 500L
                                )
                            } catch (exception: Exception) {
                                Log.e("CaptureScreen", "Frame extraction failed", exception)
                            }
                        }

                        isFinishing = false
                        onAttemptComplete(attemptId)
                    }
                },
                onRecordingCreated = { newRecording ->
                    recording = newRecording
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

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

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({

                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        poseLandmarkerHelper.detectLiveStream(
                            imageProxy = imageProxy,
                            isFrontCamera = false
                        )
                    }

                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()

                    val newVideoCapture = VideoCapture.withOutput(recorder)

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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
                        Log.e("CaptureScreen", "Camera binding failed", exception)
                    }

                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Pose skeleton overlay
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PoseOverlayView(ctx).also {
                    poseOverlayView = it
                }
            },
            update = { view ->
                poseOverlayView = view
            }
        )

        /*
         * Top title + instructions
         */
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp, start = 20.dp, end = 20.dp)
                .background(color = Color.Black.copy(alpha = 0.55f))
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
         * Live score readout.
         *
         * Shown while scoring is active so the athlete gets the instant feedback
         * the brief asks for, instead of waiting for the recording to end.
         */
        if (session.isActive) {

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = when (testType) {
                        TestType.SIT_UPS -> session.liveScore.toInt().toString()
                        TestType.VERTICAL_JUMP -> "%.1f".format(session.liveScore)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall
                )

                Text(
                    text = testType.unit,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )

                session.liveDetail?.let { detail ->
                    Text(
                        text = detail,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
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
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 40.dp, vertical = 20.dp)
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

            if (isFinishing) {
                Text(
                    text = "Scoring your attempt…",
                    color = Color.White
                )
            }

            if (!isRecording && countdown == 0 && !isFinishing) {

                Button(
                    onClick = {

                        if (videoCapture != null) {
                            session.start(
                                testType = testType,
                                athleteHeightCm = athleteHeightCm
                            )
                            countdown = 3
                            shouldStartRecording = true
                        }
                    },
                    enabled = videoCapture != null &&
                        (testType != TestType.VERTICAL_JUMP || athleteHeightCm != null)
                ) {
                    Text("RECORD")
                }
            }

            if (isRecording) {

                Button(onClick = { recording?.stop() }) {
                    Text("STOP")
                }
            }

            Button(
                onClick = {
                    recording?.stop()
                    recording = null
                    isRecording = false
                    session.cancel()
                    onBack()
                }
            ) {
                Text("Back")
            }
        }
    }

    if (showHeightDialog) {
        HeightEntryDialog(
            onConfirm = { heightCm ->
                profileStore.setHeightCm(heightCm)
                athleteHeightCm = heightCm
                showHeightDialog = false
            },
            onDismiss = {
                showHeightDialog = false
                onBack()
            }
        )
    }
}

/**
 * Collects standing height, which vertical jump calibration converts normalized
 * pose displacement into centimetres with.
 *
 * Sprint 7 moves this to registration; until then it is asked for once and
 * remembered on the device.
 */
@Composable
private fun HeightEntryDialog(
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {

    var input by remember { mutableStateOf("") }

    val parsed = input.toDoubleOrNull()
    val isValid = parsed != null && AthleteProfileStore.isPlausible(parsed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your height") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Text(
                    "Jump height is measured against your standing height, " +
                        "so we need it before your first jump."
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Height in cm") },
                    singleLine = true,
                    isError = input.isNotEmpty() && !isValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )

                if (input.isNotEmpty() && !isValid) {
                    Text(
                        text = "Enter a height between " +
                            "${AthleteProfileStore.MIN_HEIGHT_CM.toInt()} and " +
                            "${AthleteProfileStore.MAX_HEIGHT_CM.toInt()} cm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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

    val videosDirectory = File(context.filesDir, "videos")

    if (!videosDirectory.exists()) {
        videosDirectory.mkdirs()
    }

    val fileName = "test_${System.currentTimeMillis()}.mp4"

    val videoFile = File(videosDirectory, fileName)

    val outputOptions = FileOutputOptions.Builder(videoFile).build()

    val pendingRecording = videoCapture.output
        .prepareRecording(context, outputOptions)

    val newRecording = pendingRecording.start(
        ContextCompat.getMainExecutor(context)
    ) { event ->

        when (event) {

            is VideoRecordEvent.Start -> {
                onRecordingStarted()
            }

            is VideoRecordEvent.Finalize -> {

                if (!event.hasError()) {
                    onRecordingFinished(videoFile)
                } else {
                    Log.e("CaptureScreen", "Recording failed", event.cause)
                }
            }
        }
    }

    onRecordingCreated(newRecording)
}

private fun getInstructions(testName: String): String {

    return when (testName) {

        "Vertical Jump" ->
            "Stand side-on, full body in frame. Stay still for the countdown, then jump."

        "Sit-ups" ->
            "Lie down side-on to the camera with your whole body visible. " +
                "Sit all the way up each rep."

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

            Button(onClick = onRequestPermission) {
                Text("Allow Camera")
            }

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
