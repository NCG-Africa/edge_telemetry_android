package com.androidtel.telemetry_library.core

import android.os.Build
import android.util.Log

/**
 * Unified memory tracking interface that provides consistent memory telemetry
 * across all Android API levels with progressive enhancement.
 */
interface MemoryTracker {
    /**
     * Record memory usage with appropriate detail level for device capabilities
     */
    fun recordMemoryUsage()
    
    /**
     * Record storage usage information
     */
    fun recordStorageUsage()
    
    /**
     * Get current memory pressure level
     */
    fun getCurrentMemoryPressure(): String
    
    /**
     * Check if device is under memory pressure
     */
    fun isUnderMemoryPressure(): Boolean
}

/**
 * Factory for creating appropriate memory tracker based on device capabilities
 */
object MemoryTrackerFactory {
    
    private const val TAG = "MemoryTrackerFactory"
    
    /**
     * Create memory tracker with appropriate implementation for device API level
     */
    fun createMemoryTracker(telemetryManager: TelemetryManager): MemoryTracker {
        return try {
            val capabilities = telemetryManager.getDeviceCapabilities()
            val memoryCapabilityTracker = telemetryManager.getMemoryCapabilityTracker()
            
            when {
                // Enhanced memory tracking (API 21+ with MemoryCapabilityTracker)
                capabilities != null && memoryCapabilityTracker != null -> {
                    Log.d(TAG, "Creating enhanced memory tracker for API ${Build.VERSION.SDK_INT}")
                    EnhancedMemoryTracker(telemetryManager, memoryCapabilityTracker)
                }
                
                // Basic memory tracking (fallback for API 21+)
                else -> {
                    Log.d(TAG, "Creating basic memory tracker for API ${Build.VERSION.SDK_INT}")
                    BasicMemoryTracker(telemetryManager)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error creating memory tracker, using basic fallback: ${e.localizedMessage}")
            BasicMemoryTracker(telemetryManager)
        }
    }
}

/**
 * Enhanced memory tracker using MemoryCapabilityTracker for detailed insights
 */
class EnhancedMemoryTracker(
    private val telemetryManager: TelemetryManager,
    private val memoryCapabilityTracker: MemoryCapabilityTracker
) : MemoryTracker {
    
    companion object {
        private const val TAG = "EnhancedMemoryTracker"
    }
    
    override fun recordMemoryUsage() {
        try {
            val memorySummary = memoryCapabilityTracker.getMemoryUsageSummary()
            val memoryInfo = memoryCapabilityTracker.getMemoryInfo()
            val isUnderPressure = memoryCapabilityTracker.isUnderMemoryPressure()
            
            val pressureLevel = calculatePressureLevel(memorySummary, isUnderPressure)
            
            // Create standardized memory event
            val memoryAttributes = createStandardizedMemoryEvent(
                memorySummary, memoryInfo, pressureLevel, isUnderPressure, "enhanced"
            )
            
            telemetryManager.recordEvent(
                eventName = "memory_pressure",
                attributes = memoryAttributes
            )
            
            // Record primary memory metric
            val heapUsedMb = memorySummary["heap_used_mb"] as? Long ?: 0L
            telemetryManager.recordMetric(
                metricName = "memory_usage",
                value = heapUsedMb.toDouble(),
                attributes = mapOf(
                    "metric.unit" to "MB",
                    "memory.type" to "heap",
                    "memory.source" to "enhanced_tracker",
                    "memory.pressure_level" to pressureLevel
                )
            )
            
            Log.d(TAG, "Enhanced memory usage recorded: ${heapUsedMb}MB, pressure=$pressureLevel")
            
        } catch (e: Exception) {
            Log.w(TAG, "Error recording enhanced memory usage: ${e.localizedMessage}")
        }
    }
    
    override fun recordStorageUsage() {
        try {
            val storageInfo = memoryCapabilityTracker.getStorageInfo()
            
            val storageAttributes = mutableMapOf<String, Any>(
                "storage.api_level" to Build.VERSION.SDK_INT,
                "storage.tracking_method" to "enhanced"
            )
            
            // Add storage metrics
            storageInfo["internal_total_bytes"]?.let { 
                storageAttributes["storage.internal_total_mb"] = (it as Long) / (1024 * 1024)
            }
            storageInfo["internal_free_bytes"]?.let { 
                storageAttributes["storage.internal_free_mb"] = (it as Long) / (1024 * 1024)
            }
            storageInfo["internal_usable_bytes"]?.let { 
                storageAttributes["storage.internal_usable_mb"] = (it as Long) / (1024 * 1024)
            }
            
            telemetryManager.recordEvent(
                eventName = "storage_usage",
                attributes = storageAttributes
            )
            
        } catch (e: Exception) {
            Log.w(TAG, "Error recording storage usage: ${e.localizedMessage}")
        }
    }
    
    override fun getCurrentMemoryPressure(): String {
        return try {
            val memorySummary = memoryCapabilityTracker.getMemoryUsageSummary()
            val isUnderPressure = memoryCapabilityTracker.isUnderMemoryPressure()
            calculatePressureLevel(memorySummary, isUnderPressure)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting memory pressure: ${e.localizedMessage}")
            "unknown"
        }
    }
    
    override fun isUnderMemoryPressure(): Boolean {
        return try {
            memoryCapabilityTracker.isUnderMemoryPressure()
        } catch (e: Exception) {
            Log.w(TAG, "Error checking memory pressure: ${e.localizedMessage}")
            false
        }
    }
    
    private fun calculatePressureLevel(memorySummary: Map<String, Any>, isUnderPressure: Boolean): String {
        return try {
            if (isUnderPressure || memorySummary["system_low_memory"] == true) {
                return "high"
            }
            
            val heapUsedMb = memorySummary["heap_used_mb"] as? Long ?: 0L
            val heapMaxMb = memorySummary["heap_max_mb"] as? Long ?: 1L
            val systemAvailableMb = memorySummary["system_available_mb"] as? Long ?: Long.MAX_VALUE
            val memoryClassMb = memorySummary["memory_class_mb"] as? Int ?: 64
            
            val heapUsageRatio = heapUsedMb.toDouble() / heapMaxMb.toDouble()
            val systemPressure = systemAvailableMb < memoryClassMb * 2
            
            when {
                heapUsageRatio > 0.85 || systemPressure -> "high"
                heapUsageRatio > 0.65 -> "moderate"
                else -> "low"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
}

/**
 * Basic memory tracker using Runtime for API 21+ fallback
 */
class BasicMemoryTracker(
    private val telemetryManager: TelemetryManager
) : MemoryTracker {
    
    companion object {
        private const val TAG = "BasicMemoryTracker"
    }
    
    override fun recordMemoryUsage() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
            val maxMb = runtime.maxMemory() / (1024.0 * 1024.0)
            val freeMb = runtime.freeMemory() / (1024.0 * 1024.0)
            
            val pressureLevel = when {
                usedMb < maxMb * 0.5 -> "low"
                usedMb < maxMb * 0.75 -> "moderate"
                else -> "high"
            }
            
            // Create standardized memory event
            val memoryAttributes = createStandardizedMemoryEvent(
                mapOf(
                    "heap_used_mb" to usedMb.toLong(),
                    "heap_max_mb" to maxMb.toLong(),
                    "heap_free_mb" to freeMb.toLong()
                ),
                emptyMap(),
                pressureLevel,
                pressureLevel == "high",
                "basic"
            )
            
            telemetryManager.recordEvent(
                eventName = "memory_pressure",
                attributes = memoryAttributes
            )
            
            telemetryManager.recordMetric(
                metricName = "memory_usage",
                value = usedMb,
                attributes = mapOf(
                    "metric.unit" to "MB",
                    "memory.type" to "heap",
                    "memory.source" to "basic_tracker",
                    "memory.pressure_level" to pressureLevel
                )
            )
            
            Log.d(TAG, "Basic memory usage recorded: ${usedMb.toInt()}MB/${maxMb.toInt()}MB")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording basic memory usage: ${e.localizedMessage}")
        }
    }
    
    override fun recordStorageUsage() {
        // Basic storage tracking not implemented in fallback
        Log.d(TAG, "Storage tracking not available in basic mode")
    }
    
    override fun getCurrentMemoryPressure(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
            val maxMb = runtime.maxMemory() / (1024.0 * 1024.0)
            
            when {
                usedMb < maxMb * 0.5 -> "low"
                usedMb < maxMb * 0.75 -> "moderate"
                else -> "high"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    override fun isUnderMemoryPressure(): Boolean {
        return getCurrentMemoryPressure() == "high"
    }
}

/**
 * Create standardized memory event attributes across all implementations
 */
private fun createStandardizedMemoryEvent(
    memorySummary: Map<String, Any>,
    memoryInfo: Map<String, Any>,
    pressureLevel: String,
    isUnderPressure: Boolean,
    trackingMethod: String
): Map<String, Any> {
    
    val attributes = mutableMapOf<String, Any>(
        // Core attributes (always present)
        "memory.pressure_level" to pressureLevel,
        "memory.under_system_pressure" to isUnderPressure,
        "memory.api_level" to Build.VERSION.SDK_INT,
        "memory.tracking_method" to trackingMethod,
        "memory.timestamp" to System.currentTimeMillis()
    )
    
    // Basic heap memory (available in all implementations)
    memorySummary["heap_used_mb"]?.let { attributes["memory.heap_used_mb"] = it }
    memorySummary["heap_max_mb"]?.let { attributes["memory.heap_max_mb"] = it }
    memorySummary["heap_free_mb"]?.let { attributes["memory.heap_free_mb"] = it }
    
    // System memory (enhanced implementations)
    memorySummary["system_available_mb"]?.let { attributes["memory.system_available_mb"] = it }
    memorySummary["system_low_memory"]?.let { attributes["memory.system_low_memory"] = it }
    memorySummary["memory_class_mb"]?.let { attributes["memory.app_memory_class_mb"] = it }
    memorySummary["large_memory_class_mb"]?.let { attributes["memory.large_memory_class_mb"] = it }
    
    // Advanced memory info (API 16+)
    memoryInfo["system_total_bytes"]?.let { 
        attributes["memory.system_total_mb"] = (it as Long) / (1024 * 1024) 
    }
    memoryInfo["memory_threshold_bytes"]?.let { 
        attributes["memory.system_threshold_mb"] = (it as Long) / (1024 * 1024) 
    }
    
    // Detailed memory breakdown (API 26+)
    memoryInfo["native_heap_kb"]?.let { attributes["memory.native_heap_kb"] = it }
    memoryInfo["total_pss_kb"]?.let { attributes["memory.total_pss_kb"] = it }
    memoryInfo["process_total_pss_kb"]?.let { attributes["memory.process_pss_kb"] = it }
    
    return attributes
}
