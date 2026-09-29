package com.sai.sports.ui.identity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sai.sports.R
import com.sai.sports.auth.AppServices
import com.sai.sports.data.IdentityPassStore
import com.sai.sports.data.IdentityPhotos
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * A photo of the athlete's face before an official test, compared with the
 * registration photo so SAI knows who is about to be recorded.
 *
 * The athlete is told straight away why a photo did not work and how to take
 * a better one. After a few tries they may continue anyway: the attempt then
 * goes to an official with the reason attached, rather than the athlete being
 * turned away by an approximate matcher. With no signal the photo is kept and
 * checked when the phone syncs, so a test at a ground with no coverage is not
 * lost to the check either.
 *
 * One passed check covers the next official tests in the same sitting.
 */
@Composable
fun IdentityCheckScreen(
    sessionId: String,
    onBack: () -> Unit,
    onVerified: () -> Unit,
    onCompleteProfile: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }
    val passStore = remember { IdentityPassStore(context) }
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val flow = remember { IdentityCheckFlow() }

    var step by remember { mutableStateOf<IdentityStep>(IdentityStep.Ready) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var hasCamera by remember { mutableStateOf(isCameraGranted(context)) }
    var ready by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }

    fun proceed(checkId: String?, photoPath: String?, confirmed: Boolean) {
        val id = athleteId ?: return
        passStore.save(id, sessionId, passStore.stamp(checkId, photoPath, confirmed))
        onVerified()
    }

    LaunchedEffect(Unit) {
        val id = athleteId ?: return@LaunchedEffect onBack()
        // Checked a few minutes ago for an earlier test in this sitting.
        if (passStore.current(id, sessionId) != null) return@LaunchedEffect onVerified()
        // Known to be missing its photo or consent: say so before the camera opens.
        if (ProfileStatusStore(context).missing(id)?.isNotEmpty() == true) {
            step = IdentityStep.NeedsProfile
        }
        ready = true
        if (!hasCamera) permission.launch(Manifest.permission.CAMERA)
    }

    fun check(jpeg: ByteArray) {
        photo = jpeg
        scope.launch {
            val response = withContext(Dispatchers.IO) {
                api.identityCheck(jpeg, System.currentTimeMillis(), sessionId)
            }
            step = flow.onResponse(response)
            (step as? IdentityStep.Passed)?.let { proceed(it.checkId, null, confirmed = true) }
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        step = IdentityStep.Checking
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = image.use(SelfieEncoding::jpeg)
                    if (jpeg == null) step = IdentityStep.Failed else check(jpeg)
                }

                override fun onError(exception: ImageCaptureException) {
                    step = IdentityStep.Failed
                }
            }
        )
    }

    if (!ready) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TitleBar(stringResource(R.string.identity_title), onBack)

        val current = step
        val showCamera = hasCamera && (current is IdentityStep.Ready ||
            current is IdentityStep.TryAgain || current is IdentityStep.Failed ||
            current is IdentityStep.Checking)

        if (current is IdentityStep.Ready) {
            Text(stringResource(R.string.identity_body), style = MaterialTheme.typography.bodyMedium)
        }

        if (showCamera) {
            FrontCamera(onReady = { imageCapture = it })
        } else if (!hasCamera && current !is IdentityStep.NeedsProfile) {
            ErrorText(stringResource(R.string.identity_camera_needed))
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.camera_permission_allow))
            }
        }

        StepMessage(current)

        when (current) {
            IdentityStep.Ready, IdentityStep.Failed -> Button(
                onClick = ::takePhoto,
                enabled = imageCapture != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.identity_take_photo)) }

            IdentityStep.Checking -> CircularProgressIndicator()

            is IdentityStep.TryAgain -> {
                Button(onClick = ::takePhoto, enabled = imageCapture != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.identity_retake))
                }
                if (current.mayContinue) {
                    OutlinedButton(
                        onClick = { proceed(current.lastCheckId, null, confirmed = false) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.identity_continue_anyway)) }
                }
            }

            is IdentityStep.CouldNotCheck -> Button(
                onClick = { proceed(current.checkId, null, confirmed = false) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.identity_continue)) }

            IdentityStep.CheckLater -> Button(
                onClick = {
                    val saved = photo?.let { IdentityPhotos.save(context.filesDir, it) }
                    proceed(null, saved, confirmed = false)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.identity_continue)) }

            IdentityStep.NeedsProfile -> Button(onClick = onCompleteProfile, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_complete_action))
            }

            is IdentityStep.Passed -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun StepMessage(step: IdentityStep) {
    val message = when (step) {
        is IdentityStep.TryAgain -> {
            val why = stringResource(
                if (step.reason == IdentityStep.Reason.NO_FACE) R.string.identity_no_face
                else R.string.identity_no_match
            )
            val next = if (step.mayContinue) {
                stringResource(R.string.identity_may_continue)
            } else {
                stringResource(R.string.identity_tries_left, IdentityCheckFlow.MAX_TRIES - step.failures)
            }
            "$why\n$next"
        }
        is IdentityStep.CouldNotCheck -> stringResource(R.string.identity_could_not_check)
        IdentityStep.CheckLater -> stringResource(R.string.identity_check_later)
        IdentityStep.NeedsProfile -> stringResource(R.string.identity_needs_profile)
        IdentityStep.Failed -> stringResource(R.string.identity_failed)
        is IdentityStep.Passed -> stringResource(R.string.identity_passed)
        else -> null
    } ?: return

    Text(
        message,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
    if (step is IdentityStep.TryAgain) {
        Text(stringResource(R.string.identity_tips), style = MaterialTheme.typography.bodySmall)
    }
}

/** The front camera, with a capture use case for one still. */
@Composable
private fun FrontCamera(onReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        future.addListener({
            runCatching {
                provider = future.get().also {
                    it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture)
                }
                onReady(capture)
            }
        }, ContextCompat.getMainExecutor(context))

        // Only what this screen bound, so the test camera that follows starts clean.
        onDispose { provider?.unbind(preview, capture) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(16.dp))
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

/** Turns a captured frame into a small upright JPEG for the check. */
private object SelfieEncoding {
    /** Enough for a face detector; small enough for a prepaid data pack. */
    private const val MAX_EDGE_PX = 640
    private const val JPEG_QUALITY = 85

    fun jpeg(image: ImageProxy): ByteArray? = runCatching {
        val bitmap = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val scale = MAX_EDGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()
}

private fun isCameraGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
