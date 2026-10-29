package com.sai.sports.fieldtest

import com.sai.sports.api.F4allApi
import com.sai.sports.data.AttemptMode
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.history.HistoryEntry
import com.sai.sports.ui.history.HistoryFilter
import com.sai.sports.ui.history.HistoryTimeline
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sprint 18: field attempts are kept per athlete, sent once, and shown in history. */
class FieldDataTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: FieldAttemptStore
    private lateinit var sync: FieldSync

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FieldAttemptStore(folder.root)
        sync = FieldSync(store, F4allApi(baseUrl = server.url("/").toString(), accessToken = { "token" }))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val trace = FieldTrace(
        motion = listOf(Motion(-10, 0.1, 0.2, 9.8, 0.0, 0.0, 0.01), Motion(0, 0.1, 0.3, 9.7)),
        fixes = listOf(Fix(0, 12.9716, 77.5946, 5.0))
    )

    private fun attempt(
        id: String,
        athleteId: String = "asha",
        mode: AttemptMode = AttemptMode.OFFICIAL,
        score: Double = 9.71,
        sessionId: String? = "s1"
    ) = FieldAttempt(
        id = id,
        testType = FieldTestType.SHUTTLE_RUN,
        mode = mode,
        athleteId = athleteId,
        recordedAtMs = 1_760_000_000_000,
        result = FieldResult(
            FieldTestType.SHUTTLE_RUN, FieldOutcome.COMPLETE, score,
            events = listOf(FieldEvent(0, "start"), FieldEvent(9710, "finish")),
            turns = 3, legMs = listOf(2690, 2410, 2400, 2210)
        ),
        sessionId = sessionId
    )

    private fun noPracticeOnServer() =
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"attempts":[]}"""))

    // -- storage -----------------------------------------------------------

    @Test
    fun `an attempt and its trace survive a round trip`() {
        store.save(attempt("a1"))
        store.saveTrace("a1", trace)

        assertEquals(attempt("a1"), store.load("a1"))
        assertEquals(trace, store.trace("a1"))
    }

    @Test
    fun `one athlete never sees another's attempts on a shared phone`() {
        store.save(attempt("a1", athleteId = "asha"))
        store.save(attempt("r1", athleteId = "ravi"))

        assertEquals(listOf("a1"), store.list("asha").map { it.id })
        assertEquals(listOf("r1"), store.list("ravi").map { it.id })
    }

    @Test
    fun `an unsent official run holds its session slot`() {
        store.save(attempt("a1"))
        store.save(attempt("p1", mode = AttemptMode.PRACTICE, sessionId = null))

        val rows = store.sessionRows("asha")
        assertEquals(1, rows.size)
        assertEquals("SHUTTLE_RUN", rows.single().testType)
        assertNull(rows.single().resultId)
    }

    @Test
    fun `motion rows carry the gyroscope only when there is one`() {
        val withGyro = FieldAttemptStore.motionRows(trace.motion)
        assertEquals(7, withGyro.getJSONArray(0).length())
        val without = FieldAttemptStore.motionRows(listOf(Motion(0, 0.1, 0.2, 9.8)))
        assertEquals(4, without.getJSONArray(0).length())
    }

    // -- sending -----------------------------------------------------------

    @Test
    fun `an official run is sent with its trace, and the trace is then deleted`() {
        store.save(attempt("a1"))
        store.saveTrace("a1", trace)
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"result_id":"r-1","status":"verified","server_score":9.71}""")
        )
        noPracticeOnServer()

        val outcome = sync.sync("asha")

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("SHUTTLE_RUN", sent.getString("test_type"))
        assertEquals("a1", sent.getString("client_attempt_id"))
        assertEquals(9.71, sent.getDouble("provisional_score"), 0.0)
        assertEquals("s1", sent.getString("session_id"))
        assertEquals(2, sent.getJSONArray("motion").length())
        assertEquals(1, outcome.sent)
        assertEquals("r-1", store.load("a1")!!.resultId)
        assertEquals("verified", store.load("a1")!!.serverStatus)
        assertNull(store.trace("a1"))
    }

    @Test
    fun `no signal keeps the run to send later`() {
        store.save(attempt("a1"))
        store.saveTrace("a1", trace)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        noPracticeOnServer()

        assertTrue(sync.sync("asha").retryLater)
        assertTrue(store.load("a1")!!.waitingToSend)
        assertEquals(trace, store.trace("a1"))
    }

    @Test
    fun `a run SAI refuses for good is not sent again`() {
        store.save(attempt("a1"))
        store.saveTrace("a1", trace)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"already submitted"}"""))
        noPracticeOnServer()

        sync.sync("asha")

        assertTrue(store.load("a1")!!.notAccepted)
        assertFalse(store.load("a1")!!.waitingToSend)
        assertTrue(store.sessionRows("asha").isEmpty())
    }

    @Test
    fun `practice is saved to the account as a result, never with its trace`() {
        store.save(attempt("p1", mode = AttemptMode.PRACTICE, sessionId = null))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        noPracticeOnServer()

        sync.sync("asha")

        val request = server.takeRequest()
        assertEquals("/api/athletes/me/practice/p1", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("seconds", body.getString("unit"))
        assertFalse(body.has("motion"))
        assertTrue(store.load("p1")!!.savedToAccount)
    }

    @Test
    fun `field practice from another phone is brought here, and camera practice is left alone`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"attempts":[
                  {"client_attempt_id":"field_9","test_type":"ENDURANCE_RUN","score":182.4,"unit":"seconds",
                   "status":"COMPLETE","confidence":1.0,"recorded_at_ms":1760000000000,"events":[]},
                  {"client_attempt_id":"test_3","test_type":"SQUATS","score":12,"unit":"reps",
                   "status":"COMPLETE","confidence":0.9,"recorded_at_ms":1760000000000,"events":[]}]}"""
            )
        )

        sync.sync("asha")

        val pulled = store.list("asha").single()
        assertEquals(FieldTestType.ENDURANCE_RUN, pulled.testType)
        assertEquals(182.4, pulled.result.score, 0.0)
        assertTrue(pulled.savedToAccount)
    }

    // -- showing -----------------------------------------------------------

    @Test
    fun `every reason a run cannot be scored has words`() {
        FieldInvalid.ALL.forEach { code ->
            assertNotEquals(code, com.sai.sports.R.string.invalid_generic, Labels.invalidReason(code))
        }
    }

    @Test
    fun `the field tests have names`() {
        FieldTestType.entries.forEach {
            assertNotEquals(com.sai.sports.R.string.test_unknown, Labels.testName(it.name))
        }
    }

    @Test
    fun `field practice is in the history, official runs come from SAI's record`() {
        val entries = HistoryTimeline.merge(
            official = emptyList(),
            moreOfficialToLoad = false,
            attempts = emptyList(),
            filter = HistoryFilter.ALL,
            fieldAttempts = listOf(attempt("p1", mode = AttemptMode.PRACTICE), attempt("o1"))
        )
        assertEquals(listOf("field:p1"), entries.map { it.key })
        assertTrue(entries.single() is HistoryEntry.FieldPractice)
    }

    @Test
    fun `the live distance ignores jitter and jumps`() {
        val start = Fix(0, 12.9716, 77.5946, 5.0)
        val jitter = Fix(1000, 12.97161, 77.5946, 5.0) // about 1 m
        val jump = Fix(2000, 12.9816, 77.5946, 5.0) // over a kilometre in a second
        val real = Fix(3000, 12.97169, 77.5946, 5.0) // about 10 m
        assertEquals(10.0, EnduranceRunAnalyzer.distanceSoFar(listOf(start, jitter, jump, real)), 0.5)
    }
}
