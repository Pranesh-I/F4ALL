package com.sai.sports.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.PoseSequenceRecorder
import com.sai.sports.analyzer.SitUpAnalyzer
import com.sai.sports.analyzer.TestAnalyzer
import com.sai.sports.analyzer.TestType
import com.sai.sports.analyzer.VerticalJumpAnalyzer

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
        analyzer = when (testType) {
            TestType.SIT_UPS -> SitUpAnalyzer()
            TestType.VERTICAL_JUMP -> VerticalJumpAnalyzer(
                athleteHeightCm = athleteHeightCm
                    ?: error("Vertical jump needs the athlete's height to calibrate")
            )
        }

        sequenceRecorder.clear()
        liveScore = 0.0
        liveDetail = null
        framesCaptured = 0
        capturing = true
        isActive = true
    }

    /** Called from the pose callback thread for every detected frame. */
    fun onPoseFrame(
        frame: PoseFrame,
        imageWidth: Int,
        imageHeight: Int
    ) {

        if (!capturing) return

        this.imageWidth = imageWidth
        this.imageHeight = imageHeight

        val currentAnalyzer = analyzer ?: return

        sequenceRecorder.add(frame)
        currentAnalyzer.onFrame(frame)

        liveScore = currentAnalyzer.currentScore()
        framesCaptured = sequenceRecorder.size()
        liveDetail = describe(currentAnalyzer)
    }

    private fun describe(analyzer: TestAnalyzer): LiveHint? =
        when (analyzer) {

            is SitUpAnalyzer -> analyzer.currentAngle()?.let { LiveHint.TorsoAngle(it) }

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

        val currentAnalyzer = analyzer ?: return null

        return Outcome(
            result = currentAnalyzer.result(),
            frames = sequenceRecorder.frames()
        )
    }

    fun cancel() {
        capturing = false
        isActive = false
        analyzer?.reset()
        sequenceRecorder.clear()
        liveScore = 0.0
        liveDetail = null
        framesCaptured = 0
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
    data object StandStill : LiveHint
    data class Ready(val displacementCm: Double?) : LiveHint
    data object Airborne : LiveHint
    data object Failed : LiveHint
}
