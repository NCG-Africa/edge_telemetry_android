package com.androidtel.telemetry_library.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * Seam 2 — verifies [TelemetryTime] produces true-UTC ISO-8601 millisecond strings regardless
 * of the JVM/device default timezone. Guards against the "fake UTC" bug where a `SimpleDateFormat`
 * appends a literal `Z` but formats in the device's local timezone.
 */
class TelemetryTimeTest {

    private lateinit var originalTz: TimeZone

    @Before
    fun setUp() {
        originalTz = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    @Test
    fun `isoOf epoch zero is the UTC epoch string`() {
        assertEquals("1970-01-01T00:00:00.000Z", TelemetryTime.isoOf(0L))
    }

    @Test
    fun `isoOf formats a known epoch as true UTC millisecond string`() {
        // 2026-07-16T09:41:00.123Z
        val epochMs = 1_784_194_860_123L
        assertEquals("2026-07-16T09:41:00.123Z", TelemetryTime.isoOf(epochMs))
    }

    @Test
    fun `isoOf ignores a non-UTC default timezone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-4/5
        assertEquals("1970-01-01T00:00:00.000Z", TelemetryTime.isoOf(0L))

        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi")) // UTC+3
        assertEquals("2026-07-16T09:41:00.123Z", TelemetryTime.isoOf(1_784_194_860_123L))
    }

    @Test
    fun `now produces a fixed-width UTC ISO-8601 millisecond string`() {
        val value = TelemetryTime.now()
        // e.g. 2026-07-16T09:41:00.123Z — exactly 24 chars, ends in millis + Z
        assertEquals(24, value.length)
        assertTrue("expected trailing Z, got $value", value.endsWith("Z"))
        assertTrue(
            "unexpected shape: $value",
            value.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"""))
        )
    }
}
