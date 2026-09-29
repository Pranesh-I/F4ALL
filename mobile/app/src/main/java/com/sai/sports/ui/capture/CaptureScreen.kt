package com.sai.sports.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.video.FileOutputOptions
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sai.sports.PoseLandmarkerHelper
import com.sai.sports.R
import com.sai.sports.analyzer.MediaPipeMapper
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.coach.CoachCue
import com.sai.sports.coach.CueTone
import com.sai.sports.coach.VoiceCoach
import com.sai.sports.coach.VoicePreference
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.IdentityPassStore
import com.sai.sports.data.SessionAvailability
import com.sai.sports.data.SessionGate
import com.sai.sports.data.SessionRepository
import com.sai.sports.data.SyncRepository
import com.sai.sports.gesture.Gesture
import com.sai.sports.gesture.GesturePlan
import com.sai.sports.gesture.GestureVerifier
import com.sai.sports.sync.PracticeSyncWorker
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.ui.common.Labels
import com.sai.sports.utils.FrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CaptureScreen(
    testType: TestType,
    mode: AttemptMode,
    /** The assessment session an official attempt is for. */
    sessionId: String? = null,
    onBack: () -> Unit,
    onAttemptComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember { mutableStateOf(isCameraGranted(context)) }

    // Survives recreation so a denial is not forgotten, which is what decides
    // between asking again and sending the athlete to settings.
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasCameraPermission = isGranted
            askedOnce = true
        }

    // The permission can be granted, or revoked, in system settings while the
    // app is in the background. Check again every time the screen returns.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = isCameraGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraPreview(
            testType = testType,
            mode = mode,
            sessionId = sessionId,
            onBack = onBack,
            onAttemptComplete = onAttemptComplete
        )
    } else {
        // Once the athlete has denied and Android will no longer show the
        // dialog, asking again does nothing visible. Only settings can help.
        val activity = context.findActivity()
        val permanentlyDenied = askedOnce && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { openAppSettings(context) },
            onBack = onBack
        )
    }
}

@Composable
private fun CameraPreview(
    testType: TestType,
    mode: AttemptMode,
    sessionId: String?,
    onBack: () -> Unit,
    onAttemptComplete: (String) -> Unit
) {
    val context = LocalContext.current
    // Spoken prompts follow the athlete's language even if it changes mid-screen.
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val profileStore = remember { AthleteProfileStore(context) }
    val attemptStore = remember { AttemptStore(context) }
    val syncRepository = remember { SyncRepository(context) }
    val session = remember { CaptureSession() }

    val flow = remember { CaptureFlow { GesturePlan.pick(testType) } }

    /*
     * An official attempt belongs to an assessment session, and each test may
     * be submitted to it once. Checked as the screen opens, before the athlete
     * does anything, so a second attempt is refused here rather than recorded
     * and then rejected by SAI.
     */
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val assessments = remember { SessionRepository(context, AppServices.api(context)) }
    val assessment = remember(sessionId) {
        if (sessionId == null || athleteId == null) null else assessments.session(athleteId, sessionId)
    }
    var assessmentGate by remember { mutableStateOf<SessionGate?>(null) }

    // The photo check taken just before this screen, read once as it opens so a
    // long wait on this screen does not outlive it mid-recording.
    val identityPass = remember(sessionId) {
        if (mode != AttemptMode.OFFICIAL || sessionId == null || athleteId == null) null
        else IdentityPassStore(context).current(athleteId, sessionId)
    }

    LaunchedEffect(sessionId) {
        if (mode != AttemptMode.OFFICIAL || sessionId == null) {
            assessmentGate = SessionGate.OPEN
            return@LaunchedEffect
        }
        assessmentGate = withContext(Dispatchers.IO) {
            if (athleteId == null) {
                SessionGate.UNKNOWN
            } else {
                SessionAvailability.gate(
                    sessionId = sessionId,
                    testType = testType.name,
                    cached = assessments.cached(athleteId),
                    local = syncRepository.sessionAttempts(athleteId, sessionId),
                    wallNowMs = assessments.wallNow(),
                    elapsedNowMs = assessments.elapsedNow()
                )
            }
        }
    }

    /**
     * When the recording started — on SAI's clock when the session list is
     * cached, since SAI checks it fell inside the session and the phone's own
     * calendar may be wrong.
     */
    var recordingStartedAtMs by remember { mutableLongStateOf(0L) }
    val phase by flow.phase.collectAsState()

    /*
     * Spoken coaching, in the language this screen is showing. The athlete's
     * on/off choice is remembered; the phone may simply have no voice for
     * their language, in which case the cues stay on screen only.
     */
    var voiceEnabled by remember { mutableStateOf(VoicePreference.isEnabled(context)) }
    var voiceAvailable by remember { mutableStateOf(true) }
    val voiceCoach = remember {
        VoiceCoach(context) { available -> voiceAvailable = available }
    }

    DisposableEffect(voiceCoach) {
        session.onSpeak = { cues ->
            cues.forEachIndexed { index, cue ->
                voiceCoach.speak(
                    text = spokenText(context, cue, testType),
                    // Only the first cue of a batch may interrupt; otherwise a
                    // fault said after a count would cut the count off.
                    urgent = index == 0 && cue.tone == CueTone.FAULT
                )
            }
        }
        onDispose {
            session.onSpeak = null
            voiceCoach.shutdown()
        }
    }

    LaunchedEffect(voiceEnabled) {
        voiceCoach.enabled = voiceEnabled
        if (!voiceEnabled) voiceCoach.stop()
    }

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
                        Log.d("PoseFPS", "Pose inference FPS: ${fpsFrameCount * 1000L / elapsed}")
                        fpsFrameCount = 0
                        fpsLastTime = now
                    }

                    if (result.landmarks().isEmpty()) {
                        poseOverlayView?.clearPose()
                        session.onNoPose(result.timestampMs())
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

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val camera = remember {
        CameraBinding(context, analysisExecutor) { image ->
            poseLandmarkerHelper.detectLiveStream(image)
        }
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    /** True once frames are actually reaching the screen — not merely "bound". */
    var cameraStreaming by remember { mutableStateOf(false) }

    /** Bumped to (re)bind the camera. */
    var bindGeneration by remember { mutableIntStateOf(0) }
    var rebinds by remember { mutableIntStateOf(0) }

    /** Bumped to re-arm the black-preview watchdog. */
    var watchdogToken by remember { mutableIntStateOf(0) }

    /*
     * Keep the screen on for the whole test. A phone propped against a wall
     * that dims and locks mid-set ends the attempt.
     */
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    /** The camera is gone: abandon any attempt it was carrying, then say so. */
    fun failCamera() {
        val wasActive = flow.isActive && flow.phase.value != CapturePhase.Saving
        if (flow.interrupt(Interruption.CAMERA_FAILED)) recording?.stop()
        if (wasActive) session.cancel()
    }

    /*
     * Camera binding. Rebinding builds fresh use cases on the current lifecycle,
     * which is also how a stuck preview is recovered.
     */
    LaunchedEffect(previewView, bindGeneration) {
        val target = previewView ?: return@LaunchedEffect
        cameraStreaming = false
        videoCapture = null
        camera.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = target,
            onBound = { videoCapture = it },
            onError = { failCamera() }
        )
        watchdogToken++
    }

    // Follow the preview's own report of whether frames are flowing.
    DisposableEffect(previewView, lifecycleOwner) {
        val target = previewView
        val observer = Observer<PreviewView.StreamState> { state ->
            cameraStreaming = state == PreviewView.StreamState.STREAMING
        }
        target?.previewStreamState?.observe(lifecycleOwner, observer)
        onDispose { target?.previewStreamState?.removeObserver(observer) }
    }

    /*
     * Black-preview watchdog. A preview that has not started streaming a few
     * seconds after binding (or after returning from the background) is the
     * black-camera state. Rebind once — that recovers most cases — and if it
     * is still black, say so rather than leave the athlete staring at nothing.
     */
    LaunchedEffect(watchdogToken) {
        if (watchdogToken == 0) return@LaunchedEffect
        delay(PREVIEW_WATCHDOG_MS)
        val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!cameraStreaming && resumed) {
            if (rebinds < MAX_REBINDS) {
                rebinds++
                bindGeneration++
            } else {
                failCamera()
            }
        }
    }

    /*
     * Background / foreground. Leaving the app stops the camera under a running
     * attempt, so the attempt is abandoned cleanly — its recording discarded,
     * its scoring reset — rather than left half-alive. Coming back re-arms the
     * watchdog, because returning is when a preview most often stays black.
     */
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    val wasActive = flow.isActive && flow.phase.value != CapturePhase.Saving
                    if (flow.interrupt(Interruption.APP_BACKGROUNDED)) recording?.stop()
                    if (wasActive) session.cancel()
                    voiceCoach.stop()
                }
                Lifecycle.Event.ON_RESUME -> watchdogToken++
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /*
     * Leaving the screen releases everything it holds, in order: the attempt,
     * the camera, then the pose model — closed on the analysis thread, after
     * any frame already queued there, so it is never closed under a running
     * detection. The next visit builds all of it fresh.
     */
    DisposableEffect(Unit) {
        onDispose {
            if (flow.cancel()) recording?.stop()
            session.cancel()
            camera.release()
            analysisExecutor.execute { poseLandmarkerHelper.close() }
            analysisExecutor.shutdown()
        }
    }

    // The gesture verdict arrives on the pose thread; the attempt lives on main.
    DisposableEffect(session) {
        session.onGestureResult = { verifier ->
            mainExecutor.execute {
                if (verifier.status == GestureVerifier.Status.VERIFIED) {
                    flow.gestureVerified()
                } else {
                    flow.gestureFailed(verifier.failure ?: GestureVerifier.Failure.NOT_PERFORMED)
                }
            }
        }
        onDispose { session.onGestureResult = null }
    }

    fun abandonAttempt() {
        if (flow.cancel()) recording?.stop()
        session.cancel()
        voiceCoach.stop()
    }

    fun onRecordingFinalized(attemptNumber: Int, videoFile: File, success: Boolean) {

        recording = null

        when (flow.recordingFinished(attemptNumber, success)) {

            RecordingDecision.DISCARD -> {
                // Cancelled, backgrounded or failed: the video is not this
                // attempt's evidence any more, and must not linger on disk.
                videoFile.delete()
                // A late report from an older attempt must not reset the
                // scoring of a newer one the athlete has already started.
                if (attemptNumber == flow.attempt) session.cancel()
            }

            RecordingDecision.SAVE -> {

                val outcome = session.finish()

                coroutineScope.launch {

                    val attemptId = videoFile.nameWithoutExtension

                    withContext(Dispatchers.IO) {

                        if (outcome != null) {

                            val attempt = Attempt(
                                id = attemptId,
                                testType = testType,
                                videoFileName = videoFile.name,
                                recordedAtMs = recordingStartedAtMs.takeIf { it > 0 }
                                    ?: System.currentTimeMillis(),
                                result = outcome.result,
                                imageWidth = session.imageWidth,
                                imageHeight = session.imageHeight,
                                mode = mode,
                                sessionId = if (mode == AttemptMode.OFFICIAL) sessionId else null,
                                athleteId = AppServices.session(context).current()?.athleteId
                            )

                            attemptStore.save(
                                attempt = attempt,
                                frames = outcome.frames
                            )

                            if (mode == AttemptMode.PRACTICE) {
                                // Practice keeps its result and skeleton for
                                // history; the video is dropped. The result
                                // goes to the athlete's own account.
                                attemptStore.deleteVideo(attempt)
                                PracticeSyncWorker.syncSoon(context)
                            } else if (syncRepository.enqueue(attempt, identityPass)) {
                                // Only scored attempts are accepted — the
                                // repository drops the rest rather than spending
                                // the athlete's data on a video an official
                                // would reject anyway.
                                SyncScheduler.syncNow(context)
                            }
                        }

                        // Frame stills are for the official review; a practice
                        // video is already gone.
                        if (mode == AttemptMode.OFFICIAL) {
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
                    }

                    flow.saved()
                    onAttemptComplete(attemptId)
                }
            }
        }
    }

    /*
     * The attempt, phase by phase. Each phase is its own effect key, so leaving
     * a phase early (cancel, background) cancels whatever it was waiting on.
     */
    LaunchedEffect(phase) {
        when (val current = phase) {

            is CapturePhase.Verifying -> {
                session.beginVerification(current.gesture)
                voiceCoach.speak(resources.getString(gestureInstruction(current.gesture)), urgent = true)

                // Backstop for a pose pipeline that has stopped delivering
                // frames altogether: the verifier cannot time out on frames
                // that never arrive.
                delay(GestureVerifier.TIMEOUT_MS + GESTURE_BACKSTOP_MS)
                if (flow.phase.value == current) {
                    session.endVerification()
                    flow.gestureFailed(GestureVerifier.Failure.NO_PERSON)
                }
            }

            is CapturePhase.Countdown -> {
                if (current.secondsLeft == CaptureFlow.COUNTDOWN_SECONDS) {
                    voiceCoach.speak(resources.getString(R.string.gesture_verified), urgent = true)
                    // Scoring starts with the countdown: the jump calibrates
                    // on the athlete standing still for it.
                    session.start(testType = testType, athleteHeightCm = athleteHeightCm)
                }
                voiceCoach.speak(current.secondsLeft.toString(), urgent = false)

                delay(1_000L)

                if (flow.tick()) {
                    recordingStartedAtMs = athleteId
                        ?.let(assessments::cached)
                        ?.takeIf { assessment != null }
                        ?.let(assessments::serverNow)
                        ?: System.currentTimeMillis()
                    val capture = videoCapture
                    val attemptNumber = flow.attempt
                    if (capture == null) {
                        flow.interrupt(Interruption.CAMERA_FAILED)
                        session.cancel()
                    } else {
                        recording = startRecording(
                            context = context,
                            videoCapture = capture,
                            onFinalized = { file, success ->
                                onRecordingFinalized(attemptNumber, file, success)
                            }
                        )
                        if (recording == null) {
                            flow.interrupt(Interruption.RECORDING_FAILED)
                            session.cancel()
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    /*
     * System back mid-attempt cancels the attempt and stays on the screen;
     * while an attempt is being saved, back waits — the recording is complete
     * and walking away would lose it.
     */
    val attemptInProgress = phase is CapturePhase.Verifying ||
        phase is CapturePhase.Countdown ||
        phase == CapturePhase.Recording

    BackHandler(enabled = attemptInProgress || phase == CapturePhase.Saving) {
        if (attemptInProgress) abandonAttempt()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        /*
         * CameraX preview. COMPATIBLE (TextureView) rather than the default
         * SurfaceView: a SurfaceView inside Compose can come back black after
         * navigation, which is exactly the "second attempt" path.
         */
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
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
        if (phase == CapturePhase.Ready || phase == CapturePhase.Recording) {

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp, start = 20.dp, end = 20.dp)
                    .background(color = Color.Black.copy(alpha = 0.55f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                if (mode == AttemptMode.PRACTICE) {
                    PracticeBadge()
                }
                assessment?.let { OfficialBadge(it.name) }

                Text(
                    text = stringResource(Labels.testName(testType)),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )

                Text(
                    text = stringResource(
                        when (testType) {
                            TestType.SQUATS -> R.string.capture_hint_squats
                            TestType.PUSH_UPS -> R.string.capture_hint_pushups
                            TestType.BICEP_CURLS -> R.string.capture_hint_curls
                            TestType.LUNGES -> R.string.capture_hint_lunges
                            TestType.VERTICAL_JUMP -> R.string.capture_hint_jump
                            TestType.SIT_UPS -> R.string.capture_hint_situps
                        }
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    // Announced as it changes, so an athlete who cannot watch
                    // the screen mid-rep still hears the count.
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = if (testType.countsReps) session.liveScore.toInt().toString()
                        else "%.1f".format(session.liveScore),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall
                )

                Text(
                    text = stringResource(Labels.unit(testType.unit)),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )

                session.liveDetail?.let { hint ->
                    Text(
                        text = liveHintText(hint),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        /*
         * Live form coaching.
         *
         * Big, colour-coded and near the athlete's eye line, because they are
         * mid-rep and glancing, not reading. Announced to screen readers only
         * when the voice is not already saying it.
         */
        session.liveCue?.let { cue ->

            val (background, foreground) = cueColors(cue.tone)
            val speaking = voiceEnabled && voiceAvailable

            Text(
                text = cueText(cue, testType),
                color = foreground,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 210.dp, start = 20.dp, end = 20.dp)
                    .background(background, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics {
                        if (!speaking) liveRegion = LiveRegionMode.Polite
                    }
            )
        }

        /*
         * Gesture verification: the athlete proves they are there and ready
         * before anything is recorded.
         */
        (phase as? CapturePhase.Verifying)?.let { verifying ->
            GesturePanel(
                gesture = verifying.gesture,
                progress = session.gestureProgress,
                hint = session.gestureHint,
                secondsLeft = ((session.gestureRemainingMs + 999) / 1000).toInt(),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        (phase as? CapturePhase.VerificationFailed)?.let { failed ->
            MessagePanel(
                message = stringResource(
                    when (failed.failure) {
                        GestureVerifier.Failure.NO_PERSON -> R.string.gesture_failed_no_person
                        GestureVerifier.Failure.NOT_PERFORMED -> R.string.gesture_failed_not_performed
                    }
                ),
                actionLabel = stringResource(R.string.action_try_again),
                onAction = { flow.start() },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        (phase as? CapturePhase.Interrupted)?.let { interrupted ->
            val cameraFailed = interrupted.reason == Interruption.CAMERA_FAILED
            MessagePanel(
                message = stringResource(
                    when (interrupted.reason) {
                        Interruption.APP_BACKGROUNDED -> R.string.interrupted_backgrounded
                        Interruption.RECORDING_FAILED -> R.string.interrupted_recording_failed
                        Interruption.CAMERA_FAILED -> R.string.interrupted_camera_failed
                    }
                ),
                actionLabel = stringResource(
                    if (cameraFailed) R.string.action_retry_camera else R.string.action_start_again
                ),
                onAction = {
                    if (cameraFailed) {
                        rebinds = 0
                        flow.cancel()
                        bindGeneration++
                    } else {
                        flow.start()
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        assessmentGate?.takeIf { it != SessionGate.OPEN }?.let { gate ->
            MessagePanel(
                message = stringResource(
                    when (gate) {
                        SessionGate.SUBMITTED -> R.string.session_blocked_submitted
                        SessionGate.WAITING_TO_SEND -> R.string.session_blocked_waiting
                        SessionGate.CLOSED -> R.string.session_blocked_closed
                        SessionGate.UNKNOWN, SessionGate.OPEN -> R.string.session_blocked_unknown
                    }
                ),
                actionLabel = stringResource(R.string.action_back),
                onAction = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (!poseLandmarkerHelper.isReady) {
            MessagePanel(
                message = stringResource(R.string.pose_model_failed),
                actionLabel = stringResource(R.string.action_back),
                onAction = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        /*
         * Countdown overlay
         */
        (phase as? CapturePhase.Countdown)?.let { countdown ->

            Text(
                text = countdown.secondsLeft.toString(),
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 40.dp, vertical = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
            )
        }

        /*
         * Recording indicator
         */
        if (phase == CapturePhase.Recording) {

            Text(
                text = stringResource(R.string.capture_recording),
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

            if (phase == CapturePhase.Saving) {
                Text(
                    text = stringResource(R.string.capture_scoring),
                    color = Color.White
                )
            }

            if (testType.countsReps && (phase == CapturePhase.Ready || phase == CapturePhase.Recording)) {

                val toggleDescription = stringResource(R.string.voice_toggle_description)

                TextButton(
                    onClick = {
                        voiceEnabled = !voiceEnabled
                        VoicePreference.setEnabled(context, voiceEnabled)
                    },
                    enabled = voiceAvailable,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = toggleDescription }
                ) {
                    Text(
                        text = stringResource(
                            if (voiceEnabled && voiceAvailable) R.string.voice_on else R.string.voice_off
                        ),
                        color = Color.White
                    )
                }

                if (!voiceAvailable) {
                    Text(
                        text = stringResource(R.string.voice_unavailable),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(6.dp)
                    )
                }
            }

            if (phase == CapturePhase.Ready) {

                // Starting needs a live picture, not just a bound camera: an
                // attempt started on a black preview would record nothing.
                Button(
                    onClick = { flow.start() },
                    enabled = videoCapture != null && cameraStreaming && poseLandmarkerHelper.isReady &&
                        assessmentGate == SessionGate.OPEN &&
                        (testType != TestType.VERTICAL_JUMP || athleteHeightCm != null)
                ) {
                    Text(stringResource(R.string.capture_record))
                }
            }

            if (phase == CapturePhase.Recording) {

                Button(onClick = { if (flow.stopRequested()) recording?.stop() }) {
                    Text(stringResource(R.string.capture_stop))
                }
            }

            if (phase != CapturePhase.Saving) {
                Button(
                    onClick = {
                        if (attemptInProgress) abandonAttempt() else onBack()
                    }
                ) {
                    Text(
                        stringResource(if (attemptInProgress) R.string.capture_cancel else R.string.action_back)
                    )
                }
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
        title = { Text(stringResource(R.string.height_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Text(stringResource(R.string.height_dialog_body))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.height_dialog_label)) },
                    singleLine = true,
                    isError = input.isNotEmpty() && !isValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )

                if (input.isNotEmpty() && !isValid) {
                    Text(
                        text = stringResource(
                            R.string.height_dialog_invalid,
                            AthleteProfileStore.MIN_HEIGHT_CM.toInt(),
                            AthleteProfileStore.MAX_HEIGHT_CM.toInt()
                        ),
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
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Starts a CameraX video recording.
 *
 * [onFinalized] is called exactly once, on the main thread, with whether the
 * file is a complete, error-free recording. It is the caller's job to decide
 * whether that recording is still wanted. Returns null if recording could not
 * start at all.
 */
private fun startRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    onFinalized: (File, Boolean) -> Unit
): Recording? {

    val videosDirectory = File(context.filesDir, "videos")

    if (!videosDirectory.exists()) {
        videosDirectory.mkdirs()
    }

    val videoFile = File(videosDirectory, "test_${System.currentTimeMillis()}.mp4")

    val outputOptions = FileOutputOptions.Builder(videoFile).build()

    return try {
        videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) {
                        Log.e("CaptureScreen", "Recording failed: ${event.error}", event.cause)
                    }
                    onFinalized(videoFile, !event.hasError())
                }
            }
    } catch (exception: Exception) {
        Log.e("CaptureScreen", "Recording could not start", exception)
        null
    }
}

@Composable
private fun liveHintText(hint: LiveHint): String = when (hint) {
    is LiveHint.TorsoAngle -> stringResource(R.string.live_torso_angle, hint.degrees.toInt())
    is LiveHint.JointAngle -> stringResource(R.string.live_joint_angle, hint.degrees.toInt())
    LiveHint.StandStill -> stringResource(R.string.live_stand_still)
    is LiveHint.Ready -> hint.displacementCm
        ?.let { stringResource(R.string.live_ready_cm, it.toInt()) }
        ?: stringResource(R.string.live_ready)
    LiveHint.Airborne -> stringResource(R.string.live_airborne)
    LiveHint.Failed -> stringResource(R.string.live_failed)
}

@Composable
private fun cueText(cue: CoachCue, testType: TestType): String =
    if (cue is CoachCue.Counted) stringResource(Labels.cue(cue, testType), cue.count)
    else stringResource(Labels.cue(cue, testType))

/** A count is said as its bare number; everything else as its on-screen words. */
private fun spokenText(context: Context, cue: CoachCue, testType: TestType): String =
    if (cue is CoachCue.Counted) cue.count.toString()
    else context.getString(Labels.cue(cue, testType))

/** Background and text colour per tone — green, grey, amber, red, all readable in sunlight. */
private fun cueColors(tone: CueTone): Pair<Color, Color> = when (tone) {
    CueTone.POSITIVE -> Color(0xFF2E7D32) to Color.White
    CueTone.INFO -> Color(0xE6263238) to Color.White
    CueTone.WARNING -> Color(0xFFFFC107) to Color.Black
    CueTone.FAULT -> Color(0xFFC62828) to Color.White
}

@StringRes
private fun gestureInstruction(gesture: Gesture): Int = when (gesture) {
    Gesture.RAISE_HAND -> R.string.gesture_raise_hand
    Gesture.RAISE_LEFT_HAND -> R.string.gesture_raise_left
    Gesture.RAISE_RIGHT_HAND -> R.string.gesture_raise_right
    Gesture.BOTH_HANDS_UP -> R.string.gesture_both_hands
}

@StringRes
private fun gestureHintText(hint: GestureVerifier.Hint): Int = when (hint) {
    GestureVerifier.Hint.STEP_INTO_VIEW -> R.string.gesture_hint_step_into_view
    GestureVerifier.Hint.FACE_CAMERA -> R.string.gesture_hint_face_camera
    GestureVerifier.Hint.WRONG_HAND -> R.string.gesture_hint_wrong_hand
    GestureVerifier.Hint.BOTH_HANDS -> R.string.gesture_hint_both_hands
    GestureVerifier.Hint.HOLD_IT -> R.string.gesture_hint_hold
}

/**
 * What to do, whether it is being seen, and how long is left — everything the
 * athlete needs from a few metres away, in large type.
 */
@Composable
private fun GesturePanel(
    gesture: Gesture,
    progress: Float,
    hint: GestureVerifier.Hint?,
    secondsLeft: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            text = stringResource(R.string.gesture_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = stringResource(gestureInstruction(gesture)),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF2E7D32)
        )

        hint?.let {
            Text(
                text = stringResource(gestureHintText(it)),
                color = if (it == GestureVerifier.Hint.HOLD_IT) Color(0xFFA5D6A7) else Color(0xFFFFE082),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Text(
            text = stringResource(R.string.gesture_seconds_left, secondsLeft),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Names the session an official attempt counts towards. */
@Composable
private fun OfficialBadge(sessionName: String) {
    Text(
        text = stringResource(R.string.official_badge) + " · " + sessionName,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .background(Color(0xFF1565C0), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/** Marks every practice screen, so a practice attempt is never mistaken for an official one. */
@Composable
private fun PracticeBadge() {
    Text(
        text = stringResource(R.string.practice_badge),
        color = Color.Black,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .background(Color(0xFFFFC107), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/** A plain explanation of what went wrong, and the one thing to do about it. */
@Composable
private fun MessagePanel(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
        )
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
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
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            Text(
                text = stringResource(
                    if (permanentlyDenied) R.string.camera_permission_denied_body
                    else R.string.camera_permission_body
                ),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.camera_permission_open_settings))
                }
            } else {
                Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.camera_permission_allow))
                }
            }

            Button(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}

private fun isCameraGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The app's own page in system settings, where a denied permission can be turned back on. */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** How long a bound preview may stay black before it is rebound. */
private const val PREVIEW_WATCHDOG_MS = 4_000L

/** Rebinds attempted automatically before the athlete is asked to retry. */
private const val MAX_REBINDS = 1

/** Extra wall-clock slack before the gesture check gives up on a silent pose pipeline. */
private const val GESTURE_BACKSTOP_MS = 3_000L
