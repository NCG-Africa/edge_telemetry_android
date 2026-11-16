package com.androidtel.telemetry_library.core

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import com.androidtel.telemetry_library.core.log.TelemetryLog

/**
 * Memory and storage samples on the catalogue's names (processor #33, #145):
 *  - `memory.usage`: the periodic sample. `memory.usage_mb` = the process's total PSS in MB.
 *  - `memory.pressure`: the OS signal, `onTrimMemory`, with its level mapped to `normal|moderate|critical`.
 *  - `storage.usage`: internal storage, MB.
 */
interface MemoryTracker {
    fun recordMemoryUsage()
    fun recordStorageUsage()
    /** Forward of `ComponentCallbacks2.onTrimMemory`; emits `memory.pressure` for pressure levels. */
    fun recordTrimMemory(level: Int)
    /** The last trim level's catalogue pressure: `normal` until the OS says otherwise. */
    fun getCurrentMemoryPressure(): String
    fun isUnderMemoryPressure(): Boolean
}

object MemoryTrackerFactory {
    fun createMemoryTracker(telemetryManager: TelemetryManager): MemoryTracker =
        ProcessMemoryTracker(telemetryManager, telemetryManager.applicationContext)
}

internal class ProcessMemoryTracker(
    private val telemetryManager: TelemetryManager,
    private val context: Context,
    // Seam: Debug.getPss() (kB). Robolectric returns 0.
    private val pssKb: () -> Long = { Debug.getPss() }
) : MemoryTracker {

    @Volatile
    private var pressure = PRESSURE_NORMAL

    override fun recordMemoryUsage() {
        if (!telemetryManager.isMemoryTrackingEnabled()) return
        try {
            val runtime = Runtime.getRuntime()
            val system = ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }
            telemetryManager.emit(
                "memory.usage",
                mapOf(
                    "memory.usage_mb" to usageMb(),
                    "memory.source" to "pss",
                    "memory.heap_used_mb" to mb(runtime.totalMemory() - runtime.freeMemory()),
                    "memory.heap_max_mb" to mb(runtime.maxMemory()),
                    "memory.system_available_mb" to mb(system.availMem),
                    "memory.total_mb" to mb(system.totalMem)
                )
            )
        } catch (e: Exception) {
            TelemetryLog.w(TAG, "Memory sample failed: ${e.localizedMessage}")
        }
    }

    override fun recordStorageUsage() {
        if (!telemetryManager.isStorageTrackingEnabled()) return
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            telemetryManager.emit(
                "storage.usage",
                mapOf(
                    "storage.internal_total_mb" to mb(stat.totalBytes),
                    "storage.internal_free_mb" to mb(stat.freeBytes),
                    "storage.internal_usable_mb" to mb(stat.availableBytes)
                )
            )
        } catch (e: Exception) {
            TelemetryLog.w(TAG, "Storage sample failed: ${e.localizedMessage}")
        }
    }

    override fun recordTrimMemory(level: Int) {
        // UI_HIDDEN is "went to background", not memory pressure.
        val mapped = TRIM_LEVELS[level] ?: return
        pressure = mapped.second
        if (!telemetryManager.isMemoryTrackingEnabled()) return
        telemetryManager.emit(
            "memory.pressure",
            mapOf(
                "memory.pressure_level" to mapped.second,
                "memory.trim_level" to mapped.first,
                "memory.usage_mb" to usageMb()
            )
        )
    }

    override fun getCurrentMemoryPressure(): String = pressure

    override fun isUnderMemoryPressure(): Boolean = pressure != PRESSURE_NORMAL

    private fun usageMb(): Double = pssKb() / 1024.0

    private fun mb(bytes: Long): Double = bytes / 1_048_576.0

    private companion object {
        const val TAG = "MemoryTracker"
        const val PRESSURE_NORMAL = "normal"

        // Trim level -> (memory.trim_level, memory.pressure_level). Background levels are the
        // process's place in the LRU list: near the front is normal, the end is about to be killed.
        @Suppress("DEPRECATION")
        val TRIM_LEVELS = mapOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE to ("running_moderate" to "moderate"),
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to ("running_low" to "moderate"),
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL to ("running_critical" to "critical"),
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND to ("background" to "normal"),
            ComponentCallbacks2.TRIM_MEMORY_MODERATE to ("moderate" to "moderate"),
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE to ("complete" to "critical")
        )
    }
}
