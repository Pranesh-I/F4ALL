package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the sit-up state machine.
 *
 * These run on synthetic geometry, so they verify the machine does what it is
 * specified to do. Whether the thresholds themselves suit real bodies is a
 * different question, answered by the Sprint 3.4 validation harness against
 * reference video.
 */
class SitUpAnalyzerTest {

    /** Ascent slow enough to clear the minimum-duration guard, as a real rep is. */
    private val ascentFrames = 40
    private val descentFrames = 40

    private fun analyze(frames: List<PoseFrame>): AnalyzerResult =
        SitUpAnalyzer().analyzeSequence(frames)

    private fun repSequence(repCount: Int): List<PoseFrame> {

        val frames = mutableListOf<PoseFrame>()
        var cursor = 0L

        // Settle into the start position first — the machine will not count
        // anything until it has seen the athlete lying down.
        frames += PoseFixtures.hold(cursor, 155.0, 10)
        cursor += 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        repeat(repCount) {
            val rep = PoseFixtures.sitUpRep(
                startMs = cursor,
                ascentFrames = ascentFrames,
                descentFrames = descentFrames
            )
            frames += rep
            cursor = rep.last().timestampMs + PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

            // Rest at the bottom between reps.
            frames += PoseFixtures.hold(cursor, 155.0, 8)
            cursor += 8 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
        }

        return frames
    }

    @Test
    fun `counts five clean reps`() {

        val result = analyze(repSequence(5))

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(5.0, result.score, 0.0)
        assertEquals("reps", result.unit)
    }

    @Test
    fun `counts a single rep once, not once per frame past the threshold`() {

        val result = analyze(repSequence(1))

        assertEquals(1.0, result.score, 0.0)
    }

    @Test
    fun `partial rep is not counted but is reported`() {

        val frames = mutableListOf<PoseFrame>()
        var cursor = 0L

        frames += PoseFixtures.hold(cursor, 155.0, 10)
        cursor += 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        // Up to 95 degrees — past the partial mark, short of the 70 needed.
        frames += PoseFixtures.sweep(cursor, 155.0, 95.0, ascentFrames)
        cursor += ascentFrames * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        frames += PoseFixtures.sweep(cursor, 95.0, 155.0, descentFrames)
        cursor += descentFrames * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        frames += PoseFixtures.hold(cursor, 155.0, 10)

        val analyzer = SitUpAnalyzer()
        val result = analyzer.analyzeSequence(frames)

        assertEquals(0.0, result.score, 0.0)
        assertEquals(1, analyzer.rejectedPartialReps())
        assertTrue(
            "Athlete should be told why the rep did not count",
            result.events.any { it.label == "rep_rejected_partial" }
        )
    }

    @Test
    fun `rep faster than a human is rejected`() {

        val frames = mutableListOf<PoseFrame>()
        var cursor = 0L

        frames += PoseFixtures.hold(cursor, 155.0, 10)
        cursor += 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        // Four frames of ascent is about 130ms — a shaken phone, not a sit-up.
        frames += PoseFixtures.sweep(cursor, 155.0, 55.0, 4)
        cursor += 4 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        frames += PoseFixtures.sweep(cursor, 55.0, 155.0, 4)
        cursor += 4 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        frames += PoseFixtures.hold(cursor, 155.0, 10)

        val result = analyze(frames)

        assertEquals(0.0, result.score, 0.0)
        assertTrue(
            result.events.any { it.label == "rep_rejected_too_fast" }
        )
    }

    @Test
    fun `fast cadence reps are counted`() {

        // The SAI sit-up test scores maximum reps in a fixed window, so a fast
        // athlete is the normal case, not an edge case. A 660ms ascent used to
        // score zero because the minimum-duration guard was set too high —
        // silently deleting the reps of the strongest athletes.
        val frames = mutableListOf<PoseFrame>()
        var cursor = 0L

        frames += PoseFixtures.hold(cursor, 155.0, 10)
        cursor += 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        repeat(10) {
            val rep = PoseFixtures.sitUpRep(
                startMs = cursor,
                ascentFrames = 20,
                descentFrames = 20
            )
            frames += rep
            cursor = rep.last().timestampMs + PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

            frames += PoseFixtures.hold(cursor, 155.0, 4)
            cursor += 4 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
        }

        val result = analyze(frames)

        assertEquals(10.0, result.score, 0.0)
    }

    @Test
    fun `jitter around the down threshold does not count reps`() {

        val frames = mutableListOf<PoseFrame>()
        var cursor = 0L

        frames += PoseFixtures.hold(cursor, 155.0, 10)
        cursor += 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        // Twenty crossings of the DOWN threshold. Without hysteresis, a single
        // threshold here would produce a stream of phantom reps.
        repeat(20) {
            frames += PoseFixtures.sweep(cursor, 140.0, 128.0, 3)
            cursor += 3 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
            frames += PoseFixtures.sweep(cursor, 128.0, 140.0, 3)
            cursor += 3 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
        }

        val result = analyze(frames)

        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun `sequence with no start position is invalid`() {

        // Athlete stays half-way up throughout; the machine never arms.
        val frames = PoseFixtures.hold(0, 100.0, 60)

        val result = analyze(frames)

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.contains("Start position"))
    }

    @Test
    fun `fully occluded sequence is invalid and counts rejected frames`() {

        val frames = (0 until 60).map {
            PoseFixtures.occludedFrame(it * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS)
        }

        val result = analyze(frames)

        assertEquals(AttemptStatus.INVALID, result.status)
        assertEquals(0, result.framesAnalyzed)
        assertEquals(60, result.framesRejected)
    }

    @Test
    fun `losing tracking mid-attempt lowers confidence`() {

        val clean = analyze(repSequence(3))

        val interrupted = mutableListOf<PoseFrame>()
        interrupted += PoseFixtures.hold(0, 155.0, 10)

        var cursor = 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

        // A full second of lost tracking in the middle of the attempt.
        repeat(30) {
            interrupted += PoseFixtures.occludedFrame(cursor)
            cursor += PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
        }

        interrupted += PoseFixtures.sitUpRep(cursor, ascentFrames, descentFrames)

        val result = analyze(interrupted)

        assertTrue(
            "Confidence should reflect that reps may have happened unseen: " +
                "${result.confidence} vs clean ${clean.confidence}",
            result.confidence < clean.confidence
        )
    }

    @Test
    fun `reset clears state between attempts`() {

        val analyzer = SitUpAnalyzer()

        analyzer.analyzeSequence(repSequence(3))
        val second = analyzer.analyzeSequence(repSequence(1))

        assertEquals(1.0, second.score, 0.0)
    }

    @Test
    fun `confidence is high for a cleanly tracked attempt`() {

        val result = analyze(repSequence(3))

        assertTrue(
            "Expected high confidence, got ${result.confidence}",
            result.confidence > 0.9
        )
    }
}
