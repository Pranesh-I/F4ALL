package com.sai.sports.analyzer

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Geometry helpers shared by the analyzers.
 *
 * Pure functions over [PosePoint] — no MediaPipe, no Android. Everything here
 * is directly unit-testable and directly portable to the Sprint 5 Python
 * re-verification pipeline.
 */
object PoseMath {

    /**
     * Angle in degrees at [vertex], between the rays to [first] and [second].
     *
     * Computed in the image plane only (x/y). The z coordinate from the pose
     * model is a relative depth estimate, not a metric one, and folding it in
     * adds more noise than signal at this resolution.
     *
     * Returns 0.0 when either ray has zero length — the caller's quality gate
     * is expected to have already rejected degenerate frames.
     */
    fun angle(
        first: PosePoint,
        vertex: PosePoint,
        second: PosePoint
    ): Double {

        val firstX = first.x - vertex.x
        val firstY = first.y - vertex.y

        val secondX = second.x - vertex.x
        val secondY = second.y - vertex.y

        val dotProduct = firstX * secondX + firstY * secondY

        val firstMagnitude = sqrt(firstX * firstX + firstY * firstY)
        val secondMagnitude = sqrt(secondX * secondX + secondY * secondY)

        if (firstMagnitude == 0f || secondMagnitude == 0f) {
            return 0.0
        }

        val cosine = (dotProduct / (firstMagnitude * secondMagnitude))
            .coerceIn(-1f, 1f)

        return Math.toDegrees(acos(cosine.toDouble()))
    }

    /** Euclidean distance in the image plane, in normalized units. */
    fun distance(
        first: PosePoint,
        second: PosePoint
    ): Float {
        val deltaX = first.x - second.x
        val deltaY = first.y - second.y
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    /**
     * Vertical distance only, in normalized units.
     *
     * Positive when [lower] sits below [upper] on screen. Jump measurement uses
     * this rather than [distance] because horizontal sway during a jump should
     * not inflate the measured height.
     */
    fun verticalDistance(
        upper: PosePoint,
        lower: PosePoint
    ): Float = lower.y - upper.y

    /** Midpoint of two landmarks, with visibility taken as the weaker of the two. */
    fun midpoint(
        first: PosePoint,
        second: PosePoint
    ) = PosePoint(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f,
        z = (first.z + second.z) / 2f,
        visibility = minOf(first.visibility, second.visibility)
    )

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    /** Population standard deviation. */
    fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val average = mean(values)
        val variance = values.sumOf { (it - average) * (it - average) } / values.size
        return sqrt(variance)
    }

    /**
     * Picks the more visible side of the body for a set of landmark roles.
     *
     * Sit-ups are filmed from the side, so one half of the body is always
     * partially occluded by the other. Locking onto whichever side the pose
     * model can actually see beats hardcoding "left" and hoping the athlete
     * lies down the right way round.
     */
    fun selectMoreVisibleSide(
        frame: PoseFrame,
        leftIndices: List<Int>,
        rightIndices: List<Int>
    ): BodySide {
        val leftVisibility = frame.meanVisibility(leftIndices)
        val rightVisibility = frame.meanVisibility(rightIndices)
        return if (rightVisibility > leftVisibility) BodySide.RIGHT else BodySide.LEFT
    }
}
