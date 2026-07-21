package com.androidtel.telemetry_library.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #41 D-4 (2): static fence keeping the single-helper invariant. Fails if a
 * timezone-less formatter, an API-26 `Instant.now()`, or a raw epoch-ms
 * timestamp attribute sneaks back into `src/main`.
 *
 * ponytail: a grep-in-a-test, not a detekt rule — zero new tooling, rides the
 * existing testDebugUnitTest gate (#31).
 */
class TimestampSourceGuardTest {

    // No exclusions: the last live Instant.now() landmine lived in FlutterCompatiblePayload.kt,
    // deleted with crash Path B (#56). JsonEventTracker/EdgeTelemetryInterceptor went in #55.
    private val deadInstantFiles = emptySet<String>()

    private val srcMain: File =
        listOf(File("src/main"), File("telemetry_library/src/main"))
            .first { it.isDirectory }

    private fun kotlinSources(): List<File> =
        srcMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `SimpleDateFormat lives only in TelemetryTime`() {
        val offenders = kotlinSources()
            .filter { it.name != "TelemetryTime.kt" }
            .filter { it.readText().contains("SimpleDateFormat(") }
            .map { it.name }
        assertTrue(
            "SimpleDateFormat must go through TelemetryTime; found in: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `no live Instant now (API-26 landmine)`() {
        val offenders = kotlinSources()
            .filter { it.name != "TelemetryTime.kt" } // names it in a doc comment
            .filter { it.name !in deadInstantFiles }
            .filter { it.readText().contains("Instant.now(") }
            .map { it.name }
        assertTrue(
            "Instant.now() crashes on minSdk 24/25; use TelemetryTime. Found in: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `no raw epoch-ms timestamp attributes`() {
        val pattern = Regex(""""[^"]*timestamp[^"]*"\s+to\s+System\.currentTimeMillis\(""")
        val offenders = kotlinSources()
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue(
            "Timestamp attributes must be ISO via TelemetryTime, not epoch-ms. Found in: $offenders",
            offenders.isEmpty()
        )
    }
}
