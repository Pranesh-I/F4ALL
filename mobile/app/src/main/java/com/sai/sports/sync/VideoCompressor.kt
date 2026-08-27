package com.sai.sports.sync

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Transcodes a recording to 480p H.264 before upload.
 *
 * ## Why Media3 Transformer and not FFmpeg-Kit
 *
 * The README specifies FFmpeg-Kit. That is no longer buildable: Arthenica
 * retired the project and every `com.arthenica:ffmpeg-kit-*` artifact has been
 * removed from Maven Central (only `smart-exception-*` remains under that
 * group). The dependency cannot be resolved at any version.
 *
 * Media3 Transformer is the substitute, and it is a better fit for this project
 * regardless of the retirement:
 *
 *  - It transcodes through MediaCodec, i.e. the phone's hardware video encoder.
 *    FFmpeg on Android is a software encoder. On a ₹8,000 device that gap is
 *    the difference between a transcode that finishes in under a minute on a
 *    warm battery and one that pins four little cores, throttles, and drains
 *    the battery the athlete needs for the rest of the day.
 *  - It is AndroidX, maintained, and already named in the project's stack.
 *
 * The cost is less control: no arbitrary filter graphs, and encoder behaviour
 * varies across vendors. Neither matters for "make this smaller at 480p".
 */
class VideoCompressor(
    private val context: Context
) {

    sealed interface Result {

        data class Success(
            val outputFile: File,
            val originalSizeBytes: Long,
            val compressedSizeBytes: Long
        ) : Result {
            val ratio: Double
                get() = if (originalSizeBytes == 0L) 1.0
                else compressedSizeBytes.toDouble() / originalSizeBytes
        }

        data class Failure(val message: String) : Result
    }

    /**
     * Compresses [source] into [destination].
     *
     * Suspends until the transcode finishes. Cancelling the coroutine cancels
     * the transcode — important, because WorkManager can stop a worker mid-job
     * and an orphaned Transformer would keep the encoder busy.
     */
    suspend fun compress(
        source: File,
        destination: File
    ): Result = withContext(Dispatchers.Main) {

        if (!source.exists() || source.length() == 0L) {
            return@withContext Result.Failure("Source recording is missing")
        }

        destination.parentFile?.mkdirs()

        // A leftover file from an interrupted run would otherwise be appended
        // to or mistaken for a finished transcode.
        if (destination.exists()) {
            destination.delete()
        }

        val originalSize = source.length()

        try {
            suspendCancellableCoroutine<Result> { continuation ->

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {

                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.Success(
                                        outputFile = destination,
                                        originalSizeBytes = originalSize,
                                        compressedSizeBytes = destination.length()
                                    )
                                )
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Transcode failed", exportException)

                            destination.delete()

                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.Failure(
                                        exportException.message ?: "Transcode failed"
                                    )
                                )
                            }
                        }
                    })
                    .build()

                val editedItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(source.toURI().toString())
                ).setEffects(
                    Effects(
                        emptyList<AudioProcessor>(),
                        // Scales so the SHORT side is 480, preserving aspect
                        // ratio. These are portrait recordings, so asking for a
                        // 480-high output would produce a 270-wide video and
                        // throw away the detail the pose model needs.
                        listOf<Effect>(
                            Presentation.createForShortSide(TARGET_SHORT_SIDE_PX)
                        )
                    )
                ).build()

                transformer.start(editedItem, destination.absolutePath)

                continuation.invokeOnCancellation {
                    transformer.cancel()
                    destination.delete()
                }
            }

        } catch (exception: Exception) {
            Log.e(TAG, "Compression error", exception)
            destination.delete()
            Result.Failure(exception.message ?: "Compression error")
        }
    }

    companion object {

        private const val TAG = "VideoCompressor"

        /**
         * 480 on the short side — 480x854 for a portrait 16:9 recording.
         *
         * The server re-runs pose estimation on this exact file in Sprint 5, so
         * this is not purely a bandwidth decision: compress too hard and the
         * server's landmarks get noisier than the phone's were, which shows up
         * as a score discrepancy and auto-flags an honest athlete.
         */
        const val TARGET_SHORT_SIDE_PX = 480
    }
}
