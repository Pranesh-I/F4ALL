package com.sai.sports.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sprint 16: every leaderboard filter reaches the server, and pages add up. */
class LeaderboardFiltersTest {

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

    private fun board(offset: Int, entries: Int, total: Int, extra: String = "") =
        MockResponse().setResponseCode(200).setBody(
            """{"test_type":"SIT_UPS","unit":"reps","scope":"region","region":"Kerala",
               "cohort":"female, 14-15","total_ranked":$total,"offset":$offset,"age_band":"14-15",
               "entries":[${(1..entries).joinToString(",") {
                """{"rank":${offset + it},"display_name":"A $it","region":"Kerala","score":${40 - it},"is_you":false}"""
            }}]$extra}"""
        )

    @Test
    fun `the default board asks for the athlete's own state and age band`() {
        server.enqueue(board(0, 0, 0))

        api.leaderboard(LeaderboardQuery("SIT_UPS"))

        assertEquals(
            "/api/athletes/leaderboard/SIT_UPS?scope=region&age_band=mine&limit=20&offset=0",
            server.takeRequest().path
        )
    }

    @Test
    fun `another state, an age band and a session are all sent`() {
        server.enqueue(board(0, 0, 0))

        api.leaderboard(
            LeaderboardQuery("PUSH_UPS", place = "Tamil Nadu", ageBand = "12-13", sessionId = "s-1"),
            offset = 20
        )

        assertEquals(
            "/api/athletes/leaderboard/PUSH_UPS?scope=region&region=Tamil+Nadu&age_band=12-13" +
                "&session_id=s-1&limit=20&offset=20",
            server.takeRequest().path
        )
    }

    @Test
    fun `all of India and all ages`() {
        server.enqueue(board(0, 0, 0))

        api.leaderboard(
            LeaderboardQuery("SQUATS", place = LeaderboardQuery.PLACE_INDIA, ageBand = LeaderboardQuery.AGE_ALL)
        )

        assertEquals(
            "/api/athletes/leaderboard/SQUATS?scope=national&age_band=all&limit=20&offset=0",
            server.takeRequest().path
        )
    }

    @Test
    fun `a page knows whether more follow`() {
        server.enqueue(board(0, 2, 3, ""","session_name":"District trials""""))
        server.enqueue(board(2, 1, 3))

        val first = (api.leaderboard(LeaderboardQuery("SIT_UPS"), limit = 2) as ApiResult.Success).value
        val second = (api.leaderboard(LeaderboardQuery("SIT_UPS"), offset = 2, limit = 2) as ApiResult.Success).value

        assertTrue(first.hasMore)
        assertEquals("District trials", first.sessionName)
        assertEquals("14-15", first.ageBand)
        assertFalse(second.hasMore)
        assertEquals(3, second.entries.single().rank)
    }

    @Test
    fun `the filter options are parsed`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"age_bands":["9-11","12-13"],"your_age_band":"12-13","your_region":"Kerala",
                   "sessions":[{"session_id":"s-1","name":"District trials"}]}"""
            )
        )

        val options = (api.leaderboardOptions() as ApiResult.Success).value

        assertEquals(listOf("9-11", "12-13"), options.ageBands)
        assertEquals("12-13", options.yourAgeBand)
        assertEquals(listOf(LeaderboardSession("s-1", "District trials")), options.sessions)
        assertEquals("/api/athletes/leaderboard-options", server.takeRequest().path)
    }

    @Test
    fun `an older server without the new fields still parses`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"test_type":"SIT_UPS","unit":"reps","scope":"national","region":null,
                   "cohort":"male, 9-11","entries":[],"total_ranked":0}"""
            )
        )

        val board = (api.leaderboard("SIT_UPS", "national") as ApiResult.Success).value

        assertNull(board.ageBand)
        assertFalse(board.hasMore)
    }
}
