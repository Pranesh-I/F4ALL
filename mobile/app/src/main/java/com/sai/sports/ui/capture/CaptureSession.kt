package com.sai.sports.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.PoseSequenceRecorder
import com.sai.sports.analyzer.RepExerciseAnalyzer
import com.sai.sports.analyzer.SitUpAnalyzer
import com.sai.sports.analyzer.TestAnalyzer
import com.sai.sports.analyzer.TestType
import com.sai.sports.analyzer.VerticalJumpAnalyzer
import com.sai.sports.analyzer.analyzerFor
import com.sai.sports.coach.CoachCue
import com.sai.sports.coach.FormCoach
import com.sai.sports.gesture.Gesture
import com.sai.sports.gesture.GestureVerifier

/**
 * Owns the scoring state for one capture screen.
 *
 * Frames arrive on the MediaPipe callback thread and the UI reads the running
 * score on the main thread, so this class is the boundary between the two.
 * Compose snapshot state handles the cross-thread reads; [capturing] is
 * volatile because it gates whether a frame is consumed at all and a stale
 * value there means frames scored after the athlete pressed stop.
 */
class CaptureSession {

    private var analyzer: TestAnalyzer? = null
    private val sequenceRecorder = PoseSequenceRecorder()

    @Volatile
    private var capturing = false

    /** Live score for the on-screen readout: rep count, or best jump in cm. */
    var liveScore by mutableStateOf(0.0)
        private set

    /** Secondary live readout — torso angle or height above the standing reference. */
    var liveDetail by mutableStateOf<LiveHint?>(null)
        private set

    var framesCaptured by mutableStateOf(0)
        private set

    /** The coaching cue on screen right now, if any. */
    var liveCue by mutableStateOf<CoachCue?>(null)
        private set

    /**
     * Called on the pose thread with cues to say aloud. The coach has already
     * applied cooldowns; the listener only has to speak.
     */
    @Volatile
    var onSpeak: ((List<CoachCue>) -> Unit)? = null

    private var coach: FormCoach? = null

    /** How many analyzer trace events the coach has already seen. */
    private var eventsSeen = 0

    /** Non-null only while the start gesture is being checked. */
    @Volatile
    private var verifier: GestureVerifier? = null

    /** 0..1 of the gesture hold, for the progress bar. */
    var gestureProgress by mutableFloatStateOf(0f)
        private set

    var gestureHint by mutableStateOf<GestureVerifier.Hint?>(null)
        private set

    var gestureRemainingMs by mutableLongStateOf(GestureVerifier.TIMEOUT_MS)
        private set

    /**
     * Called once on the pose thread when the gesture is verified or fails.
     * The listener must hop to the main thread before touching the attempt.
     */
    @Volatile
    var onGestureResult: ((GestureVerifier) -> Unit)? = null

    /** Dimensions of the frames the pose model saw, carried into the attempt for replay. */
    @Volatile
    var imageWidth = 0
        private set

    @Volatile
    var imageHeight = 0
        private set

    /**
     * Compose-observable mirror of [capturing].
     *
     * [capturing] is volatile because the pose thread checks it per frame, but a
     * plain field gives Compose nothing to observe — the UI would only notice a
     * change when some neighbouring state happened to trigger recomposition.
     * The UI reads this; the frame path reads the volatile.
     */
    var isActive by mutableStateOf(false)
        private set

    /**
     * Arms the session for a new attempt.
     *
     * [athleteHeightCm] is required for [TestType.VERTICAL_JUMP] — without it
     * there is no way to turn normalized displacement into centimetres.
     */
    fun start(
        testType: TestType,
        athleteHeightCm: Double?
    ) {
        analyzer = analyzerFor(testType, athleteHeightCm)

        // Rep tests get live coaching. The jump has its own phase hints and no
        // form to correct mid-flight.
        coach = if (testType.countsReps) FormCoach() else null
        eventsSeen = 0
        liveCue = null

        sequenceRecorder.clear()
        liveScore = 0.0
        liveDetail = null
        framesCaptured = 0
        capturing = true
        isActive = true
    }

    /** Starts checking for [gesture]. Scoring is not running yet. */
    fun beginVerification(gesture: Gesture) {
        gestureProgress = 0f
        gestureHint = null
        gestureRemainingMs = GestureVerifier.TIMEOUT_MS
        verifier = GestureVerifier(gesture)
    }

    fun endVerification() {
        verifier = null
        gestureProgress = 0f
        gestureHint = null
    }

    /**
     * A camera frame in which nobody was found. Only the gesture check needs
     * these — it must be able to time out on an empty room. Scoring ignores
     * them, exactly as before, so the analyzers see the same frames the
     * server's re-scoring does.
     */
    fun onNoPose(timestampMs: Long) {
        verifier?.let { publish(it, it.onNoPerson(timestampMs), timestampMs) }
    }

    private fun publish(verifier: GestureVerifier, status: GestureVerifier.Status, timestampMs: Long) {
        gestureProgress = verifier.progress
        gestureHint = verifier.hint
        gestureRemainingMs = verifier.remainingMs(timestampMs)
        if (status != GestureVerifier.Status.WAITING) {
            this.verifier = null
            onGestureResult?.invoke(verifier)
        }
    }

    /** Called from the pose callback thread for every detected frame. */
    fun onPoseFrame(
        frame: PoseFrame,
        imageWidth: Int,
        imageHeight: Int
    ) {

        verifier?.let { publish(it, it.onFrame(frame), frame.timestampMs) }

        if (!capturing) return

        this.imageWidth = imageWidth
        this.imageHeight = imageHeight

        val currentAnalyzer = analyzer ?: return

        sequenceRecorder.add(frame)
        currentAnalyzer.onFrame(frame)

        liveScore = currentAnalyzer.currentScore()
        framesCaptured = sequenceRecorder.size()
        liveDetail = describe(currentAnalyzer)

        coach?.let { coach(it, currentAnalyzer, frame.timestampMs) }
    }

    private fun coach(coach: FormCoach, analyzer: TestAnalyzer, timestampMs: Long) {

        val newEvents = analyzer.eventsSince(eventsSeen)
        eventsSeen += newEvents.size

        val update = coach.update(
            timestampMs = timestampMs,
            ready = analyzer.isReady(),
            liveIssues = (analyzer as? RepExerciseAnalyzer)?.currentIssues().orEmpty(),
            newEvents = newEvents
        )

        liveCue = update.display
        if (update.speak.isNotEmpty()) {
            onSpeak?.invoke(update.speak)
        }
    }

    private fun describe(analyzer: TestAnalyzer): LiveHint? =
        when (analyzer) {

            is SitUpAnalyzer -> analyzer.currentAngle()?.let { LiveHint.TorsoAngle(it) }

            is RepExerciseAnalyzer -> analyzer.currentAngle()?.let { LiveHint.JointAngle(it) }

            is VerticalJumpAnalyzer -> when (analyzer.currentPhase()) {
                VerticalJumpAnalyzer.Phase.CALIBRATING -> LiveHint.StandStill
                VerticalJumpAnalyzer.Phase.READY -> LiveHint.Ready(analyzer.currentDisplacementCm())
                VerticalJumpAnalyzer.Phase.AIRBORNE -> LiveHint.Airborne
                VerticalJumpAnalyzer.Phase.INVALID -> LiveHint.Failed
            }

            else -> null
        }

    /**
     * Stops consuming frames and produces the final result.
     *
     * Returns null if no attempt was ever started, which happens if the athlete
     * backs out of the screen before recording.
     */
    fun finish(): Outcome? {

        capturing = false
        isActive = false
        liveCue = null

        val currentAnalyzer = analyzer ?: return null

        return Outcome(
            result = currentAnalyzer.result(),
            frames = sequenceRecorder.frames()
        )
    }

    fun cancel() {
        endVerification()
        capturing = false
        isActive = false
        analyzer?.reset()
        sequenceRecorder.clear()
        liveScore = 0.0
        liveDetail = null
        framesCaptured = 0
        liveCue = null
        coach?.reset()
        eventsSeen = 0
    }

    data class Outcome(
        val result: AnalyzerResult,
        val frames: List<PoseFrame>
    )
}

/**
 * What the athlete should know right now, as data rather than text, so the
 * capture screen can say it in the athlete's language.
 */
sealed interface LiveHint {
    data class TorsoAngle(val degrees: Double) : LiveHint
    data class JointAngle(val degrees: Double) : LiveHint
    data object StandStill : LiveHint
    data class Ready(val displacementCm: Double?) : LiveHint
    data object Airborne : LiveHint
    data object Failed : LiveHint
}
