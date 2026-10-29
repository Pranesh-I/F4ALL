package com.sai.sports.fieldtest

import android.content.Context
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.local.SessionAttemptRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One field-test attempt as the phone keeps it.
 *
 * [athleteId] makes it the signed-in athlete's own on a shared phone. An
 * official attempt keeps its raw trace until SAI has it ([resultId] set); a
 * practice attempt keeps only the result, as practice never leaves the
 * athlete's account.
 */
data class FieldAttempt(
    val id: String,
    val testType: FieldTestType,
    val mode: AttemptMode,
    val athleteId: String,
    val recordedAtMs: Long,
    val result: FieldResult,
    val sessionId: String? = null,
    val identityCheckId: String? = null,
    /** SAI's result for an official attempt, once submitted. */
    val resultId: String? = null,
    /** verified / flagged / rejected, as SAI answered the submission. */
    val serverStatus: String? = null,
    /** SAI refused the submission outright (for example, the session slot was taken). */
    val notAccepted: Boolean = false,
    /** Practice saved to the athlete's account. */
    val savedToAccount: Boolean = false
) {
    val waitingToSend: Boolean
        get() = mode == AttemptMode.OFFICIAL && resultId == null && !notAccepted
}

/** The raw evidence of a run: motion rows and GPS fixes. */
data class FieldTrace(val motion: List<Motion>, val fixes: List<Fix>)

/**
 * Field attempts and their traces, in app-private files.
 *
 * Plain JSON files, like [com.sai.sports.data.AttemptStore], rather than the
 * upload queue's Room table: a field attempt has no video to compress or
 * upload in chunks, just one small request.
 */
class FieldAttemptStore(private val root: File) {

    constructor(context: Context) : this(context.filesDir)

    fun save(attempt: FieldAttempt) {
        file(ATTEMPTS, "${attempt.id}.json").writeText(encode(attempt).toString())
    }

    fun saveTrace(attemptId: String, trace: FieldTrace) {
        file(TRACES, "$attemptId.json").writeText(encodeTrace(trace).toString())
    }

    fun trace(attemptId: String): FieldTrace? =
        file(TRACES, "$attemptId.json").takeIf { it.exists() }?.let { decodeTrace(JSONObject(it.readText())) }

    fun deleteTrace(attemptId: String) {
        file(TRACES, "$attemptId.json").delete()
    }

    fun load(attemptId: String): FieldAttempt? =
        file(ATTEMPTS, "$attemptId.json").takeIf { it.exists() }
            ?.let { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }

    /** The athlete's attempts, newest first. Never another athlete's. */
    fun list(athleteId: String): List<FieldAttempt> =
        directory(ATTEMPTS).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }
            .filter { it.athleteId == athleteId }
            .sortedByDescending { it.recordedAtMs }

    /** Official attempts as the session rules see them: sent or still waiting to send. */
    fun sessionRows(athleteId: String): List<SessionAttemptRow> =
        list(athleteId)
            .filter { it.mode == AttemptMode.OFFICIAL && it.sessionId != null && !it.notAccepted }
            .map { SessionAttemptRow(it.sessionId!!, it.testType.name, it.resultId) }

    private fun directory(name: String) = File(root, name).apply { mkdirs() }

    private fun file(directory: String, name: String) = File(directory(directory), name)

    companion object {
        private const val ATTEMPTS = "field_attempts"
        private const val TRACES = "field_traces"

        fun encode(attempt: FieldAttempt): JSONObject = JSONObject()
            .put("id", attempt.id)
            .put("testType", attempt.testType.name)
            .put("mode", attempt.mode.name)
            .put("athleteId", attempt.athleteId)
            .put("recordedAtMs", attempt.recordedAtMs)
            .put("result", encodeResult(attempt.result))
            .putOpt("sessionId", attempt.sessionId)
            .putOpt("identityCheckId", attempt.identityCheckId)
            .putOpt("resultId", attempt.resultId)
            .putOpt("serverStatus", attempt.serverStatus)
            .put("notAccepted", attempt.notAccepted)
            .put("savedToAccount", attempt.savedToAccount)

        fun decode(json: JSONObject): FieldAttempt = FieldAttempt(
            id = json.getString("id"),
            testType = FieldTestType.valueOf(json.getString("testType")),
            mode = AttemptMode.valueOf(json.getString("mode")),
            athleteId = json.getString("athleteId"),
            recordedAtMs = json.getLong("recordedAtMs"),
            result = decodeResult(json.getJSONObject("result")),
            sessionId = json.optString("sessionId").ifBlank { null },
            identityCheckId = json.optString("identityCheckId").ifBlank { null },
            resultId = json.optString("resultId").ifBlank { null },
            serverStatus = json.optString("serverStatus").ifBlank { null },
            notAccepted = json.optBoolean("notAccepted", false),
            savedToAccount = json.optBoolean("savedToAccount", false)
        )

        fun encodeResult(result: FieldResult): JSONObject = JSONObject()
            .put("testType", result.testType.name)
            .put("outcome", result.outcome.name)
            .put("score", result.score)
            .putOpt("invalid", result.invalid)
            .put("events", JSONArray().apply {
                result.events.forEach { put(JSONArray().put(it.tMs).put(it.label).put(it.detail)) }
            })
            .putOpt("turns", result.turns)
            .putOpt("legMs", result.legMs?.let { JSONArray(it) })
            .putOpt("distanceM", result.distanceM)
            .putOpt("paceSecondsPerKm", result.paceSecondsPerKm)
            .putOpt("cadenceSpm", result.cadenceSpm)
            .putOpt("gpsJumps", result.gpsJumps)

        fun decodeResult(json: JSONObject): FieldResult {
            val events = json.getJSONArray("events")
            val legs = json.optJSONArray("legMs")
            return FieldResult(
                testType = FieldTestType.valueOf(json.getString("testType")),
                outcome = FieldOutcome.valueOf(json.getString("outcome")),
                score = json.getDouble("score"),
                invalid = json.optString("invalid").ifBlank { null },
                events = (0 until events.length()).map {
                    val row = events.getJSONArray(it)
                    FieldEvent(row.getLong(0), row.getString(1), row.optString(2))
                },
                turns = if (json.has("turns")) json.getInt("turns") else null,
                legMs = legs?.let { array -> (0 until array.length()).map { array.getLong(it) } },
                distanceM = if (json.has("distanceM")) json.getDouble("distanceM") else null,
                paceSecondsPerKm = if (json.has("paceSecondsPerKm")) json.getDouble("paceSecondsPerKm") else null,
                cadenceSpm = if (json.has("cadenceSpm")) json.getDouble("cadenceSpm") else null,
                gpsJumps = if (json.has("gpsJumps")) json.getInt("gpsJumps") else null
            )
        }

        fun encodeTrace(trace: FieldTrace): JSONObject = JSONObject()
            .put("motion", motionRows(trace.motion))
            .put("fixes", fixRows(trace.fixes))

        fun decodeTrace(json: JSONObject): FieldTrace {
            val motion = json.getJSONArray("motion")
            val fixes = json.getJSONArray("fixes")
            return FieldTrace(
                motion = (0 until motion.length()).map {
                    val row = motion.getJSONArray(it)
                    Motion(
                        row.getLong(0), row.getDouble(1), row.getDouble(2), row.getDouble(3),
                        row.optDouble(4, 0.0), row.optDouble(5, 0.0), row.optDouble(6, 0.0)
                    )
                },
                fixes = (0 until fixes.length()).map {
                    val row = fixes.getJSONArray(it)
                    Fix(row.getLong(0), row.getDouble(1), row.getDouble(2), row.getDouble(3))
                }
            )
        }

        /** Rows as the server expects them; the gyroscope columns only where they carry something. */
        fun motionRows(motion: List<Motion>): JSONArray = JSONArray().apply {
            val withGyro = motion.any { it.gx != 0.0 || it.gy != 0.0 || it.gz != 0.0 }
            motion.forEach { m ->
                val row = JSONArray().put(m.tMs).put(m.ax).put(m.ay).put(m.az)
                if (withGyro) row.put(m.gx).put(m.gy).put(m.gz)
                put(row)
            }
        }

        fun fixRows(fixes: List<Fix>): JSONArray = JSONArray().apply {
            fixes.forEach { put(JSONArray().put(it.tMs).put(it.lat).put(it.lon).put(it.accuracyM)) }
        }
    }
}
