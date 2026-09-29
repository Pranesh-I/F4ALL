package com.sai.sports.analyzer

/**
 * The F4ALL test battery.
 *
 * Declaration order is display order. Room stores the name, never the ordinal,
 * so reordering is safe; renaming a constant is not — it is the stable code the
 * server keys verification off.
 */
enum class TestType(
    val displayName: String,
    val unit: String
) {
    SQUATS("Squats", "reps"),
    PUSH_UPS("Push-ups", "reps"),
    BICEP_CURLS("Bicep Curls", "reps"),
    LUNGES("Lunges", "reps"),
    VERTICAL_JUMP("Vertical Jump", "cm"),
    SIT_UPS("Sit-ups", "reps");

    /** Scored by counting repetitions, rather than by a measurement. */
    val countsReps: Boolean
        get() = unit == "reps"

    /**
     * Whether a bigger score is a better one. True for every test in the
     * current battery (reps, jump height); a timed test such as the shuttle
     * run will not be, and personal bests must not assume otherwise.
     */
    val higherIsBetter: Boolean
        get() = true

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
        if (testType.countsReps) score.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", score)

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

    /** Whether the start position has been seen, so reps or jumps can now score. */
    fun isReady(): Boolean

    /**
     * Trace events recorded so far, skipping the first [fromIndex]. Lets live
     * coaching react to each rep as it is judged without copying the whole
     * trace every frame.
     */
    fun eventsSince(fromIndex: Int): List<AnalyzerEvent>

    /** Finalize and return the attempt result. */
    fun result(): AnalyzerResult

    /** Clear all state for a fresh attempt. */
    fun reset()
}

/**
 * The analyzer that scores [testType].
 *
 * [athleteHeightCm] is required for [TestType.VERTICAL_JUMP] — without it there
 * is no way to turn normalized displacement into centimetres — and ignored by
 * every other test.
 */
fun analyzerFor(testType: TestType, athleteHeightCm: Double?): TestAnalyzer =
    when (testType) {
        TestType.SQUATS -> SquatAnalyzer()
        TestType.PUSH_UPS -> PushUpAnalyzer()
        TestType.BICEP_CURLS -> BicepCurlAnalyzer()
        TestType.LUNGES -> LungeAnalyzer()
        TestType.SIT_UPS -> SitUpAnalyzer()
        TestType.VERTICAL_JUMP -> VerticalJumpAnalyzer(
            athleteHeightCm = athleteHeightCm
                ?: error("Vertical jump needs the athlete's height to calibrate")
        )
    }

/** Runs an analyzer over a complete recorded sequence. Used by the validation harness. */
fun TestAnalyzer.analyzeSequence(frames: List<PoseFrame>): AnalyzerResult {
    reset()
    for (frame in frames) {
        onFrame(frame)
    }
    return result()
}
