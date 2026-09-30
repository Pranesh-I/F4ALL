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
    fun `an official submission names its session and when it was recorded`() {
        server.enqueue(json(201, """{"result_id":"r-9","status":"processing"}"""))

        api.submitTest("SQUATS", 15.0, "video-9", null, sessionId = "s-1", recordedAtMs = 1_700_000_000_000)

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("s-1", sent.getString("session_id"))
        assertEquals(1_700_000_000_000, sent.getLong("recorded_at_ms"))
    }

    @Test
    fun `a submission carries the phone's form score only when it has one`() {
        server.enqueue(json(201, """{"result_id":"r-9","status":"uploaded"}"""))
        server.enqueue(json(201, """{"result_id":"r-10","status":"uploaded"}"""))

        api.submitTest("SQUATS", 15.0, "video-9", null, provisionalFormScore = 80)
        api.submitTest("VERTICAL_JUMP", 41.0, "video-10", 170.0)

        assertEquals(80, JSONObject(server.takeRequest().body.readUtf8()).getInt("provisional_form_score"))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("provisional_form_score"))
    }

    @Test
    fun `an official submission names the photo check taken before it`() {
        server.enqueue(json(201, """{"result_id":"r-9","status":"processing"}"""))

        api.submitTest("SQUATS", 15.0, "video-9", null, sessionId = "s-1", identityCheckId = "c-7")

        assertEquals("c-7", JSONObject(server.takeRequest().body.readUtf8()).getString("identity_check_id"))
    }

    @Test
    fun `the photo check sends the photo, when it was taken and the session`() {
        server.enqueue(json(201, """{"check_id":"c-1","outcome":"no_face","remaining_this_hour":10}"""))

        val result = (api.identityCheck(byteArrayOf(1, 2, 3), 1_700_000_000_000, "s-1") as ApiResult.Success).value

        val request = server.takeRequest()
        assertEquals("/api/athletes/me/identity-checks", request.path)
        assertEquals("Bearer token-123", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("name=\"captured_at_ms\""))
        assertTrue(body.contains("1700000000000"))
        assertTrue(body.contains("name=\"session_id\""))
        assertEquals(IdentityCheckResult("c-1", "no_face", 10), result)
        assertFalse(result.matched)
    }

    @Test
    fun `consent is sent with its purpose, and the profile says what is missing`() {
        server.enqueue(
            json(
                200,
                """{"athlete_id":"a","name":"A","dob":"2011-01-01","age_years":15,"gender":"female",
                   "region":"Kerala","phone":"9","height_cm":150,"weight_kg":null,"has_reference_photo":false,
                   "city":"Kochi","place":null,"achievements":"District relay",
                   "consents":["face_verification","registration"],"missing":["photo"]}"""
            )
        )

        val profile = (api.giveConsent(
            ConsentPurpose.FACE_VERIFICATION,
            ConsentGrant("2026-09", "guardian", "R. Nair")
        ) as ApiResult.Success).value

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("face_verification", sent.getString("purpose"))
        assertEquals("R. Nair", sent.getString("guardian_name"))
        assertEquals("Kochi", profile.city)
        assertNull(profile.place)
        assertEquals("District relay", profile.achievements)
        assertEquals(listOf("face_verification", "registration"), profile.consents)
        assertEquals(listOf("photo"), profile.missing)
        assertFalse(profile.complete)
    }

    @Test
    fun `withdrawing face consent is a delete of that consent`() {
        server.enqueue(
            json(
                200,
                """{"athlete_id":"a","name":"A","dob":"2011-01-01","age_years":15,"gender":"female",
                   "region":"Kerala","phone":"9","height_cm":150,"weight_kg":null,"has_reference_photo":false,
                   "consents":["registration"],"missing":["face_consent","photo"]}"""
            )
        )

        api.withdrawFaceConsent()

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/athletes/me/consents/face_verification", request.path)
    }

    @Test
    fun `a profile from an older server reads as complete-unknown, not a crash`() {
        server.enqueue(
            json(
                200,
                """{"athlete_id":"a","name":"A","dob":"2011-01-01","age_years":15,"gender":"female",
                   "region":"Kerala","phone":"9","height_cm":150,"weight_kg":null,"has_reference_photo":true}"""
            )
        )

        val profile = (api.profile() as ApiResult.Success).value

        assertNull(profile.city)
        assertEquals(emptyList<String>(), profile.missing)
    }

    @Test
    fun `a submission outside any session sends no session`() {
        server.enqueue(json(201, """{"result_id":"r-9","status":"processing"}"""))

        api.submitTest("SQUATS", 15.0, "video-9", null)

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(sent.has("session_id"))
    }

    @Test
    fun `active sessions are read with their tests and windows`() {
        server.enqueue(
            json(
                200,
                """{"server_time":"2026-10-01T10:00:00.123456+00:00","sessions":[{"session_id":"s-1",
                "name":"Trials","description":null,"rules":null,"starts_at":"2026-10-01T09:00:00Z",
                "ends_at":"2026-10-01T11:00:00Z","tests":[{"test_type":"LUNGES","unit":"reps",
                "submitted":true,"result_status":"verified"}]}]}"""
            )
        )

        val sessions = (api.activeSessions() as ApiResult.Success).value

        assertEquals("/api/sessions/active", server.takeRequest().path)
        assertEquals(java.time.Instant.parse("2026-10-01T10:00:00.123Z").toEpochMilli(), sessions.serverTimeMs)
        val session = sessions.sessions.single()
        assertNull(session.description)
        assertEquals(7_200_000L, session.endsAtMs - session.startsAtMs)
        assertTrue(session.tests.single().submitted)
        assertEquals("verified", session.tests.single().resultStatus)
    }

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
            Registration(
                name = "A",
                dateOfBirthIso = "2008-01-01",
                gender = "male",
                region = "Kerala",
                city = "Kochi",
                place = null,
                heightCm = 170.0,
                weightKg = null,
                achievements = null,
                consent = ConsentGrant("2026-09", "guardian", "R. Nair")
            )
        ) as ApiResult.Success).value

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("2008-01-01", sent.getString("dob"))
        assertEquals("Kochi", sent.getString("city"))
        assertFalse("A missing weight is omitted, not sent as null", sent.has("weight_kg"))
        assertFalse(sent.has("place"))
        val consent = sent.getJSONObject("consent")
        assertEquals("guardian", consent.getString("given_by"))
        assertEquals("R. Nair", consent.getString("guardian_name"))
        assertEquals("2026-09", consent.getString("version"))

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
