package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepCycleTrackerTest {

    private val thresholds = RepThresholds(
        extendedAngle = 160.0,
        depthAngle = 100.0,
        partialAngle = 135.0,
        minRepDurationMs = 400L
    )

    private val interval = 33L

    /** Feeds angles one per frame and returns every event emitted. */
    private fun feed(
        tracker: RepCycleTracker,
        angles: List<Double>,
        issuesAt: (Int) -> Set<FormIssue> = { emptySet() },
        inStartPose: Boolean = true
    ): List<RepEvent> =
        angles.mapIndexedNotNull { index, angle ->
            tracker.update(RepSample(angle, issuesAt(index), inStartPose), index * interval)
        }

    private fun ramp(from: Double, to: Double, frames: Int): List<Double> =
        (1..frames).map { from + (to - from) * it / frames }

    private val start = List(AnalyzerThresholds.REP_START_HOLD_FRAMES) { 175.0 }
    private val fullRep = ramp(175.0, 90.0, 12) + ramp(90.0, 175.0, 12)

    @Test
    fun `the start position must be held before anything counts`() {
        val tracker = RepCycleTracker(thresholds)

        // One frame short of the hold, then straight into a rep: the rep began
        // before the counter armed, so it must not count.
        val events = feed(
            tracker,
            List(AnalyzerThresholds.REP_START_HOLD_FRAMES - 1) { 175.0 } +
                ramp(150.0, 90.0, 12) + ramp(90.0, 175.0, 12)
        )

        assertTrue(events.isEmpty())
        assertEquals(RepCycleTracker.Phase.WAITING_FOR_START, tracker.phase)
    }

    @Test
    fun `a full rep is counted on the return to the top`() {
        val tracker = RepCycleTracker(thresholds)

        val events = feed(tracker, start + fullRep)

        assertTrue(events.first() is RepEvent.Ready)
        val counted = events.filterIsInstance<RepEvent.Counted>().single()
        // Counted on the first frame back above EXTENDED, not at the bottom.
        assertTrue(counted.timestampMs > (start.size + 12) * interval)
    }

    @Test
    fun `a rep that turns back before depth is a partial`() {
        val tracker = RepCycleTracker(thresholds)

        val events = feed(tracker, start + ramp(175.0, 120.0, 12) + ramp(120.0, 175.0, 12))

        val partial = events.filterIsInstance<RepEvent.Partial>().single()
        assertEquals(120.0, partial.deepestAngle, 0.001)
    }

    @Test
    fun `a small dip is neither a rep nor a partial`() {
        val tracker = RepCycleTracker(thresholds)

        val events = feed(tracker, start + ramp(175.0, 150.0, 6) + ramp(150.0, 175.0, 6))

        assertEquals(listOf(RepEvent.Ready::class), events.map { it::class })
    }

    @Test
    fun `a rep faster than the floor is rejected`() {
        val tracker = RepCycleTracker(thresholds)

        val events = feed(tracker, start + ramp(175.0, 90.0, 3) + ramp(90.0, 175.0, 3))

        assertEquals(1, events.filterIsInstance<RepEvent.TooFast>().size)
    }

    @Test
    fun `a rejecting fault must persist to reject the rep`() {
        val tracker = RepCycleTracker(thresholds)
        val repStart = start.size

        // One frame of fault is pose-model noise.
        val blip = feed(tracker, start + fullRep, issuesAt = {
            if (it == repStart + 6) setOf(FormIssue.HIPS_SAGGING) else emptySet()
        })
        assertEquals(1, blip.filterIsInstance<RepEvent.Counted>().size)

        tracker.reset()

        // Enough consecutive frames is the athlete.
        val sustained = feed(tracker, start + fullRep, issuesAt = {
            if (it in repStart + 6 until repStart + 6 + AnalyzerThresholds.FORM_FAULT_MIN_FRAMES) {
                setOf(FormIssue.HIPS_SAGGING)
            } else {
                emptySet()
            }
        })
        val rejected = sustained.filterIsInstance<RepEvent.FormRejected>().single()
        assertEquals(listOf(FormIssue.HIPS_SAGGING), rejected.issues)
    }

    @Test
    fun `a warning fault still counts the rep`() {
        val tracker = RepCycleTracker(thresholds)

        val events = feed(tracker, start + fullRep, issuesAt = { setOf(FormIssue.TORSO_LEAN) })

        val counted = events.filterIsInstance<RepEvent.Counted>().single()
        assertEquals(listOf(FormIssue.TORSO_LEAN), counted.warnings)
    }

    @Test
    fun `the start pose check blocks arming`() {
        val tracker = RepCycleTracker(thresholds)

        val events = feed(tracker, start + fullRep, inStartPose = false)

        assertTrue(events.isEmpty())
        assertEquals(RepCycleTracker.Phase.WAITING_FOR_START, tracker.phase)
    }

    @Test
    fun `reset returns the machine to waiting`() {
        val tracker = RepCycleTracker(thresholds)
        feed(tracker, start + ramp(175.0, 90.0, 6))

        tracker.reset()

        assertEquals(RepCycleTracker.Phase.WAITING_FOR_START, tracker.phase)
        assertNull(tracker.update(RepSample(90.0), 0))
    }
}
