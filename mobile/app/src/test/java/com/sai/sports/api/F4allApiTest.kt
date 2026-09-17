package com.sai.sports.api

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

class F4allApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: F4allApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = F4allApi(
            baseUrl = server.url("/").toString(),
            accessToken = { "token-123" }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json")

    @Test
    fun `requesting an otp sends the phone and no token`() {
        server.enqueue(json(200, """{"message":"sent","expires_at":"2026-01-01T00:00:00Z","development_code":"000000"}"""))

        val result = api.requestOtp("9876543210")

        val request = server.takeRequest()
        assertEquals("/api/auth/request-otp", request.path)
        assertNull("Login calls must not carry a stale token", request.getHeader("Authorization"))
        assertEquals("9876543210", JSONObject(request.body.readUtf8()).getString("phone"))
        assertEquals("000000", (result as ApiResult.Success).value.developmentCode)
    }

    @Test
    fun `a rate-limited otp request reports when to retry`() {
        server.enqueue(json(429, """{"detail":"Too many OTP requests."}""").setHeader("Retry-After", "42"))

        val result = api.requestOtp("9876543210") as ApiResult.Failure

        assertEquals(ApiFailure.RATE_LIMITED, result.kind)
        assertEquals(42, result.retryAfterSeconds)
        assertEquals("Too many OTP requests.", result.message)
    }

    @Test
    fun `verifying an unregistered phone is parsed as not registered`() {
        server.enqueue(json(200, """{"access_token":"a","refresh_token":"r","token_type":"bearer","expires_in":3600,"athlete_id":null,"registered":false}"""))

        val pair = (api.verifyOtp("9876543210", "000000") as ApiResult.Success).value

        assertFalse(pair.registered)
        assertNull(pair.athleteId)
        assertEquals(3600L, pair.expiresInSeconds)
    }

    @Test
    fun `registration returns the profile and a fresh athlete session`() {
        server.enqueue(
            json(
                201,
                """{"profile":{"athlete_id":"id-1","name":"A","dob":"2008-01-01","age_years":18,"gender":"male",
                   "region":"Kerala","phone":"9876543210","height_cm":170.0,"weight_kg":null,"has_reference_photo":false},
                   "tokens":{"access_token":"new-a","refresh_token":"new-r","token_type":"bearer","expires_in":3600,
                   "athlete_id":"id-1","registered":true}}"""
            )
        )

        val registered = (api.register(
            Registration("A", "2008-01-01", "male", "Kerala", 170.0, null)
        ) as ApiResult.Success).value

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("2008-01-01", sent.getString("dob"))
        assertFalse("A missing weight is omitted, not sent as null", sent.has("weight_kg"))

        assertEquals("new-a", registered.tokens.accessToken)
        assertEquals("id-1", registered.tokens.athleteId)
        assertEquals(170.0, registered.profile.heightCm!!, 0.0)
    }

    @Test
    fun `authenticated calls carry the bearer token`() {
        server.enqueue(json(201, """{"result_id":"r-1","status":"processing"}"""))

        api.submitTest("SIT_UPS", 24.0, "video-1", null)

        assertEquals("Bearer token-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `submitting a test returns the server result id`() {
        server.enqueue(json(201, """{"result_id":"r-1","status":"processing"}"""))

        val result = api.submitTest("VERTICAL_JUMP", 41.5, "video-1", 172.0)

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("VERTICAL_JUMP", sent.getString("test_id"))
        assertEquals("video-1", sent.getString("video_id"))
        assertEquals(172.0, sent.getDouble("athlete_height_cm"), 0.0)
        assertEquals("r-1", (result as ApiResult.Success).value)
    }

    @Test
    fun `a result with a provisional benchmark is parsed with its caveat`() {
        server.enqueue(
            json(
                200,
                """{"result_id":"r-1","test_type":"SIT_UPS","status":"verified","provisional_score":30,
                   "server_score":28,"final_score":null,"unit":"reps","verified_at":null,"flags":[],
                   "benchmark":{"band":"below_average","label":"Keep training","percentile":null,
                     "percentile_50":34,"percentile_75":40,"percentile_90":46,"next_target":34,
                     "cohort":"male, 14-15","unit":"reps","source":"PROVISIONAL placeholder","provisional":true},
                   "benchmark_unavailable":null}"""
            )
        )

        val result = (api.result("r-1") as ApiResult.Success).value

        assertEquals(28.0, result.serverScore!!, 0.0)
        assertNull(result.finalScore)
        val benchmark = result.benchmark!!
        assertNull("No percentile is invented below the median", benchmark.percentile)
        assertTrue(benchmark.provisional)
        assertEquals(34.0, benchmark.nextTarget!!, 0.0)
    }

    @Test
    fun `an unverified result explains why there is no benchmark`() {
        server.enqueue(
            json(
                200,
                """{"result_id":"r-1","test_type":"SIT_UPS","status":"processing","provisional_score":30,
                   "server_score":null,"final_score":null,"unit":"reps","flags":[],"benchmark":null,
                   "benchmark_unavailable":"This result has not been verified by SAI yet"}"""
            )
        )

        val result = (api.result("r-1") as ApiResult.Success).value

        assertNull(result.benchmark)
        assertEquals("This result has not been verified by SAI yet", result.benchmarkUnavailable)
    }

    @Test
    fun `validation errors surface the server's message`() {
        server.enqueue(json(422, """{"detail":[{"loc":["body","height_cm"],"msg":"Input should be less than 260"}]}"""))

        val result = api.profile() as ApiResult.Failure

        assertEquals(ApiFailure.INVALID, result.kind)
        assertEquals("Input should be less than 260", result.message)
    }

    @Test
    fun `a dropped connection is a network failure, not a crash`() {
        // Twice: OkHttp transparently retries a dropped connection once.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = api.summary() as ApiResult.Failure

        assertEquals(ApiFailure.NETWORK, result.kind)
        assertTrue(result.kind.isTransient)
    }

    @Test
    fun `a garbled body is a server failure, not a crash`() {
        server.enqueue(json(200, "<html>proxy login page</html>"))

        val result = api.profile() as ApiResult.Failure

        assertEquals(ApiFailure.SERVER, result.kind)
    }

    @Test
    fun `status codes map to failure kinds`() {
        assertEquals(ApiFailure.UNAUTHORIZED, ApiFailure.forStatus(401))
        assertEquals(ApiFailure.FORBIDDEN, ApiFailure.forStatus(403))
        assertEquals(ApiFailure.NOT_FOUND, ApiFailure.forStatus(404))
        assertEquals(ApiFailure.CONFLICT, ApiFailure.forStatus(409))
        assertEquals(ApiFailure.INVALID, ApiFailure.forStatus(422))
        assertEquals(ApiFailure.RATE_LIMITED, ApiFailure.forStatus(429))
        assertEquals(ApiFailure.SERVER, ApiFailure.forStatus(503))
    }
}
