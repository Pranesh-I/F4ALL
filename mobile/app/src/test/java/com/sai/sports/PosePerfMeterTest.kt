package com.sai.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosePerfMeterTest {

    @Test
    fun `a window reports results per second, latency and frames that never came back`() {
        val meter = PosePerfMeter()
        // 10 frames sent 100 ms apart; the 4th is dropped by the model.
        var window: PosePerfMeter.Window? = null
        for (i in 0 until 10) {
            val sentAt = i * 100L
            meter.onSent(sentAt)
            if (i == 3) continue
            val result = meter.onResult(frameTimeMs = sentAt, nowMs = sentAt + 30 + i)
            if (result != null) window = result
        }
        assertNull(window)
        meter.onSent(1000)
        window = meter.onResult(frameTimeMs = 1000, nowMs = 1050)

        val closed = window!!
        assertEquals(11, closed.sent)
        assertEquals(1, closed.dropped)
        assertEquals(50, closed.latencyMaxMs)
        assertEquals("fps=9 sent=11 latencyAvgMs=36 latencyMaxMs=50 dropped=1", closed.logLine())
    }

    @Test
    fun `the next window starts empty`() {
        val meter = PosePerfMeter(windowMs = 100)
        meter.onSent(0)
        meter.onResult(0, 100)!!
        meter.onSent(150)
        val next = meter.onResult(150, 210)!!
        assertEquals(1, next.sent)
        assertEquals(0, next.dropped)
        assertEquals(60, next.latencyAvgMs)
    }
}
