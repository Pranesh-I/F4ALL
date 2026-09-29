package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the squat, push-up, bicep curl and lunge analyzers on synthetic
 * sequences. One section per exercise, each covering: clean reps count, the
 * rejection rules fire, and a missing start position is reported.
 */
class RepExerciseAnalyzersTest {

    private fun AnalyzerResult.count(label: String) = events.count { it.label == label }

    private fun AnalyzerResult.details(label: String) =
        events.filter { it.label == label }.map { it.detail }

    // -----------------------------------------------------------------
    // Squat
    // -----------------------------------------------------------------

    @Test
    fun `squat counts clean reps`() {
        val result = SquatAnalyzer().analyzeSequence(RepFixtures.run(5, RepFixtures.squat()))

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(5.0, result.score, 0.0)
        assertEquals("reps", result.unit)
        assertEquals(0, result.count("form_warning"))
        assertTrue(result.confidence > 0.9)
    }

    @Test
    fun `squat rejects half squats`() {
        val result = SquatAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.squat(bottomAngle = 120.0))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(3, result.count("rep_rejected_partial"))
    }

    @Test
    fun `squat rejects bounced reps`() {
        val result = SquatAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.squat(), downFrames = 3, upFrames = 3)
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(3, result.count("rep_rejected_too_fast"))
    }

    @Test
    fun `squat forward lean is a warning, not a rejection`() {
        val result = SquatAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.squat(maxLean = 70.0))
        )

        assertEquals(3.0, result.score, 0.0)
        assertEquals(listOf("torso_lean", "torso_lean", "torso_lean"), result.details("form_warning"))
    }

    @Test
    fun `squat knees travelling past the toes is a warning`() {
        val result = SquatAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.squat(maxShinTilt = 40.0))
        )

        assertEquals(3.0, result.score, 0.0)
        assertEquals(List(3) { "knee_past_toes" }, result.details("form_warning"))
    }

    @Test
    fun `squat feet sliding during a rep is a warning`() {
        val result = SquatAnalyzer().analyzeSequence(
            RepFixtures.run(2, RepFixtures.squat(maxAnkleShift = 0.15))
        )

        assertEquals(2.0, result.score, 0.0)
        assertEquals(List(2) { "feet_moved" }, result.details("form_warning"))
    }

    @Test
    fun `squat live issues are exposed for coaching`() {
        val analyzer = SquatAnalyzer()
        val frames = RepFixtures.hold(0, 10, RepFixtures.squat()) +
            RepFixtures.hold(10 * RepFixtures.INTERVAL, 6, RepFixtures.squat(maxLean = 70.0), progress = 1.0)

        frames.forEach(analyzer::onFrame)

        assertTrue(analyzer.isReady())
        assertEquals(setOf(FormIssue.TORSO_LEAN), analyzer.currentIssues())
    }

    @Test
    fun `squat works with the far leg hidden`() {
        val result = SquatAnalyzer().analyzeSequence(
            RepFixtures.run(4, RepFixtures.squat(farSideVisibility = 0.2f))
        )

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(4.0, result.score, 0.0)
        assertEquals(listOf("LEFT"), result.details("side_locked"))
        assertEquals(0, result.framesRejected)
    }

    @Test
    fun `squat that never stands tall has no start position`() {
        val frames = RepFixtures.hold(0, 40, RepFixtures.squat(bottomAngle = 120.0), progress = 1.0)

        val result = SquatAnalyzer().analyzeSequence(frames)

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.startsWith("Start position never detected — stand tall"))
    }

    @Test
    fun `squat with no body in view has no pose data`() {
        val result = SquatAnalyzer().analyzeSequence(RepFixtures.occluded(0, 30))

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.startsWith("No usable pose data"))
        assertEquals(30, result.framesRejected)
    }

    @Test
    fun `squat tracking loss is recorded and lowers confidence`() {
        val clean = RepFixtures.run(2, RepFixtures.squat())
        val frames = RepFixtures.then(clean) { RepFixtures.occluded(it, 20) }
            .let { withGap -> RepFixtures.then(withGap) { start -> shifted(RepFixtures.run(2, RepFixtures.squat()), start) } }

        val result = SquatAnalyzer().analyzeSequence(frames)

        assertEquals(4.0, result.score, 0.0)
        assertEquals(1, result.count("tracking_lost"))
        assertTrue(result.confidence < 0.5)
    }

    @Test
    fun `reset clears a squat attempt for the next one`() {
        val analyzer = SquatAnalyzer()
        analyzer.analyzeSequence(RepFixtures.run(3, RepFixtures.squat()))

        analyzer.reset()

        assertEquals(0.0, analyzer.currentScore(), 0.0)
        assertEquals(AttemptStatus.INVALID, analyzer.result().status)
        assertEquals(2.0, analyzer.analyzeSequence(RepFixtures.run(2, RepFixtures.squat())).score, 0.0)
    }

    // -----------------------------------------------------------------
    // Push-up
    // -----------------------------------------------------------------

    @Test
    fun `push-up counts clean reps`() {
        val result = PushUpAnalyzer().analyzeSequence(RepFixtures.run(5, RepFixtures.pushUp()))

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(5.0, result.score, 0.0)
    }

    @Test
    fun `push-up with sagging hips does not count`() {
        val result = PushUpAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.pushUp(hipOffset = 0.1))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(listOf("hips_sagging", "hips_sagging", "hips_sagging"), result.details("rep_rejected_form"))
    }

    @Test
    fun `push-up with piked hips does not count`() {
        val result = PushUpAnalyzer().analyzeSequence(
            RepFixtures.run(2, RepFixtures.pushUp(hipOffset = -0.1))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(listOf("hips_piked", "hips_piked"), result.details("rep_rejected_form"))
    }

    @Test
    fun `bending the arms while standing is not a push-up`() {
        val result = PushUpAnalyzer().analyzeSequence(
            RepFixtures.run(5, RepFixtures.pushUp(standing = true))
        )

        assertEquals(AttemptStatus.INVALID, result.status)
        assertTrue(result.invalidReason!!.startsWith("Start position never detected — hold a straight-arm plank"))
    }

    @Test
    fun `standing up mid-attempt to fake reps does not score`() {
        val real = RepFixtures.run(2, RepFixtures.pushUp())
        val frames = RepFixtures.then(real) { start ->
            shifted(RepFixtures.run(3, RepFixtures.pushUp(standing = true), startFrames = 0), start)
        }

        val result = PushUpAnalyzer().analyzeSequence(frames)

        assertEquals(2.0, result.score, 0.0)
        assertEquals(3, result.count("rep_rejected_form"))
        assertTrue(result.details("rep_rejected_form").all { "not_in_plank" in it })
    }

    @Test
    fun `push-up rejects shallow reps`() {
        val result = PushUpAnalyzer().analyzeSequence(
            RepFixtures.run(2, RepFixtures.pushUp(bottomAngle = 115.0))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(2, result.count("rep_rejected_partial"))
    }

    // -----------------------------------------------------------------
    // Bicep curl
    // -----------------------------------------------------------------

    @Test
    fun `curl counts reps on the arm that curls`() {
        val analyzer = BicepCurlAnalyzer()

        val result = analyzer.analyzeSequence(RepFixtures.run(4, RepFixtures.curl(right = true)))

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(4.0, result.score, 0.0)
        assertEquals(listOf("RIGHT"), result.details("arm_locked"))
        assertEquals(BodySide.RIGHT, analyzer.activeArm())
    }

    @Test
    fun `a two-arm curl counts once per rep`() {
        val result = BicepCurlAnalyzer().analyzeSequence(
            RepFixtures.run(4, RepFixtures.curl(left = true, right = true))
        )

        assertEquals(4.0, result.score, 0.0)
        assertEquals(0, result.count("rep_rejected_wrong_arm"))
    }

    @Test
    fun `curls with the other arm are not counted`() {
        val working = RepFixtures.run(2, RepFixtures.curl(right = true))
        val frames = RepFixtures.then(working) { start ->
            shifted(RepFixtures.run(3, RepFixtures.curl(left = true, right = false), startFrames = 0), start)
        }

        val result = BicepCurlAnalyzer().analyzeSequence(frames)

        assertEquals(2.0, result.score, 0.0)
        assertEquals(3, result.count("rep_rejected_wrong_arm"))
    }

    @Test
    fun `a chosen working arm is enforced from the first rep`() {
        val result = BicepCurlAnalyzer(workingArm = BodySide.LEFT).analyzeSequence(
            RepFixtures.run(3, RepFixtures.curl(right = true))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(3, result.count("rep_rejected_wrong_arm"))
    }

    @Test
    fun `swinging the elbow out is not a curl`() {
        val result = BicepCurlAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.curl(right = true, flare = 50.0))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(listOf("elbow_flare", "elbow_flare", "elbow_flare"), result.details("rep_rejected_form"))
    }

    @Test
    fun `swaying the body through a curl is a warning`() {
        val result = BicepCurlAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.curl(right = true, maxSway = 0.08))
        )

        assertEquals(3.0, result.score, 0.0)
        assertEquals(List(3) { "body_swing" }, result.details("form_warning"))
    }

    @Test
    fun `curl that stops halfway is partial`() {
        val result = BicepCurlAnalyzer().analyzeSequence(
            RepFixtures.run(2, RepFixtures.curl(right = true, topAngle = 85.0))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(2, result.count("rep_rejected_partial"))
    }

    // -----------------------------------------------------------------
    // Lunge
    // -----------------------------------------------------------------

    @Test
    fun `lunge counts clean reps`() {
        val result = LungeAnalyzer().analyzeSequence(RepFixtures.run(4, RepFixtures.lunge()))

        assertEquals(AttemptStatus.COMPLETE, result.status)
        assertEquals(4.0, result.score, 0.0)
        assertEquals(0, result.count("form_warning"))
    }

    @Test
    fun `bending with the feet together is a squat, not a lunge`() {
        val result = LungeAnalyzer().analyzeSequence(
            RepFixtures.run(3, RepFixtures.lunge(feetTogether = true))
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(3, result.count("rep_rejected_form"))
        assertTrue(result.details("rep_rejected_form").all { "stance_too_narrow" in it })
    }

    @Test
    fun `front knee past the toes is a warning`() {
        val result = LungeAnalyzer().analyzeSequence(
            RepFixtures.run(2, RepFixtures.lunge(frontHipAtBottom = 80.0, frontKneeAtBottom = 70.0))
        )

        assertEquals(2.0, result.score, 0.0)
        assertEquals(listOf("knee_past_toes", "knee_past_toes"), result.details("form_warning"))
    }

    @Test
    fun `leaning over the front leg is a warning`() {
        val result = LungeAnalyzer().analyzeSequence(
            RepFixtures.run(2, RepFixtures.lunge(maxTorsoLean = 45.0))
        )

        assertEquals(2.0, result.score, 0.0)
        assertEquals(List(2) { "torso_lean" }, result.details("form_warning"))
    }

    @Test
    fun `lunge that does not go deep enough is partial`() {
        val result = LungeAnalyzer().analyzeSequence(
            RepFixtures.run(
                2,
                RepFixtures.lunge(frontHipAtBottom = 50.0, frontKneeAtBottom = 125.0, backKneeAtBottom = 130.0)
            )
        )

        assertEquals(0.0, result.score, 0.0)
        assertEquals(2, result.count("rep_rejected_partial"))
    }

    // -----------------------------------------------------------------

    @Test
    fun `every rep test is scored in reps`() {
        listOf(TestType.SQUATS, TestType.PUSH_UPS, TestType.BICEP_CURLS, TestType.LUNGES).forEach {
            assertTrue(it.countsReps)
        }
        assertEquals("5", AnalyzerResult.invalid(TestType.SQUATS, "x").copy(score = 5.0).formattedScore())
    }

    private fun shifted(frames: List<PoseFrame>, startMs: Long): List<PoseFrame> {
        val offset = startMs - (frames.firstOrNull()?.timestampMs ?: 0L)
        return frames.map { it.copy(timestampMs = it.timestampMs + offset) }
    }
}
