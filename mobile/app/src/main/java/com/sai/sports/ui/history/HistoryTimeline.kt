package com.sai.sports.ui.history

import com.sai.sports.api.HistoryItem
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptMode
import com.sai.sports.fieldtest.FieldAttempt

/** One row of the athlete's history: an official result SAI holds, or a practice attempt. */
sealed interface HistoryEntry {
    val atMs: Long
    val key: String

    data class Official(val item: HistoryItem) : HistoryEntry {
        override val atMs: Long get() = item.createdAtMs
        override val key: String get() = "official:${item.resultId}"
    }

    data class Practice(val attempt: Attempt) : HistoryEntry {
        override val atMs: Long get() = attempt.recordedAtMs
        override val key: String get() = "practice:${attempt.id}"
    }

    /** A shuttle or endurance run practised on this phone or another (Sprint 18). */
    data class FieldPractice(val attempt: FieldAttempt) : HistoryEntry {
        override val atMs: Long get() = attempt.recordedAtMs
        override val key: String get() = "field:${attempt.id}"
    }
}

enum class HistoryFilter { ALL, OFFICIAL, PRACTICE }

/**
 * Official results and practice attempts as one list, newest first.
 *
 * Official results arrive from the server a page at a time; practice is all on
 * the phone. While older official pages are still to come, a practice attempt
 * older than the oldest official result loaded would sit above results not yet
 * fetched — so it is held back until those pages arrive. The list is therefore
 * always in true date order, however far the athlete has scrolled.
 */
object HistoryTimeline {

    fun merge(
        official: List<HistoryItem>,
        moreOfficialToLoad: Boolean,
        attempts: List<Attempt>,
        filter: HistoryFilter = HistoryFilter.ALL,
        fieldAttempts: List<FieldAttempt> = emptyList()
    ): List<HistoryEntry> {
        val officialEntries =
            if (filter == HistoryFilter.PRACTICE) emptyList() else official.map { HistoryEntry.Official(it) }

        val practice =
            if (filter == HistoryFilter.OFFICIAL) {
                emptyList()
            } else {
                // Only practice: an official attempt is listed from SAI's record of it.
                attempts.filter { it.mode == AttemptMode.PRACTICE }.map { HistoryEntry.Practice(it) } +
                    fieldAttempts.filter { it.mode == AttemptMode.PRACTICE }.map { HistoryEntry.FieldPractice(it) }
            }

        val horizon = if (moreOfficialToLoad && filter == HistoryFilter.ALL) {
            official.minOfOrNull { it.createdAtMs } ?: Long.MAX_VALUE
        } else {
            null
        }
        val shownPractice = if (horizon == null) practice else practice.filter { it.atMs >= horizon }

        return (officialEntries + shownPractice).sortedByDescending { it.atMs }
    }
}
