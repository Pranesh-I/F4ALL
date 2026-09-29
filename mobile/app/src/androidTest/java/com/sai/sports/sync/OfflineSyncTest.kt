package com.sai.sports.sync

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sai.sports.api.TokenPair
import com.sai.sports.auth.AppServices
import com.sai.sports.auth.StoredSession
import com.sai.sports.data.local.F4allDatabase
import com.sai.sports.data.local.TestAttemptEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The Sprint 9 Definition of Done, on a device: a test recorded with no
 * network waits on the phone, and goes to SAI by itself once the network is
 * back — without anyone opening the app.
 *
 * "No network" and "network back" are WorkManager's view of the constraint,
 * driven by its test driver; the worker, the queue, the upload and the submit
 * are the real ones, against a fake SAI server. What survives the app being
 * closed is WorkManager's own persisted job, which is the platform's guarantee
 * rather than something this test can kill a process to show.
 *
 * Uses the device's signed-in session and server address, and puts both back.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSyncTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var previousBaseUrl: String? = null
    private var previousSession: StoredSession? = null
    private val attemptId = "offline-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        )

        server = MockWebServer().apply {
            dispatcher = FakeSai()
            start()
        }

        previousBaseUrl = SyncConfig.baseUrl(context)
        SyncConfig.setBaseUrl(context, server.url("/").toString())

        val session = AppServices.session(context)
        previousSession = session.current()
        session.store(TokenPair("access", "refresh", 3600, ATHLETE, registered = true))
    }

    @After
    fun tearDown() {
        runBlocking { F4allDatabase.get(context).testAttemptDao().delete(attemptId) }
        File(context.filesDir, "compressed/$attemptId.mp4").delete()
        previousBaseUrl?.let { SyncConfig.setBaseUrl(context, it) }

        val session = AppServices.session(context)
        val previous = previousSession
        if (previous == null) {
            session.clear()
        } else {
            val secondsLeft = ((previous.accessExpiresAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            session.store(
                TokenPair(previous.accessToken, previous.refreshToken, secondsLeft, previous.athleteId, previous.isRegistered)
            )
        }
        server.shutdown()
    }

    @Test
    fun aTestRecordedOfflineIsSentAutomaticallyWhenTheNetworkReturns() {
        // Recorded in airplane mode: compressed and queued, nothing sent.
        val video = File(context.filesDir, "compressed/$attemptId.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(700_000) { (it % 251).toByte() })
        }
        runBlocking {
            F4allDatabase.get(context).testAttemptDao().insert(
                TestAttemptEntity(
                    id = attemptId,
                    testType = "SQUATS",
                    recordedAtMs = System.currentTimeMillis(),
                    provisionalScore = 14.0,
                    scoreUnit = "reps",
                    syncStatus = SyncStatus.QUEUED,
                    sourceVideoPath = "videos/$attemptId.mp4",
                    compressedVideoPath = "compressed/$attemptId.mp4",
                    compressedSizeBytes = video.length(),
                    checksumSha256 = Checksum.sha256(video),
                    athleteId = ATHLETE
                )
            )
        }

        SyncScheduler.syncNow(context)
        val workManager = WorkManager.getInstance(context)
        val work = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get().single()

        // No network: the job waits, and nothing reaches the server.
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(0, server.requestCount)

        // The network comes back.
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)

        val finished = waitForFinish(workManager, work.id)
        assertEquals(WorkInfo.State.SUCCEEDED, finished)

        val row = runBlocking { F4allDatabase.get(context).testAttemptDao().findById(attemptId) }!!
        assertEquals(SyncStatus.SYNCED, row.syncStatus)
        assertEquals("v-1", row.videoId)
        assertEquals("The test was submitted, not only the video", "r-1", row.resultId)
        assertEquals(Delivery.SUBMITTED, Delivery.of(row))
    }

    private fun waitForFinish(workManager: WorkManager, id: UUID): WorkInfo.State {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val state = workManager.getWorkInfoById(id).get()!!.state
            if (state.isFinished) return state
            Thread.sleep(100)
        }
        throw AssertionError("The sync did not finish within 30 s")
    }

    /** Just enough of SAI's upload and submit API. */
    private class FakeSai : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                request.method == "POST" && path == "/api/videos/upload/init" -> json(201, SESSION)
                request.method == "GET" && path == "/api/videos/upload/u-1" -> json(200, SESSION)
                request.method == "PUT" && path.startsWith("/api/videos/upload/u-1/chunks/") -> json(200, "{}")
                request.method == "POST" && path == "/api/videos/upload/u-1/complete" ->
                    json(200, """{"video_id":"v-1"}""")
                request.method == "POST" && path == "/api/tests/submit" -> {
                    val sent = JSONObject(request.body.readUtf8())
                    assertTrue(sent.getString("video_id") == "v-1")
                    json(201, """{"result_id":"r-1","status":"processing"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun json(code: Int, body: String) =
            MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

        private companion object {
            const val SESSION = """{"upload_id":"u-1","chunk_size_bytes":262144,"received_chunks":[]}"""
        }
    }

    private companion object {
        const val ATHLETE = "offline-athlete"
    }
}
