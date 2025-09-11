package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import java.lang.ref.WeakReference

/**
 * Alternative performance tracking for devices with API < 24 that don't support FrameMetrics.
 * Uses Choreographer callbacks and UI thread timing to detect performance issues.
 */
class LegacyPerformanceTracker(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) {
    companion object {
        private const val TAG = "LegacyPerformanceTracker"
        private const val FRAME_CALLBACK_INTERVAL_MS = 16L // ~60 FPS
        private const val SLOW_FRAME_THRESHOLD_MS = 32L // 2 frames worth
        private const val VERY_SLOW_FRAME_THRESHOLD_MS = 48L // 3 frames worth
        private const val SAMPLING_RATE = 0.1f // Sample 10% of frames to reduce overhead
    }
    
    private var isTracking = false
    private var currentActivityRef: WeakReference<Activity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    
    // Performance metrics
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var slowFrameCount = 0
    private var verySlowFrameCount = 0
    private var totalFrameTime = 0L
    private var maxFrameTime = 0L
    
    @Synchronized
    fun start(activity: Activity) {
        if (isTracking) {
            Log.d(TAG, "Performance tracking already active")
            return
        }
        
        // Check if Choreographer is available (API 16+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            Log.d(TAG, "Choreographer not available on API ${Build.VERSION.SDK_INT}, using basic timing")
            startBasicPerformanceTracking(activity)
            return
        }
        
        try {
            choreographer = Choreographer.getInstance()
            startChoreographerTracking(activity)
            Log.d(TAG, "Started Choreographer-based performance tracking for ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Choreographer tracking, falling back to basic timing: ${e.localizedMessage}")
            startBasicPerformanceTracking(activity)
        }
    }
    
    @Synchronized
    fun stop() {
        if (!isTracking) {
            return
        }
        
        try {
            // Stop Choreographer callbacks
            frameCallback?.let { callback ->
                choreographer?.removeFrameCallback(callback)
            }
            
            // Generate final performance summary
            generatePerformanceSummary()
            
            // Reset state
            resetMetrics()
            
            val activityName = currentActivityRef?.get()?.javaClass?.simpleName ?: "Unknown"
            Log.d(TAG, "Stopped performance tracking for $activityName")
            
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping performance tracking: ${e.localizedMessage}")
        } finally {
            isTracking = false
            currentActivityRef = null
            frameCallback = null
        }
    }
    
    private fun startChoreographerTracking(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        isTracking = true
        resetMetrics()
        
        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    processFrameTiming(frameTimeNanos)
                    
                    // Continue tracking if still active
                    if (isTracking) {
                        choreographer?.postFrameCallback(this)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in frame callback: ${e.localizedMessage}")
                }
            }
        }
        
        choreographer?.postFrameCallback(frameCallback!!)
    }
    
    private fun startBasicPerformanceTracking(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        isTracking = true
        resetMetrics()
        
        // Use basic timing with Handler for older devices
        val runnable = object : Runnable {
            override fun run() {
                try {
                    if (isTracking) {
                        val currentTime = System.nanoTime()
                        processFrameTiming(currentTime)
                        mainHandler.postDelayed(this, FRAME_CALLBACK_INTERVAL_MS)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in basic performance tracking: ${e.localizedMessage}")
                }
            }
        }
        
        mainHandler.post(runnable)
        Log.d(TAG, "Started basic performance tracking for ${activity.javaClass.simpleName}")
    }
    
    private fun processFrameTiming(frameTimeNanos: Long) {
        if (lastFrameTime == 0L) {
            lastFrameTime = frameTimeNanos
            return
        }
        
        val frameDurationNanos = frameTimeNanos - lastFrameTime
        val frameDurationMs = frameDurationNanos / 1_000_000.0
        
        // Sample frames to reduce overhead
        if (Math.random() > SAMPLING_RATE) {
            lastFrameTime = frameTimeNanos
            return
        }
        
        // Update metrics
        frameCount++
        totalFrameTime += frameDurationNanos
        maxFrameTime = maxOf(maxFrameTime, frameDurationNanos)
        
        // Classify frame performance
        when {
            frameDurationMs > VERY_SLOW_FRAME_THRESHOLD_MS -> {
                verySlowFrameCount++
                recordFrameEvent(frameDurationMs, "high")
            }
            frameDurationMs > SLOW_FRAME_THRESHOLD_MS -> {
                slowFrameCount++
                recordFrameEvent(frameDurationMs, "medium")
            }
            else -> {
                // Only record occasional good frames to avoid spam
                if (frameCount % 100 == 0) {
                    recordFrameEvent(frameDurationMs, "low")
                }
            }
        }
        
        lastFrameTime = frameTimeNanos
        
        // Generate periodic summaries
        if (frameCount % 1000 == 0) {
            generatePerformanceSummary()
        }
    }
    
    private fun recordFrameEvent(frameDurationMs: Double, severity: String) {
        try {
            val activityName = currentActivityRef?.get()?.javaClass?.simpleName ?: "Unknown"
            
            // Estimate component durations (since we don't have detailed metrics)
            val estimatedBuildMs = frameDurationMs * 0.3 // Rough estimate: 30% layout/measure
            val estimatedRasterMs = frameDurationMs * 0.4 // Rough estimate: 40% drawing
            
            telemetryManager.recordEvent(
                eventName = "frame_drop",
                attributes = mapOf(
                    "frame.build_duration_ms" to estimatedBuildMs,
                    "frame.raster_duration_ms" to estimatedRasterMs,
                    "frame.total_duration_ms" to frameDurationMs,
                    "frame.severity" to severity,
                    "frame.target_fps" to 60,
                    "frame.tracking_method" to "legacy_choreographer",
                    "frame.api_level" to Build.VERSION.SDK_INT,
                    "screen.name" to activityName
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record frame event: ${e.localizedMessage}")
        }
    }
    
    private fun generatePerformanceSummary() {
        if (frameCount == 0) return
        
        try {
            val avgFrameTimeMs = (totalFrameTime / frameCount) / 1_000_000.0
            val maxFrameTimeMs = maxFrameTime / 1_000_000.0
            val slowFramePercentage = (slowFrameCount.toDouble() / frameCount) * 100
            val verySlowFramePercentage = (verySlowFrameCount.toDouble() / frameCount) * 100
            val activityName = currentActivityRef?.get()?.javaClass?.simpleName ?: "Unknown"
            
            telemetryManager.recordEvent(
                eventName = "performance.frame_summary",
                attributes = mapOf(
                    "performance.avg_frame_time_ms" to avgFrameTimeMs,
                    "performance.max_frame_time_ms" to maxFrameTimeMs,
                    "performance.total_frames" to frameCount,
                    "performance.slow_frame_count" to slowFrameCount,
                    "performance.very_slow_frame_count" to verySlowFrameCount,
                    "performance.slow_frame_percentage" to slowFramePercentage,
                    "performance.very_slow_frame_percentage" to verySlowFramePercentage,
                    "performance.tracking_method" to "legacy_choreographer",
                    "performance.api_level" to Build.VERSION.SDK_INT,
                    "screen.name" to activityName,
                    "performance.sample_rate" to SAMPLING_RATE
                )
            )
            
            Log.d(TAG, "Performance Summary - Avg: ${String.format("%.2f", avgFrameTimeMs)}ms, " +
                      "Slow: ${slowFrameCount}/${frameCount} (${String.format("%.1f", slowFramePercentage)}%)")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate performance summary: ${e.localizedMessage}")
        }
    }
    
    private fun resetMetrics() {
        lastFrameTime = 0L
        frameCount = 0
        slowFrameCount = 0
        verySlowFrameCount = 0
        totalFrameTime = 0L
        maxFrameTime = 0L
    }
    
    /**
     * Check if performance tracking is currently active
     */
    fun isActive(): Boolean = isTracking
    
    /**
     * Get current performance metrics
     */
    fun getCurrentMetrics(): Map<String, Any> {
        return if (frameCount > 0) {
            mapOf(
                "frame_count" to frameCount,
                "slow_frame_count" to slowFrameCount,
                "very_slow_frame_count" to verySlowFrameCount,
                "avg_frame_time_ms" to (totalFrameTime / frameCount) / 1_000_000.0,
                "max_frame_time_ms" to maxFrameTime / 1_000_000.0,
                "tracking_method" to "legacy_choreographer",
                "is_active" to isTracking
            )
        } else {
            mapOf(
                "frame_count" to 0,
                "tracking_method" to "legacy_choreographer",
                "is_active" to isTracking
            )
        }
    }
}
