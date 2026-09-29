package com.sai.sports.coach

import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.FormIssue
import com.sai.sports.analyzer.RepFixtures
import com.sai.sports.analyzer.SquatAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormCoachTest {

    private val coach = FormCoach()

    private fun update(
        timestampMs: Long,
        issues: Set<FormIssue> = emptySet(),
        events: List<AnalyzerEvent> = emptyList(),
        ready: Boolean = true
    ) = coach.update(timestampMs, ready, issues, events)

    /** Feeds [issues] every 33 ms from [fromMs] to [toMs] inclusive; returns every update. */
    private fun hold(fromMs: Long, toMs: Long, issues: Set<FormIssue>): List<CoachUpdate> =
        (fromMs..toMs step 33).map { update(it, issues) }

    private val sagging = setOf(FormIssue.HIPS_SAGGING)

    @Test
    fun `a clean rep shows praise and says the count`() {
        val result = update(1_000, events = listOf(AnalyzerEvent(1_000, "rep_counted", "Rep 1")))

        assertEquals(CoachCue.Counted(1), result.display)
        assertEquals(listOf(CoachCue.Counted(1)), result.speak)
    }

    @Test
    fun `counts keep climbing and are never held back`() {
        val spoken = (1..5).flatMap { rep ->
            // Reps 200 ms apart: well inside every cooldown.
            update(rep * 200L, events = listOf(AnalyzerEvent(rep * 200L, "rep_counted"))).speak
        }

        assertEquals((1..5).map { CoachCue.Counted(it) }, spoken)
    }

    @Test
    fun `a momentary fault is ignored`() {
        val updates = hold(0, FormCoach.ISSUE_ONSET_MS - 50, sagging) + update(FormCoach.ISSUE_ONSET_MS)

        assertTrue(updates.all { it.display == null && it.speak.isEmpty() })
    }

    @Test
    fun `a persistent fault is shown and said once, then again after the cooldown`() {
        val first = hold(0, 2_000, sagging)

        val fault = CoachCue.Fault(FormIssue.HIPS_SAGGING)
        assertEquals(fault, first.last().display)
        assertEquals(1, first.count { it.speak == listOf(fault) })

        // Still sagging past the cooldown: the reminder comes back, once.
        val later = hold(2_033, FormCoach.REPEAT_COOLDOWN_MS + 500, sagging)
        assertEquals(1, later.count { it.speak == listOf(fault) })
    }

    @Test
    fun `a rep refused for depth says why`() {
        val result = update(500, events = listOf(AnalyzerEvent(500, "rep_rejected_partial")))

        assertEquals(CoachCue.GoDeeper, result.display)
        assertEquals(listOf(CoachCue.GoDeeper), result.speak)
    }

    @Test
    fun `a rep refused for form names the rejecting fault`() {
        val result = update(
            500,
            events = listOf(AnalyzerEvent(500, "rep_rejected_form", "torso_lean,hips_sagging"))
        )

        assertEquals(CoachCue.Fault(FormIssue.HIPS_SAGGING), result.display)
    }

    @Test
    fun `a counted rep with a warning says the count then the advice`() {
        val result = update(
            500,
            events = listOf(
                AnalyzerEvent(500, "rep_counted", "Rep 1"),
                AnalyzerEvent(500, "form_warning", "torso_lean")
            )
        )

        val warning = CoachCue.Fault(FormIssue.TORSO_LEAN)
        assertEquals(warning, result.display)
        assertEquals(listOf(CoachCue.Counted(1), warning), result.speak)
    }

    @Test
    fun `advice already given mid-rep is not repeated in the verdict`() {
        val midRep = hold(0, 1_000, setOf(FormIssue.TORSO_LEAN))
        assertEquals(1, midRep.count { it.speak.isNotEmpty() })

        val verdict = update(
            1_100,
            events = listOf(
                AnalyzerEvent(1_100, "rep_counted"),
                AnalyzerEvent(1_100, "form_warning", "torso_lean")
            )
        )

        assertEquals(listOf(CoachCue.Counted(1)), verdict.speak)
    }

    @Test
    fun `two different pieces of advice do not talk over each other`() {
        hold(0, 500, setOf(FormIssue.TORSO_LEAN))

        val next = hold(533, 1_000, setOf(FormIssue.FEET_MOVED))

        assertTrue(next.all { it.speak.isEmpty() })
    }

    @Test
    fun `a more serious fault takes the screen from a lesser one`() {
        hold(0, 500, setOf(FormIssue.TORSO_LEAN))

        val updates = hold(533, 1_200, setOf(FormIssue.TORSO_LEAN, FormIssue.HIPS_SAGGING))

        assertEquals(CoachCue.Fault(FormIssue.HIPS_SAGGING), updates.last().display)
    }

    @Test
    fun `the start prompt waits, then repeats slowly`() {
        val early = (0L..2_900L step 100).map { update(it, ready = false) }
        assertTrue(early.all { it.display == null })

        val prompts = (3_000L..20_000L step 100).map { update(it, ready = false) }
        assertEquals(CoachCue.StartPosition, prompts.first().display)
        assertEquals(3, prompts.count { CoachCue.StartPosition in it.speak })
    }

    @Test
    fun `faults before the start position is held are not coached`() {
        val updates = (0L..1_000L step 33).map { update(it, sagging, ready = false) }

        assertTrue(updates.none { it.display is CoachCue.Fault })
    }

    @Test
    fun `a cue leaves the screen once it is no longer true`() {
        update(0, events = listOf(AnalyzerEvent(0, "rep_counted")))

        assertNull(update(FormCoach.DISPLAY_HOLD_MS + 1).display)
    }

    @Test
    fun `reset starts the count again`() {
        update(0, events = listOf(AnalyzerEvent(0, "rep_counted")))
        coach.reset()

        val result = update(100, events = listOf(AnalyzerEvent(100, "rep_counted")))

        assertEquals(CoachCue.Counted(1), result.display)
    }

    @Test
    fun `coaching a real squat set says every count and rations the advice`() {
        // Six leaning squats back to back, fed exactly as the capture session does.
        val analyzer = SquatAnalyzer()
        var seen = 0
        val spoken = mutableListOf<CoachCue>()

        RepFixtures.run(6, RepFixtures.squat(maxLean = 70.0)).forEach { frame ->
            analyzer.onFrame(frame)
            val events = analyzer.eventsSince(seen)
            seen += events.size
            spoken += coach.update(frame.timestampMs, analyzer.isReady(), analyzer.currentIssues(), events).speak
        }

        assertEquals((1..6).map { CoachCue.Counted(it) }, spoken.filterIsInstance<CoachCue.Counted>())

        // ~6 s of leaning: said at the start and once more after the cooldown,
        // not once per rep.
        val advice = spoken.count { it == CoachCue.Fault(FormIssue.TORSO_LEAN) }
        assertTrue("Advice said $advice times", advice in 1..2)
    }
}
