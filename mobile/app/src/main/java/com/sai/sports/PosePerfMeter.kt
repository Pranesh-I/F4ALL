package com.sai.sports

/**
 * Pose pipeline numbers for the device matrix (Sprint 21), one line a second:
 *
 *     PosePerf fps=24 sent=25 latencyAvgMs=31 latencyMaxMs=48 dropped=1
 *
 * - `sent`: camera frames handed to the model;
 * - `fps`: results that came back;
 * - `latency`: from taking the frame (before the bitmap conversion and
 *   rotation) to the result, so it is what the athlete waits for, not just the
 *   model's own time;
 * - `dropped`: frames sent that never produced a result — MediaPipe's live
 *   stream skips a frame while it is still busy with the last one.
 *
 * Kept free of Android so it is unit-tested; the caller supplies the clock.
 */
class PosePerfMeter(private val windowMs: Long = 1000L) {

    data class Window(
        val fps: Int,
        val sent: Int,
        val latencyAvgMs: Long,
        val latencyMaxMs: Long,
        val dropped: Int
    ) {
        fun logLine(): String =
            "fps=$fps sent=$sent latencyAvgMs=$latencyAvgMs latencyMaxMs=$latencyMaxMs dropped=$dropped"
    }

    private var windowStart = -1L
    private var sent = 0
    private var results = 0
    private var latencySum = 0L
    private var latencyMax = 0L

    @Synchronized
    fun onSent(nowMs: Long) {
        if (windowStart < 0) windowStart = nowMs
        sent++
    }

    /** Returns the finished window when this result closes one, else null. */
    @Synchronized
    fun onResult(frameTimeMs: Long, nowMs: Long): Window? {
        if (windowStart < 0) windowStart = nowMs
        results++
        val latency = (nowMs - frameTimeMs).coerceAtLeast(0)
        latencySum += latency
        latencyMax = maxOf(latencyMax, latency)

        val elapsed = nowMs - windowStart
        if (elapsed < windowMs) return null
        val window = Window(
            fps = (results * 1000L / elapsed).toInt(),
            sent = sent,
            latencyAvgMs = latencySum / results,
            latencyMaxMs = latencyMax,
            dropped = (sent - results).coerceAtLeast(0)
        )
        windowStart = nowMs
        sent = 0
        results = 0
        latencySum = 0
        latencyMax = 0
        return window
    }
}
