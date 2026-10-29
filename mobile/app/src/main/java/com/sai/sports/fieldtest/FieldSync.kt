package com.sai.sports.fieldtest

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.F4allApi
import com.sai.sports.api.PracticeEvent
import com.sai.sports.api.PracticeUpload
import com.sai.sports.data.AttemptMode

/**
 * Sends the signed-in athlete's field attempts where they belong.
 *
 * * An official run goes to SAI with its trace; once SAI has answered, the
 *   trace is deleted from the phone.
 * * A practice run is saved to the athlete's account (result only, never the
 *   trace), and practice from their other phones is brought here.
 *
 * Free of Android so every branch is tested against a mock server.
 */
class FieldSync(private val store: FieldAttemptStore, private val api: F4allApi) {

    data class Outcome(val sent: Int, val retryLater: Boolean)

    fun sync(athleteId: String): Outcome {
        var sent = 0
        var retry = false
        for (attempt in store.list(athleteId)) {
            when {
                attempt.waitingToSend -> when (submit(attempt)) {
                    Step.DONE -> sent += 1
                    Step.RETRY -> retry = true
                    Step.SKIP -> Unit
                }
                attempt.mode == AttemptMode.PRACTICE && !attempt.savedToAccount -> when (savePractice(attempt)) {
                    Step.DONE -> sent += 1
                    Step.RETRY -> retry = true
                    Step.SKIP -> Unit
                }
            }
        }
        if (!pull(athleteId)) retry = true
        return Outcome(sent, retry)
    }

    private enum class Step { DONE, RETRY, SKIP }

    private fun submit(attempt: FieldAttempt): Step {
        val trace = store.trace(attempt.id)
        if (trace == null) {
            // The evidence is gone; SAI could not check it. Shown as not accepted.
            store.save(attempt.copy(notAccepted = true))
            return Step.SKIP
        }
        val answer = api.submitFieldTest(
            testType = attempt.testType.name,
            clientAttemptId = attempt.id,
            provisionalScore = attempt.result.score.takeIf { attempt.result.outcome == FieldOutcome.COMPLETE },
            motion = FieldAttemptStore.motionRows(trace.motion),
            fixes = FieldAttemptStore.fixRows(trace.fixes),
            sessionId = attempt.sessionId,
            identityCheckId = attempt.identityCheckId,
            recordedAtMs = attempt.recordedAtMs
        )
        return when (answer) {
            is ApiResult.Success -> {
                store.save(attempt.copy(resultId = answer.value.resultId, serverStatus = answer.value.status))
                store.deleteTrace(attempt.id)
                Step.DONE
            }
            is ApiResult.Failure -> when {
                answer.kind.isTransient || answer.kind == ApiFailure.UNAUTHORIZED -> Step.RETRY
                // Refused for good (the session slot is taken, the session closed):
                // sending it again will not change that.
                else -> {
                    store.save(attempt.copy(notAccepted = true))
                    Step.SKIP
                }
            }
        }
    }

    private fun savePractice(attempt: FieldAttempt): Step {
        val result = attempt.result
        val answer = api.savePractice(
            PracticeUpload(
                clientAttemptId = attempt.id,
                testType = attempt.testType.name,
                score = result.score,
                unit = attempt.testType.unit,
                status = result.outcome.name,
                confidence = if (result.outcome == FieldOutcome.COMPLETE) 1.0 else 0.0,
                invalidReason = result.invalid,
                recordedAtMs = attempt.recordedAtMs,
                events = result.events.map { PracticeEvent(it.tMs, it.label, it.detail) }
            )
        )
        return when (answer) {
            is ApiResult.Success -> {
                store.save(attempt.copy(savedToAccount = true))
                Step.DONE
            }
            is ApiResult.Failure -> if (answer.kind.isTransient) Step.RETRY else Step.SKIP
        }
    }

    /** Field practice from the athlete's other phones. True unless the network failed. */
    private fun pull(athleteId: String): Boolean {
        val answer = api.practiceHistory()
        if (answer !is ApiResult.Success) return answer is ApiResult.Failure && !answer.kind.isTransient
        val known = store.list(athleteId).map { it.id }.toSet()
        answer.value
            .filter { it.clientAttemptId !in known }
            .forEach { remote ->
                val type = FieldTestType.fromCode(remote.testType) ?: return@forEach
                store.save(
                    FieldAttempt(
                        id = remote.clientAttemptId,
                        testType = type,
                        mode = AttemptMode.PRACTICE,
                        athleteId = athleteId,
                        recordedAtMs = remote.recordedAtMs,
                        result = FieldResult(
                            testType = type,
                            outcome = if (remote.status == FieldOutcome.COMPLETE.name) FieldOutcome.COMPLETE
                            else FieldOutcome.INVALID,
                            score = remote.score,
                            invalid = remote.invalidReason,
                            events = remote.events.map { FieldEvent(it.timestampMs, it.label, it.detail) }
                        ),
                        savedToAccount = true
                    )
                )
            }
        return true
    }
}
