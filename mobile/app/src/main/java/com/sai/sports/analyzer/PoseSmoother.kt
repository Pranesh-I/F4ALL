package com.sai.sports.analyzer

import kotlin.math.abs

/**
 * One-Euro filter.
 *
 * Adaptive low-pass: at rest the cutoff is low so jitter is heavily damped;
 * as the signal moves faster the cutoff rises so real motion passes through
 * with minimal lag.
 *
 * That trade-off is the whole reason this exists instead of a moving average.
 * Vertical jump height is read off a single peak frame — a filter that lags
 * during fast motion reports the athlete jumped lower than they did, and it
 * does so consistently, which is worse than noise because it looks plausible.
 *
 * Reference: Casiez, Roussel & Vogel, "1 Euro Filter" (CHI 2012).
 */
class OneEuroFilter(
    private val minCutoff: Double = AnalyzerThresholds.SMOOTHING_MIN_CUTOFF,
    private val beta: Double = AnalyzerThresholds.SMOOTHING_BETA,
    private val derivativeCutoff: Double = AnalyzerThresholds.SMOOTHING_DERIVATIVE_CUTOFF
) {

    private var previousValue: Double? = null
    private var previousDerivative = 0.0
    private var previousTimestampMs: Long? = null

    fun reset() {
        previousValue = null
        previousDerivative = 0.0
        previousTimestampMs = null
    }

    fun filter(value: Double, timestampMs: Long): Double {

        val lastValue = previousValue
        val lastTimestamp = previousTimestampMs

        if (lastValue == null || lastTimestamp == null) {
            previousValue = value
            previousTimestampMs = timestampMs
            previousDerivative = 0.0
            return value
        }

        val elapsedSeconds = (timestampMs - lastTimestamp) / 1000.0

        // Out-of-order or duplicate timestamps would produce an infinite rate.
        // MediaPipe's async delivery makes this rare but not impossible.
        if (elapsedSeconds <= 0.0) {
            return lastValue
        }

        val sampleRate = 1.0 / elapsedSeconds

        val rawDerivative = (value - lastValue) * sampleRate
        val smoothedDerivative = lowPass(
            value = rawDerivative,
            previous = previousDerivative,
            alpha = alpha(derivativeCutoff, sampleRate)
        )

        val cutoff = minCutoff + beta * abs(smoothedDerivative)

        val smoothedValue = lowPass(
            value = value,
            previous = lastValue,
            alpha = alpha(cutoff, sampleRate)
        )

        previousValue = smoothedValue
        previousDerivative = smoothedDerivative
        previousTimestampMs = timestampMs

        return smoothedValue
    }

    private fun lowPass(value: Double, previous: Double, alpha: Double): Double =
        alpha * value + (1.0 - alpha) * previous

    private fun alpha(cutoff: Double, sampleRate: Double): Double {
        val timeConstant = 1.0 / (2.0 * Math.PI * cutoff)
        val samplePeriod = 1.0 / sampleRate
        return 1.0 / (1.0 + timeConstant / samplePeriod)
    }
}

/**
 * Applies One-Euro smoothing to every landmark coordinate in a stream of frames.
 *
 * Visibility is passed through unfiltered — it is a confidence value, not a
 * position, and smoothing it would mask exactly the dropouts the quality gate
 * needs to see.
 */
class PoseSmoother(
    landmarkCount: Int = PoseFrame.LANDMARK_COUNT
) {

    private val xFilters = List(landmarkCount) { OneEuroFilter() }
    private val yFilters = List(landmarkCount) { OneEuroFilter() }
    private val zFilters = List(landmarkCount) { OneEuroFilter() }

    fun reset() {
        xFilters.forEach { it.reset() }
        yFilters.forEach { it.reset() }
        zFilters.forEach { it.reset() }
    }

    fun smooth(frame: PoseFrame): PoseFrame {

        val smoothedPoints = frame.points.mapIndexed { index, point ->

            if (index >= xFilters.size) {
                return@mapIndexed point
            }

            // An invisible landmark's coordinates are meaningless. Feeding them
            // to the filter would poison its state and drag the next few real
            // samples toward garbage, so the point passes through untouched.
            if (point.visibility < AnalyzerThresholds.MIN_LANDMARK_VISIBILITY) {
                return@mapIndexed point
            }

            PosePoint(
                x = xFilters[index]
                    .filter(point.x.toDouble(), frame.timestampMs)
                    .toFloat(),
                y = yFilters[index]
                    .filter(point.y.toDouble(), frame.timestampMs)
                    .toFloat(),
                z = zFilters[index]
                    .filter(point.z.toDouble(), frame.timestampMs)
                    .toFloat(),
                visibility = point.visibility
            )
        }

        return PoseFrame(
            timestampMs = frame.timestampMs,
            points = smoothedPoints
        )
    }
}
