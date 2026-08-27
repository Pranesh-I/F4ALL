package com.sai.sports.sync

import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Chunked, resumable video upload.
 *
 * Deliberately free of Android imports so the whole protocol — including the
 * resume path, which is the part that actually matters and the part hardest to
 * test on a device — can be exercised against MockWebServer on the JVM.
 *
 * Protocol (see the video upload endpoints in docs/openapi.yaml):
 *
 *   1. POST   init                       reserve an upload, get an upload_id
 *   2. GET    {upload_id}                ask which chunks the server already has
 *   3. PUT    {upload_id}/chunks/{n}     send one chunk
 *   4. POST   {upload_id}/complete       server reassembles and verifies checksum
 *
 * Step 2 is what makes this survive an app restart: the client trusts the
 * server's record of what arrived, never its own memory of what it sent.
 */
class UploadClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val authTokenProvider: () -> String? = { null }
) {

    /** Progress callback: bytes sent so far, total bytes. */
    fun interface ProgressListener {
        fun onProgress(bytesSent: Long, totalBytes: Long)
    }

    sealed interface Result {

        data class Success(val videoId: String) : Result

        data class Failure(
            val reason: UploadFailure,
            val detail: String? = null
        ) : Result
    }

    data class UploadSession(
        val uploadId: String,
        val chunkSizeBytes: Int,
        val receivedChunks: Set<Int>
    )

    /**
     * Uploads [file] end to end, resuming if [existingUploadId] is supplied.
     *
     * [onUploadIdIssued] fires as soon as the server allocates an id, so the
     * caller can persist it BEFORE any bytes move. If the process dies during
     * the first chunk, that id is the only thing standing between the athlete
     * and a full re-upload.
     */
    fun upload(
        file: File,
        checksumSha256: String,
        testType: String,
        existingUploadId: String? = null,
        onUploadIdIssued: (String) -> Unit = {},
        progressListener: ProgressListener? = null
    ): Result {

        if (!file.exists() || file.length() == 0L) {
            return Result.Failure(UploadFailure.FILE_MISSING)
        }

        return try {

            val session = existingUploadId
                ?.let { resumeSession(it) }
                ?: initSession(
                    file = file,
                    checksumSha256 = checksumSha256,
                    testType = testType
                ).also { onUploadIdIssued(it.uploadId) }

            sendChunks(
                file = file,
                session = session,
                progressListener = progressListener
            )

        } catch (exception: UploadException) {
            Result.Failure(exception.reason, exception.message)
        } catch (exception: SocketTimeoutException) {
            Result.Failure(UploadFailure.TIMEOUT, exception.message)
        } catch (exception: IOException) {
            Result.Failure(UploadFailure.NETWORK, exception.message)
        }
    }

    private fun initSession(
        file: File,
        checksumSha256: String,
        testType: String
    ): UploadSession {

        val body = JSONObject()
            .put("test_type", testType)
            .put("file_size_bytes", file.length())
            .put("chunk_size_bytes", ChunkPlan.DEFAULT_CHUNK_SIZE_BYTES)
            .put("checksum_sha256", checksumSha256)
            .put("content_type", VIDEO_CONTENT_TYPE)
            .toString()

        val request = requestBuilder("$baseUrl/api/videos/upload/init")
            .post(body.toRequestBody(JSON_CONTENT_TYPE.toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw UploadException(failureFor(response), "init: HTTP ${response.code}")
            }

            val json = JSONObject(response.body?.string().orEmpty())

            return UploadSession(
                uploadId = json.getString("upload_id"),
                chunkSizeBytes = json.optInt(
                    "chunk_size_bytes",
                    ChunkPlan.DEFAULT_CHUNK_SIZE_BYTES
                ),
                receivedChunks = json.optJSONArray("received_chunks").toIntSet()
            )
        }
    }

    /**
     * Asks the server what it already holds for this upload.
     *
     * A 404 means the server forgot the session — expired, or cleaned up. That
     * is recoverable by starting a fresh upload, so it is reported as a
     * distinct signal rather than a hard failure.
     */
    private fun resumeSession(uploadId: String): UploadSession {

        val request = requestBuilder("$baseUrl/api/videos/upload/$uploadId")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->

            if (response.code == 404) {
                throw UploadException(
                    UploadFailure.REJECTED,
                    "Upload session $uploadId no longer exists on the server"
                )
            }

            if (!response.isSuccessful) {
                throw UploadException(failureFor(response), "status: HTTP ${response.code}")
            }

            val json = JSONObject(response.body?.string().orEmpty())

            return UploadSession(
                uploadId = uploadId,
                chunkSizeBytes = json.optInt(
                    "chunk_size_bytes",
                    ChunkPlan.DEFAULT_CHUNK_SIZE_BYTES
                ),
                receivedChunks = json.optJSONArray("received_chunks").toIntSet()
            )
        }
    }

    private fun sendChunks(
        file: File,
        session: UploadSession,
        progressListener: ProgressListener?
    ): Result {

        val plan = ChunkPlan(
            fileSizeBytes = file.length(),
            chunkSizeBytes = session.chunkSizeBytes
        )

        val received = session.receivedChunks.toMutableSet()
        val totalBytes = file.length()

        RandomAccessFile(file, "r").use { handle ->

            for (index in plan.remainingChunks(received)) {

                val size = plan.sizeOf(index)
                val buffer = ByteArray(size)

                handle.seek(plan.offsetOf(index))
                handle.readFully(buffer)

                sendChunk(
                    uploadId = session.uploadId,
                    index = index,
                    body = buffer.toRequestBody(VIDEO_CONTENT_TYPE.toMediaType())
                )

                received += index

                progressListener?.onProgress(
                    received.sumOf { plan.sizeOf(it).toLong() },
                    totalBytes
                )
            }
        }

        return complete(session.uploadId)
    }

    private fun sendChunk(
        uploadId: String,
        index: Int,
        body: RequestBody
    ) {

        val request = requestBuilder(
            "$baseUrl/api/videos/upload/$uploadId/chunks/$index"
        ).put(body).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UploadException(
                    failureFor(response),
                    "chunk $index: HTTP ${response.code}"
                )
            }
        }
    }

    private fun complete(uploadId: String): Result {

        val request = requestBuilder("$baseUrl/api/videos/upload/$uploadId/complete")
            .post(EMPTY_JSON.toRequestBody(JSON_CONTENT_TYPE.toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->

            // The server recomputes the hash over what it actually assembled.
            // A mismatch means the bytes on the server are not the bytes on the
            // phone, and no amount of retrying the same corrupt source fixes
            // it — so this is deliberately not retryable.
            if (response.code == 422) {
                return Result.Failure(
                    UploadFailure.CHECKSUM_MISMATCH,
                    "Server checksum did not match"
                )
            }

            if (!response.isSuccessful) {
                throw UploadException(
                    failureFor(response),
                    "complete: HTTP ${response.code}"
                )
            }

            val json = JSONObject(response.body?.string().orEmpty())

            return Result.Success(videoId = json.getString("video_id"))
        }
    }

    private fun requestBuilder(url: String): Request.Builder {

        val builder = Request.Builder().url(url)

        authTokenProvider()?.let { token ->
            builder.headers(mapOf("Authorization" to "Bearer $token").toHeaders())
        }

        return builder
    }

    private fun failureFor(response: Response): UploadFailure =
        when (response.code) {
            401, 403 -> UploadFailure.UNAUTHORIZED
            408 -> UploadFailure.TIMEOUT
            429 -> UploadFailure.RATE_LIMITED
            in 500..599 -> UploadFailure.SERVER_ERROR
            else -> UploadFailure.REJECTED
        }

    private fun JSONArray?.toIntSet(): Set<Int> {
        if (this == null) return emptySet()
        return (0 until length()).map { getInt(it) }.toSet()
    }

    private class UploadException(
        val reason: UploadFailure,
        message: String?
    ) : RuntimeException(message)

    companion object {

        private const val JSON_CONTENT_TYPE = "application/json"
        private const val VIDEO_CONTENT_TYPE = "video/mp4"
        private const val EMPTY_JSON = "{}"

        /**
         * Timeouts sized for a bad rural connection rather than an office one.
         * Being patient costs a stalled worker; being impatient costs the
         * athlete their upload and their data.
         */
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
