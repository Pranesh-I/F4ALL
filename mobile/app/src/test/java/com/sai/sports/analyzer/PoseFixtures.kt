package com.sai.sports.analyzer

import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic pose sequences for the analyzer tests.
 *
 * These are geometry, not recordings: a frame is constructed to produce an
 * exact torso angle or an exact hip displacement, so a test can assert what the
 * analyzer *should* do without a device or a person in the loop.
 *
 * They prove the state machines behave. They do NOT prove the thresholds are
 * right for real bodies — only reference video can do that, which is what the
 * Sprint 3.4 validation harness is for.
 */
object PoseFixtures {

    const val DEFAULT_FRAME_INTERVAL_MS = 33L

    private const val HIGH_VISIBILITY = 0.95f

    /**
     * A frame whose shoulder-hip-knee angle is exactly [angleDegrees].
     *
     * The hip sits at the origin, the knee extends along +x, and the shoulder is
     * placed at the requested angle from it.
     */
    fun sitUpFrame(
        timestampMs: Long,
        angleDegrees: Double,
        visibility: Float = HIGH_VISIBILITY
    ): PoseFrame {

        val points = MutableList(PoseFrame.LANDMARK_COUNT) {
            PosePoint(x = 0.5f, y = 0.5f, visibility = visibility)
        }

        val hipX = 0.5f
        val hipY = 0.5f

        val kneeX = hipX + 0.2f
        val kneeY = hipY

        val radians = Math.toRadians(angleDegrees)
        val shoulderX = hipX + (0.25 * cos(radians)).toFloat()
        val shoulderY = hipY + (0.25 * sin(radians)).toFloat()

        val hip = PosePoint(hipX, hipY, visibility = visibility)
        val knee = PosePoint(kneeX, kneeY, visibility = visibility)
        val shoulder = PosePoint(shoulderX, shoulderY, visibility = visibility)

        points[PoseLandmarkIndex.LEFT_HIP] = hip
        points[PoseLandmarkIndex.RIGHT_HIP] = hip
        points[PoseLandmarkIndex.LEFT_KNEE] = knee
        points[PoseLandmarkIndex.RIGHT_KNEE] = knee
        points[PoseLandmarkIndex.LEFT_SHOULDER] = shoulder
        points[PoseLandmarkIndex.RIGHT_SHOULDER] = shoulder

        return PoseFrame(timestampMs = timestampMs, points = points)
    }

    /** A frame the quality gate must reject. */
    fun occludedFrame(timestampMs: Long): PoseFrame =
        PoseFrame(
            timestampMs = timestampMs,
            points = List(PoseFrame.LANDMARK_COUNT) {
                PosePoint(x = 0.5f, y = 0.5f, visibility = 0.1f)
            }
        )

    /**
     * Holds an angle for [frameCount] frames starting at [startMs].
     * Returns the frames and the timestamp after the last one.
     */
    fun hold(
        startMs: Long,
        angleDegrees: Double,
        frameCount: Int,
        intervalMs: Long = DEFAULT_FRAME_INTERVAL_MS
    ): List<PoseFrame> =
        (0 until frameCount).map { index ->
            sitUpFrame(
                timestampMs = startMs + index * intervalMs,
                angleDegrees = angleDegrees
            )
        }

    /** Linear sweep between two angles, inclusive of both ends. */
    fun sweep(
        startMs: Long,
        fromDegrees: Double,
        toDegrees: Double,
        frameCount: Int,
        intervalMs: Long = DEFAULT_FRAME_INTERVAL_MS
    ): List<PoseFrame> =
        (0 until frameCount).map { index ->
            val progress =
                if (frameCount <= 1) 1.0 else index.toDouble() / (frameCount - 1)
            sitUpFrame(
                timestampMs = startMs + index * intervalMs,
                angleDegrees = fromDegrees + (toDegrees - fromDegrees) * progress
            )
        }

    /**
     * A full sit-up rep: rest, sit up, hold, lie back.
     *
     * [ascentFrames] controls how long the up phase takes, which is what the
     * minimum-duration guard keys off.
     */
    fun sitUpRep(
        startMs: Long,
        ascentFrames: Int = 12,
        descentFrames: Int = 12,
        topAngle: Double = 55.0,
        restAngle: Double = 155.0,
        intervalMs: Long = DEFAULT_FRAME_INTERVAL_MS
    ): List<PoseFrame> {

        val frames = mutableListOf<PoseFrame>()
        var cursor = startMs

        frames += sweep(cursor, restAngle, topAngle, ascentFrames, intervalMs)
        cursor += ascentFrames * intervalMs

        frames += hold(cursor, topAngle, 3, intervalMs)
        cursor += 3 * intervalMs

        frames += sweep(cursor, topAngle, restAngle, descentFrames, intervalMs)

        return frames
    }

    // -----------------------------------------------------------------
    // Vertical jump
    // -----------------------------------------------------------------

    const val STANDING_NOSE_Y = 0.10f
    const val STANDING_HIP_Y = 0.50f
    const val STANDING_ANKLE_Y = 0.90f

    /** Centimetres per normalized unit implied by the standing fixture geometry. */
    fun cmPerUnit(athleteHeightCm: Double): Double {
        val span = (STANDING_ANKLE_Y - STANDING_NOSE_Y).toDouble()
        val stature = span / AnalyzerThresholds.NOSE_HEIGHT_STATURE_RATIO
        return athleteHeightCm / stature
    }

    /** Normalized displacement equivalent to [heightCm] of real vertical movement. */
    fun unitsForCm(heightCm: Double, athleteHeightCm: Double): Double =
        heightCm / cmPerUnit(athleteHeightCm)

    /**
     * A jump frame with the whole body raised by [displacementUnits].
     *
     * [scale] shrinks or grows the body about the hip to simulate the athlete
     * moving toward or away from the camera — the drift the analyzer rejects.
     */
    fun jumpFrame(
        timestampMs: Long,
        displacementUnits: Double,
        visibility: Float = HIGH_VISIBILITY,
        scale: Double = 1.0
    ): PoseFrame {

        val points = MutableList(PoseFrame.LANDMARK_COUNT) {
            PosePoint(x = 0.5f, y = 0.5f, visibility = visibility)
        }

        val hipY = STANDING_HIP_Y - displacementUnits.toFloat()

        val noseOffset = (STANDING_NOSE_Y - STANDING_HIP_Y) * scale
        val ankleOffset = (STANDING_ANKLE_Y - STANDING_HIP_Y) * scale

        val nose = PosePoint(0.5f, hipY + noseOffset.toFloat(), visibility = visibility)
        val hip = PosePoint(0.5f, hipY, visibility = visibility)
        val ankle = PosePoint(0.5f, hipY + ankleOffset.toFloat(), visibility = visibility)

        points[PoseLandmarkIndex.NOSE] = nose
        points[PoseLandmarkIndex.LEFT_HIP] = hip
        points[PoseLandmarkIndex.RIGHT_HIP] = hip
        points[PoseLandmarkIndex.LEFT_ANKLE] = ankle
        points[PoseLandmarkIndex.RIGHT_ANKLE] = ankle

        return PoseFrame(timestampMs = timestampMs, points = points)
    }

    /** Standing still, long enough for the jump analyzer to calibrate. */
    fun standing(
        startMs: Long,
        frameCount: Int = AnalyzerThresholds.JUMP_CALIBRATION_FRAMES + 4,
        intervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
        scale: Double = 1.0
    ): List<PoseFrame> =
        (0 until frameCount).map { index ->
            jumpFrame(
                timestampMs = startMs + index * intervalMs,
                displacementUnits = 0.0,
                scale = scale
            )
        }

    /**
     * A parabolic jump arc peaking at [peakCm].
     *
     * Real jumps are close enough to a parabola for threshold testing, and the
     * shape matters: the analyzer has to find the peak of a curve, not a step.
     */
    fun jumpArc(
        startMs: Long,
        peakCm: Double,
        athleteHeightCm: Double,
        frameCount: Int = 20,
        intervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
        scale: Double = 1.0
    ): List<PoseFrame> {

        val peakUnits = unitsForCm(peakCm, athleteHeightCm)

        return (0 until frameCount).map { index ->
            val progress = index.toDouble() / (frameCount - 1)
            // 4p(1-p) peaks at exactly 1.0 halfway through.
            val arc = 4.0 * progress * (1.0 - progress)
            jumpFrame(
                timestampMs = startMs + index * intervalMs,
                displacementUnits = peakUnits * arc,
                scale = scale
            )
        }
    }
}
