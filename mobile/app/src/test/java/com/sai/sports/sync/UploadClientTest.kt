package com.sai.sports.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * End-to-end tests for the resumable upload protocol, against MockWebServer.
 *
 * The resume path is the whole reason chunked upload exists here, and it is the
 * hardest thing to verify on a real device — you cannot reliably kill a phone's
 * connection mid-chunk on demand. On the JVM it is a one-line socket policy.
 */
class UploadClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: UploadClient
    private lateinit var videoFile: File

    /** Small enough that a few-KB fixture spans several chunks. */
    private val chunkSize = 1024

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        client = UploadClient(baseUrl = server.url("/").toString().trimEnd('/'))

        videoFile = File.createTempFile("upload-test", ".mp4").apply {
            writeBytes(Random(seed = 7).nextBytes(4096))
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        videoFile.delete()
    }

    private fun initResponse(received: List<Int> = emptyList()) =
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """{"upload_id":"up-123","chunk_size_bytes":$chunkSize,
                   |"received_chunks":${received}}""".trimMargin()
            )

    private fun statusResponse(received: List<Int>) =
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """{"upload_id":"up-123","chunk_size_bytes":$chunkSize,
                   |"received_chunks":${received}}""".trimMargin()
            )

    private fun chunkAccepted() = MockResponse().setResponseCode(200).setBody("{}")

    private fun completeResponse() =
        MockResponse().setResponseCode(200).setBody("""{"video_id":"vid-789"}""")

    private fun drainRequests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .toList()

    @Test
    fun `uploads every chunk then completes`() {

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        val result = client.upload(
            file = videoFile,
            checksumSha256 = "abc123",
            testType = "SIT_UPS"
        )

        assertTrue(result is UploadClient.Result.Success)
        assertEquals("vid-789", (result as UploadClient.Result.Success).videoId)

        val requests = drainRequests()

        assertEquals("POST", requests[0].method)
        assertTrue(requests[0].path!!.endsWith("/api/videos/upload/init"))

        // 4096 bytes / 1024 = 4 chunks, indices 0..3
        val chunkPaths = requests.filter { it.method == "PUT" }.map { it.path }
        assertEquals(4, chunkPaths.size)
        (0..3).forEach { index ->
            assertTrue(
                "Missing chunk $index in $chunkPaths",
                chunkPaths.any { it!!.endsWith("/chunks/$index") }
            )
        }

        assertTrue(requests.last().path!!.endsWith("/complete"))
    }

    @Test
    fun `chunk bodies reassemble to the original file`() {

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        client.upload(videoFile, "abc123", "SIT_UPS")

        val chunks = drainRequests()
            .filter { it.method == "PUT" }
            .sortedBy { it.path!!.substringAfterLast('/').toInt() }
            .map { it.body.readByteArray() }

        val reassembled = chunks.reduce { accumulator, next -> accumulator + next }

        // If offsets or sizes are wrong the server ends up with a corrupt video
        // that still looks like an MP4 and scores differently.
        assertTrue(
            "Reassembled bytes differ from the source file",
            reassembled.contentEquals(videoFile.readBytes())
        )
    }

    @Test
    fun `resume sends only the chunks the server is missing`() {

        // Server already holds chunks 0 and 1 from an interrupted attempt.
        server.enqueue(statusResponse(listOf(0, 1)))
        repeat(2) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        val result = client.upload(
            file = videoFile,
            checksumSha256 = "abc123",
            testType = "SIT_UPS",
            existingUploadId = "up-123"
        )

        assertTrue(result is UploadClient.Result.Success)

        val requests = drainRequests()

        // No init call — the session was resumed, not recreated.
        assertTrue(requests.none { it.path!!.endsWith("/init") })

        val chunkPaths = requests.filter { it.method == "PUT" }.map { it.path }

        assertEquals(
            "Should re-send exactly the two missing chunks",
            2,
            chunkPaths.size
        )
        assertTrue(chunkPaths.any { it!!.endsWith("/chunks/2") })
        assertTrue(chunkPaths.any { it!!.endsWith("/chunks/3") })
        assertTrue(
            "Chunks the server already had must not be re-sent — that is the " +
                "athlete's data being spent twice",
            chunkPaths.none { it!!.endsWith("/chunks/0") || it.endsWith("/chunks/1") }
        )
    }

    @Test
    fun `upload id is surfaced before any bytes are sent`() {

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        var issuedId: String? = null
        var chunksSentWhenIssued = -1

        client.upload(
            file = videoFile,
            checksumSha256 = "abc123",
            testType = "SIT_UPS",
            onUploadIdIssued = {
                issuedId = it
                chunksSentWhenIssued = server.requestCount - 1
            }
        )

        // If the id arrived late and the process died first, the whole video
        // would be re-uploaded from scratch.
        assertEquals("up-123", issuedId)
        assertEquals(0, chunksSentWhenIssued)
    }

    @Test
    fun `dropped connection mid-upload is reported as retryable`() {

        server.enqueue(initResponse())
        server.enqueue(chunkAccepted())
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )

        val result = client.upload(videoFile, "abc123", "SIT_UPS")

        assertTrue(result is UploadClient.Result.Failure)

        val failure = result as UploadClient.Result.Failure

        assertTrue(
            "A dropped connection must be retryable, not terminal",
            RetryPolicy().isRetryable(failure.reason)
        )
    }

    @Test
    fun `progress is reported as chunks are acknowledged`() {

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        val progressUpdates = mutableListOf<Long>()

        client.upload(
            file = videoFile,
            checksumSha256 = "abc123",
            testType = "SIT_UPS",
            progressListener = { sent, _ -> progressUpdates += sent }
        )

        assertEquals(listOf(1024L, 2048L, 3072L, 4096L), progressUpdates)
    }

    @Test
    fun `server checksum mismatch is not retried`() {

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(MockResponse().setResponseCode(422))

        val result = client.upload(videoFile, "abc123", "SIT_UPS")

        val failure = result as UploadClient.Result.Failure

        assertEquals(UploadFailure.CHECKSUM_MISMATCH, failure.reason)
        assertTrue(
            "Retrying the same corrupt source cannot fix a checksum mismatch",
            !RetryPolicy().isRetryable(failure.reason)
        )
    }

    @Test
    fun `expired upload session is reported rather than silently restarted`() {

        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.upload(
            file = videoFile,
            checksumSha256 = "abc123",
            testType = "SIT_UPS",
            existingUploadId = "up-gone"
        )

        val failure = result as UploadClient.Result.Failure
        assertEquals(UploadFailure.REJECTED, failure.reason)
        assertNotNull(failure.detail)
    }

    @Test
    fun `expired auth is not retried`() {

        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.upload(videoFile, "abc123", "SIT_UPS")

        val failure = result as UploadClient.Result.Failure

        assertEquals(UploadFailure.UNAUTHORIZED, failure.reason)
        assertTrue(!RetryPolicy().isRetryable(failure.reason))
    }

    @Test
    fun `server error is retryable`() {

        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.upload(videoFile, "abc123", "SIT_UPS")

        val failure = result as UploadClient.Result.Failure

        assertEquals(UploadFailure.SERVER_ERROR, failure.reason)
        assertTrue(RetryPolicy().isRetryable(failure.reason))
    }

    @Test
    fun `missing file fails without touching the network`() {

        val missing = File("does-not-exist.mp4")

        val result = client.upload(missing, "abc123", "SIT_UPS")

        assertEquals(
            UploadFailure.FILE_MISSING,
            (result as UploadClient.Result.Failure).reason
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `auth token is attached when available`() {

        val authed = UploadClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            authTokenProvider = { "token-abc" }
        )

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        authed.upload(videoFile, "abc123", "SIT_UPS")

        val request = server.takeRequest()

        assertEquals("Bearer token-abc", request.getHeader("Authorization"))
    }

    @Test
    fun `init declares the file size and checksum up front`() {

        server.enqueue(initResponse())
        repeat(4) { server.enqueue(chunkAccepted()) }
        server.enqueue(completeResponse())

        client.upload(videoFile, "checksum-xyz", "VERTICAL_JUMP")

        val body = server.takeRequest().body.readUtf8()

        // The server needs these to allocate the upload and to verify what it
        // reassembled at the end.
        assertTrue(body.contains("\"file_size_bytes\":4096"))
        assertTrue(body.contains("\"checksum_sha256\":\"checksum-xyz\""))
        assertTrue(body.contains("\"test_type\":\"VERTICAL_JUMP\""))
    }
}
