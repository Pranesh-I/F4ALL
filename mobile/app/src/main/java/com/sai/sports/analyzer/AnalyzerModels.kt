package com.sai.sports.analyzer

/** The two tests in the MVP battery. Sprint 10 adds shuttle run and endurance run. */
enum class TestType(
    val displayName: String,
    val unit: String
) {
    SIT_UPS("Sit-ups", "reps"),
    VERTICAL_JUMP("Vertical Jump", "cm");

    companion object {
        fun fromDisplayName(name: String): TestType? =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
    }
}

enum class AttemptStatus {
    /** Still collecting frames. */
    IN_PROGRESS,

    /** Finished with a usable score. */
    COMPLETE,

    /** Finished with no usable score — see [AnalyzerResult.invalidReason]. */
    INVALID
}

/**
 * A notable moment during an attempt.
 *
 * This is the debug trace the README's Sprint 3 asks for, and it doubles as the
 * explanation shown to the athlete ("2 partial reps not counted") — which is
 * the difference between an app that feels broken and one that feels strict.
 */
data class AnalyzerEvent(
    val timestampMs: Long,
    val label: String,
    val detail: String = ""
)

/**
 * The provisional, on-device score for one attempt.
 *
 * PROVISIONAL is the operative word. Per the project's first invariant, this is
 * never the final score — the server independently re-scores the uploaded video
 * in Sprint 5 and its answer wins.
 */
data class AnalyzerResult(
    val testType: TestType,
    val score: Double,
    val unit: String,
    val status: AttemptStatus,
    /** 0.0..1.0, from landmark visibility and how many frames survived the quality gate. */
    val confidence: Double,
    val framesAnalyzed: Int,
    val framesRejected: Int,
    val invalidReason: String? = null,
    val events: List<AnalyzerEvent> = emptyList()
) {

    val isUsable: Boolean
        get() = status == AttemptStatus.COMPLETE

    /** Score formatted for display — reps are whole numbers, jump height is not. */
    fun formattedScore(): String =
        when (testType) {
            TestType.SIT_UPS -> score.toInt().toString()
            TestType.VERTICAL_JUMP -> String.format(java.util.Locale.US, "%.1f", score)
        }

    companion object {

        fun invalid(
            testType: TestType,
            reason: String,
            framesAnalyzed: Int = 0,
            framesRejected: Int = 0,
            events: List<AnalyzerEvent> = emptyList()
        ) = AnalyzerResult(
            testType = testType,
            score = 0.0,
            unit = testType.unit,
            status = AttemptStatus.INVALID,
            confidence = 0.0,
            framesAnalyzed = framesAnalyzed,
            framesRejected = framesRejected,
            invalidReason = reason,
            events = events
        )
    }
}

/**
 * Common shape for every test scorer.
 *
 * Analyzers are streaming: they are fed frames one at a time so the capture
 * screen can show a live count, and they can also be replayed over a recorded
 * sequence offline by the validation harness. Same code path either way — the
 * harness would be worthless if it tested a different implementation than the
 * one athletes run.
 */
interface TestAnalyzer {

    val testType: TestType

    /** Feed one frame. Safe to call with poor-quality frames — they are gated internally. */
    fun onFrame(frame: PoseFrame)

    /** The score as it stands right now, for live display. */
    fun currentScore(): Double

    /** Finalize and return the attempt result. */
    fun result(): AnalyzerResult

    /** Clear all state for a fresh attempt. */
    fun reset()
}

/** Runs an analyzer over a complete recorded sequence. Used by the validation harness. */
fun TestAnalyzer.analyzeSequence(frames: List<PoseFrame>): AnalyzerResult {
    reset()
    for (frame in frames) {
        onFrame(frame)
    }
    return result()
}
