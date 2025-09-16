package com.androidtel.telemetry_library.core

import android.os.Build
import android.util.Log
import java.util.Date

/**
 * Enhanced memory usage tracking with API-level appropriate methods.
 * Provides progressive enhancement across Android API levels 21-35.
 */
class TelemetryMemoryUsage(private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()) {
    
    companion object {
        private const val TAG = "TelemetryMemoryUsage"
    }
    
    private val memoryCapabilityTracker: MemoryCapabilityTracker? by lazy {
        try {
            telemetryManager.getMemoryCapabilityTracker()
        } catch (e: Exception) {
            Log.w(TAG, "MemoryCapabilityTracker not available: ${e.localizedMessage}")
            null
        }
    }

    /**
     * Record comprehensive memory usage using appropriate APIs for device capabilities
     */
    fun recordMemoryUsage() {
        try {
            if (memoryCapabilityTracker != null) {
                recordEnhancedMemoryUsage()
            } else {
                recordBasicMemoryUsage()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error recording memory usage: ${e.localizedMessage}")
            // Fallback to basic memory tracking
            recordBasicMemoryUsage()
        }
    }
    
    /**
     * Enhanced memory tracking using MemoryCapabilityTracker (API-aware)
     */
    private fun recordEnhancedMemoryUsage() {
        val memoryTracker = memoryCapabilityTracker ?: return
        
        try {
            // Get comprehensive memory summary
            val memorySummary = memoryTracker.getMemoryUsageSummary()
            val memoryInfo = memoryTracker.getMemoryInfo()
            val isUnderPressure = memoryTracker.isUnderMemoryPressure()
            
            // Calculate enhanced pressure level
            val pressureLevel = calculateEnhancedPressureLevel(memorySummary, isUnderPressure)
            val timestamp = telemetryManager.dateFormat.format(Date())
            
            // Record comprehensive memory event
            val memoryAttributes = mutableMapOf<String, Any>(
                "memory.timestamp" to timestamp,
                "memory.pressure_level" to pressureLevel,
                "memory.under_system_pressure" to isUnderPressure,
                "memory.api_level" to Build.VERSION.SDK_INT,
                "memory.tracking_method" to "enhanced_capability_aware"
            )
            
            // Add basic memory metrics (available on all APIs)
            memorySummary["heap_used_mb"]?.let { memoryAttributes["memory.heap_used_mb"] = it }
            memorySummary["heap_max_mb"]?.let { memoryAttributes["memory.heap_max_mb"] = it }
            memorySummary["heap_free_mb"]?.let { memoryAttributes["memory.heap_free_mb"] = it }
            memorySummary["memory_class_mb"]?.let { memoryAttributes["memory.app_memory_class_mb"] = it }
            
            // Add system memory info (API 16+)
            memorySummary["system_available_mb"]?.let { memoryAttributes["memory.system_available_mb"] = it }
            memorySummary["system_low_memory"]?.let { memoryAttributes["memory.system_low_memory"] = it }
            memorySummary["large_memory_class_mb"]?.let { memoryAttributes["memory.large_memory_class_mb"] = it }
            
            // Add advanced memory info where available
            memoryInfo["system_total_bytes"]?.let { 
                memoryAttributes["memory.system_total_mb"] = (it as Long) / (1024 * 1024) 
            }
            memoryInfo["memory_threshold_bytes"]?.let { 
                memoryAttributes["memory.system_threshold_mb"] = (it as Long) / (1024 * 1024) 
            }
            
            // Add detailed memory breakdown (API 26+)
            memoryInfo["native_heap_kb"]?.let { memoryAttributes["memory.native_heap_kb"] = it }
            memoryInfo["total_pss_kb"]?.let { memoryAttributes["memory.total_pss_kb"] = it }
            memoryInfo["process_total_pss_kb"]?.let { memoryAttributes["memory.process_pss_kb"] = it }
            
            // Record memory pressure event
            telemetryManager.recordEvent(
                eventName = "memory_pressure",
                attributes = memoryAttributes
            )
            
            // Record primary memory usage metric
            val heapUsedMb = memorySummary["heap_used_mb"] as? Long ?: 0L
            telemetryManager.recordMetric(
                metricName = "memory_usage",
                value = heapUsedMb.toDouble(),
                attributes = mapOf(
                    "metric.unit" to "MB",
                    "memory.type" to "heap",
                    "memory.source" to "capability_aware",
                    "memory.pressure_level" to pressureLevel
                )
            )
            
            // Record system memory metric if available
            memorySummary["system_available_mb"]?.let { systemAvailableMb ->
                telemetryManager.recordMetric(
                    metricName = "system_memory_available",
                    value = (systemAvailableMb as Long).toDouble(),
                    attributes = mapOf(
                        "metric.unit" to "MB",
                        "memory.type" to "system",
                        "memory.source" to "activity_manager"
                    )
                )
            }
            
            Log.d(TAG, "Enhanced memory usage recorded: pressure=$pressureLevel, heap=${heapUsedMb}MB, API=${Build.VERSION.SDK_INT}")
            
        } catch (e: Exception) {
            Log.w(TAG, "Error in enhanced memory tracking: ${e.localizedMessage}")
            recordBasicMemoryUsage()
        }
    }
    
    /**
     * Basic memory tracking (fallback for API 21+ when enhanced tracking fails)
     */
    private fun recordBasicMemoryUsage() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
            val maxMb = runtime.maxMemory() / (1024.0 * 1024.0)
            val freeMb = runtime.freeMemory() / (1024.0 * 1024.0)
            
            // Basic pressure level calculation
            val pressureLevel = when {
                usedMb < maxMb * 0.5 -> "low"
                usedMb < maxMb * 0.75 -> "moderate"
                else -> "high"
            }
            
            val timestamp = telemetryManager.dateFormat.format(Date())
            
            // Record basic memory event
            telemetryManager.recordEvent(
                eventName = "memory_pressure",
                attributes = mapOf(
                    "memory.heap_used_mb" to usedMb,
                    "memory.heap_max_mb" to maxMb,
                    "memory.heap_free_mb" to freeMb,
                    "memory.pressure_level" to pressureLevel,
                    "memory.timestamp" to timestamp,
                    "memory.api_level" to Build.VERSION.SDK_INT,
                    "memory.tracking_method" to "basic_runtime"
                )
            )
            
            // Record basic memory metric
            telemetryManager.recordMetric(
                metricName = "memory_usage",
                value = usedMb,
                attributes = mapOf(
                    "metric.unit" to "MB",
                    "memory.type" to "heap",
                    "memory.source" to "runtime",
                    "memory.pressure_level" to pressureLevel
                )
            )
            
            Log.d(TAG, "Basic memory usage recorded: ${usedMb.toInt()}MB/${maxMb.toInt()}MB, pressure=$pressureLevel")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in basic memory tracking: ${e.localizedMessage}")
        }
    }
    
    /**
     * Calculate enhanced pressure level using system memory information
     */
    private fun calculateEnhancedPressureLevel(memorySummary: Map<String, Any>, isUnderPressure: Boolean): String {
        return try {
            // If system reports low memory, that's high pressure
            if (isUnderPressure || memorySummary["system_low_memory"] == true) {
                return "high"
            }
            
            val heapUsedMb = memorySummary["heap_used_mb"] as? Long ?: 0L
            val heapMaxMb = memorySummary["heap_max_mb"] as? Long ?: 1L
            val systemAvailableMb = memorySummary["system_available_mb"] as? Long ?: Long.MAX_VALUE
            val memoryClassMb = memorySummary["memory_class_mb"] as? Int ?: 64
            
            val heapUsageRatio = heapUsedMb.toDouble() / heapMaxMb.toDouble()
            val systemPressure = systemAvailableMb < memoryClassMb * 2 // Less than 2x memory class available
            
            when {
                heapUsageRatio > 0.85 || systemPressure -> "high"
                heapUsageRatio > 0.65 -> "moderate"
                else -> "low"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating pressure level: ${e.localizedMessage}")
            "unknown"
        }
    }
    
    /**
     * Record storage usage information
     */
    fun recordStorageUsage() {
        memoryCapabilityTracker?.let { tracker ->
            try {
                val storageInfo = tracker.getStorageInfo()
                val timestamp = telemetryManager.dateFormat.format(Date())
                
                val storageAttributes = mutableMapOf<String, Any>(
                    "storage.timestamp" to timestamp,
                    "storage.api_level" to Build.VERSION.SDK_INT
                )
                
                // Add internal storage info
                storageInfo["internal_total_bytes"]?.let { 
                    storageAttributes["storage.internal_total_mb"] = (it as Long) / (1024 * 1024)
                }
                storageInfo["internal_free_bytes"]?.let { 
                    storageAttributes["storage.internal_free_mb"] = (it as Long) / (1024 * 1024)
                }
                storageInfo["internal_usable_bytes"]?.let { 
                    storageAttributes["storage.internal_usable_mb"] = (it as Long) / (1024 * 1024)
                }
                
                // Add external storage info if available
                storageInfo["external_total_bytes"]?.let { 
                    storageAttributes["storage.external_total_mb"] = (it as Long) / (1024 * 1024)
                }
                storageInfo["external_free_bytes"]?.let { 
                    storageAttributes["storage.external_free_mb"] = (it as Long) / (1024 * 1024)
                }
                
                telemetryManager.recordEvent(
                    eventName = "storage_usage",
                    attributes = storageAttributes
                )
                
                Log.d(TAG, "Storage usage recorded")
                
            } catch (e: Exception) {
                Log.w(TAG, "Error recording storage usage: ${e.localizedMessage}")
            }
        }
    }
}