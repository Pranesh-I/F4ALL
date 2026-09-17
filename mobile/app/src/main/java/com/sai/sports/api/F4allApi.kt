package com.sai.sports.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The backend's account, profile and result endpoints.
 *
 * Free of Android imports for the same reason as `UploadClient`: every response
 * shape and failure mapping here can be verified against MockWebServer on the
 * JVM, without a device or a running server.
 *
 * Calls block. Callers run them on `Dispatchers.IO`.
 */
class F4allApi(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val accessToken: () -> String? = { null }
) {

    // -- auth ---------------------------------------------------------------

    fun requestOtp(phone: String): ApiResult<OtpRequested> =
        post("/api/auth/request-otp", JSONObject().put("phone", phone), auth = false) {
            OtpRequested(
                developmentCode = it.optStringOrNull("development_code")
            )
        }

    fun verifyOtp(phone: String, otp: String): ApiResult<TokenPair> =
        post(
            "/api/auth/verify-otp",
            JSONObject().put("phone", phone).put("otp", otp),
            auth = false,
            parse = ::tokenPair
        )

    fun refresh(refreshToken: String): ApiResult<TokenPair> =
        post(
            "/api/auth/refresh",
            JSONObject().put("refresh_token", refreshToken),
            auth = false,
            parse = ::tokenPair
        )

    fun logout(refreshToken: String?): ApiResult<Unit> =
        post(
            "/api/auth/logout",
            JSONObject().apply { refreshToken?.let { put("refresh_token", it) } }
        ) { }

    // -- profile ------------------------------------------------------------

    /**
     * Returns the new profile AND a fresh athlete session. The registering token
     * that authorised this call names a phone, not an athlete; the server revokes
     * it, and every later request must use the returned tokens.
     */
    fun register(registration: Registration): ApiResult<Registered> =
        post(
            "/api/athletes/register",
            JSONObject()
                .put("name", registration.name)
                .put("dob", registration.dateOfBirthIso)
                .put("gender", registration.gender)
                .put("region", registration.region)
                .putOpt("height_cm", registration.heightCm)
                .putOpt("weight_kg", registration.weightKg)
        ) { json ->
            Registered(
                profile = athleteProfile(json.getJSONObject("profile")),
                tokens = tokenPair(json.getJSONObject("tokens"))
            )
        }

    fun profile(): ApiResult<AthleteProfile> =
        get("/api/athletes/me", parse = ::athleteProfile)

    fun uploadReferencePhoto(jpeg: ByteArray): ApiResult<Unit> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "reference.jpg",
                jpeg.toRequestBody(JPEG.toMediaType())
            )
            .build()

        return execute(
            request("/api/athletes/me/photo", auth = true).post(body).build()
        ) { }
    }

    /** Only the fields given are changed. */
    fun updatePreferences(
        leaderboardOptIn: Boolean? = null,
        preferredLanguage: String? = null
    ): ApiResult<AthleteProfile> {
        val body = JSONObject()
            .putOpt("leaderboard_opt_in", leaderboardOptIn)
            .putOpt("preferred_language", preferredLanguage)
        return execute(
            request("/api/athletes/me", auth = true)
                .patch(body.toString().toRequestBody(JSON.toMediaType()))
                .build(),
            ::athleteProfile
        )
    }

    fun badges(): ApiResult<BadgeSummary> =
        get("/api/athletes/me/badges") { json ->
            BadgeSummary(
                badges = json.getJSONArray("badges").objects().map {
                    Badge(
                        code = it.getString("code"),
                        earned = it.getBoolean("earned"),
                        progress = it.optInt("progress", 0),
                        target = it.optInt("target", 1)
                    )
                },
                currentStreakWeeks = json.getInt("current_streak_weeks"),
                longestStreakWeeks = json.getInt("longest_streak_weeks")
            )
        }

    /** [scope] is "region" (the athlete's own state) or "national". */
    fun leaderboard(testType: String, scope: String): ApiResult<Leaderboard> =
        get("/api/athletes/leaderboard/$testType?scope=$scope") { json ->
            Leaderboard(
                testType = json.getString("test_type"),
                unit = json.getString("unit"),
                region = json.optStringOrNull("region"),
                cohort = json.getString("cohort"),
                entries = json.getJSONArray("entries").objects().map {
                    LeaderboardEntry(
                        rank = it.getInt("rank"),
                        displayName = it.getString("display_name"),
                        region = it.getString("region"),
                        score = it.getDouble("score"),
                        isYou = it.optBoolean("is_you", false)
                    )
                },
                you = json.optJSONObject("you")?.let {
                    YourStanding(
                        rank = it.getInt("rank"),
                        score = it.getDouble("score"),
                        visibleToOthers = it.getBoolean("visible_to_others")
                    )
                },
                totalRanked = json.optInt("total_ranked", 0)
            )
        }

    fun summary(): ApiResult<AthleteSummary> =
        get("/api/athletes/me/summary") { json ->
            AthleteSummary(
                profile = athleteProfile(json.getJSONObject("profile")),
                personalBests = json.getJSONArray("personal_bests").objects().map {
                    PersonalBest(
                        testType = it.getString("test_type"),
                        unit = it.getString("unit"),
                        score = it.getDouble("score"),
                        official = it.getBoolean("official")
                    )
                },
                history = json.getJSONArray("history").objects().map {
                    HistoryItem(
                        resultId = it.getString("result_id"),
                        testType = it.getString("test_type"),
                        unit = it.getString("unit"),
                        status = it.getString("status"),
                        provisionalScore = it.optDoubleOrNull("provisional_score"),
                        serverScore = it.optDoubleOrNull("server_score"),
                        finalScore = it.optDoubleOrNull("final_score"),
                        createdAt = it.getString("created_at")
                    )
                }
            )
        }

    // -- tests --------------------------------------------------------------

    fun submitTest(
        testType: String,
        provisionalScore: Double,
        videoId: String,
        heightCm: Double?
    ): ApiResult<String> =
        post(
            "/api/tests/submit",
            JSONObject()
                .put("test_id", testType)
                .put("provisional_score", provisionalScore)
                .put("video_id", videoId)
                .putOpt("athlete_height_cm", heightCm)
        ) { it.getString("result_id") }

    fun result(resultId: String): ApiResult<ServerResult> =
        get("/api/results/$resultId") { json ->
            ServerResult(
                resultId = json.getString("result_id"),
                testType = json.getString("test_type"),
                status = json.getString("status"),
                unit = json.getString("unit"),
                provisionalScore = json.optDoubleOrNull("provisional_score"),
                serverScore = json.optDoubleOrNull("server_score"),
                finalScore = json.optDoubleOrNull("final_score"),
                benchmark = json.optJSONObject("benchmark")?.let(::benchmark),
                benchmarkUnavailable = json.optStringOrNull("benchmark_unavailable"),
                review = json.optJSONObject("latest_review")?.let {
                    ReviewNote(
                        action = it.getString("action"),
                        notes = it.optStringOrNull("notes")
                    )
                }
            )
        }

    // -- plumbing -----------------------------------------------------------

    private fun <T> get(path: String, parse: (JSONObject) -> T): ApiResult<T> =
        execute(request(path, auth = true).get().build(), parse)

    private fun <T> post(
        path: String,
        body: JSONObject,
        auth: Boolean = true,
        parse: (JSONObject) -> T
    ): ApiResult<T> =
        execute(
            request(path, auth)
                .post(body.toString().toRequestBody(JSON.toMediaType()))
                .build(),
            parse
        )

    private fun request(path: String, auth: Boolean): Request.Builder {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
        if (auth) {
            accessToken()?.let { builder.header("Authorization", "Bearer $it") }
        }
        return builder
    }

    private fun <T> execute(request: Request, parse: (JSONObject) -> T): ApiResult<T> =
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                    ApiResult.Success(parse(json))
                } else {
                    ApiResult.Failure(
                        kind = ApiFailure.forStatus(response.code),
                        message = detailOf(text) ?: "Request failed (${response.code})",
                        retryAfterSeconds = response.header("Retry-After")?.toIntOrNull()
                    )
                }
            }
        } catch (error: IOException) {
            ApiResult.Failure(
                ApiFailure.NETWORK,
                "No connection. Check your signal and try again."
            )
        } catch (error: org.json.JSONException) {
            ApiResult.Failure(ApiFailure.SERVER, "Unexpected response from the server")
        }

    /** FastAPI puts a human-readable reason in `detail`; validation errors nest it. */
    private fun detailOf(text: String): String? =
        runCatching {
            when (val detail = JSONObject(text).opt("detail")) {
                is String -> detail
                is JSONArray -> detail.optJSONObject(0)?.optString("msg")
                else -> null
            }
        }.getOrNull()

    private fun tokenPair(json: JSONObject) = TokenPair(
        accessToken = json.getString("access_token"),
        refreshToken = json.getString("refresh_token"),
        expiresInSeconds = json.getLong("expires_in"),
        athleteId = json.optStringOrNull("athlete_id"),
        registered = json.optBoolean("registered", true)
    )

    private fun athleteProfile(json: JSONObject) = AthleteProfile(
        athleteId = json.getString("athlete_id"),
        name = json.getString("name"),
        ageYears = json.getInt("age_years"),
        gender = json.getString("gender"),
        region = json.getString("region"),
        heightCm = json.optDoubleOrNull("height_cm"),
        weightKg = json.optDoubleOrNull("weight_kg"),
        hasReferencePhoto = json.optBoolean("has_reference_photo", false),
        leaderboardOptIn = json.optBoolean("leaderboard_opt_in", false),
        preferredLanguage = json.optString("preferred_language", "en")
    )

    private fun benchmark(json: JSONObject) = Benchmark(
        band = json.getString("band"),
        label = json.getString("label"),
        percentile = if (json.isNull("percentile")) null else json.getInt("percentile"),
        nextTarget = json.optDoubleOrNull("next_target"),
        cohort = json.getString("cohort"),
        unit = json.getString("unit"),
        source = json.getString("source"),
        provisional = json.getBoolean("provisional")
    )

    companion object {
        private const val JSON = "application/json"
        private const val JPEG = "image/jpeg"

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { getJSONObject(it) }
