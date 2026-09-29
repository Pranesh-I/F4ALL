package com.sai.sports.ui.capture

import com.sai.sports.gesture.Gesture
import com.sai.sports.gesture.GestureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFlowTest {

    private val flow = CaptureFlow { Gesture.RAISE_HAND }

    private val phase get() = flow.phase.value

    /** Start -> verified -> 3, 2, 1 -> recording. Returns the attempt number. */
    private fun runToRecording(): Int {
        assertTrue(flow.start())
        assertEquals(CapturePhase.Verifying(Gesture.RAISE_HAND), phase)
        assertTrue(flow.gestureVerified())
        assertEquals(CapturePhase.Countdown(3), phase)
        assertFalse(flow.tick())
        assertEquals(CapturePhase.Countdown(2), phase)
        assertFalse(flow.tick())
        assertEquals(CapturePhase.Countdown(1), phase)
        assertTrue("The last tick starts the recording", flow.tick())
        assertEquals(CapturePhase.Recording, phase)
        return flow.attempt
    }

    @Test
    fun `two attempts in a row without leaving the screen`() {
        // The Sprint 5 Definition of Done: gesture, countdown, test, result —
        // twice, with nothing carried over from the first.
        repeat(2) { index ->
            val attempt = runToRecording()
            assertEquals(index + 1, attempt)

            assertTrue(flow.stopRequested())
            assertEquals(CapturePhase.Saving, phase)
            assertEquals(RecordingDecision.SAVE, flow.recordingFinished(attempt, success = true))

            flow.saved()
            assertEquals(CapturePhase.Ready, phase)
            assertFalse(flow.isActive)
        }
    }

    @Test
    fun `nothing records until the gesture is verified`() {
        flow.start()

        assertFalse(flow.tick())
        assertFalse(flow.stopRequested())
        assertTrue(phase is CapturePhase.Verifying)
    }

    @Test
    fun `a failed gesture can be retried with a fresh attempt`() {
        flow.start()
        flow.gestureFailed(GestureVerifier.Failure.NO_PERSON)
        assertEquals(CapturePhase.VerificationFailed(GestureVerifier.Failure.NO_PERSON), phase)

        assertTrue(flow.start())
        assertEquals(2, flow.attempt)
        assertTrue(phase is CapturePhase.Verifying)
    }

    @Test
    fun `an attempt cannot be started twice`() {
        flow.start()

        assertFalse(flow.start())
        assertEquals(1, flow.attempt)
    }

    @Test
    fun `going to the background mid-recording discards the recording`() {
        val attempt = runToRecording()

        assertTrue("A running recording must be stopped", flow.interrupt(Interruption.APP_BACKGROUNDED))
        assertEquals(CapturePhase.Interrupted(Interruption.APP_BACKGROUNDED), phase)

        // The recorder reports in after the fact; the file is not kept.
        assertEquals(RecordingDecision.DISCARD, flow.recordingFinished(attempt, success = true))
        assertEquals(CapturePhase.Interrupted(Interruption.APP_BACKGROUNDED), phase)

        // And the athlete can go again.
        assertTrue(flow.start())
    }

    @Test
    fun `going to the background during the countdown abandons it without a recording`() {
        flow.start()
        flow.gestureVerified()

        assertFalse(flow.interrupt(Interruption.APP_BACKGROUNDED))
        assertEquals(CapturePhase.Interrupted(Interruption.APP_BACKGROUNDED), phase)
        assertFalse("The countdown cannot resume", flow.tick())
    }

    @Test
    fun `a cancelled attempt never comes back as a result`() {
        val cancelled = runToRecording()
        assertTrue(flow.cancel())
        assertEquals(CapturePhase.Ready, phase)

        assertEquals(RecordingDecision.DISCARD, flow.recordingFinished(cancelled, success = true))
        assertEquals(CapturePhase.Ready, phase)
    }

    @Test
    fun `a late report from an old attempt cannot hijack the new one`() {
        val first = runToRecording()
        flow.cancel()

        val second = runToRecording()

        assertEquals(RecordingDecision.DISCARD, flow.recordingFinished(first, success = true))
        assertEquals("The new attempt is untouched", CapturePhase.Recording, phase)

        flow.stopRequested()
        assertEquals(RecordingDecision.SAVE, flow.recordingFinished(second, success = true))
    }

    @Test
    fun `a recorder error is reported, not saved`() {
        val attempt = runToRecording()
        flow.stopRequested()

        assertEquals(RecordingDecision.DISCARD, flow.recordingFinished(attempt, success = false))
        assertEquals(CapturePhase.Interrupted(Interruption.RECORDING_FAILED), phase)
    }

    @Test
    fun `an attempt already saving survives the app going to the background`() {
        val attempt = runToRecording()
        flow.stopRequested()
        flow.recordingFinished(attempt, success = true)

        assertFalse(flow.interrupt(Interruption.APP_BACKGROUNDED))
        assertEquals(CapturePhase.Saving, phase)

        flow.saved()
        assertEquals(CapturePhase.Ready, phase)
    }

    @Test
    fun `a camera failure while idle is shown and can be retried`() {
        flow.interrupt(Interruption.CAMERA_FAILED)
        assertEquals(CapturePhase.Interrupted(Interruption.CAMERA_FAILED), phase)

        flow.cancel()
        assertEquals(CapturePhase.Ready, phase)
    }

    @Test
    fun `backgrounding while idle changes nothing`() {
        flow.interrupt(Interruption.APP_BACKGROUNDED)

        assertEquals(CapturePhase.Ready, phase)
    }

    @Test
    fun `each attempt asks for a freshly picked gesture`() {
        val gestures = ArrayDeque(listOf(Gesture.RAISE_LEFT_HAND, Gesture.BOTH_HANDS_UP))
        val picking = CaptureFlow { gestures.removeFirst() }

        picking.start()
        assertEquals(CapturePhase.Verifying(Gesture.RAISE_LEFT_HAND), picking.phase.value)
        picking.cancel()
        picking.start()
        assertEquals(CapturePhase.Verifying(Gesture.BOTH_HANDS_UP), picking.phase.value)
    }
}
