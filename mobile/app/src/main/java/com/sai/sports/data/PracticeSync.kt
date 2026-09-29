package com.sai.sports.data

import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.F4allApi
import com.sai.sports.api.PracticeEvent
import com.sai.sports.api.PracticeUpload

/**
 * Keeps an athlete's practice history in step with their account.
 *
 *  - Push: practice results recorded on this phone that have not reached the
 *    account yet. Idempotent on the attempt id, so repeating it is harmless.
 *  - Pull: practice recorded on the athlete's other phones, saved here so the
 *    history, personal bests and form feedback are complete — and still there
 *    offline afterwards.
 *
 * Only the result and the analyzer trace travel; the video and the skeleton
 * stay on the phone that recorded them. Blocking: call from a background
 * thread.
 */
class PracticeSync(
    private val store: AttemptStore,
    private val api: F4allApi
) {

    data class Outcome(
        val pushed: Int,
        val pulled: Int,
        /** True when something failed for a reason worth retrying (no signal, server down). */
        val retryLater: Boolean
    ) {
        val changedHistory: Boolean get() = pulled > 0
    }

    fun sync(athleteId: String): Outcome {
        val push = push(athleteId)
        val pull = pull(athleteId)
        return Outcome(
            pushed = push.first,
            pulled = pull.first,
            retryLater = push.second || pull.second
        )
    }

    /** Returns (uploaded, should retry). */
    private fun push(athleteId: String): Pair<Int, Boolean> {

        var pushed = 0
        var retry = false

        val pending = store.listAttempts(athleteId)
            .filter { it.mode == AttemptMode.PRACTICE && !it.savedToAccount }

        for (attempt in pending) {
            when (val result = api.savePractice(toUpload(attempt))) {
                is ApiResult.Success -> {
                    store.update(attempt.copy(savedToAccount = true))
                    pushed++
                }
                is ApiResult.Failure -> {
                    if (result.kind.isTransient || result.kind == ApiFailure.UNAUTHORIZED) {
                        // No point hammering a dead link attempt by attempt.
                        retry = true
                        break
                    }
                    // Refused outright (e.g. a test this server does not know
                    // yet). It stays on the phone, unsynced, and is tried again
                    // next time rather than retried in a loop now.
                }
            }
        }

        return pushed to retry
    }

    /** Returns (saved locally, should retry). */
    private fun pull(athleteId: String): Pair<Int, Boolean> {

        var pulled = 0
        var before: Long? = null

        repeat(MAX_PAGES) {
            val page = when (val result = api.practiceHistory(beforeMs = before, limit = PAGE_SIZE)) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> {
                    val retry = result.kind.isTransient || result.kind == ApiFailure.UNAUTHORIZED
                    return pulled to retry
                }
            }

            for (remote in page) {
                val existing = store.load(remote.clientAttemptId)
                // Already here — ours, with its skeleton — or, on a shared
                // phone, another athlete's attempt that happens to share the
                // id. Either way it is not overwritten.
                if (existing != null) continue

                val attempt = fromRemote(remote, athleteId) ?: continue
                if (store.save(attempt, frames = emptyList())) pulled++
            }

            if (page.size < PAGE_SIZE) return pulled to false
            before = page.minOf { it.recordedAtMs }
        }

        return pulled to false
    }

    private fun toUpload(attempt: Attempt) = PracticeUpload(
        clientAttemptId = attempt.id,
        testType = attempt.testType.name,
        score = attempt.result.score,
        unit = attempt.result.unit,
        status = attempt.result.status.name,
        confidence = attempt.result.confidence,
        invalidReason = attempt.result.invalidReason,
        recordedAtMs = attempt.recordedAtMs,
        events = attempt.result.events.map { PracticeEvent(it.timestampMs, it.label, it.detail) }
    )

    /** Null for anything this version of the app cannot represent, such as a newer test. */
    private fun fromRemote(remote: PracticeUpload, athleteId: String): Attempt? {

        val testType = TestType.entries.firstOrNull { it.name == remote.testType } ?: return null
        val status = AttemptStatus.entries.firstOrNull { it.name == remote.status } ?: return null

        return Attempt(
            id = remote.clientAttemptId,
            testType = testType,
            // No video: practice videos never leave the phone that recorded them.
            videoFileName = "",
            recordedAtMs = remote.recordedAtMs,
            mode = AttemptMode.PRACTICE,
            athleteId = athleteId,
            savedToAccount = true,
            result = AnalyzerResult(
                testType = testType,
                score = remote.score,
                unit = remote.unit,
                status = status,
                confidence = remote.confidence,
                framesAnalyzed = 0,
                framesRejected = 0,
                invalidReason = remote.invalidReason,
                events = remote.events.map { AnalyzerEvent(it.timestampMs, it.label, it.detail) }
            )
        )
    }

    private companion object {
        const val PAGE_SIZE = 200

        /** 4,000 attempts: years of daily practice, and a hard stop on a runaway loop. */
        const val MAX_PAGES = 20
    }
}
