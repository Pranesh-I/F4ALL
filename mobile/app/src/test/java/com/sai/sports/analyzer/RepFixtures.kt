package com.sai.sports.analyzer

import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic pose sequences for the squat, push-up, bicep curl and lunge
 * analyzers.
 *
 * Like [PoseFixtures], these are built from exact joint angles, so a test can
 * say "a rep to 90 deg" and know that is what the analyzer saw. They prove the
 * state machines and form rules behave; they say nothing about whether the
 * thresholds suit real bodies.
 *
 * Directions are angles in screen space: 0 deg is +x (right), 90 deg is +y
 * (DOWN — screen y grows downward). The angle at a joint is the difference
 * between the directions of its two rays, which is how each builder sets
 * joint angles exactly.
 */
object RepFixtures {

    const val INTERVAL = PoseFixtures.DEFAULT_FRAME_INTERVAL_MS

    private const val VISIBLE = 0.95f

    private fun blank(): MutableList<PosePoint> =
        MutableList(PoseFrame.LANDMARK_COUNT) { PosePoint(0.5f, 0.5f, visibility = VISIBLE) }

    private fun step(from: PosePoint, length: Double, directionDegrees: Double): PosePoint {
        val radians = Math.toRadians(directionDegrees)
        return PosePoint(
            x = (from.x + length * cos(radians)).toFloat(),
            y = (from.y + length * sin(radians)).toFloat(),
            visibility = VISIBLE
        )
    }

    private fun MutableList<PosePoint>.both(left: Int, right: Int, point: PosePoint) {
        this[left] = point
        this[right] = point
    }

    // -----------------------------------------------------------------
    // Squat — side-on, facing +x
    // -----------------------------------------------------------------

    /**
     * A squat frame with an exact hip-knee-ankle angle and torso lean from
     * vertical. [farSideVisibility] below the gate simulates the far leg being
     * hidden behind the near one.
     */
    fun squatFrame(
        timestampMs: Long,
        kneeAngle: Double,
        torsoLean: Double = 15.0,
        farSideVisibility: Float = VISIBLE,
        shinTilt: Double = 0.0,
        ankleShift: Double = 0.0
    ): PoseFrame {
        val points = blank()
        val ankle = PosePoint((0.5 + ankleShift).toFloat(), 0.9f, visibility = VISIBLE)
        // A tilted shin pushes the knee forward (+x), past the ankle.
        val knee = step(ankle, 0.2, -90.0 + shinTilt)
        // The ray knee->ankle points down-ish (90 + tilt); the hip ray is kneeAngle away, behind the athlete.
        val hip = step(knee, 0.22, 90.0 + shinTilt + kneeAngle)
        val shoulder = step(hip, 0.28, -90.0 + torsoLean)

        points.both(PoseLandmarkIndex.LEFT_ANKLE, PoseLandmarkIndex.RIGHT_ANKLE, ankle)
        points.both(PoseLandmarkIndex.LEFT_KNEE, PoseLandmarkIndex.RIGHT_KNEE, knee)
        points.both(PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP, hip)
        points.both(PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER, shoulder)

        if (farSideVisibility != VISIBLE) {
            listOf(
                PoseLandmarkIndex.RIGHT_ANKLE, PoseLandmarkIndex.RIGHT_KNEE,
                PoseLandmarkIndex.RIGHT_HIP, PoseLandmarkIndex.RIGHT_SHOULDER
            ).forEach { points[it] = points[it].copy(visibility = farSideVisibility) }
        }

        return PoseFrame(timestampMs, points)
    }

    /**
     * [maxShinTilt] drives the knee past the toes at depth; [maxAnkleShift]
     * slides the feet during the rep.
     */
    fun squat(
        bottomAngle: Double = 85.0,
        maxLean: Double = 35.0,
        farSideVisibility: Float = VISIBLE,
        maxShinTilt: Double = 0.0,
        maxAnkleShift: Double = 0.0
    ): (Long, Double) -> PoseFrame = { timestampMs, progress ->
        squatFrame(
            timestampMs = timestampMs,
            kneeAngle = 175.0 + (bottomAngle - 175.0) * progress,
            torsoLean = 10.0 + (maxLean - 10.0) * progress,
            farSideVisibility = farSideVisibility,
            shinTilt = maxShinTilt * progress,
            ankleShift = maxAnkleShift * progress
        )
    }

    // -----------------------------------------------------------------
    // Push-up — side-on, head toward -x
    // -----------------------------------------------------------------

    /**
     * A push-up frame with an exact elbow angle.
     *
     * [hipOffset] pushes the hip off the shoulder-ankle line, perpendicular to
     * it: positive drops it toward the floor (sagging), negative lifts it
     * (piked). [standing] stands the body upright to fake a push-up with arm
     * bends alone.
     */
    fun pushUpFrame(
        timestampMs: Long,
        elbowAngle: Double,
        hipOffset: Double = 0.0,
        standing: Boolean = false
    ): PoseFrame {
        val points = blank()
        val wrist = PosePoint(0.3f, 0.85f, visibility = VISIBLE)
        val elbow = step(wrist, 0.13, -90.0)
        val shoulder = step(elbow, 0.13, 90.0 - elbowAngle)

        val ankle =
            if (standing) PosePoint(shoulder.x + 0.02f, shoulder.y + 0.55f, visibility = VISIBLE)
            else PosePoint(0.85f, 0.85f, visibility = VISIBLE)

        val deltaX = (ankle.x - shoulder.x).toDouble()
        val deltaY = (ankle.y - shoulder.y).toDouble()
        val length = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
        val hip = PosePoint(
            x = ((shoulder.x + ankle.x) / 2.0 - hipOffset * deltaY / length).toFloat(),
            y = ((shoulder.y + ankle.y) / 2.0 + hipOffset * deltaX / length).toFloat(),
            visibility = VISIBLE
        )

        points.both(PoseLandmarkIndex.LEFT_WRIST, PoseLandmarkIndex.RIGHT_WRIST, wrist)
        points.both(PoseLandmarkIndex.LEFT_ELBOW, PoseLandmarkIndex.RIGHT_ELBOW, elbow)
        points.both(PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER, shoulder)
        points.both(PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP, hip)
        points.both(PoseLandmarkIndex.LEFT_ANKLE, PoseLandmarkIndex.RIGHT_ANKLE, ankle)

        return PoseFrame(timestampMs, points)
    }

    fun pushUp(
        bottomAngle: Double = 80.0,
        hipOffset: Double = 0.0,
        standing: Boolean = false
    ): (Long, Double) -> PoseFrame = { timestampMs, progress ->
        pushUpFrame(
            timestampMs = timestampMs,
            elbowAngle = 170.0 + (bottomAngle - 170.0) * progress,
            hipOffset = hipOffset,
            standing = standing
        )
    }

    // -----------------------------------------------------------------
    // Bicep curl — facing the camera
    // -----------------------------------------------------------------

    /**
     * A curl frame with exact elbow angles for each arm and optional elbow
     * flare (upper arm swung out from the body).
     *
     * The athlete faces the camera, so their left side appears on the right of
     * the image.
     */
    fun curlFrame(
        timestampMs: Long,
        leftElbow: Double,
        rightElbow: Double,
        leftFlare: Double = 0.0,
        rightFlare: Double = 0.0,
        sway: Double = 0.0
    ): PoseFrame {
        val points = blank()

        // Sway moves the upper body sideways over fixed hips.
        val leftShoulder = PosePoint((0.6 + sway).toFloat(), 0.3f, visibility = VISIBLE)
        val rightShoulder = PosePoint((0.4 + sway).toFloat(), 0.3f, visibility = VISIBLE)
        val leftHip = PosePoint(0.58f, 0.6f, visibility = VISIBLE)
        val rightHip = PosePoint(0.42f, 0.6f, visibility = VISIBLE)

        // Upper arms hang down, swung outward (away from the midline) by the flare.
        val leftElbowPoint = step(leftShoulder, 0.15, 90.0 - leftFlare)
        val rightElbowPoint = step(rightShoulder, 0.15, 90.0 + rightFlare)

        // Forearms fold toward the midline: the elbow->shoulder ray is
        // (upper-arm direction + 180), and the forearm sits elbowAngle from it.
        val leftWrist = step(leftElbowPoint, 0.14, 270.0 - leftFlare - leftElbow)
        val rightWrist = step(rightElbowPoint, 0.14, 270.0 + rightFlare + rightElbow)

        points[PoseLandmarkIndex.LEFT_SHOULDER] = leftShoulder
        points[PoseLandmarkIndex.RIGHT_SHOULDER] = rightShoulder
        points[PoseLandmarkIndex.LEFT_HIP] = leftHip
        points[PoseLandmarkIndex.RIGHT_HIP] = rightHip
        points[PoseLandmarkIndex.LEFT_ELBOW] = leftElbowPoint
        points[PoseLandmarkIndex.RIGHT_ELBOW] = rightElbowPoint
        points[PoseLandmarkIndex.LEFT_WRIST] = leftWrist
        points[PoseLandmarkIndex.RIGHT_WRIST] = rightWrist

        return PoseFrame(timestampMs, points)
    }

    /** Curls with the chosen arms; an arm not curling hangs straight. */
    fun curl(
        left: Boolean = false,
        right: Boolean = true,
        topAngle: Double = 40.0,
        flare: Double = 0.0,
        maxSway: Double = 0.0
    ): (Long, Double) -> PoseFrame = { timestampMs, progress ->
        val bent = 170.0 + (topAngle - 170.0) * progress
        curlFrame(
            timestampMs = timestampMs,
            leftElbow = if (left) bent else 170.0,
            rightElbow = if (right) bent else 170.0,
            leftFlare = if (left) flare * progress else 0.0,
            rightFlare = if (right) flare * progress else 0.0,
            sway = maxSway * progress
        )
    }

    // -----------------------------------------------------------------
    // Lunge — side-on, facing +x; left leg in front
    // -----------------------------------------------------------------

    /**
     * A lunge frame.
     *
     * Front leg: thigh raised [frontHipFlexion] deg forward from vertical, knee
     * at [frontKnee]. Back leg: thigh [backHipExtension] deg behind vertical,
     * knee at [backKnee]. With [feetTogether] both legs copy the front leg's
     * geometry — a squat filmed side-on.
     */
    fun lungeFrame(
        timestampMs: Long,
        frontHipFlexion: Double,
        frontKnee: Double,
        backHipExtension: Double,
        backKnee: Double,
        feetTogether: Boolean = false,
        torsoLean: Double = 5.0
    ): PoseFrame {
        val points = blank()
        val hip = PosePoint(0.5f, 0.5f, visibility = VISIBLE)
        val shoulder = step(hip, 0.28, -90.0 + torsoLean)
        points.both(PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER, shoulder)

        val frontKneePoint = step(hip, 0.2, 90.0 - frontHipFlexion)
        val frontAnkle = step(frontKneePoint, 0.2, 270.0 - frontHipFlexion - frontKnee)

        val backKneePoint =
            if (feetTogether) frontKneePoint else step(hip, 0.2, 90.0 + backHipExtension)
        val backAnkle =
            if (feetTogether) frontAnkle
            else step(backKneePoint, 0.2, 270.0 + backHipExtension - backKnee)

        points.both(PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP, hip)
        points[PoseLandmarkIndex.LEFT_KNEE] = frontKneePoint
        points[PoseLandmarkIndex.LEFT_ANKLE] = frontAnkle
        points[PoseLandmarkIndex.RIGHT_KNEE] = backKneePoint
        points[PoseLandmarkIndex.RIGHT_ANKLE] = backAnkle

        return PoseFrame(timestampMs, points)
    }

    /**
     * [frontKneeAtBottom] below 90 with the thigh short of horizontal drives
     * the front knee out past the ankle.
     */
    fun lunge(
        frontHipAtBottom: Double = 90.0,
        frontKneeAtBottom: Double = 90.0,
        backKneeAtBottom: Double = 100.0,
        feetTogether: Boolean = false,
        maxTorsoLean: Double = 10.0
    ): (Long, Double) -> PoseFrame = { timestampMs, progress ->
        lungeFrame(
            timestampMs = timestampMs,
            frontHipFlexion = frontHipAtBottom * progress,
            frontKnee = 178.0 + (frontKneeAtBottom - 178.0) * progress,
            backHipExtension = 10.0 * progress,
            backKnee = 178.0 + (backKneeAtBottom - 178.0) * progress,
            feetTogether = feetTogether,
            torsoLean = maxTorsoLean * progress
        )
    }

    // -----------------------------------------------------------------
    // Sequencing
    // -----------------------------------------------------------------

    /** [count] frames at a fixed progress (0 = start position, 1 = bottom). */
    fun hold(
        startMs: Long,
        count: Int,
        frame: (Long, Double) -> PoseFrame,
        progress: Double = 0.0
    ): List<PoseFrame> =
        (0 until count).map { frame(startMs + it * INTERVAL, progress) }

    /** One rep from the start position to the bottom and back, ending at the start position. */
    fun rep(
        startMs: Long,
        frame: (Long, Double) -> PoseFrame,
        downFrames: Int = 12,
        bottomFrames: Int = 3,
        upFrames: Int = 12
    ): List<PoseFrame> {
        val frames = mutableListOf<PoseFrame>()
        var cursor = startMs
        for (index in 1..downFrames) {
            frames += frame(cursor, index.toDouble() / downFrames)
            cursor += INTERVAL
        }
        repeat(bottomFrames) {
            frames += frame(cursor, 1.0)
            cursor += INTERVAL
        }
        for (index in 1..upFrames) {
            frames += frame(cursor, 1.0 - index.toDouble() / upFrames)
            cursor += INTERVAL
        }
        return frames
    }

    /** Start-position hold, then [reps] reps separated by short holds at the top. */
    fun run(
        reps: Int,
        frame: (Long, Double) -> PoseFrame,
        downFrames: Int = 12,
        upFrames: Int = 12,
        startFrames: Int = 10,
        gapFrames: Int = 6
    ): List<PoseFrame> {
        val frames = mutableListOf<PoseFrame>()
        frames += hold(0, startFrames, frame)
        var cursor = startFrames * INTERVAL
        repeat(reps) {
            val rep = rep(cursor, frame, downFrames = downFrames, upFrames = upFrames)
            frames += rep
            cursor = rep.last().timestampMs + INTERVAL
            frames += hold(cursor, gapFrames, frame)
            cursor += gapFrames * INTERVAL
        }
        return frames
    }

    /** Appends [more] after [frames], re-timed to follow on without a gap. */
    fun then(frames: List<PoseFrame>, more: (Long) -> List<PoseFrame>): List<PoseFrame> {
        val next = (frames.lastOrNull()?.timestampMs ?: -INTERVAL) + INTERVAL
        return frames + more(next)
    }

    fun occluded(startMs: Long, count: Int): List<PoseFrame> =
        (0 until count).map { PoseFixtures.occludedFrame(startMs + it * INTERVAL) }
}
