package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalJumpAnalyzerTest {

    private val athleteHeightCm = 170.0
    private val interval = PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

    private fun analyzer() = VerticalJumpAnalyzer(athleteHeightCm)

    /** Stand still, jump to [peakCm], land, stand still again. */
    private fun jumpSequence(
        peakCm: Double,
        arcFrames: Int = 20,
        scaleAfterLanding: Double = 1.0
    ): List<PoseFrame> {

        val frames = mutableListOf<PoseFrame>()

        val standing = PoseFixtures.standing(0)
        frames += standing

        var cursor = standing.last().timestampMs + interval

        val arc = PoseFixtures.jumpArc(
            startMs = cursor,
            peakCm = peakCm,
            athleteHeightCm = athleteHeightCm,
            frameCount = arcFrames
        )
        frames += arc

        cursor = arc.last().timestampMs + interval

        frames += PoseFixtures.standing(
            startMs = cursor,
            frameCount = 10,
            scale = scaleAfterLanding
        )

        return frames
    }

    @Test
    fun `measures a jump within the sprint 3 accuracy target`() {

        val expectedCm = 40.0

        val result = analyzer().analyzeSequence(jumpSequence(expectedCm))

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(
            "Measured ${result.score}cm against a ${expectedCm}cm arc",
            expectedCm,
            result.score,
            AnalyzerThresholds.TARGET_JUMP_TOLERANCE_CM
        )
        assertEquals("cm", result.unit)
    }

    @Test
    fun `measures jumps across the plausible range`() {

        listOf(15.0, 25.0, 40.0, 60.0).forEach { expectedCm ->

            val result = analyzer().analyzeSequence(jumpSequence(expectedCm))

            assertEquals(
                "Jump of ${expectedCm}cm measured as ${result.score}cm",
                expectedCm,
                result.score,
                AnalyzerThresholds.TARGET_JUMP_TOLERANCE_CM
            )
        }
    }

    @Test
    fun `standing still produces no jump`() {

        val result = analyzer().analyzeSequence(
            PoseFixtures.standing(0, frameCount = 60)
        )

        assertEquals(AttemptStatus.INVALID, result.status)
        assertEquals("No jump detected", result.invalidReason)
    }

    @Test
    fun `movement below the takeoff threshold is not a jump`() {

        // A 4cm bob, under the 6cm takeoff threshold.
        val result = analyzer().analyzeSequence(jumpSequence(4.0))

        assertEquals(AttemptStatus.INVALID, result.status)
    }

    @Test
    fun `best jump is reported when several are recorded`() {

        val frames = mutableListOf<PoseFrame>()

        val standing = PoseFixtures.standing(0)
        frames += standing
        var cursor = standing.last().timestampMs + interval

        listOf(22.0, 45.0, 30.0).forEach { peakCm ->

            val arc = PoseFixtures.jumpArc(
                startMs = cursor,
                peakCm = peakCm,
                athleteHeightCm = athleteHeightCm
            )
            frames += arc
            cursor = arc.last().timestampMs + interval

            val rest = PoseFixtures.standing(cursor, frameCount = 8)
            frames += rest
            cursor = rest.last().timestampMs + interval
        }

        val result = analyzer().analyzeSequence(frames)

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(45.0, result.score, AnalyzerThresholds.TARGET_JUMP_TOLERANCE_CM)
    }

    @Test
    fun `never settling means calibration never happens`() {

        // Athlete drifts continuously — the standing reference is never stable.
        val frames = (0 until 60).map { index ->
            PoseFixtures.jumpFrame(
                timestampMs = index * interval,
                displacementUnits = index * 0.004
            )
        }

        val result = analyzer().analyzeSequence(frames)

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.contains("standing reference"))
    }

    @Test
    fun `recording that stops mid-jump is rejected rather than guessed at`() {

        val frames = mutableListOf<PoseFrame>()

        val standing = PoseFixtures.standing(0)
        frames += standing

        val cursor = standing.last().timestampMs + interval

        // Only the ascent — the athlete is still in the air when frames stop.
        frames += PoseFixtures.jumpArc(
            startMs = cursor,
            peakCm = 40.0,
            athleteHeightCm = athleteHeightCm,
            frameCount = 20
        ).take(10)

        val result = analyzer().analyzeSequence(frames)

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.contains("mid-jump"))
    }

    @Test
    fun `losing the athlete mid-flight invalidates the attempt`() {

        val frames = mutableListOf<PoseFrame>()

        val standing = PoseFixtures.standing(0)
        frames += standing
        var cursor = standing.last().timestampMs + interval

        frames += PoseFixtures.jumpArc(
            startMs = cursor,
            peakCm = 40.0,
            athleteHeightCm = athleteHeightCm
        ).take(8)

        cursor += 8 * interval

        repeat(AnalyzerThresholds.MAX_CONSECUTIVE_REJECTED_FRAMES + 2) {
            frames += PoseFixtures.occludedFrame(cursor)
            cursor += interval
        }

        val result = analyzer().analyzeSequence(frames)

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.contains("mid-jump"))
    }

    @Test
    fun `athlete changing distance from the camera invalidates the measurement`() {

        // The body appears 25% larger after landing: they walked toward the
        // camera, so the calibrated pixel-to-cm ratio no longer holds.
        val result = analyzer().analyzeSequence(
            jumpSequence(peakCm = 40.0, scaleAfterLanding = 1.25)
        )

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.contains("moved"))
    }

    @Test
    fun `a taller athlete jumping the same normalized distance scores higher`() {

        // Same on-screen movement, different registered heights. The taller
        // athlete's body covers the same pixels, so each pixel is worth more cm.
        val shortAthlete = VerticalJumpAnalyzer(150.0)
        val tallAthlete = VerticalJumpAnalyzer(190.0)

        val frames = jumpSequence(40.0)

        val shortResult = shortAthlete.analyzeSequence(frames)
        val tallResult = tallAthlete.analyzeSequence(frames)

        assertTrue(
            "Taller athlete: ${tallResult.score}, shorter: ${shortResult.score}",
            tallResult.score > shortResult.score
        )
    }

    @Test
    fun `reset clears state between attempts`() {

        val analyzer = analyzer()

        analyzer.analyzeSequence(jumpSequence(50.0))
        val second = analyzer.analyzeSequence(jumpSequence(20.0))

        assertEquals(20.0, second.score, AnalyzerThresholds.TARGET_JUMP_TOLERANCE_CM)
    }
}
