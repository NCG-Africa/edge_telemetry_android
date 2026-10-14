package com.androidtel.telemetry_library.core.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #140 static fence: logcat is reached only through [TelemetryLog], so the debugMode gate can't
 * be bypassed. ponytail: grep-in-a-test, same as TimestampSourceGuardTest.
 */
class TelemetryLogGuardTest {

    private val srcMain: File =
        listOf(File("src/main"), File("telemetry_library/src/main")).first { it.isDirectory }

    @Test
    fun `no android Log call outside TelemetryLog`() {
        val rawLog = Regex("""(?<![\w.])(android\.util\.)?Log\.(v|d|i|w|e|wtf|println)\(""")
        val offenders = srcMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "TelemetryLog.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (rawLog.containsMatchIn(line)) "${f.name}:${i + 1}" else null } }
            .toList()
        assertTrue("Route logging through TelemetryLog; raw Log.* at: $offenders", offenders.isEmpty())
    }
}
