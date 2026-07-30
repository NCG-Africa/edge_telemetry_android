package com.androidtel.telemetry_library.core.device

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Freeze-on-fault device-state snapshot (issue #96): the 5 point-in-time `device.*` state keys read
 * at fault time. Seam A — the frozen key bundle. Asserts keys present/absent per API level, native
 * types, and the no-sentinel / omit-on-failure contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], manifest = Config.NONE)
class DeviceStateSnapshotTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `battery, power_save, orientation present with native types at API 24`() {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(battery).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 77)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsPowerSaveMode(true)

        val snap = DeviceStateSnapshot.read(context)

        assertEquals(77, snap["device.battery_level"])
        assertTrue("battery_charging is a Boolean", snap["device.battery_charging"] is Boolean)
        assertEquals(true, snap["device.power_save"])
        assertTrue("orientation is a String", snap["device.orientation"] is String)
    }

    @Test
    fun `thermal_status absent below API 29`() {
        assertFalse(DeviceStateSnapshot.read(context).containsKey("device.thermal_status"))
    }

    @Test
    @Config(sdk = [29])
    fun `thermal_status present as Int at API 29`() {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)

        val snap = DeviceStateSnapshot.read(context)

        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, snap["device.thermal_status"])
    }

    @Test
    fun `unreadable battery level is omitted, never a sentinel`() {
        // Default shadow returns Int.MIN_VALUE for BATTERY_PROPERTY_CAPACITY when unset (unsupported).
        val snap = DeviceStateSnapshot.read(context)

        val level = snap["device.battery_level"]
        // Either a valid 0–100 read or absent — never the Int.MIN_VALUE sentinel, never -1.
        if (level != null) {
            assertTrue("battery_level in 0..100", (level as Int) in 0..100)
        }
        assertFalse(snap.containsValue(Int.MIN_VALUE))
        assertFalse(snap.containsValue(-1))

        // "Omits only its key": a battery read that fails must not drop the battery-independent keys.
        assertTrue("power_save still present", snap.containsKey("device.power_save"))
        assertTrue("orientation still present", snap.containsKey("device.orientation"))
    }
}
