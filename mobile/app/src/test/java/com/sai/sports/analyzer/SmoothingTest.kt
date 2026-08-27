package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The smoothing filter has to do two opposing jobs: kill jitter at rest, and
 * stay out of the way during fast movement. These tests pin both, because
 * tuning one without checking the other is how the jump peak gets flattened.
 */
class SmoothingTest {

    private val intervalMs = 33L

    @Test
    fun `first sample passes through untouched`() {

        val filter = OneEuroFilter()

        assertEquals(0.5, filter.filter(0.5, 0), 0.0001)
    }

    @Test
    fun `constant signal converges to that constant`() {

        val filter = OneEuroFilter()

        var output = 0.0
        repeat(30) { index ->
            output = filter.filter(0.42, index * intervalMs)
        }

        assertEquals(0.42, output, 0.0001)
    }

    @Test
    fun `jitter at rest is substantially reduced`() {

        val random = Random(seed = 42)
        val filter = OneEuroFilter()

        val rawValues = mutableListOf<Double>()
        val smoothedValues = mutableListOf<Double>()

        repeat(120) { index ->
            // Resting landmark: constant position plus realistic pose jitter.
            val raw = 0.5 + (random.nextDouble() - 0.5) * 0.01
            rawValues += raw
            smoothedValues += filter.filter(raw, index * intervalMs)
        }

        // Discard the filter's warm-up before comparing.
        val rawSpread = PoseMath.standardDeviation(rawValues.drop(20))
        val smoothedSpread = PoseMath.standardDeviation(smoothedValues.drop(20))

        assertTrue(
            "Smoothed spread $smoothedSpread should be well under raw $rawSpread",
            smoothedSpread < rawSpread * 0.6
        )
    }

    @Test
    fun `fast movement is tracked with small lag`() {

        val filter = OneEuroFilter()

        // A landmark moving at ~1 unit/s, the speed of a shoulder mid-rep.
        var maxLag = 0.0

        repeat(30) { index ->
            val timeSeconds = index * intervalMs / 1000.0
            val truth = 0.2 + timeSeconds * 1.0
            val smoothed = filter.filter(truth, index * intervalMs)

            if (index > 10) {
                maxLag = maxOf(maxLag, abs(truth - smoothed))
            }
        }

        // 0.02 units is roughly 2% of the frame — a couple of frames of lag at
        // this speed. Enough to keep a jump peak inside the 3cm target.
        assertTrue(
            "Lag of $maxLag units is too much for fast motion",
            maxLag < 0.02
        )
    }

    @Test
    fun `out of order timestamps do not corrupt the filter`() {

        val filter = OneEuroFilter()

        filter.filter(0.5, 1000)
        val repeated = filter.filter(0.9, 1000)

        // A duplicate timestamp implies an infinite rate; the filter holds
        // rather than dividing by zero.
        assertEquals(0.5, repeated, 0.0001)
    }

    @Test
    fun `invisible landmarks are not fed to the filter`() {

        val smoother = PoseSmoother()

        val visible = PoseFrame(
            timestampMs = 0,
            points = List(PoseFrame.LANDMARK_COUNT) {
                PosePoint(0.5f, 0.5f, visibility = 0.9f)
            }
        )

        val hidden = PoseFrame(
            timestampMs = intervalMs,
            points = List(PoseFrame.LANDMARK_COUNT) {
                PosePoint(0.99f, 0.99f, visibility = 0.1f)
            }
        )

        smoother.smooth(visible)
        val smoothedHidden = smoother.smooth(hidden)

        // The garbage coordinates come back untouched rather than being blended
        // into the filter's state, where they would drag the next real samples.
        assertEquals(0.99f, smoothedHidden.points[0].x, 0.0001f)
    }

    @Test
    fun `smoothing preserves the peak of a jump arc`() {

        // The whole reason for One-Euro over a moving average: this peak is the
        // measurement, and a lagging filter reports it as lower than it was.
        val smoother = PoseSmoother()

        val athleteHeightCm = 170.0
        val peakCm = 40.0

        val frames = PoseFixtures.standing(0) + PoseFixtures.jumpArc(
            startMs = 20 * 33L,
            peakCm = peakCm,
            athleteHeightCm = athleteHeightCm
        )

        var lowestHipY = Float.MAX_VALUE

        frames.forEach { frame ->
            val smoothed = smoother.smooth(frame)
            lowestHipY = minOf(lowestHipY, smoothed.points[PoseLandmarkIndex.LEFT_HIP].y)
        }

        val displacementUnits = PoseFixtures.STANDING_HIP_Y - lowestHipY
        val measuredCm = displacementUnits * PoseFixtures.cmPerUnit(athleteHeightCm)

        assertEquals(
            "Smoothing lost too much of the peak",
            peakCm,
            measuredCm,
            AnalyzerThresholds.TARGET_JUMP_TOLERANCE_CM
        )
    }
}
