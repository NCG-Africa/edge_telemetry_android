package com.androidtel.telemetry_library.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #62 Seam 3 static fence: fails if a symbol the dead-code removal deleted
 * ([docs/specs/dead-code-removal.md]) reappears anywhere in `src/main`. The
 * removals compiled clean because nothing live referenced them; this guard
 * keeps a removed footgun (geo-IP provider, full-URL interceptor, dead payload
 * wrappers) from silently returning under a future edit.
 *
 * The timezone/`Instant.now()` half of the Seam 3 fence lives in
 * [TimestampSourceGuardTest] (#41) — this file only covers deleted symbols.
 *
 * ponytail: a grep-in-a-test, not new tooling — rides the existing
 * testDebugUnitTest gate (#31).
 */
class DeletedSymbolGuardTest {

    // Deleted classes/config from dead-code-removal.md items A–F (+ crash Path B, #56).
    // Word-boundary match: substrings of live names (EventPayloadValidator, etc.) don't trip it.
    private val deletedSymbols = listOf(
        "IpLocationProvider",              // A: geo-IP stack
        "LocationProvider",                // A: 9-line interface, only IpLocationProvider implemented it
        "enableLocationTracking",          // A: no-op config flag
        "JsonEventTracker",                // B
        "testConnectivity",                // B: dead public debug method on TelemetryManager
        "LegacyPerformanceTracker",        // C
        "LegacyPerformanceTrackerWrapper", // C: dead wrapper class
        "EdgeTelemetryInterceptor",        // D: leaked full URL incl. query
        "TelemetryPayload",                // E: dead wrapper
        "EventBatchPayload",               // F: unused batch payload
        "createEventBatchPayload",         // F: its factory
        "FlutterCompatiblePayload",        // crash Path B retirement (#56)
    )

    private val srcMain: File =
        listOf(File("src/main"), File("telemetry_library/src/main"))
            .first { it.isDirectory }

    private fun kotlinSources(): List<File> =
        srcMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no deleted dead-code symbol reappears in src-main`() {
        val offenders = kotlinSources().flatMap { file ->
            val text = file.readText()
            deletedSymbols
                .filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
                .map { "${file.name}: $it" }
        }
        assertTrue(
            "Deleted dead-code symbols must stay deleted (see dead-code-removal.md); found: $offenders",
            offenders.isEmpty()
        )
    }
}
