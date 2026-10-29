package com.sai.sports.ui

import com.sai.sports.R
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.FormIssue
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.HistoryItem
import com.sai.sports.api.PersonalBest
import com.sai.sports.api.ServerForm
import com.sai.sports.api.ServerFormFault
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptMode
import com.sai.sports.ui.history.HistoryEntry
import com.sai.sports.ui.history.HistoryFilter
import com.sai.sports.ui.history.HistoryTimeline
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.profile.ResultPresentation.Validity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sprint 15: what the result screen says about a result, and how history is ordered. */
class ResultsHistoryTest {

    // -----------------------------------------------------------------
    // Validity: the same rule the server uses for bests and benchmarks
    // -----------------------------------------------------------------

    @Test
    fun `only verified and approved results count`() {
        assertEquals(Validity.COUNTS, ResultPresentation.validity("verified"))
        assertEquals(Validity.COUNTS, ResultPresentation.validity("approved"))
        assertEquals(Validity.UNDER_REVIEW, ResultPresentation.validity("flagged"))
        assertEquals(Validity.DOES_NOT_COUNT, ResultPresentation.validity("rejected"))
        assertEquals(Validity.DOES_NOT_COUNT, ResultPresentation.validity("pending_sync"))
        assertEquals(Validity.PENDING, ResultPresentation.validity("uploaded"))
        assertEquals(Validity.PENDING, ResultPresentation.validity("processing"))
    }

    @Test
    fun `a missing benchmark is explained by the result's status`() {
        assertEquals(R.string.benchmark_not_verified, ResultPresentation.benchmarkUnavailable("processing"))
        assertEquals(R.string.benchmark_under_review, ResultPresentation.benchmarkUnavailable("flagged"))
        assertEquals(R.string.benchmark_not_counted, ResultPresentation.benchmarkUnavailable("rejected"))
        // A counting result without a comparison has no cohort to compare with.
        assertEquals(R.string.benchmark_no_cohort, ResultPresentation.benchmarkUnavailable("approved"))
    }

    @Test
    fun `server form faults are labelled, most reps first, unknown codes dropped`() {
        val form = ServerForm(
            score = 50,
            faults = listOf(
                ServerFormFault("TORSO_LEAN", 1),
                ServerFormFault("A_FAULT_FROM_A_NEWER_SERVER", 4),
                ServerFormFault("KNEE_PAST_TOES", 3)
            )
        )

        assertEquals(
            listOf(FormIssue.KNEE_PAST_TOES to 3, FormIssue.TORSO_LEAN to 1),
            ResultPresentation.formFaults(form)
        )
    }

    @Test
    fun `a personal best is this result only when the server says so`() {
        val best = PersonalBest("SIT_UPS", "reps", 30.0, official = true, resultId = "r-1")

        assertTrue(ResultPresentation.isPersonalBest(best, "r-1"))
        assertFalse(ResultPresentation.isPersonalBest(best, "r-2"))
        assertFalse(ResultPresentation.isPersonalBest(best.copy(resultId = null), "r-1"))
        assertFalse(ResultPresentation.isPersonalBest(null, "r-1"))
    }

    // -----------------------------------------------------------------
    // History: official and practice, in true date order
    // -----------------------------------------------------------------

    private fun official(id: String, atMs: Long) = HistoryItem(
        resultId = id, testType = "SQUATS", unit = "reps", status = "verified",
        provisionalScore = 10.0, serverScore = 10.0, finalScore = null,
        createdAt = atMs.toString(), createdAtMs = atMs
    )

    private fun attempt(id: String, atMs: Long, mode: AttemptMode = AttemptMode.PRACTICE) = Attempt(
        id = id,
        testType = TestType.SQUATS,
        videoFileName = "$id.mp4",
        recordedAtMs = atMs,
        mode = mode,
        athleteId = "asha",
        result = AnalyzerResult(TestType.SQUATS, 8.0, "reps", AttemptStatus.COMPLETE, 0.9, 100, 0)
    )

    private fun keys(entries: List<HistoryEntry>) = entries.map { it.key }

    @Test
    fun `official and practice interleave newest first`() {
        val entries = HistoryTimeline.merge(
            official = listOf(official("o2", 400), official("o1", 200)),
            moreOfficialToLoad = false,
            attempts = listOf(attempt("p1", 100), attempt("p2", 300), attempt("p3", 500))
        )

        assertEquals(
            listOf("practice:p3", "official:o2", "practice:p2", "official:o1", "practice:p1"),
            keys(entries)
        )
    }

    @Test
    fun `practice older than the loaded official page waits for the next page`() {
        val entries = HistoryTimeline.merge(
            official = listOf(official("o2", 400), official("o1", 200)),
            moreOfficialToLoad = true,
            attempts = listOf(attempt("p1", 100), attempt("p2", 300))
        )

        // p1 could be older than official results not fetched yet.
        assertEquals(listOf("official:o2", "practice:p2", "official:o1"), keys(entries))
    }

    @Test
    fun `before the first official page arrives no practice is placed`() {
        val entries = HistoryTimeline.merge(
            official = emptyList(),
            moreOfficialToLoad = true,
            attempts = listOf(attempt("p1", 100))
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `the practice filter shows all practice whatever is still to load`() {
        val entries = HistoryTimeline.merge(
            official = listOf(official("o1", 400)),
            moreOfficialToLoad = true,
            attempts = listOf(attempt("p1", 100), attempt("p2", 300)),
            filter = HistoryFilter.PRACTICE
        )
        assertEquals(listOf("practice:p2", "practice:p1"), keys(entries))
    }

    @Test
    fun `the official filter shows only official results`() {
        val entries = HistoryTimeline.merge(
            official = listOf(official("o1", 400)),
            moreOfficialToLoad = false,
            attempts = listOf(attempt("p1", 500)),
            filter = HistoryFilter.OFFICIAL
        )
        assertEquals(listOf("official:o1"), keys(entries))
    }

    @Test
    fun `a local copy of an official attempt is listed from SAI's record, not twice`() {
        val entries = HistoryTimeline.merge(
            official = listOf(official("o1", 400)),
            moreOfficialToLoad = false,
            attempts = listOf(attempt("local-official", 390, AttemptMode.OFFICIAL))
        )
        assertEquals(listOf("official:o1"), keys(entries))
    }
}
