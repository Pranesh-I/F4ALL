package com.sai.sports.ui.capture

import com.sai.sports.gesture.Gesture
import com.sai.sports.gesture.GestureVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where one attempt is, from the athlete pressing Start to the result being saved. */
sealed interface CapturePhase {

    /** Camera live, nothing in progress. Start is available. */
    data object Ready : CapturePhase

    /** Waiting for the athlete to make [gesture]. Nothing is being recorded yet. */
    data class Verifying(val gesture: Gesture) : CapturePhase

    data class VerificationFailed(val failure: GestureVerifier.Failure) : CapturePhase

    /** 3 -> 2 -> 1. Scoring already runs, so the jump can calibrate on the still athlete. */
    data class Countdown(val secondsLeft: Int) : CapturePhase

    data object Recording : CapturePhase

    /** The recording finished cleanly and the attempt is being written. */
    data object Saving : CapturePhase

    /** The attempt was abandoned and nothing was kept; [reason] is shown. */
    data class Interrupted(val reason: Interruption) : CapturePhase
}

enum class Interruption {
    /** The athlete left the app mid-attempt; the camera stopped under them. */
    APP_BACKGROUNDED,
    /** The recorder reported an error, so the video cannot be trusted. */
    RECORDING_FAILED,
    /** The camera could not start. */
    CAMERA_FAILED
}

/** What to do with a recording that has just finished. */
enum class RecordingDecision { SAVE, DISCARD }

/**
 * The attempt state machine, kept free of Android so every path — including
 * the awkward ones — is unit-tested.
 *
 *     Ready -> Verifying -> Countdown(3,2,1) -> Recording -> Saving -> Ready
 *                  |                    \           |
 *        VerificationFailed           Interrupted <-+   (backgrounded, recorder error)
 *
 * Every attempt gets a number. The recorder reports its end asynchronously,
 * sometimes long after the athlete pressed Back or the app went to the
 * background; [recordingFinished] is told which attempt the recording belonged
 * to, and anything that is not the live, still-wanted attempt is discarded.
 * That is what stops a cancelled attempt reappearing as a result.
 *
 * Call it from the main thread only.
 */
class CaptureFlow(
    private val pickGesture: () -> Gesture
) {

    private val state = MutableStateFlow<CapturePhase>(CapturePhase.Ready)

    val phase: StateFlow<CapturePhase> = state.asStateFlow()

    /** The number of the current (or most recent) attempt. */
    var attempt = 0
        private set

    /** True while something is in progress that leaving the screen would abandon. */
    val isActive: Boolean
        get() = when (state.value) {
            is CapturePhase.Verifying,
            is CapturePhase.Countdown,
            CapturePhase.Recording,
            CapturePhase.Saving -> true
            else -> false
        }

    /** Begins a new attempt with a freshly picked gesture. */
    fun start(): Boolean {
        val current = state.value
        if (current != CapturePhase.Ready &&
            current !is CapturePhase.VerificationFailed &&
            current !is CapturePhase.Interrupted
        ) {
            return false
        }
        attempt++
        state.value = CapturePhase.Verifying(pickGesture())
        return true
    }

    fun gestureVerified(): Boolean {
        if (state.value !is CapturePhase.Verifying) return false
        state.value = CapturePhase.Countdown(COUNTDOWN_SECONDS)
        return true
    }

    fun gestureFailed(failure: GestureVerifier.Failure) {
        if (state.value !is CapturePhase.Verifying) return
        state.value = CapturePhase.VerificationFailed(failure)
    }

    /**
     * One second of countdown has passed. Returns true exactly once, when the
     * countdown ends and the recording must start.
     */
    fun tick(): Boolean {
        val current = state.value as? CapturePhase.Countdown ?: return false
        return if (current.secondsLeft > 1) {
            state.value = CapturePhase.Countdown(current.secondsLeft - 1)
            false
        } else {
            state.value = CapturePhase.Recording
            true
        }
    }

    /** The athlete pressed Stop. Returns whether there is a recording to stop. */
    fun stopRequested(): Boolean {
        if (state.value != CapturePhase.Recording) return false
        state.value = CapturePhase.Saving
        return true
    }

    /**
     * The recorder has finalized the file for [recordingAttempt]. Only a clean
     * recording of the attempt still in progress is kept.
     */
    fun recordingFinished(recordingAttempt: Int, success: Boolean): RecordingDecision {
        val current = state.value
        val live = recordingAttempt == attempt &&
            (current == CapturePhase.Recording || current == CapturePhase.Saving)
        if (!live) return RecordingDecision.DISCARD
        if (!success) {
            state.value = CapturePhase.Interrupted(Interruption.RECORDING_FAILED)
            return RecordingDecision.DISCARD
        }
        state.value = CapturePhase.Saving
        return RecordingDecision.SAVE
    }

    /** The attempt is saved; the screen is ready for the next one. */
    fun saved() {
        if (state.value == CapturePhase.Saving) state.value = CapturePhase.Ready
    }

    /**
     * Abandons whatever is in progress. Returns whether a recording is running
     * and must be stopped (its file will then be discarded).
     *
     * An attempt already Saving is left alone: the recording is complete and
     * valid, and throwing it away because the athlete glanced at a message
     * would lose a good attempt.
     */
    fun interrupt(reason: Interruption): Boolean {
        val wasRecording = state.value == CapturePhase.Recording
        when (state.value) {
            is CapturePhase.Verifying,
            is CapturePhase.Countdown,
            CapturePhase.Recording -> state.value = CapturePhase.Interrupted(reason)
            CapturePhase.Ready -> if (reason == Interruption.CAMERA_FAILED) {
                state.value = CapturePhase.Interrupted(reason)
            }
            else -> Unit
        }
        return wasRecording
    }

    /** The athlete backed out of the attempt. Returns whether a recording must be stopped. */
    fun cancel(): Boolean {
        val wasRecording = state.value == CapturePhase.Recording
        when (state.value) {
            is CapturePhase.Verifying,
            is CapturePhase.Countdown,
            CapturePhase.Recording,
            is CapturePhase.VerificationFailed,
            is CapturePhase.Interrupted -> state.value = CapturePhase.Ready
            else -> Unit
        }
        return wasRecording
    }

    companion object {
        const val COUNTDOWN_SECONDS = 3
    }
}
