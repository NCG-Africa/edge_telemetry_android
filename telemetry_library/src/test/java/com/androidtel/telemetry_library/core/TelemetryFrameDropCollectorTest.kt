package com.androidtel.telemetry_library.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #54: windowed `frame.summary` replaces the per-frame `frame_drop` firehose.
 * Frames are fed through the internal `onFrame(totalMs, buildMs, rasterMs)` seam; screen and clock
 * are injected so window boundaries (screen-change, 10s cap) can be forced deterministically.
 */
class TelemetryFrameDropCollectorTest {

    private var screen: String? = "A"
    private var clockMs = 0L
    private var epochMs = 0L
    private var refreshRate = 90f
    private val captured = mutableListOf<Map<String, Any>>()
    private val stamps = mutableListOf<String>()

    private fun newCollector(): TelemetryFrameDropCollector {
        captured.clear()
        stamps.clear()
        val manager = mockk<TelemetryManager>(relaxed = true)
        every { manager.isFrameTrackingEnabled() } returns true
        every { manager.emit(eq("frame.summary"), capture(captured), capture(stamps)) } returns Unit
        return TelemetryFrameDropCollector(
            telemetryManager = manager,
            clock = { clockMs },
            screenOverride = { screen },
            refreshRateOverride = { refreshRate },
            epochClock = { epochMs },
        )
    }

    @Test
    fun `counts, rate and maxes match golden payload`() {
        screen = "Home"; clockMs = 0; refreshRate = 90f
        val c = newCollector()
        // 3 smooth (<= the 11.1 ms budget at 90 Hz)
        repeat(3) { c.onFrame(totalMs = 10.0, buildMs = 2.0, rasterMs = 3.0) }
        // 2 slow (budget < ms < 700)
        c.onFrame(totalMs = 20.0, buildMs = 5.0, rasterMs = 8.0)
        c.onFrame(totalMs = 30.0, buildMs = 7.0, rasterMs = 10.0)
        // 1 frozen (>= 700)
        c.onFrame(totalMs = 800.0, buildMs = 12.0, rasterMs = 40.0)
        c.stop() // forces trailing flush

        assertEquals(1, captured.size)
        val a = captured.single()
        assertEquals(6, a["frame.total_frames"])
        assertEquals(3, a["frame.slow_frames"])          // 2 slow + 1 frozen
        assertEquals(1, a["frame.frozen_frames"])
        assertEquals(0.5, a["frame.slow_frame_rate"])    // 3/6
        assertEquals(800.0, a["frame.max_total_duration_ms"])
        assertEquals(12.0, a["frame.max_build_duration_ms"])
        assertEquals(40.0, a["frame.max_raster_duration_ms"])
        assertEquals(90f, a["display.refresh_rate"])
        assertEquals("Home", a["screen.name"])
    }

    @Test
    fun `smooth-only window is still sent`() {
        screen = "A"; clockMs = 0; refreshRate = 90f
        val c = newCollector()
        repeat(10) { c.onFrame(totalMs = 8.0, buildMs = 1.0, rasterMs = 2.0) }
        c.stop()
        assertEquals(1, captured.size)
        assertEquals(10, captured.single()["frame.total_frames"])
        assertEquals(0, captured.single()["frame.slow_frames"])
    }

    @Test
    fun `slow is over the refresh-rate budget and frozen starts at 700 ms`() {
        screen = "A"; clockMs = 0
        refreshRate = 60f
        newCollector().apply { onFrame(15.0, 1.0, 1.0); onFrame(700.0, 1.0, 1.0); stop() }
        assertEquals(1, captured.single()["frame.slow_frames"])   // 15 ms fits 16.7 ms
        assertEquals(1, captured.single()["frame.frozen_frames"])

        refreshRate = 120f
        newCollector().apply { onFrame(15.0, 1.0, 1.0); stop() }
        assertEquals(1, captured.single()["frame.slow_frames"])   // 15 ms is over 8.3 ms

        refreshRate = 0f // unknown rate: 60 Hz budget
        newCollector().apply { onFrame(15.0, 1.0, 1.0); stop() }
        assertEquals(0, captured.single()["frame.slow_frames"])
    }

    @Test
    fun `window is stamped at its start, with an int duration`() {
        screen = "A"; clockMs = 1000; epochMs = 1_700_000_000_000
        val c = newCollector()
        c.onFrame(20.0, 1.0, 1.0)
        clockMs = 3500; epochMs += 2500
        c.onFrame(20.0, 1.0, 1.0)
        c.stop()
        assertEquals(TelemetryTime.isoOf(1_700_000_000_000), stamps.single())
        assertEquals(2500L, captured.single()["frame.window_duration_ms"])
    }

    @Test
    fun `screen change splits into two summaries`() {
        screen = "A"; clockMs = 0
        val c = newCollector()
        c.onFrame(totalMs = 20.0, buildMs = 3.0, rasterMs = 4.0)
        c.onFrame(totalMs = 25.0, buildMs = 3.0, rasterMs = 4.0)
        screen = "B"
        c.onFrame(totalMs = 30.0, buildMs = 3.0, rasterMs = 4.0) // boundary frame -> counts in A, flushes A
        c.onFrame(totalMs = 40.0, buildMs = 3.0, rasterMs = 4.0) // opens & fills B
        c.stop()

        assertEquals(2, captured.size)
        assertEquals("A", captured[0]["screen.name"])
        assertEquals(3, captured[0]["frame.slow_frames"])
        assertEquals("B", captured[1]["screen.name"])
        assertEquals(1, captured[1]["frame.slow_frames"])
    }

    @Test
    fun `10s cap flushes and opens a fresh window`() {
        screen = "A"; clockMs = 1000
        val c = newCollector()
        c.onFrame(totalMs = 20.0, buildMs = 3.0, rasterMs = 4.0) // window starts at 1000
        clockMs = 2000
        c.onFrame(totalMs = 25.0, buildMs = 3.0, rasterMs = 4.0)
        clockMs = 12000 // >= 10s since start -> boundary frame flushes window 1
        c.onFrame(totalMs = 30.0, buildMs = 3.0, rasterMs = 4.0)
        c.onFrame(totalMs = 40.0, buildMs = 3.0, rasterMs = 4.0) // fresh window 2
        c.stop()

        assertEquals(2, captured.size)
        assertEquals(3, captured[0]["frame.slow_frames"]) // window 1
        assertEquals(1, captured[1]["frame.slow_frames"]) // window 2
    }

    @Test
    fun `retired frame_drop event is never emitted`() {
        screen = "A"; clockMs = 0
        val manager = mockk<TelemetryManager>(relaxed = true)
        every { manager.isFrameTrackingEnabled() } returns true
        val c = TelemetryFrameDropCollector(
            telemetryManager = manager,
            clock = { clockMs },
            screenOverride = { screen },
            refreshRateOverride = { 90f },
        )
        c.onFrame(totalMs = 800.0, buildMs = 3.0, rasterMs = 4.0)
        c.stop()
        verify(exactly = 0) { manager.emit(eq("frame_drop"), any(), any()) }
    }
}
