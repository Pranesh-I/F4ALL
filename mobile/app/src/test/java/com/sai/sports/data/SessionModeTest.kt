package com.sai.sports.data

import android.content.SharedPreferences
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ActiveSessions
import com.sai.sports.api.AssessmentSessionInfo
import com.sai.sports.api.F4allApi
import com.sai.sports.api.SessionTest
import com.sai.sports.data.local.SessionAttemptRow
import com.sai.sports.testing.MemoryPreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionModeTest {

    private val hour = 3_600_000L

    private fun session(
        id: String = "s1",
        startsAtMs: Long = 0,
        endsAtMs: Long = 10 * hour,
        tests: List<SessionTest> = listOf(SessionTest("SQUATS", "reps", submitted = false, resultStatus = null))
    ) = AssessmentSessionInfo(id, "District trials", null, null, startsAtMs, endsAtMs, tests)

    private fun cached(
        vararg sessions: AssessmentSessionInfo,
        serverTimeMs: Long = hour,
        fetchedAtWallMs: Long = 50 * hour,
        fetchedAtElapsedMs: Long = 1_000
    ) = CachedSessions(ActiveSessions(serverTimeMs, sessions.toList()), fetchedAtWallMs, fetchedAtElapsedMs)

    // -----------------------------------------------------------------
    // Whose clock
    // -----------------------------------------------------------------

    @Test
    fun `session windows are judged on the server's clock, not the phone's calendar`() {
        // The phone's calendar is 49 hours ahead of the server; it does not matter.
        val cache = cached(session())

        val now = SessionAvailability.serverNow(cache, wallNowMs = 999 * hour, elapsedNowMs = 1_000 + 2 * hour)

        assertEquals(3 * hour, now)
    }

    @Test
    fun `after a reboot the wall clock carries the time forward instead`() {
        val cache = cached(session())

        // Uptime restarted from zero; the wall clock has moved on two hours since the fetch.
        val now = SessionAvailability.serverNow(cache, wallNowMs = 52 * hour, elapsedNowMs = 10)

        assertEquals(3 * hour, now)
    }

    @Test
    fun `a session that has ended by the server's clock disappears offline`() {
        val cache = cached(session(endsAtMs = 2 * hour))

        val open = SessionAvailability.views(cache, emptyList(), wallNowMs = 0, elapsedNowMs = 1_000 + hour / 2)
        val closed = SessionAvailability.views(cache, emptyList(), wallNowMs = 0, elapsedNowMs = 1_000 + 2 * hour)

        assertEquals(1, open.size)
        assertTrue(closed.isEmpty())
    }

    // -----------------------------------------------------------------
    // One submission per test
    // -----------------------------------------------------------------

    private fun state(server: SessionTest, local: List<SessionAttemptRow>) =
        SessionAvailability.stateOf("s1", "SQUATS", cached(session(tests = listOf(server))), local)

    private val fresh = SessionTest("SQUATS", "reps", submitted = false, resultStatus = null)

    @Test
    fun `a test nobody has taken is available`() {
        assertEquals(SessionTestState.AVAILABLE, state(fresh, emptyList()))
    }

    @Test
    fun `a test recorded here but not sent is blocked even though the server has not heard of it`() {
        val waiting = listOf(SessionAttemptRow("s1", "SQUATS", resultId = null))

        assertEquals(SessionTestState.WAITING_TO_SEND, state(fresh, waiting))
    }

    @Test
    fun `a test the server has is blocked`() {
        val submitted = SessionTest("SQUATS", "reps", submitted = true, resultStatus = "processing")

        assertEquals(SessionTestState.SUBMITTED, state(submitted, emptyList()))
    }

    @Test
    fun `a test delivered from this phone stays blocked while the server list is stale`() {
        val delivered = listOf(SessionAttemptRow("s1", "SQUATS", resultId = "r1"))

        assertEquals(SessionTestState.SUBMITTED, state(fresh, delivered))
    }

    @Test
    fun `a resubmission request opens it again, until the new attempt is recorded`() {
        val sentBack = SessionTest("SQUATS", "reps", submitted = false, resultStatus = "pending_sync")
        val delivered = listOf(SessionAttemptRow("s1", "SQUATS", resultId = "r1"))

        assertEquals(SessionTestState.RESUBMIT, state(sentBack, delivered))
        assertTrue(SessionTestState.RESUBMIT.canRecord)

        val recordedAgain = delivered + SessionAttemptRow("s1", "SQUATS", resultId = null)
        assertEquals(SessionTestState.WAITING_TO_SEND, state(sentBack, recordedAgain))
    }

    @Test
    fun `another session or another test is unaffected`() {
        val elsewhere = listOf(
            SessionAttemptRow("s2", "SQUATS", resultId = null),
            SessionAttemptRow("s1", "LUNGES", resultId = null)
        )

        assertEquals(SessionTestState.AVAILABLE, state(fresh, elsewhere))
    }

    @Test
    fun `only the session's tests appear, and ones this app does not know are skipped`() {
        val cache = cached(
            session(
                tests = listOf(
                    SessionTest("LUNGES", "reps", false, null),
                    SessionTest("SHUTTLE_RUN", "seconds", false, null),
                    SessionTest("VERTICAL_JUMP", "cm", false, null)
                )
            )
        )

        val views = SessionAvailability.views(cache, emptyList(), wallNowMs = 0, elapsedNowMs = 1_000)

        assertEquals(listOf(TestType.LUNGES, TestType.VERTICAL_JUMP), views.single().tests.map { it.testType })
    }

    // -----------------------------------------------------------------
    // The capture screen's gate
    // -----------------------------------------------------------------

    private fun gate(
        cache: CachedSessions?,
        local: List<SessionAttemptRow> = emptyList(),
        testType: String = "SQUATS",
        elapsedNowMs: Long = 1_000
    ) = SessionAvailability.gate("s1", testType, cache, local, wallNowMs = 0, elapsedNowMs = elapsedNowMs)

    @Test
    fun `the gate opens only for an open session with the test still available`() {
        assertEquals(SessionGate.OPEN, gate(cached(session())))
        assertEquals(SessionGate.UNKNOWN, gate(null))
        assertEquals(SessionGate.UNKNOWN, gate(cached(session()), testType = "LUNGES"))
        assertEquals(SessionGate.CLOSED, gate(cached(session(endsAtMs = 2 * hour)), elapsedNowMs = 1_000 + 3 * hour))
        assertEquals(
            SessionGate.WAITING_TO_SEND,
            gate(cached(session()), listOf(SessionAttemptRow("s1", "SQUATS", null)))
        )
        assertEquals(
            SessionGate.SUBMITTED,
            gate(cached(session(tests = listOf(SessionTest("SQUATS", "reps", true, "verified")))))
        )
    }

    // -----------------------------------------------------------------
    // The offline cache
    // -----------------------------------------------------------------

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun repository(preferences: SharedPreferences = MemoryPreferences()) = SessionRepository(
        preferences = preferences,
        api = F4allApi(baseUrl = server.url("/").toString(), accessToken = { "token" }),
        wallClock = { 50 * hour },
        elapsedClock = { 1_000 }
    )

    private val activeBody = JSONObject(
        """
        {"server_time":"2026-10-01T10:00:00Z","sessions":[{
          "session_id":"s1","name":"District trials","description":"Open trials","rules":"Film outdoors",
          "starts_at":"2026-10-01T09:00:00+00:00","ends_at":"2026-10-03T09:00:00Z",
          "tests":[{"test_type":"SQUATS","unit":"reps","submitted":false,"result_status":null}]}]}
        """
    ).toString()

    @Test
    fun `sessions fetched once are still there with no signal`() {
        val preferences = MemoryPreferences()
        server.enqueue(MockResponse().setResponseCode(200).setBody(activeBody))

        assertTrue(repository(preferences).refresh("asha") != null)

        server.enqueue(MockResponse().setResponseCode(503))
        val offline = repository(preferences)
        assertNull("The failed fetch reports failure", offline.refresh("asha"))

        val cached = offline.cached("asha")!!
        val session = cached.sessions.sessions.single()
        assertEquals("District trials", session.name)
        assertEquals("Film outdoors", session.rules)
        assertEquals(java.time.Instant.parse("2026-10-01T09:00:00Z").toEpochMilli(), session.startsAtMs)
        assertEquals(java.time.Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(), cached.sessions.serverTimeMs)
    }

    @Test
    fun `one athlete's sessions are not shown to another on the same phone`() {
        val preferences = MemoryPreferences()
        server.enqueue(MockResponse().setResponseCode(200).setBody(activeBody))
        repository(preferences).refresh("asha")

        assertNull(repository(preferences).cached("ravi"))
    }

    @Test
    fun `the cache survives a round trip exactly`() {
        val original = cached(
            session(tests = listOf(SessionTest("VERTICAL_JUMP", "cm", submitted = true, resultStatus = "pending_sync")))
                .copy(description = "d", rules = null)
        )

        assertEquals(original, SessionRepository.decode(SessionRepository.encode(original)))
    }

    @Test
    fun `a malformed server time is a failed fetch, not a crash`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"server_time":"yesterday","sessions":[]}""")
        )

        assertNull(repository().refresh("asha"))
        assertFalse(server.requestCount == 0)
    }
}
