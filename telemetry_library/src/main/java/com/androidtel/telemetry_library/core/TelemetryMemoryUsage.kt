package com.androidtel.telemetry_library.core

import java.util.Date
class TelemetryMemoryUsage(private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()) {

    // --- Memory Tracking ---
    fun recordMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

        // Determine pressure level
        val pressureLevel = when {
            usedMb < 256 -> "low"
            usedMb < 512 -> "moderate"
            else -> "high"
        }

        val timestamp = telemetryManager.dateFormat.format(Date())

        // Record memory event (attributes-based)
        telemetryManager.recordEvent(
            eventName = "memory_pressure",
            attributes = mapOf(
                "memory.usage_mb" to usedMb,
                "memory.pressure_level" to pressureLevel,
                "memory.timestamp" to timestamp
            )
        )

        // Record memory metric (numeric with unit/type/source)
        telemetryManager.recordMetric(
            metricName = "memory_usage",
            value = usedMb,
            attributes = mapOf(
                "metric.unit" to "MB",
                "memory.type" to "heap",
                "memory.source" to "runtime"
            )
        )
    }

}