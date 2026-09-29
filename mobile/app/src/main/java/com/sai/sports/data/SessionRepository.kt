package com.sai.sports.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.sai.sports.api.ActiveSessions
import com.sai.sports.api.ApiResult
import com.sai.sports.api.AssessmentSessionInfo
import com.sai.sports.api.F4allApi
import com.sai.sports.api.SessionTest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assessment sessions open to the signed-in athlete, fetched when there
 * is signal and remembered for when there is not.
 *
 * An athlete who opened the app at home, then walked to the ground with no
 * signal, must still see the session they came to take. The cache is per
 * athlete: on a shared phone, one athlete's regional sessions are not shown
 * to another.
 */
class SessionRepository(
    private val preferences: SharedPreferences,
    private val api: F4allApi,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime
) {

    constructor(context: Context, api: F4allApi) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        api
    )

    /**
     * Fetches the current list. Blocking; call from a background thread.
     * Returns the fresh list, or null when it could not be fetched — the
     * cached one stands.
     */
    fun refresh(athleteId: String): CachedSessions? =
        when (val result = api.activeSessions()) {
            is ApiResult.Success -> CachedSessions(
                sessions = result.value,
                fetchedAtWallMs = wallClock(),
                fetchedAtElapsedMs = elapsedClock()
            ).also { save(athleteId, it) }
            is ApiResult.Failure -> null
        }

    fun cached(athleteId: String): CachedSessions? =
        preferences.getString(key(athleteId), null)?.let { raw ->
            runCatching { decode(raw) }.getOrNull()
        }

    /** The session with [sessionId], if it is in this athlete's cached list. */
    fun session(athleteId: String, sessionId: String): AssessmentSessionInfo? =
        cached(athleteId)?.sessions?.sessions?.firstOrNull { it.id == sessionId }

    fun serverNow(cached: CachedSessions): Long =
        SessionAvailability.serverNow(cached, wallClock(), elapsedClock())

    fun wallNow(): Long = wallClock()

    fun elapsedNow(): Long = elapsedClock()

    private fun save(athleteId: String, cached: CachedSessions) {
        preferences.edit().putString(key(athleteId), encode(cached)).apply()
    }

    private fun key(athleteId: String) = "sessions.$athleteId"

    companion object {

        private const val PREFERENCES = "assessment_sessions"

        fun encode(cached: CachedSessions): String {
            val sessions = JSONArray()
            cached.sessions.sessions.forEach { session ->
                val tests = JSONArray()
                session.tests.forEach { test ->
                    tests.put(
                        JSONObject()
                            .put("testType", test.testType)
                            .put("unit", test.unit)
                            .put("submitted", test.submitted)
                            .put("resultStatus", test.resultStatus ?: JSONObject.NULL)
                    )
                }
                sessions.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("name", session.name)
                        .put("description", session.description ?: JSONObject.NULL)
                        .put("rules", session.rules ?: JSONObject.NULL)
                        .put("startsAtMs", session.startsAtMs)
                        .put("endsAtMs", session.endsAtMs)
                        .put("tests", tests)
                )
            }
            return JSONObject()
                .put("serverTimeMs", cached.sessions.serverTimeMs)
                .put("fetchedAtWallMs", cached.fetchedAtWallMs)
                .put("fetchedAtElapsedMs", cached.fetchedAtElapsedMs)
                .put("sessions", sessions)
                .toString()
        }

        fun decode(raw: String): CachedSessions {
            val json = JSONObject(raw)
            val sessions = json.getJSONArray("sessions")
            return CachedSessions(
                sessions = ActiveSessions(
                    serverTimeMs = json.getLong("serverTimeMs"),
                    sessions = (0 until sessions.length()).map { index ->
                        val session = sessions.getJSONObject(index)
                        val tests = session.getJSONArray("tests")
                        AssessmentSessionInfo(
                            id = session.getString("id"),
                            name = session.getString("name"),
                            description = session.optNullable("description"),
                            rules = session.optNullable("rules"),
                            startsAtMs = session.getLong("startsAtMs"),
                            endsAtMs = session.getLong("endsAtMs"),
                            tests = (0 until tests.length()).map { t ->
                                val test = tests.getJSONObject(t)
                                SessionTest(
                                    testType = test.getString("testType"),
                                    unit = test.getString("unit"),
                                    submitted = test.getBoolean("submitted"),
                                    resultStatus = test.optNullable("resultStatus")
                                )
                            }
                        )
                    }
                ),
                fetchedAtWallMs = json.getLong("fetchedAtWallMs"),
                fetchedAtElapsedMs = json.getLong("fetchedAtElapsedMs")
            )
        }

        private fun JSONObject.optNullable(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
