package com.androidtel.telemetry_library.core.exit

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam C (issue #94): pure harvest(records, watermark) -> (events, newWatermark).
 * No Android runtime needed — ExitRecord is a plain data holder and the reason/importance
 * constants are compile-time ints, so this runs as a plain JUnit test (no Robolectric).
 */
class ApplicationExitHarvesterTest {

    private fun rec(
        reason: Int,
        ts: Long,
        importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        description: String? = "desc",
        status: Int = 0,
        pss: Long = 100,
        rss: Long = 200,
        trace: String? = null,
    ) = ExitRecord(reason, importance, description, status, pss, rss, ts, trace)

    @Test
    fun `abnormal reasons map to enum strings`() {
        val records = listOf(
            rec(ApplicationExitInfo.REASON_ANR, 10),
            rec(ApplicationExitInfo.REASON_CRASH, 9),
            rec(ApplicationExitInfo.REASON_CRASH_NATIVE, 8),
            rec(ApplicationExitInfo.REASON_LOW_MEMORY, 7),
            rec(ApplicationExitInfo.REASON_SIGNALED, 6),
            rec(ApplicationExitInfo.REASON_DEPENDENCY_DIED, 5),
            rec(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, 4),
            rec(ApplicationExitInfo.REASON_PERMISSION_CHANGE, 3),
        )

        val reasons = ApplicationExitHarvester.harvest(records, watermark = null)
            .events.map { it["exit.reason"] }

        assertEquals(
            listOf(
                "anr", "crash", "crash_native", "low_memory",
                "signaled", "dependency_died", "excessive_resource_usage", "permission_change",
            ),
            reasons,
        )
    }

    @Test
    fun `benign reasons are dropped`() {
        val records = listOf(
            rec(ApplicationExitInfo.REASON_EXIT_SELF, 10),
            rec(ApplicationExitInfo.REASON_USER_REQUESTED, 9),
            rec(ApplicationExitInfo.REASON_OTHER, 8),
            rec(ApplicationExitInfo.REASON_ANR, 7),
        )

        val result = ApplicationExitHarvester.harvest(records, watermark = null)

        assertEquals(1, result.events.size)
        assertEquals("anr", result.events.single()["exit.reason"])
    }

    @Test
    fun `strict watermark never re-emits the watermark record`() {
        val records = listOf(
            rec(ApplicationExitInfo.REASON_ANR, 30),
            rec(ApplicationExitInfo.REASON_ANR, 20),
            rec(ApplicationExitInfo.REASON_ANR, 10),
        )

        val result = ApplicationExitHarvester.harvest(records, watermark = 20)

        // Only ts=30 survives (>20); ts=20 (== watermark) and ts=10 are excluded.
        assertEquals(listOf(30L), result.events.map { it["exit.timestamp"] })
        assertEquals(30L, result.newWatermark)
    }

    @Test
    fun `first launch backfills whole buffer and sets watermark to newest emitted`() {
        val records = listOf(
            rec(ApplicationExitInfo.REASON_ANR, 30),
            rec(ApplicationExitInfo.REASON_LOW_MEMORY, 20),
        )

        val result = ApplicationExitHarvester.harvest(records, watermark = null)

        assertEquals(2, result.events.size)
        assertEquals(30L, result.newWatermark)
    }

    @Test
    fun `nothing emitted keeps the prior watermark`() {
        val records = listOf(rec(ApplicationExitInfo.REASON_ANR, 10))

        val result = ApplicationExitHarvester.harvest(records, watermark = 50)

        assertTrue(result.events.isEmpty())
        assertEquals(50L, result.newWatermark)
    }

    @Test
    fun `trace over cap is truncated with marker, under cap is intact`() {
        val big = "x".repeat(10_000)
        val small = "short trace"
        val records = listOf(
            rec(ApplicationExitInfo.REASON_ANR, 20, trace = big),
            rec(ApplicationExitInfo.REASON_CRASH_NATIVE, 10, trace = small),
        )

        val events = ApplicationExitHarvester.harvest(records, watermark = null).events
        val bigTrace = events[0]["exit.trace"] as String
        val smallTrace = events[1]["exit.trace"] as String

        assertTrue(bigTrace.length < big.length)
        assertTrue(bigTrace.contains("truncated"))
        assertEquals(small, smallTrace)
    }

    @Test
    fun `trace absent when null`() {
        val records = listOf(rec(ApplicationExitInfo.REASON_LOW_MEMORY, 10, trace = null))

        val event = ApplicationExitHarvester.harvest(records, watermark = null).events.single()

        assertFalse(event.containsKey("exit.trace"))
    }

    @Test
    fun `importance maps to enum string, null description omitted`() {
        val records = listOf(
            rec(
                ApplicationExitInfo.REASON_ANR, 10,
                importance = RunningAppProcessInfo.IMPORTANCE_VISIBLE,
                description = null,
            ),
        )

        val event = ApplicationExitHarvester.harvest(records, watermark = null).events.single()

        assertEquals("visible", event["exit.importance"])
        assertFalse(event.containsKey("exit.description"))
    }

    @Test
    fun `carries pss rss status timestamp`() {
        val records = listOf(
            rec(ApplicationExitInfo.REASON_ANR, ts = 12345, status = 9, pss = 4096, rss = 8192),
        )

        val event = ApplicationExitHarvester.harvest(records, watermark = null).events.single()

        assertEquals(9, event["exit.status"])
        assertEquals(4096, event["exit.pss_kb"])
        assertEquals(8192, event["exit.rss_kb"])
        assertEquals(12345L, event["exit.timestamp"])
    }
}
