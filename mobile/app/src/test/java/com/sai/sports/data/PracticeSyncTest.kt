package com.sai.sports.data

import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.RepFixtures
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.F4allApi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Practice follows the athlete's account — and only theirs.
 *
 * The "server" here is a small fake of the practice endpoints that keeps one
 * list per bearer token, so a test can play two phones and two athletes
 * against it.
 */
class PracticeSyncTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    /** Stored practice, per access token (i.e. per athlete). */
    private val accounts = mutableMapOf<String, MutableMap<String, JSONObject>>()

    @Volatile
    private var offline = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Dropped after the request is read: the phone sees a broken
                // connection, never a response. (DISCONNECT_AT_START only works
                // from peek(); returned here it quietly becomes a 200.)
                if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
                    ?: return MockResponse().setResponseCode(401)
                val account = accounts.getOrPut(token) { linkedMapOf() }
                val path = request.path.orEmpty()

                return when {
                    request.method == "PUT" && path.startsWith(PRACTICE + "/") -> {
                        val id = path.removePrefix(PRACTICE + "/")
                        val body = JSONObject(request.body.readUtf8())
                        if (id == REFUSED_ID) {
                            MockResponse().setResponseCode(422).setBody("""{"detail":"Unknown test type"}""")
                        } else {
                            account[id] = body.put("client_attempt_id", id)
                            json(body.toString())
                        }
                    }
                    request.method == "GET" && path.startsWith(PRACTICE) -> {
                        val before = Regex("before_ms=(\\d+)").find(path)?.groupValues?.get(1)?.toLong()
                        val limit = Regex("limit=(\\d+)").find(path)!!.groupValues[1].toInt()
                        val page = account.values
                            .filter { before == null || it.getLong("recorded_at_ms") < before }
                            .sortedByDescending { it.getLong("recorded_at_ms") }
                            .take(limit)
                        json(JSONObject().put("attempts", JSONArray(page)).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json")

    /** A phone: its own files, signed in as [athleteId]. */
    private inner class Phone(name: String, val athleteId: String) {
        val store = AttemptStore(folder.newFolder(name))
        val sync = PracticeSync(
            store,
            F4allApi(baseUrl = server.url("/").toString(), accessToken = { "token-$athleteId" })
        )
    }

    private fun practice(
        id: String,
        athleteId: String,
        score: Double,
        recordedAtMs: Long,
        mode: AttemptMode = AttemptMode.PRACTICE,
        testType: TestType = TestType.SQUATS
    ) = Attempt(
        id = id,
        testType = testType,
        videoFileName = "$id.mp4",
        recordedAtMs = recordedAtMs,
        mode = mode,
        athleteId = athleteId,
        result = AnalyzerResult(
            testType = testType,
            score = score,
            unit = testType.unit,
            status = AttemptStatus.COMPLETE,
            confidence = 0.9,
            framesAnalyzed = 50,
            framesRejected = 0,
            events = listOf(
                AnalyzerEvent(recordedAtMs, "rep_counted", "Rep 1"),
                AnalyzerEvent(recordedAtMs, "form_warning", "torso_lean")
            )
        )
    )

    @Test
    fun `practice recorded on one phone shows up on the athlete's next phone`() {
        val oldPhone = Phone("old", "asha")
        oldPhone.store.save(practice("test_1", "asha", 12.0, 1_000), RepFixtures.run(1, RepFixtures.squat()))
        oldPhone.store.save(practice("test_2", "asha", 15.0, 2_000), emptyList())

        assertEquals(2, oldPhone.sync.sync("asha").pushed)

        val newPhone = Phone("new", "asha")
        val outcome = newPhone.sync.sync("asha")

        assertEquals(2, outcome.pulled)
        assertTrue(outcome.changedHistory)
        val history = newPhone.store.listAttempts("asha")
        assertEquals(listOf("test_2", "test_1"), history.map { it.id })
        assertTrue(history.all { it.mode == AttemptMode.PRACTICE && it.savedToAccount })

        // Personal bests and form feedback rebuild from what came across.
        assertEquals("test_2", PracticeStats.personalBest(history, TestType.SQUATS)?.id)
        assertEquals(listOf("rep_counted", "form_warning"), history.first().result.events.map { it.label })
        // The skeleton stays on the phone that recorded it.
        assertTrue(newPhone.store.loadSequence("test_1").isEmpty())
    }

    @Test
    fun `a friend on the same phone never sees another athlete's practice`() {
        val sharedPhone = Phone("shared", "asha")
        sharedPhone.store.save(practice("test_1", "asha", 12.0, 1_000), emptyList())
        sharedPhone.sync.sync("asha")

        val friendSignedIn = PracticeSync(
            sharedPhone.store,
            F4allApi(baseUrl = server.url("/").toString(), accessToken = { "token-ravi" })
        )
        val outcome = friendSignedIn.sync("ravi")

        assertEquals(0, outcome.pushed)
        assertEquals(0, outcome.pulled)
        assertTrue(sharedPhone.store.listAttempts("ravi").isEmpty())
        assertEquals("Asha's practice never reached Ravi's account", 0, accounts["token-ravi"]!!.size)
    }

    @Test
    fun `only practice goes to the account, never official tests`() {
        val phone = Phone("p", "asha")
        phone.store.save(practice("test_1", "asha", 12.0, 1_000), emptyList())
        phone.store.save(practice("test_2", "asha", 30.0, 2_000, mode = AttemptMode.OFFICIAL), emptyList())

        phone.sync.sync("asha")

        assertEquals(setOf("test_1"), accounts["token-asha"]!!.keys)
    }

    @Test
    fun `saved attempts are not sent twice`() {
        val phone = Phone("p", "asha")
        phone.store.save(practice("test_1", "asha", 12.0, 1_000), emptyList())

        assertEquals(1, phone.sync.sync("asha").pushed)
        assertEquals(0, phone.sync.sync("asha").pushed)
        assertTrue(phone.store.load("test_1")!!.savedToAccount)
    }

    @Test
    fun `with no signal nothing is lost and it is tried again later`() {
        val phone = Phone("p", "asha")
        phone.store.save(practice("test_1", "asha", 12.0, 1_000), emptyList())
        offline = true

        val outcome = phone.sync.sync("asha")

        assertTrue(outcome.retryLater)
        assertFalse(phone.store.load("test_1")!!.savedToAccount)

        offline = false
        assertEquals(1, phone.sync.sync("asha").pushed)
    }

    @Test
    fun `an attempt the server refuses stays on the phone without blocking the rest`() {
        val phone = Phone("p", "asha")
        phone.store.save(practice(REFUSED_ID, "asha", 12.0, 1_000), emptyList())
        phone.store.save(practice("test_2", "asha", 13.0, 2_000), emptyList())

        val outcome = phone.sync.sync("asha")

        assertFalse("A refusal is not a reason to retry", outcome.retryLater)
        assertTrue(phone.store.load("test_2")!!.savedToAccount)
        assertFalse("Kept, unsynced, for next time", phone.store.load(REFUSED_ID)!!.savedToAccount)
    }

    @Test
    fun `a pulled attempt never overwrites one already on the phone`() {
        val phone = Phone("p", "asha")
        phone.store.save(practice("test_1", "asha", 12.0, 1_000), RepFixtures.run(1, RepFixtures.squat()))
        phone.sync.sync("asha")

        phone.sync.sync("asha")

        assertTrue("The local skeleton survives", phone.store.loadSequence("test_1").isNotEmpty())
    }

    @Test
    fun `long histories come across page by page`() {
        val oldPhone = Phone("old", "asha")
        repeat(450) { index ->
            oldPhone.store.save(practice("test_$index", "asha", index.toDouble(), 1_000L + index), emptyList())
        }
        oldPhone.sync.sync("asha")

        val newPhone = Phone("new", "asha")

        assertEquals(450, newPhone.sync.sync("asha").pulled)
        assertEquals(450, newPhone.store.listAttempts("asha").size)
    }

    @Test
    fun `attempts from a newer app version are skipped, not crashed on`() {
        accounts["token-asha"] = linkedMapOf(
            "test_9" to JSONObject()
                .put("client_attempt_id", "test_9")
                .put("test_type", "SHUTTLE_RUN")
                .put("score", 9.5)
                .put("unit", "seconds")
                .put("status", "COMPLETE")
                .put("confidence", 0.9)
                .put("recorded_at_ms", 5_000)
                .put("events", JSONArray())
        )
        val phone = Phone("p", "asha")

        assertEquals(0, phone.sync.sync("asha").pulled)
        assertNull(phone.store.load("test_9"))
    }

    private companion object {
        const val PRACTICE = "/api/athletes/me/practice"

        /** The fake server refuses this one, as a real one would a test it does not know. */
        const val REFUSED_ID = "test_refused"
    }
}
