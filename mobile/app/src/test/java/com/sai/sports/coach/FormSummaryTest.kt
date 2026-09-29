package com.sai.sports.coach

import com.sai.sports.R
import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.BicepCurlAnalyzer
import com.sai.sports.analyzer.FormIssue
import com.sai.sports.analyzer.PushUpAnalyzer
import com.sai.sports.analyzer.RepFixtures
import com.sai.sports.analyzer.TestType
import com.sai.sports.analyzer.analyzeSequence
import com.sai.sports.ui.common.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSummaryTest {

    @Test
    fun `clean reps score full marks with nothing to work on`() {
        val events = (1..4).map { AnalyzerEvent(it * 1_000L, "rep_counted") }

        val summary = FormSummary.from(events)!!

        assertEquals(4, summary.attemptedReps)
        assertEquals(4, summary.goodReps)
        assertEquals(100, summary.scorePercent)
        assertTrue(summary.toWorkOn.isEmpty())
    }

    @Test
    fun `warned and refused reps lower the score and name the commonest fault first`() {
        val events = listOf(
            AnalyzerEvent(1_000, "rep_counted"),
            AnalyzerEvent(2_000, "rep_counted"),
            AnalyzerEvent(2_000, "form_warning", "torso_lean"),
            AnalyzerEvent(3_000, "rep_rejected_partial"),
            AnalyzerEvent(4_000, "rep_rejected_partial"),
            AnalyzerEvent(5_000, "rep_counted"),
            AnalyzerEvent(5_000, "form_warning", "torso_lean"),
            AnalyzerEvent(6_000, "rep_rejected_partial")
        )

        val summary = FormSummary.from(events)!!

        assertEquals(6, summary.attemptedReps)
        assertEquals(1, summary.goodReps)
        assertEquals(16, summary.scorePercent)
        assertEquals(
            listOf(CoachCue.GoDeeper, CoachCue.Fault(FormIssue.TORSO_LEAN)),
            summary.toWorkOn
        )
    }

    @Test
    fun `an attempt with no reps has no form summary`() {
        assertNull(FormSummary.from(emptyList()))
        assertNull(FormSummary.from(listOf(AnalyzerEvent(0, "jump_measured"))))
    }

    @Test
    fun `summaries come straight from a stored attempt's trace`() {
        val sagging = PushUpAnalyzer().analyzeSequence(RepFixtures.run(3, RepFixtures.pushUp(hipOffset = 0.1)))
        assertEquals(listOf(CoachCue.Fault(FormIssue.HIPS_SAGGING)), FormSummary.from(sagging.events)!!.toWorkOn)
        assertEquals(0, FormSummary.from(sagging.events)!!.scorePercent)

        val swinging = BicepCurlAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.curl(right = true, maxSway = 0.08))
        )
        val summary = FormSummary.from(swinging.events)!!
        assertEquals(3, summary.attemptedReps)
        assertEquals(0, summary.goodReps)
        assertEquals(listOf(CoachCue.Fault(FormIssue.BODY_SWING)), summary.toWorkOn)
    }

    @Test
    fun `every cue has words for every test`() {
        val cues = FormIssue.entries.map { CoachCue.Fault(it) } + listOf(
            CoachCue.Counted(1), CoachCue.GoDeeper, CoachCue.TooFast, CoachCue.WrongArm, CoachCue.StartPosition
        )
        TestType.entries.forEach { type -> cues.forEach { Labels.cue(it, type) } }

        // Each fault gets its own advice, not a shared catch-all.
        assertEquals(FormIssue.entries.size, FormIssue.entries.map(Labels::formIssue).toSet().size)

        // Depth advice names the movement.
        assertEquals(R.string.cue_depth_curl, Labels.cue(CoachCue.GoDeeper, TestType.BICEP_CURLS))
        assertEquals(R.string.cue_depth_situp, Labels.cue(CoachCue.GoDeeper, TestType.SIT_UPS))
    }
}
