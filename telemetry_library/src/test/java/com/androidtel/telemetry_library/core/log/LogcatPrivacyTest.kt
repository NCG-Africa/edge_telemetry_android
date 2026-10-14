package com.androidtel.telemetry_library.core.log

import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** #140 (SDK-001, CWE-532): profile fields never reach logcat, in any build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogcatPrivacyTest {

    private val pii = listOf("Jane Wanjiru", "jane@example.com", "+254700000001")

    @Before
    fun setup() {
        TelemetryManager.resetForTesting()
        ShadowLog.clear()
    }

    @After
    fun tearDown() = TelemetryManager.resetForTesting()

    private fun setProfileAndCollectLogs(debugMode: Boolean): List<String> {
        val config = TelemetryConfig(
            apiKey = "edge_test_key",
            endpoint = "https://telemetry.ncgafrica.com/telemetry",
            debugMode = debugMode,
        )
        val manager = TelemetryManager.initialize(RuntimeEnvironment.getApplication(), config)
        manager.setUserProfile(pii[0], pii[1], pii[2])
        manager.recordEvent("after_profile") // enrichment path reads the profile back (getUserInfo)
        return ShadowLog.getLogs().map { "${it.msg} ${it.throwable?.message.orEmpty()}" }
    }

    @Test
    fun `setUserProfile with debugMode off logs no profile field`() {
        val leaks = setProfileAndCollectLogs(debugMode = false).filter { line -> pii.any { it in line } }
        assertTrue("profile fields in logcat: $leaks", leaks.isEmpty())
    }

    @Test
    fun `setUserProfile with debugMode on still logs no profile field`() {
        val logs = setProfileAndCollectLogs(debugMode = true)
        assertTrue("debugMode on should surface debug logs", logs.any { "setUserProfile" in it })
        val leaks = logs.filter { line -> pii.any { it in line } }
        assertTrue("profile fields in logcat: $leaks", leaks.isEmpty())
    }
}
