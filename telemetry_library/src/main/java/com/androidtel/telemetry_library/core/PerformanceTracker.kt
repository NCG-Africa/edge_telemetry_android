package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.Build
import android.util.Log

/**
 * Unified performance tracking interface that provides consistent performance monitoring
 * across all Android API levels with appropriate implementations.
 */
interface PerformanceTracker {
    fun start(activity: Activity)
    fun stop()
    fun isActive(): Boolean
    fun getCurrentMetrics(): Map<String, Any>
}

/**
 * Factory for creating appropriate performance tracker based on device capabilities.
 */
object PerformanceTrackerFactory {
    private const val TAG = "PerformanceTrackerFactory"
    
    fun createPerformanceTracker(telemetryManager: TelemetryManager): PerformanceTracker {
        val capabilities = telemetryManager.getDeviceCapabilities()
        
        return if (capabilities?.canCollectFrameMetrics == true) {
            Log.d(TAG, "Creating FrameMetrics-based performance tracker for API ${Build.VERSION.SDK_INT}")
            ModernPerformanceTracker(telemetryManager)
        } else {
            Log.d(TAG, "Creating legacy performance tracker for API ${Build.VERSION.SDK_INT}")
            LegacyPerformanceTrackerWrapper(telemetryManager)
        }
    }
}

/**
 * Wrapper for the existing TelemetryFrameDropCollector to implement PerformanceTracker interface.
 */
class ModernPerformanceTracker(
    private val telemetryManager: TelemetryManager
) : PerformanceTracker {
    
    private val frameDropCollector = TelemetryFrameDropCollector(telemetryManager)
    
    override fun start(activity: Activity) {
        frameDropCollector.start(activity)
    }
    
    override fun stop() {
        frameDropCollector.stop()
    }
    
    override fun isActive(): Boolean {
        return frameDropCollector.isActive()
    }
    
    override fun getCurrentMetrics(): Map<String, Any> {
        return mapOf(
            "tracking_method" to "frame_metrics",
            "api_level" to Build.VERSION.SDK_INT,
            "is_active" to isActive(),
            "supports_detailed_metrics" to true
        )
    }
}

/**
 * Wrapper for LegacyPerformanceTracker to implement PerformanceTracker interface.
 */
class LegacyPerformanceTrackerWrapper(
    private val telemetryManager: TelemetryManager
) : PerformanceTracker {
    
    private val legacyTracker = LegacyPerformanceTracker(telemetryManager)
    
    override fun start(activity: Activity) {
        legacyTracker.start(activity)
    }
    
    override fun stop() {
        legacyTracker.stop()
    }
    
    override fun isActive(): Boolean {
        return legacyTracker.isActive()
    }
    
    override fun getCurrentMetrics(): Map<String, Any> {
        return legacyTracker.getCurrentMetrics()
    }
}
