package com.sai.sports.ui.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.ui.res.stringResource
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.ScreenTitle
import com.sai.sports.auth.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The registration photo SAI compares test videos against.
 *
 * Skippable. A camera that will not cooperate must not stop an athlete from
 * registering; without a photo the identity check reports "not checked", which
 * is honest, rather than blocking them outright.
 */
@Composable
fun ReferencePhotoStep(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }

    val photoFile = remember { File(context.cacheDir, "reference/photo.jpg") }
    val photoUri = remember {
        photoFile.parentFile?.mkdirs()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        if (!saved) {
            message = R.string.photo_error_none
            return@rememberLauncherForActivityResult
        }

        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val jpeg = PhotoPreparation.prepare(context, photoUri)
                // The full-resolution original is a child's face on shared
                // storage-adjacent cache; it is not kept once prepared.
                photoFile.delete()
                if (jpeg == null) null else api.uploadReferencePhoto(jpeg)
            }
            busy = false
            when (result) {
                null -> message = R.string.photo_error_unreadable
                is ApiResult.Success -> onDone()
                is ApiResult.Failure -> message = Labels.failure(result.kind, R.string.photo_error_upload)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle(stringResource(R.string.photo_title))

        Text(
            stringResource(R.string.photo_body),
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            stringResource(R.string.photo_privacy),
            style = MaterialTheme.typography.bodySmall
        )

        message?.let { ErrorText(stringResource(it)) }

        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = { takePicture.launch(photoUri) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.photo_take))
            }
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.photo_skip))
            }
        }
    }
}

/** Downscale, fix orientation and re-encode a camera photo for upload. */
object PhotoPreparation {

    /**
     * Large enough for a face detector, small enough that the upload costs a
     * fraction of what a 12MP original would on a prepaid data pack.
     */
    private const val MAX_EDGE_PX = 1024
    private const val JPEG_QUALITY = 85

    fun prepare(context: Context, uri: Uri): ByteArray? = runCatching {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        // Face detectors expect an upright face. Many camera apps write the
        // rotation into EXIF instead of the pixels, and the server does not
        // read EXIF — an unrotated photo reads as "no face found".
        val upright = if (rotation == 0f) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation) }, true
        )

        val scaled = scaleToFit(upright, MAX_EDGE_PX)

        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()

    /** Largest power-of-two subsample that keeps the long edge at or above [target]. */
    fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
