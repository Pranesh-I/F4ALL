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
    val imageHeight: Int = 0
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
    private val context: Context
) {

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

    /** Newest first. Sprint 7's athlete profile screen reads this. */
    fun listAttempts(): List<Attempt> =
        directory(ATTEMPTS_DIR)
            .listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                try {
                    fromJson(JSONObject(file.readText()))
                } catch (exception: Exception) {
                    Log.e(TAG, "Skipping unreadable attempt ${file.name}", exception)
                    null
                }
            }
            ?: emptyList()

    fun videoFile(attempt: Attempt): File =
        directory(VIDEOS_DIR).resolve(attempt.videoFileName)

    private fun directory(name: String): File =
        File(context.filesDir, name).apply {
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
