package com.sai.sports.api

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class Sprint9ApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(client: OkHttpClient = F4allApi.defaultHttpClient()) =
        F4allApi(baseUrl = server.url("/").toString(), httpClient = client, accessToken = { "t" })

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `badges are parsed as codes, not display text`() {
        server.enqueue(
            json(
                """{"badges":[{"code":"first_test","earned":true,"earned_at":"2026-09-01T00:00:00Z","progress":1,"target":1},
                   {"code":"streak_4_weeks","earned":false,"earned_at":null,"progress":2,"target":4}],
                   "current_streak_weeks":2,"longest_streak_weeks":2}"""
            )
        )

        val summary = (api().badges() as ApiResult.Success).value

        assertEquals("/api/athletes/me/badges", server.takeRequest().path)
        assertEquals(listOf("first_test", "streak_4_weeks"), summary.badges.map { it.code })
        assertEquals(4, summary.badges[1].target)
        assertEquals(2, summary.currentStreakWeeks)
    }

    @Test
    fun `leaderboard includes the private standing of the athlete`() {
        server.enqueue(
            json(
                """{"test_type":"SIT_UPS","unit":"reps","scope":"region","region":"Kerala","cohort":"female, 14-15",
                   "entries":[{"rank":1,"display_name":"Asha P.","region":"Kerala","score":44.0,"is_you":false}],
                   "you":{"rank":2,"score":30.0,"visible_to_others":false},"total_ranked":1}"""
            )
        )

        val board = (api().leaderboard("SIT_UPS", "region") as ApiResult.Success).value

        assertEquals("/api/athletes/leaderboard/SIT_UPS?scope=region", server.takeRequest().path)
        assertEquals("Asha P.", board.entries.single().displayName)
        assertEquals(2, board.you?.rank)
        assertFalse(board.you!!.visibleToOthers)
    }

    @Test
    fun `a leaderboard with no standing for the athlete parses`() {
        server.enqueue(
            json(
                """{"test_type":"SIT_UPS","unit":"reps","scope":"national","region":null,"cohort":"male, 16-17",
                   "entries":[],"you":null,"total_ranked":0}"""
            )
        )

        val board = (api().leaderboard("SIT_UPS", "national") as ApiResult.Success).value

        assertNull(board.region)
        assertNull(board.you)
    }

    @Test
    fun `preferences send only the fields being changed`() {
        server.enqueue(
            json(
                """{"athlete_id":"a","name":"A","dob":"2010-01-01","age_years":16,"gender":"female","region":"Kerala",
                   "phone":"1","height_cm":null,"weight_kg":null,"has_reference_photo":false,
                   "leaderboard_opt_in":true,"preferred_language":"ta"}"""
            )
        )

        val profile = (api().updatePreferences(leaderboardOptIn = true) as ApiResult.Success).value

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("leaderboard_opt_in"))
        assertFalse("A field not being changed must not be sent", body.has("preferred_language"))
        assertTrue(profile.leaderboardOptIn)
        assertEquals("ta", profile.preferredLanguage)
    }

    // -- low bandwidth ------------------------------------------------------

    @Test
    fun `a slow 2G-speed response still completes`() {
        // ~1KB/s: slower than a poor EDGE connection.
        server.enqueue(
            json("""{"badges":[],"current_streak_weeks":0,"longest_streak_weeks":0}""" + " ".repeat(1500))
                .throttleBody(512, 500, TimeUnit.MILLISECONDS)
        )

        val result = api().badges()

        assertTrue("A slow but working connection must not be treated as failure", result is ApiResult.Success)
    }

    @Test
    fun `a connection that stalls fails cleanly as a network problem instead of hanging`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val impatient = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val started = System.nanoTime()
        val result = api(impatient).badges() as ApiResult.Failure
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(ApiFailure.NETWORK, result.kind)
        assertTrue("Should give up at the timeout, took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `the production client has bounded timeouts sized for rural networks`() {
        val client = F4allApi.defaultHttpClient()
        // Patient enough for 2G, but finite: a screen must never spin forever.
        assertTrue(client.connectTimeoutMillis in 10_000..60_000)
        assertTrue(client.readTimeoutMillis in 30_000..120_000)
    }
}
