package com.sai.sports.data

import android.content.Context
import android.util.Log
import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.PoseSequenceCodec
import com.sai.sports.analyzer.TestType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Why an attempt was recorded, which decides where it may go.
 *
 * PRACTICE attempts are the athlete's own: kept on the phone for history and
 * personal bests, never queued for upload, never an official submission.
 * OFFICIAL attempts go to SAI. Attempts saved before modes existed were all
 * uploaded, so an attempt with no mode on disk reads as OFFICIAL.
 */
enum class AttemptMode {
    PRACTICE,
    OFFICIAL;

    companion object {
        fun fromName(name: String?): AttemptMode =
            entries.firstOrNull { it.name == name } ?: OFFICIAL
    }
}

/**
 * One recorded attempt: the video, the pose sequence, and the provisional score.
 */
data class Attempt(
    val id: String,
    val testType: TestType,
    val videoFileName: String,
    val recordedAtMs: Long,
    val result: AnalyzerResult,
    /** Source frame dimensions, so the results screen can replay at the right aspect ratio. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val mode: AttemptMode = AttemptMode.OFFICIAL,
    /** Who recorded it. Null only for attempts saved before accounts were tracked. */
    val athleteId: String? = null,
    /** For practice: whether the result has reached the athlete's account yet. */
    val savedToAccount: Boolean = false,
    /** For an official attempt: the assessment session it was recorded for. */
    val sessionId: String? = null
)

/**
 * Flat-file storage for attempts.
 *
 * Deliberately not Room. Sprint 4 owns the local database, complete with the
 * sync-status state machine the upload queue needs, and building a throwaway
 * schema here would only have to be migrated away from. What Sprint 3 needs is
 * for the results screen to survive process death, and files do that.
 *
 * Layout under filesDir:
 *
 *     videos/test_<ts>.mp4          the recording
 *     sequences/test_<ts>.csv       pose frames, for replay and offline tuning
 *     attempts/test_<ts>.json       the provisional result
 */
class AttemptStore(
    /** Normally the app's files directory; a temporary folder in tests. */
    private val root: File
) {

    constructor(context: Context) : this(context.filesDir)

    fun save(
        attempt: Attempt,
        frames: List<PoseFrame>
    ): Boolean = try {

        directory(SEQUENCES_DIR)
            .resolve("${attempt.id}.csv")
            .writeText(
                PoseSequenceCodec.encode(
                    frames = frames,
                    testName = attempt.testType.displayName
                )
            )

        directory(ATTEMPTS_DIR)
            .resolve("${attempt.id}.json")
            .writeText(toJson(attempt).toString())

        true

    } catch (exception: Exception) {
        // A failed save must not take the results screen down with it — the
        // athlete still gets their score, it just will not survive a restart.
        Log.e(TAG, "Failed to save attempt ${attempt.id}", exception)
        false
    }

    fun load(attemptId: String): Attempt? = try {

        val file = directory(ATTEMPTS_DIR).resolve("$attemptId.json")

        if (file.exists()) {
            fromJson(JSONObject(file.readText()))
        } else {
            null
        }

    } catch (exception: Exception) {
        Log.e(TAG, "Failed to load attempt $attemptId", exception)
        null
    }

    fun loadSequence(attemptId: String): List<PoseFrame> = try {

        val file = directory(SEQUENCES_DIR).resolve("$attemptId.csv")

        if (file.exists()) {
            PoseSequenceCodec.decode(file.readText())
        } else {
            emptyList()
        }

    } catch (exception: Exception) {
        Log.e(TAG, "Failed to load sequence $attemptId", exception)
        emptyList()
    }

    /**
     * Rewrites just the result JSON, e.g. to record that a practice attempt
     * reached the athlete's account. The pose sequence is left alone.
     */
    fun update(attempt: Attempt): Boolean = try {
        directory(ATTEMPTS_DIR)
            .resolve("${attempt.id}.json")
            .writeText(toJson(attempt).toString())
        true
    } catch (exception: Exception) {
        Log.e(TAG, "Failed to update attempt ${attempt.id}", exception)
        false
    }

    /** Whether an attempt with this id is already on the phone. */
    fun exists(attemptId: String): Boolean =
        directory(ATTEMPTS_DIR).resolve("$attemptId.json").exists()

    /** Only [athleteId]'s attempts, newest first. Nobody else's are ever shown. */
    fun listAttempts(athleteId: String): List<Attempt> =
        listAttempts().filter { it.athleteId == athleteId }

    /**
     * Newest first, by when the attempt was recorded — not by file time, which
     * a backup restore or a re-save would reorder.
     */
    fun listAttempts(): List<Attempt> =
        directory(ATTEMPTS_DIR)
            .listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    fromJson(JSONObject(file.readText()))
                } catch (exception: Exception) {
                    Log.e(TAG, "Skipping unreadable attempt ${file.name}", exception)
                    null
                }
            }
            ?.sortedByDescending { it.recordedAtMs }
            ?: emptyList()

    fun videoFile(attempt: Attempt): File =
        directory(VIDEOS_DIR).resolve(attempt.videoFileName)

    /**
     * Deletes the attempt's video, keeping its result and pose sequence.
     *
     * Practice videos are never uploaded and the results screen replays the
     * skeleton, not the video, so keeping them would only fill the phone —
     * one HD minute at a time, with unlimited attempts.
     */
    fun deleteVideo(attempt: Attempt): Boolean {
        val file = videoFile(attempt)
        return !file.exists() || file.delete()
    }

    private fun directory(name: String): File =
        File(root, name).apply {
            if (!exists()) mkdirs()
        }

    private fun toJson(attempt: Attempt): JSONObject {

        val events = JSONArray()
        attempt.result.events.forEach { event ->
            events.put(
                JSONObject()
                    .put("timestampMs", event.timestampMs)
                    .put("label", event.label)
                    .put("detail", event.detail)
            )
        }

        return JSONObject()
            .put("id", attempt.id)
            .put("testType", attempt.testType.name)
            .put("videoFileName", attempt.videoFileName)
            .put("recordedAtMs", attempt.recordedAtMs)
            .put("imageWidth", attempt.imageWidth)
            .put("imageHeight", attempt.imageHeight)
            .put("mode", attempt.mode.name)
            .put("athleteId", attempt.athleteId ?: JSONObject.NULL)
            .put("savedToAccount", attempt.savedToAccount)
            .put("sessionId", attempt.sessionId ?: JSONObject.NULL)
            .put("score", attempt.result.score)
            .put("unit", attempt.result.unit)
            .put("status", attempt.result.status.name)
            .put("confidence", attempt.result.confidence)
            .put("framesAnalyzed", attempt.result.framesAnalyzed)
            .put("framesRejected", attempt.result.framesRejected)
            .put("invalidReason", attempt.result.invalidReason ?: JSONObject.NULL)
            .put("events", events)
    }

    private fun fromJson(json: JSONObject): Attempt {

        val events = mutableListOf<AnalyzerEvent>()
        val eventArray = json.optJSONArray("events")

        if (eventArray != null) {
            for (index in 0 until eventArray.length()) {
                val item = eventArray.getJSONObject(index)
                events += AnalyzerEvent(
                    timestampMs = item.optLong("timestampMs"),
                    label = item.optString("label"),
                    detail = item.optString("detail")
                )
            }
        }

        val testType = TestType.valueOf(json.getString("testType"))

        return Attempt(
            id = json.getString("id"),
            testType = testType,
            videoFileName = json.optString("videoFileName"),
            recordedAtMs = json.optLong("recordedAtMs"),
            imageWidth = json.optInt("imageWidth"),
            imageHeight = json.optInt("imageHeight"),
            mode = AttemptMode.fromName(json.optString("mode").takeIf { it.isNotEmpty() }),
            athleteId = json.optString("athleteId").takeIf { it.isNotEmpty() && it != "null" },
            savedToAccount = json.optBoolean("savedToAccount", false),
            sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() && it != "null" },
            result = AnalyzerResult(
                testType = testType,
                score = json.optDouble("score", 0.0),
                unit = json.optString("unit", testType.unit),
                status = AttemptStatus.valueOf(
                    json.optString("status", AttemptStatus.INVALID.name)
                ),
                confidence = json.optDouble("confidence", 0.0),
                framesAnalyzed = json.optInt("framesAnalyzed"),
                framesRejected = json.optInt("framesRejected"),
                invalidReason = json.optString("invalidReason").takeIf {
                    it.isNotEmpty() && it != "null"
                },
                events = events
            )
        )
    }

    companion object {
        private const val TAG = "AttemptStore"
        const val VIDEOS_DIR = "videos"
        const val SEQUENCES_DIR = "sequences"
        const val ATTEMPTS_DIR = "attempts"
    }
}
