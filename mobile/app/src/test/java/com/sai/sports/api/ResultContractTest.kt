package com.sai.sports.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The result and history screens read exactly what the server writes.
 *
 * The JSON files are rendered from the real endpoints by the backend's
 * `test_the_result_and_history_fixtures_match_the_api`, which fails when the
 * API changes without them. This test parses those same files, so a renamed
 * or retyped field breaks a build on one side or the other — never silently
 * on an athlete's phone.
 */
class ResultContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: F4allApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = F4allApi(baseUrl = server.url("/").toString(), accessToken = { "token" })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serve(fixture: String) {
        val body = File(FIXTURE_DIRECTORY, fixture).readText()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json"))
    }

    @Test
    fun `a result carries everything its screen shows`() {
        serve("result_sprint15.json")

        val result = (api.result("00000000-0000-0000-0000-00000000000c") as ApiResult.Success).value

        assertEquals("verified", result.status)
        assertEquals(25.0, result.provisionalScore!!, 0.0)
        assertEquals(24.0, result.serverScore!!, 0.0)
        assertNull(result.finalScore)
        assertEquals("District trials", result.sessionName)
        assertEquals(utc(2026, 10, 1, 11, 0), result.createdAtMs)

        val form = result.serverForm!!
        assertEquals(75, form.score)
        assertEquals(listOf(ServerFormFault("TORSO_LEAN", 2)), form.faults)
        assertNull(result.serverInvalidReason)

        val best = result.personalBest!!
        assertEquals(30.0, best.score, 0.0)
        assertTrue(best.official)
        assertEquals("00000000-0000-0000-0000-00000000000a", best.resultId)

        val previous = result.previousAttempt!!
        assertEquals("rejected", previous.status)
        assertEquals("00000000-0000-0000-0000-00000000000b", previous.resultId)
        assertEquals(utc(2026, 10, 1, 10, 0), previous.createdAtMs)

        val benchmark = result.benchmark!!
        assertEquals("above_average", benchmark.band)
        assertEquals(67, benchmark.percentile)
        assertEquals("Tamil Nadu", benchmark.region)
        assertTrue(benchmark.provisional)
    }

    @Test
    fun `a history page lists results newest first with a cursor to the next`() {
        serve("history_sprint15.json")

        val page = (api.history(limit = 2) as ApiResult.Success).value

        assertEquals(listOf("verified", "rejected"), page.items.map { it.status })
        assertEquals("District trials", page.items[0].sessionName)
        assertNull(page.items[1].sessionName)
        // The cursor is the oldest item's own timestamp, passed back unchanged.
        assertEquals(page.items.last().createdAt, page.nextBefore)
        assertEquals("/api/athletes/me/history?limit=2", server.takeRequest().path)
    }

    @Test
    fun `the next page is asked for with the cursor encoded`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"items":[],"next_before":null}""")
        )

        api.history(before = "2026-10-01T10:00:00+00:00", limit = 2)

        assertEquals(
            "/api/athletes/me/history?limit=2&before=2026-10-01T10%3A00%3A00%2B00%3A00",
            server.takeRequest().path
        )
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        const val FIXTURE_DIRECTORY = "../../backend/tests/fixtures/api"
    }
}
