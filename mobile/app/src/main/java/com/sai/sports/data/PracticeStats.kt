package com.sai.sports.data

import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.TestType

/** How one practice attempt compares with the athlete's practice before it. */
data class PracticeComparison(
    /** Better than every earlier scored practice attempt at this test, or the first one scored. */
    val isPersonalBest: Boolean,
    /** Whether any scored practice attempt came before this one. */
    val isFirstScored: Boolean,
    /** The best score before this attempt, if there was one. */
    val previousBest: Double?,
    /** This score minus the previous scored attempt's; null when there is nothing to compare. */
    val changeFromPrevious: Double?
)

/** One test's practice record, for the practice home screen. */
data class PracticeRecord(
    val testType: TestType,
    val attempts: Int,
    val best: Attempt?
)

/**
 * Personal bests and progress, computed from the attempts already on the phone.
 *
 * Only PRACTICE attempts count and only scored ones: an attempt the app could
 * not score has no number to be a best, and an official test is not practice.
 * Nothing is stored — a personal best is always recomputed from the attempts
 * themselves, so it can never disagree with the history the athlete sees.
 */
object PracticeStats {

    fun practiceAttempts(all: List<Attempt>, testType: TestType? = null): List<Attempt> =
        all.filter { it.mode == AttemptMode.PRACTICE && (testType == null || it.testType == testType) }
            .sortedByDescending { it.recordedAtMs }

    fun personalBest(all: List<Attempt>, testType: TestType): Attempt? =
        scored(all, testType).reduceOrNull { best, next -> if (isBetter(testType, next.result.score, best.result.score)) next else best }

    fun records(all: List<Attempt>): List<PracticeRecord> =
        TestType.entries.map { type ->
            PracticeRecord(
                testType = type,
                attempts = practiceAttempts(all, type).size,
                best = personalBest(all, type)
            )
        }

    /** Null when [attempt] is not a scored practice attempt — there is nothing to compare. */
    fun compare(attempt: Attempt, all: List<Attempt>): PracticeComparison? {

        if (attempt.mode != AttemptMode.PRACTICE || attempt.result.status != AttemptStatus.COMPLETE) {
            return null
        }

        val type = attempt.testType
        val earlier = scored(all, type)
            .filter { it.id != attempt.id && it.recordedAtMs < attempt.recordedAtMs }

        val previousBest = earlier.map { it.result.score }
            .reduceOrNull { best, next -> if (isBetter(type, next, best)) next else best }

        val previous = earlier.maxByOrNull { it.recordedAtMs }

        return PracticeComparison(
            isPersonalBest = previousBest == null || isBetter(type, attempt.result.score, previousBest),
            isFirstScored = earlier.isEmpty(),
            previousBest = previousBest,
            changeFromPrevious = previous?.let { attempt.result.score - it.result.score }
        )
    }

    private fun scored(all: List<Attempt>, testType: TestType): List<Attempt> =
        all.filter {
            it.mode == AttemptMode.PRACTICE &&
                it.testType == testType &&
                it.result.status == AttemptStatus.COMPLETE
        }

    /** Strictly better: equalling a best is not a new one. */
    private fun isBetter(testType: TestType, candidate: Double, current: Double): Boolean =
        if (testType.higherIsBetter) candidate > current else candidate < current
}
