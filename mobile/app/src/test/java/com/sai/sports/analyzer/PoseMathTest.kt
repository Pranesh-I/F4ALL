package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseMathTest {

    private fun point(x: Float, y: Float, visibility: Float = 1f) =
        PosePoint(x = x, y = y, visibility = visibility)

    @Test
    fun `straight limb measures 180 degrees`() {

        val angle = PoseMath.angle(
            first = point(0f, 0f),
            vertex = point(1f, 0f),
            second = point(2f, 0f)
        )

        assertEquals(180.0, angle, 0.001)
    }

    @Test
    fun `perpendicular limbs measure 90 degrees`() {

        val angle = PoseMath.angle(
            first = point(0f, 1f),
            vertex = point(0f, 0f),
            second = point(1f, 0f)
        )

        assertEquals(90.0, angle, 0.001)
    }

    @Test
    fun `fully folded limb measures 0 degrees`() {

        val angle = PoseMath.angle(
            first = point(1f, 0f),
            vertex = point(0f, 0f),
            second = point(2f, 0f)
        )

        assertEquals(0.0, angle, 0.001)
    }

    @Test
    fun `degenerate points do not blow up`() {

        val angle = PoseMath.angle(
            first = point(0f, 0f),
            vertex = point(0f, 0f),
            second = point(1f, 0f)
        )

        assertEquals(0.0, angle, 0.001)
    }

    @Test
    fun `fixture geometry produces the angle it claims to`() {

        // The analyzer tests rest on this being true.
        listOf(30.0, 70.0, 90.0, 135.0, 155.0).forEach { expected ->

            val frame = PoseFixtures.sitUpFrame(0, expected)

            val actual = PoseMath.angle(
                first = frame[PoseLandmarkIndex.LEFT_SHOULDER]!!,
                vertex = frame[PoseLandmarkIndex.LEFT_HIP]!!,
                second = frame[PoseLandmarkIndex.LEFT_KNEE]!!
            )

            assertEquals(expected, actual, 0.1)
        }
    }

    @Test
    fun `vertical distance ignores horizontal movement`() {

        val distance = PoseMath.verticalDistance(
            upper = point(0.1f, 0.2f),
            lower = point(0.9f, 0.6f)
        )

        assertEquals(0.4f, distance, 0.0001f)
    }

    @Test
    fun `more visible side is selected`() {

        val points = MutableList(PoseFrame.LANDMARK_COUNT) {
            PosePoint(0.5f, 0.5f, visibility = 0.2f)
        }

        points[PoseLandmarkIndex.RIGHT_SHOULDER] = point(0.5f, 0.5f, 0.9f)
        points[PoseLandmarkIndex.RIGHT_HIP] = point(0.5f, 0.5f, 0.9f)
        points[PoseLandmarkIndex.RIGHT_KNEE] = point(0.5f, 0.5f, 0.9f)

        val side = PoseMath.selectMoreVisibleSide(
            frame = PoseFrame(0, points),
            leftIndices = listOf(
                PoseLandmarkIndex.LEFT_SHOULDER,
                PoseLandmarkIndex.LEFT_HIP,
                PoseLandmarkIndex.LEFT_KNEE
            ),
            rightIndices = listOf(
                PoseLandmarkIndex.RIGHT_SHOULDER,
                PoseLandmarkIndex.RIGHT_HIP,
                PoseLandmarkIndex.RIGHT_KNEE
            )
        )

        assertEquals(BodySide.RIGHT, side)
    }

    @Test
    fun `standard deviation is zero for a constant signal`() {

        assertEquals(0.0, PoseMath.standardDeviation(listOf(0.5, 0.5, 0.5)), 0.0001)
    }

    @Test
    fun `standard deviation grows with spread`() {

        val tight = PoseMath.standardDeviation(listOf(0.50, 0.51, 0.49))
        val loose = PoseMath.standardDeviation(listOf(0.30, 0.70, 0.50))

        assertTrue(loose > tight)
    }

    @Test
    fun `quality gate rejects frames with an invisible required landmark`() {

        val points = MutableList(PoseFrame.LANDMARK_COUNT) {
            PosePoint(0.5f, 0.5f, visibility = 0.9f)
        }
        points[PoseLandmarkIndex.LEFT_KNEE] = point(0.5f, 0.5f, 0.2f)

        val gate = FrameQualityGate(
            requiredIndices = listOf(
                PoseLandmarkIndex.LEFT_SHOULDER,
                PoseLandmarkIndex.LEFT_HIP,
                PoseLandmarkIndex.LEFT_KNEE
            )
        )

        val verdict = gate.evaluate(PoseFrame(0, points))

        assertTrue(!verdict.accepted)
        assertEquals(listOf(PoseLandmarkIndex.LEFT_KNEE), verdict.missingIndices)
    }
}
