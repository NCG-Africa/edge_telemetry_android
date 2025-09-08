package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

@RequiresApi(Build.VERSION_CODES.N)
class TelemetryFrameDropCollector(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) {
    // Store listener reference to enable removal
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    
    // Store current activity reference to track state (using WeakReference to prevent memory leaks)
    private var currentActivityRef: WeakReference<Activity>? = null
    
    @Synchronized
    fun start(activity: Activity) {
        // Prevent duplicate listeners - stop existing listener first
        if (frameMetricsListener != null) {
            stop()
        }
        
        // Check if activity window is available
        val window = activity.window
        if (window == null) {
            Log.w("TelemetryFrameDropCollector", "Activity window is null, cannot add frame metrics listener")
            return
        }
        
        // Create and store the listener
        frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val totalDurationNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val drawDurationNs = metrics.getMetric(FrameMetrics.DRAW_DURATION)
            val layoutMeasureNs = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)

            val totalMs = totalDurationNs / 1_000_000.0
            val buildMs = layoutMeasureNs / 1_000_000.0
            val rasterMs = drawDurationNs / 1_000_000.0

            val targetFps = 60
            val budgetMs = 1000.0 / targetFps
            val severity = when {
                totalMs > budgetMs * 3 -> "high"
                totalMs > budgetMs * 2 -> "medium"
                else -> "low"
            }

            telemetryManager.recordEvent(
                eventName = "frame_drop",
                attributes = mapOf(
                    "frame.build_duration_ms" to buildMs,
                    "frame.raster_duration_ms" to rasterMs,
                    "frame.total_duration_ms" to totalMs,
                    "frame.severity" to severity,
                    "frame.target_fps" to targetFps
                )
            )
        }
        
        // Add listener to window
        frameMetricsListener?.let { listener ->
            window.addOnFrameMetricsAvailableListener(listener, null)
        }
        
        // Store activity reference
        currentActivityRef = WeakReference(activity)
        
        Log.d("TelemetryFrameDropCollector", "Frame metrics listener started for ${activity.javaClass.simpleName}")
    }
    
    @Synchronized
    fun stop() {
        val listener = frameMetricsListener
        val activity = currentActivityRef?.get()
        
        if (listener != null && activity != null) {
            try {
                activity.window?.removeOnFrameMetricsAvailableListener(listener)
                Log.d("TelemetryFrameDropCollector", "Frame metrics listener removed for ${activity.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w("TelemetryFrameDropCollector", "Failed to remove frame metrics listener: ${e.localizedMessage}")
            }
        }
        
        // Clear references to prevent memory leaks
        frameMetricsListener = null
        currentActivityRef = null
    }
    
    /**
     * Check if collector is currently active
     */
    fun isActive(): Boolean {
        return frameMetricsListener != null && currentActivityRef?.get() != null
    }
}
