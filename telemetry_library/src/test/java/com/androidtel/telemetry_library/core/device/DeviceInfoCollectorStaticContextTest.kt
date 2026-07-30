package com.androidtel.telemetry_library.core.device

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Static device-context mint (issue #93): the 9 launch-time `device.*` keys minted once at init,
 * as native types on a `Map<String, Any?>` (not the all-String identity bundle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeviceInfoCollectorStaticContextTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `mints exactly the 9 spec keys with native types`() {
        val ctx = DeviceInfoCollector.collectStaticDeviceContext(context)

        assertEquals(
            setOf(
                "device.cpu_abi", "device.cpu_cores", "device.low_ram",
                "device.screen_density", "device.screen_width_px", "device.screen_height_px",
                "device.dark_mode", "device.locale", "device.timezone"
            ),
            ctx.keys
        )

        assertTrue("cpu_abi is a String", ctx["device.cpu_abi"] is String)
        assertTrue("cpu_cores is an Int", ctx["device.cpu_cores"] is Int)
        assertTrue("low_ram is a Boolean", ctx["device.low_ram"] is Boolean)
        assertTrue("screen_density is an Int", ctx["device.screen_density"] is Int)
        assertTrue("screen_width_px is an Int", ctx["device.screen_width_px"] is Int)
        assertTrue("screen_height_px is an Int", ctx["device.screen_height_px"] is Int)
        assertTrue("dark_mode is a Boolean", ctx["device.dark_mode"] is Boolean)
        assertTrue("locale is a String", ctx["device.locale"] is String)
        assertTrue("timezone is a String", ctx["device.timezone"] is String)
    }

    @Test
    fun `cpu_cores is positive and locale is a BCP-47 tag`() {
        val ctx = DeviceInfoCollector.collectStaticDeviceContext(context)

        assertTrue("at least one core", (ctx["device.cpu_cores"] as Int) >= 1)
        // toLanguageTag() never yields an empty string for a valid default locale.
        assertTrue("locale non-blank", (ctx["device.locale"] as String).isNotBlank())
        assertTrue("timezone non-blank", (ctx["device.timezone"] as String).isNotBlank())
    }
}
