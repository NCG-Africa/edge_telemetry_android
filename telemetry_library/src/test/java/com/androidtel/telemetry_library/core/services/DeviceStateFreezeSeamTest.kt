package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.UserInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Seam A (issue #96): the freeze-on-fault device-state bundle must actually land on the built
 * `app.crash` / `app.anr` / `app.hang` payloads — not just inside the [DeviceStateSnapshot] helper.
 * This drives each of the three freeze sites and asserts the 5 `device.*` state keys reach the
 * attribute map the enrichment/sink sees, so a dropped `+ read(context)` merge fails the suite.
 * SDK 29 so `device.thermal_status` (gated ≥29) is present too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DeviceStateFreezeSeamTest {

    private lateinit var context: Context
    private lateinit var service: CrashReportingService
    private var captured: Map<String, Any>? = null
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    private val stateKeys = setOf(
        "device.battery_level", "device.battery_charging",
        "device.power_save", "device.thermal_status", "device.orientation"
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        // Deterministic device state so the assertions are exact, not environment-dependent.
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(battery).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 55)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsPowerSaveMode(true)
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)

        // No-op default handler so the crash rail's chain-to-original in installFatalHandler is inert.
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }

        val config = TelemetryConfig(
            apiKey = "edge_seam_test",
            endpoint = "https://example.com/telemetry",
            enableCrashReporting = true
        )
        val httpClient = TelemetryHttpClient(config.endpoint, config.apiKey, false)
        service = CrashReportingService(context, config, httpClient)
        FatalCrashStore.delete(context.filesDir)

        service.initialize(
            buildAttributesFn = { attrs -> captured = attrs; stubAttributes(attrs) },
            recordCrashEventFn = { captured = it },
            recordHangEventFn = { captured = it }
        )
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        FatalCrashStore.delete(context.filesDir)
    }

    @Test
    fun `app_crash payload carries the 5 device state keys with native types`() {
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertStateKeysPresent()
    }

    @Test
    fun `app_anr payload carries the 5 device state keys`() {
        service.freezeAnr(durationMs = 6000L, threads = emptyList())

        assertStateKeysPresent()
    }

    @Test
    fun `app_hang payload carries the 5 device state keys`() {
        service.recordHang(durationMs = 250L, stack = "at Foo.bar(Foo.kt:1)")

        assertStateKeysPresent()
    }

    private fun assertStateKeysPresent() {
        val attrs = captured ?: error("no attributes were built at the freeze site")
        assertTrue("all 5 state keys present", attrs.keys.containsAll(stateKeys))
        assertEquals(55, attrs["device.battery_level"])
        assertTrue("battery_charging Boolean", attrs["device.battery_charging"] is Boolean)
        assertEquals(true, attrs["device.power_save"])
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, attrs["device.thermal_status"])
        assertTrue("orientation String", attrs["device.orientation"] is String)
    }

    private fun stubAttributes(attrs: Map<String, Any>): EventAttributes = EventAttributes(
        app = AppInfo("TestApp", "1.0.0", "100", "com.test.app"),
        device = DeviceInfo(
            deviceId = "device_001", platform = "android", platformVersion = "13",
            model = "M", manufacturer = "Mf", brand = "B", androidSdk = "33",
            androidRelease = "13", fingerprint = "fp", hardware = "hw", product = "p"
        ),
        user = UserInfo(userId = "user_001"),
        session = SessionInfo(
            sessionId = "session_001", startTime = "2026-07-20T00:00:00.000Z", durationMs = 1L,
            eventCount = 1, metricCount = 0, screenCount = 0, visitedScreens = "",
            isFirstSession = true, totalSessions = 1, networkType = "wifi"
        ),
        customAttributes = attrs
    )
}
