package com.androidtel.telemetry_library.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File

/**
 * Memory tracking with capability-aware implementations.
 * Uses appropriate APIs based on device capabilities for memory monitoring.
 */
class MemoryCapabilityTracker(
    private val context: Context,
    private val deviceCapabilities: DeviceCapabilities
) {
    companion object {
        private const val TAG = "MemoryCapabilityTracker"
    }
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    /**
     * Get comprehensive memory information using appropriate APIs
     */
    fun getMemoryInfo(): Map<String, Any> {
        val memoryInfo = mutableMapOf<String, Any>()
        
        try {
            // Basic memory info (available on all API levels)
            addBasicMemoryInfo(memoryInfo)
            
            // Advanced memory info (API 19+)
            if (deviceCapabilities.supportsAdvancedMemoryInfo) {
                addAdvancedMemoryInfo(memoryInfo)
            }
            
            // Process-specific memory info
            addProcessMemoryInfo(memoryInfo)
            
            // System memory pressure (API 23+)
            if (deviceCapabilities.supportsMemoryManager) {
                addMemoryPressureInfo(memoryInfo)
            }
            
            memoryInfo["api_level"] = deviceCapabilities.apiLevel
            memoryInfo["supports_advanced"] = deviceCapabilities.supportsAdvancedMemoryInfo
            
        } catch (e: Exception) {
            Log.w(TAG, "Error collecting memory info: ${e.localizedMessage}")
            memoryInfo["error"] = e.localizedMessage ?: "Unknown error"
        }
        
        return memoryInfo
    }
    
    /**
     * Get memory usage summary for telemetry events
     */
    fun getMemoryUsageSummary(): Map<String, Any> {
        return try {
            val runtime = Runtime.getRuntime()
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            mapOf(
                "heap_used_mb" to (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                "heap_max_mb" to runtime.maxMemory() / (1024 * 1024),
                "heap_free_mb" to runtime.freeMemory() / (1024 * 1024),
                "system_available_mb" to memInfo.availMem / (1024 * 1024),
                "system_low_memory" to memInfo.lowMemory,
                "memory_class_mb" to activityManager.memoryClass,
                "large_memory_class_mb" to activityManager.largeMemoryClass
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error getting memory summary: ${e.localizedMessage}")
            mapOf("error" to (e.localizedMessage ?: "Unknown error"))
        }
    }
    
    /**
     * Check if device is under memory pressure
     */
    fun isUnderMemoryPressure(): Boolean {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val availableMemoryMB = memInfo.availMem / (1024 * 1024)
            val thresholdMB = memInfo.threshold / (1024 * 1024)
            
            memInfo.lowMemory || availableMemoryMB < thresholdMB * 1.5
        } catch (e: Exception) {
            Log.w(TAG, "Error checking memory pressure: ${e.localizedMessage}")
            false
        }
    }
    
    private fun addBasicMemoryInfo(memoryInfo: MutableMap<String, Any>) {
        val runtime = Runtime.getRuntime()
        
        memoryInfo["heap_total_bytes"] = runtime.totalMemory()
        memoryInfo["heap_free_bytes"] = runtime.freeMemory()
        memoryInfo["heap_used_bytes"] = runtime.totalMemory() - runtime.freeMemory()
        memoryInfo["heap_max_bytes"] = runtime.maxMemory()
        
        val activityMemInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(activityMemInfo)
        
        memoryInfo["system_available_bytes"] = activityMemInfo.availMem
        memoryInfo["system_low_memory"] = activityMemInfo.lowMemory
        memoryInfo["memory_class_mb"] = activityManager.memoryClass
        memoryInfo["large_memory_class_mb"] = activityManager.largeMemoryClass
    }
    
    private fun addAdvancedMemoryInfo(memoryInfo: MutableMap<String, Any>) {
        try {
            val activityMemInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(activityMemInfo)
            
            // Total system memory (API 16+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                memoryInfo["system_total_bytes"] = activityMemInfo.totalMem
                memoryInfo["memory_threshold_bytes"] = activityMemInfo.threshold
            }
            
            // Debug memory info
            val debugMemInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(debugMemInfo)
            
            // Use reflection to safely access native heap properties (deprecated in newer APIs)
            try {
                val nativeHeapSize = debugMemInfo.javaClass.getField("nativeHeapSize").getInt(debugMemInfo)
                val nativeHeapAllocatedSize = debugMemInfo.javaClass.getField("nativeHeapAllocatedSize").getInt(debugMemInfo)
                val nativeHeapFreeSize = debugMemInfo.javaClass.getField("nativeHeapFreeSize").getInt(debugMemInfo)
                
                memoryInfo["native_heap_kb"] = nativeHeapSize
                memoryInfo["native_heap_alloc_kb"] = nativeHeapAllocatedSize
                memoryInfo["native_heap_free_kb"] = nativeHeapFreeSize
            } catch (e: Exception) {
                Log.d(TAG, "Native heap info not available: ${e.localizedMessage}")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                memoryInfo["total_pss_kb"] = debugMemInfo.totalPss
                memoryInfo["total_shared_dirty_kb"] = debugMemInfo.totalSharedDirty
                memoryInfo["total_private_dirty_kb"] = debugMemInfo.totalPrivateDirty
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting advanced memory info: ${e.localizedMessage}")
        }
    }
    
    private fun addProcessMemoryInfo(memoryInfo: MutableMap<String, Any>) {
        try {
            val myPid = android.os.Process.myPid()
            val processMemInfo = activityManager.getProcessMemoryInfo(intArrayOf(myPid))
            
            if (processMemInfo.isNotEmpty()) {
                val memInfo = processMemInfo[0]
                memoryInfo["process_total_pss_kb"] = memInfo.totalPss
                memoryInfo["process_total_private_dirty_kb"] = memInfo.totalPrivateDirty
                memoryInfo["process_total_shared_dirty_kb"] = memInfo.totalSharedDirty
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting process memory info: ${e.localizedMessage}")
        }
    }
    
    private fun addMemoryPressureInfo(memoryInfo: MutableMap<String, Any>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Get running app processes to check memory pressure
                val runningProcesses = activityManager.runningAppProcesses
                val myPid = android.os.Process.myPid()
                
                runningProcesses?.find { it.pid == myPid }?.let { myProcess ->
                    memoryInfo["process_importance"] = myProcess.importance
                    memoryInfo["process_importance_reason_code"] = myProcess.importanceReasonCode
                }
            }
            
            // Check if we can get memory pressure information
            memoryInfo["under_memory_pressure"] = isUnderMemoryPressure()
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting memory pressure info: ${e.localizedMessage}")
        }
    }
    
    /**
     * Get storage information with capability awareness
     */
    fun getStorageInfo(): Map<String, Any> {
        val storageInfo = mutableMapOf<String, Any>()
        
        try {
            // Internal storage
            val internalDir = context.filesDir
            storageInfo["internal_total_bytes"] = getTotalSpace(internalDir)
            storageInfo["internal_free_bytes"] = getFreeSpace(internalDir)
            storageInfo["internal_usable_bytes"] = getUsableSpace(internalDir)
            
            // Cache directory
            val cacheDir = context.cacheDir
            storageInfo["cache_total_bytes"] = getTotalSpace(cacheDir)
            storageInfo["cache_free_bytes"] = getFreeSpace(cacheDir)
            storageInfo["cache_usable_bytes"] = getUsableSpace(cacheDir)
            
            // External storage (if available)
            if (deviceCapabilities.supportsExternalFilesDir) {
                context.getExternalFilesDir(null)?.let { externalDir ->
                    storageInfo["external_total_bytes"] = getTotalSpace(externalDir)
                    storageInfo["external_free_bytes"] = getFreeSpace(externalDir)
                    storageInfo["external_usable_bytes"] = getUsableSpace(externalDir)
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting storage info: ${e.localizedMessage}")
            storageInfo["error"] = e.localizedMessage ?: "Unknown error"
        }
        
        return storageInfo
    }
    
    private fun getTotalSpace(file: File): Long {
        return try {
            file.totalSpace
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getFreeSpace(file: File): Long {
        return try {
            file.freeSpace
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getUsableSpace(file: File): Long {
        return try {
            file.usableSpace
        } catch (e: Exception) {
            0L
        }
    }
}
