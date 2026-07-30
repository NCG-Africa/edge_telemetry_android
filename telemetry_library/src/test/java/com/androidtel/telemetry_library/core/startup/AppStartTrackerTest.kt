package com.androidtel.telemetry_library.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #95 conformance (spec `docs/specs/app-start-timing.md`): the emit-vs-drop decision at seam B.
 * start-time / importance / clock are injected (prior art AnrWatchdogTest) so no process or real clock
 * is needed — a foreground start emits one `app.start` with type "cold" + Int duration, and each
 * background-start guard drops the sample (emits nothing).
 */
class AppStartTrackerTest {

    // IMPORTANCE_FOREGROUND (100) — a normal foreground cold start.
    private val foreground = 100

    private val emitted = mutableListOf<Pair<String, Int>>()

    private fun tracker(
        startUptimeMs: Long,
        importance: Int,
        now: Long
    ) = AppStartTracker(
        startUptimeMs = startUptimeMs,
        importanceAtInit = importance,
        emit = { type, durationMs -> emitted.add(type to durationMs) },
        clock = { now }
    )

    // AC1/AC2: foreground cold start → one app.start, type "cold", Int duration = resume − start.
    @Test
    fun `foreground cold start emits one cold app_start with duration`() {
        tracker(startUptimeMs = 1_000, importance = foreground, now = 1_850).onFirstResume()

        assertEquals(1, emitted.size)
        val (type, durationMs) = emitted.first()
        assertEquals("cold", type)
        assertEquals(850, durationMs)
    }

    // AC2: only the first observed resume is timed; later resumes are ignored.
    @Test
    fun `only the first resume is timed`() {
        val t = tracker(startUptimeMs = 0, importance = foreground, now = 500)
        t.onFirstResume()
        t.onFirstResume()
        t.onFirstResume()

        assertEquals(1, emitted.size)
    }

    // AC3: importance worse than VISIBLE at init (SERVICE = 300) → background-forked start, drop.
    @Test
    fun `background-forked start emits nothing`() {
        val service = 300
        tracker(startUptimeMs = 0, importance = service, now = 500).onFirstResume()

        assertTrue(emitted.isEmpty())
    }

    // Boundary: VISIBLE (200) is kept — the guard is strictly worse-than-VISIBLE.
    @Test
    fun `visible importance at the boundary still emits`() {
        tracker(startUptimeMs = 0, importance = AppStartTracker.IMPORTANCE_VISIBLE, now = 500)
            .onFirstResume()

        assertEquals(1, emitted.size)
    }

    // AC4: duration over the 60s cap → drop.
    @Test
    fun `duration over the cap emits nothing`() {
        tracker(startUptimeMs = 0, importance = foreground, now = AppStartTracker.MAX_DURATION_MS + 1)
            .onFirstResume()

        assertTrue(emitted.isEmpty())
    }

    // Boundary: exactly at the cap still emits (guard is strictly greater-than).
    @Test
    fun `duration exactly at the cap still emits`() {
        tracker(startUptimeMs = 0, importance = foreground, now = AppStartTracker.MAX_DURATION_MS)
            .onFirstResume()

        assertEquals(1, emitted.size)
        assertEquals(AppStartTracker.MAX_DURATION_MS.toInt(), emitted.first().second)
    }
}
