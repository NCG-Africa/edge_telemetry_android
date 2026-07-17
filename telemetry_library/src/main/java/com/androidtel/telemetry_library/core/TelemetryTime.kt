package com.androidtel.telemetry_library.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single, thread-safe source of truth for point-in-time timestamps on the wire.
 *
 * Every timestamp the SDK emits — envelope `timestamp`, per-event `timestamp`, and every
 * `*.timestamp` attribute — is a millisecond-precision ISO-8601 string in true UTC
 * (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, e.g. `2026-07-16T09:41:00.123Z`).
 *
 * Uses a [ThreadLocal] [SimpleDateFormat] so formatting is safe without locks — [SimpleDateFormat]
 * is not thread-safe. Stdlib only: no `java.time` (which crashes on the SDK's min-SDK 24/25 without
 * core-library desugaring) and no new dependency.
 */
object TelemetryTime {
    private val fmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** Current wall-clock instant as a true-UTC ISO-8601 millisecond string. */
    fun now(): String = isoOf(System.currentTimeMillis())

    /** Convert an epoch-millisecond value to the same true-UTC ISO-8601 millisecond string. */
    fun isoOf(epochMs: Long): String = fmt.get()!!.format(Date(epochMs))
}
