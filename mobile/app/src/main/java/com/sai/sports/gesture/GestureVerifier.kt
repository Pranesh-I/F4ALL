package com.sai.sports.gesture

import com.sai.sports.analyzer.AnalyzerThresholds
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.PoseLandmarkIndex
import com.sai.sports.analyzer.PoseMath
import com.sai.sports.analyzer.PosePoint

/**
 * Decides, frame by frame, whether the athlete has made [gesture].
 *
 * The gesture must be held for [holdMs] without a break — a hand flicking past
 * the head is not a deliberate signal — and made within [timeoutMs] of the
 * first frame, or verification fails with a reason the athlete can act on.
 *
 * "Above the head" means the wrist is higher than the nose by a margin scaled
 * to the athlete's torso, so the same rule works whether they stand one metre
 * from the phone or four.
 *
 * Driven by frame timestamps, not a clock, so it is deterministic in tests.
 * Frames with no person in them must still be fed (see [onNoPerson]),
 * otherwise an empty room would never time out.
 */
class GestureVerifier(
    val gesture: Gesture,
    private val holdMs: Long = HOLD_MS,
    private val timeoutMs: Long = TIMEOUT_MS
) {

    enum class Status {
        /** Watching; the gesture has not been held long enough yet. */
        WAITING,
        VERIFIED,
        FAILED
    }

    enum class Failure {
        /** Nobody was seen at all — the athlete is out of frame or the camera is blocked. */
        NO_PERSON,
        /** The athlete was seen but never held the gesture. */
        NOT_PERFORMED
    }

    /** What the athlete should change right now. */
    enum class Hint {
        STEP_INTO_VIEW,
        FACE_CAMERA,
        WRONG_HAND,
        BOTH_HANDS,
        HOLD_IT
    }

    var status = Status.WAITING
        private set

    var failure: Failure? = null
        private set

    var hint: Hint? = null
        private set

    /** 0..1: how much of the hold has been completed. */
    var progress = 0f
        private set

    private var startMs: Long? = null
    private var holdStartMs: Long? = null
    private var sawPerson = false

    /** How long is left before timing out, for the on-screen countdown. */
    fun remainingMs(nowMs: Long): Long =
        (timeoutMs - (nowMs - (startMs ?: nowMs))).coerceAtLeast(0L)

    fun onFrame(frame: PoseFrame): Status {

        if (status != Status.WAITING) return status

        val start = startMs ?: frame.timestampMs.also { startMs = it }

        val performed = evaluate(frame)

        if (performed) {
            val since = holdStartMs ?: frame.timestampMs.also { holdStartMs = it }
            val held = frame.timestampMs - since
            progress = (held.toFloat() / holdMs).coerceIn(0f, 1f)
            hint = Hint.HOLD_IT
            if (held >= holdMs) {
                status = Status.VERIFIED
                hint = null
                return status
            }
        } else {
            holdStartMs = null
            progress = 0f
        }

        if (frame.timestampMs - start >= timeoutMs) {
            status = Status.FAILED
            failure = if (sawPerson) Failure.NOT_PERFORMED else Failure.NO_PERSON
        }

        return status
    }

    /** A camera frame in which the pose model found nobody. */
    fun onNoPerson(timestampMs: Long): Status =
        onFrame(PoseFrame(timestampMs, emptyList()))

    private fun evaluate(frame: PoseFrame): Boolean {

        val nose = frame.visible(PoseLandmarkIndex.NOSE)
        val shoulder = frame.visible(PoseLandmarkIndex.LEFT_SHOULDER)
            ?: frame.visible(PoseLandmarkIndex.RIGHT_SHOULDER)
        val hip = frame.visible(PoseLandmarkIndex.LEFT_HIP)
            ?: frame.visible(PoseLandmarkIndex.RIGHT_HIP)

        if (nose == null || shoulder == null || hip == null) {
            hint = Hint.STEP_INTO_VIEW
            return false
        }
        sawPerson = true

        val torso = PoseMath.distance(shoulder, hip).toDouble()
        val margin = RAISE_MARGIN_RATIO * torso

        fun up(wristIndex: Int): Boolean {
            val wrist = frame.visible(wristIndex) ?: return false
            return wrist.y < nose.y - margin
        }

        val leftUp = up(PoseLandmarkIndex.LEFT_WRIST)
        val rightUp = up(PoseLandmarkIndex.RIGHT_WRIST)

        if (gesture.facingCamera && !facingCamera(frame, torso)) {
            hint = Hint.FACE_CAMERA
            return false
        }

        val performed = when (gesture) {
            Gesture.RAISE_HAND -> leftUp || rightUp
            Gesture.RAISE_LEFT_HAND -> leftUp && !rightUp
            Gesture.RAISE_RIGHT_HAND -> rightUp && !leftUp
            Gesture.BOTH_HANDS_UP -> leftUp && rightUp
        }

        hint = when {
            performed -> Hint.HOLD_IT
            gesture == Gesture.RAISE_LEFT_HAND && rightUp -> Hint.WRONG_HAND
            gesture == Gesture.RAISE_RIGHT_HAND && leftUp -> Hint.WRONG_HAND
            gesture == Gesture.BOTH_HANDS_UP && (leftUp || rightUp) -> Hint.BOTH_HANDS
            else -> null
        }

        return performed
    }

    /**
     * Facing the phone, on an unmirrored camera frame, the athlete's LEFT
     * shoulder appears on the RIGHT of the image. If it does not, either they
     * have turned side-on or away, or something upstream mirrored the image
     * and every left/right label is now reversed. Either way a named hand
     * cannot be trusted, so the gesture is not accepted.
     */
    private fun facingCamera(frame: PoseFrame, torso: Double): Boolean {
        val left = frame.visible(PoseLandmarkIndex.LEFT_SHOULDER) ?: return false
        val right = frame.visible(PoseLandmarkIndex.RIGHT_SHOULDER) ?: return false
        return (left.x - right.x) > FACING_MIN_SHOULDER_RATIO * torso
    }

    private fun PoseFrame.visible(index: Int): PosePoint? =
        this[index]?.takeIf { it.visibility >= AnalyzerThresholds.MIN_LANDMARK_VISIBILITY }

    companion object {
        /** A deliberate hold, not a hand passing through. */
        const val HOLD_MS = 1_000L

        /** Long enough to walk back into position from the phone. */
        const val TIMEOUT_MS = 15_000L

        /** How far above the nose the wrist must be, as a fraction of torso length. */
        const val RAISE_MARGIN_RATIO = 0.15

        /**
         * Minimum left-to-right shoulder separation, as a fraction of torso
         * length, to count as facing the camera. Side-on the shoulders overlap.
         */
        const val FACING_MIN_SHOULDER_RATIO = 0.3
    }
}
