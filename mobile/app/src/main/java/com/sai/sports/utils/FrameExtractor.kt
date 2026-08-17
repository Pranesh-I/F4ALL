package com.sai.sports.utils

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

object FrameExtractor {

    data class ExtractionResult(
        val totalFrames: Int,
        val frameFiles: List<File>
    )

    fun extractFrames(
        videoFile: File,
        outputDirectory: File,
        intervalMs: Long = 500L
    ): ExtractionResult {

        if (!videoFile.exists()) {
            throw IllegalArgumentException(
                "Video file does not exist: ${videoFile.absolutePath}"
            )
        }

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        val retriever = MediaMetadataRetriever()
        val frameFiles = mutableListOf<File>()

        try {
            retriever.setDataSource(videoFile.absolutePath)

            val durationString =
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )

            val durationMs =
                durationString?.toLongOrNull() ?: 0L

            var timestampMs = 0L
            var frameIndex = 0

            while (timestampMs < durationMs) {

                val bitmap: Bitmap? =
                    retriever.getFrameAtTime(
                        timestampMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )

                if (bitmap != null) {

                    val frameFile = File(
                        outputDirectory,
                        "frame_%05d.jpg".format(frameIndex)
                    )

                    FileOutputStream(frameFile).use { outputStream ->

                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            85,
                            outputStream
                        )
                    }

                    bitmap.recycle()

                    frameFiles.add(frameFile)

                    frameIndex++
                }

                timestampMs += intervalMs
            }

            return ExtractionResult(
                totalFrames = frameFiles.size,
                frameFiles = frameFiles
            )

        } finally {
            retriever.release()
        }
    }
}