package com.androidtel.telemetry_library.core.device

import android.content.Context
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Freeze-on-fault device-state snapshot (issue #96, spec `docs/specs/dynamic-device-state.md`).
 *
 * One point-in-time read merged into a fault event's attributes at the three freeze sites
 * (`app.crash` / `app.anr` / `app.hang`) before enrichment. No timer, no `device.state` event, no
 * receivers, no listeners — present only on fault events, unlike the always-present static `device.*`
 * bundle (#93).
 *
 * **Must not throw.** Reads run on the crashing / watchdog thread. Each read is individually guarded;
 * on failure or below-min API the key is simply **omitted** — never a sentinel (`-1`) or a fabricated
 * `false`. An absent key means "couldn't read", and the fault event still freezes regardless.
 */
internal object DeviceStateSnapshot {

    fun read(context: Context): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        val batteryManager = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull()
        val powerManager = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull()

        // device.battery_level (Int 0–100). BATTERY_PROPERTY_CAPACITY yields Int.MIN_VALUE when unsupported.
        runCatching { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrNull()
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { out["device.battery_level"] = it }

        // device.battery_charging (Bool)
        runCatching { batteryManager?.isCharging }
            .getOrNull()?.let { out["device.battery_charging"] = it }

        // device.power_save (Bool)
        runCatching { powerManager?.isPowerSaveMode }
            .getOrNull()?.let { out["device.power_save"] = it }

        // device.thermal_status (Int 0–6) — gated minSDK 29 (getCurrentThermalStatus), key absent below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus }
                .getOrNull()?.let { out["device.thermal_status"] = it }
        }

        // device.orientation ("portrait" / "landscape"); UNDEFINED omitted rather than guessed.
        runCatching {
            when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                else -> null
            }
        }.getOrNull()?.let { out["device.orientation"] = it }

        return out
    }
}
