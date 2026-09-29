package com.sai.sports.gesture

import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.PoseLandmarkIndex
import com.sai.sports.analyzer.PosePoint
import com.sai.sports.analyzer.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GestureVerifierTest {

    private val interval = 33L

    /**
     * A standing athlete. Facing the camera on an unmirrored frame, their LEFT
     * side appears on the RIGHT of the image (larger x). [mirrored] swaps that,
     * as a mirroring bug upstream would; [sideOn] stacks both shoulders.
     */
    private fun frame(
        timestampMs: Long,
        leftUp: Boolean = false,
        rightUp: Boolean = false,
        mirrored: Boolean = false,
        sideOn: Boolean = false,
        visibility: Float = 0.95f
    ): PoseFrame {
        val points = MutableList(PoseFrame.LANDMARK_COUNT) { PosePoint(0.5f, 0.5f, visibility = 0.1f) }

        fun x(offset: Float) = if (sideOn) 0.5f else 0.5f + (if (mirrored) -offset else offset)
        fun put(index: Int, x: Float, y: Float) {
            points[index] = PosePoint(x, y, visibility = visibility)
        }

        put(PoseLandmarkIndex.NOSE, 0.5f, 0.2f)
        put(PoseLandmarkIndex.LEFT_SHOULDER, x(0.08f), 0.3f)
        put(PoseLandmarkIndex.RIGHT_SHOULDER, x(-0.08f), 0.3f)
        put(PoseLandmarkIndex.LEFT_HIP, x(0.06f), 0.6f)
        put(PoseLandmarkIndex.RIGHT_HIP, x(-0.06f), 0.6f)
        put(PoseLandmarkIndex.LEFT_WRIST, x(0.1f), if (leftUp) 0.08f else 0.55f)
        put(PoseLandmarkIndex.RIGHT_WRIST, x(-0.1f), if (rightUp) 0.08f else 0.55f)

        return PoseFrame(timestampMs, points)
    }

    private fun feed(verifier: GestureVerifier, fromMs: Long, toMs: Long, make: (Long) -> PoseFrame) =
        (fromMs..toMs step interval).map { verifier.onFrame(make(it)) }.last()

    @Test
    fun `a raised hand held for a second verifies`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)

        feed(verifier, 0, 900) { frame(it, rightUp = true) }
        assertEquals(GestureVerifier.Status.WAITING, verifier.status)
        assertTrue(verifier.progress > 0.8f)

        feed(verifier, 933, 1_100) { frame(it, rightUp = true) }
        assertEquals(GestureVerifier.Status.VERIFIED, verifier.status)
    }

    @Test
    fun `a hand flicked up and down does not verify`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)

        // Up for 600 ms, down, up for 600 ms: never a full second unbroken.
        feed(verifier, 0, 600) { frame(it, leftUp = true) }
        feed(verifier, 633, 700) { frame(it) }
        feed(verifier, 733, 1_333) { frame(it, leftUp = true) }

        assertEquals(GestureVerifier.Status.WAITING, verifier.status)
    }

    @Test
    fun `the side-on gesture works with the shoulders stacked`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)

        feed(verifier, 0, 1_100) { frame(it, rightUp = true, sideOn = true) }

        assertEquals(GestureVerifier.Status.VERIFIED, verifier.status)
    }

    @Test
    fun `the named hand must be the one raised`() {
        val left = GestureVerifier(Gesture.RAISE_LEFT_HAND)
        feed(left, 0, 1_100) { frame(it, leftUp = true) }
        assertEquals(GestureVerifier.Status.VERIFIED, left.status)

        val wrong = GestureVerifier(Gesture.RAISE_LEFT_HAND)
        feed(wrong, 0, 1_100) { frame(it, rightUp = true) }
        assertEquals(GestureVerifier.Status.WAITING, wrong.status)
        assertEquals(GestureVerifier.Hint.WRONG_HAND, wrong.hint)

        // Both up is not "the left hand".
        val both = GestureVerifier(Gesture.RAISE_LEFT_HAND)
        feed(both, 0, 1_100) { frame(it, leftUp = true, rightUp = true) }
        assertEquals(GestureVerifier.Status.WAITING, both.status)
    }

    @Test
    fun `both hands means both`() {
        val verifier = GestureVerifier(Gesture.BOTH_HANDS_UP)

        feed(verifier, 0, 1_100) { frame(it, leftUp = true) }
        assertEquals(GestureVerifier.Hint.BOTH_HANDS, verifier.hint)

        feed(verifier, 1_133, 2_300) { frame(it, leftUp = true, rightUp = true) }
        assertEquals(GestureVerifier.Status.VERIFIED, verifier.status)
    }

    @Test
    fun `a mirrored frame is caught before a named hand is trusted`() {
        // With the image mirrored, the athlete's real left hand is labelled
        // RIGHT. Accepting "left" here would pass the wrong person's gesture.
        val verifier = GestureVerifier(Gesture.RAISE_LEFT_HAND)

        feed(verifier, 0, 2_000) { frame(it, leftUp = true, mirrored = true) }

        assertEquals(GestureVerifier.Status.WAITING, verifier.status)
        assertEquals(GestureVerifier.Hint.FACE_CAMERA, verifier.hint)
    }

    @Test
    fun `named-hand gestures need the athlete facing the phone`() {
        val verifier = GestureVerifier(Gesture.RAISE_RIGHT_HAND)

        feed(verifier, 0, 1_500) { frame(it, rightUp = true, sideOn = true) }

        assertEquals(GestureVerifier.Hint.FACE_CAMERA, verifier.hint)
        assertEquals(GestureVerifier.Status.WAITING, verifier.status)
    }

    @Test
    fun `an empty room times out as nobody seen`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)

        (0L..GestureVerifier.TIMEOUT_MS step 100).forEach { verifier.onNoPerson(it) }

        assertEquals(GestureVerifier.Status.FAILED, verifier.status)
        assertEquals(GestureVerifier.Failure.NO_PERSON, verifier.failure)
    }

    @Test
    fun `an athlete who never makes the gesture times out as not performed`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)

        // One frame past the timeout (33 ms steps do not land on it exactly).
        feed(verifier, 0, GestureVerifier.TIMEOUT_MS + interval) { frame(it) }

        assertEquals(GestureVerifier.Status.FAILED, verifier.status)
        assertEquals(GestureVerifier.Failure.NOT_PERFORMED, verifier.failure)
        assertEquals(0L, verifier.remainingMs(GestureVerifier.TIMEOUT_MS))
    }

    @Test
    fun `landmarks the model is unsure of do not count`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)

        feed(verifier, 0, 1_500) { frame(it, rightUp = true, visibility = 0.3f) }

        assertEquals(GestureVerifier.Status.WAITING, verifier.status)
        assertEquals(GestureVerifier.Hint.STEP_INTO_VIEW, verifier.hint)
    }

    @Test
    fun `a finished verifier ignores later frames`() {
        val verifier = GestureVerifier(Gesture.RAISE_HAND)
        feed(verifier, 0, 1_100) { frame(it, rightUp = true) }

        verifier.onNoPerson(GestureVerifier.TIMEOUT_MS * 2)

        assertEquals(GestureVerifier.Status.VERIFIED, verifier.status)
        assertNull(verifier.failure)
    }

    @Test
    fun `each test gets gestures it can actually be seen doing`() {
        TestType.entries.forEach { type ->
            val choices = GesturePlan.choicesFor(type)
            assertTrue(choices.isNotEmpty())
            if (type != TestType.BICEP_CURLS) {
                // Filmed side-on: a named hand could be hidden behind the body.
                assertTrue(choices.none { it.facingCamera })
            }
        }

        // Curls vary, so a pre-recorded video cannot know what will be asked.
        val picks = (0 until 30).map { GesturePlan.pick(TestType.BICEP_CURLS, Random(it)) }.toSet()
        assertTrue(picks.size > 1)
    }
}
