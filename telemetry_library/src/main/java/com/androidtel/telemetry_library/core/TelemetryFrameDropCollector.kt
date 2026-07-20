package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker
import java.lang.ref.WeakReference

/**
 * Windowed frame-metric collector (issue #54).
 *
 * Replaces the retired per-frame `frame_drop` firehose with at most one `frame.summary` per
 * screen-segment (≤10s), emitted only when the window contained slow frames. All aggregation is
 * O(1)-memory counters updated inside the frame callback — no timer, no external wiring.
 *
 * A window closes on whichever comes first: the current screen differs from the one captured at
 * window start, or 10s elapse. Thresholds are fixed, Android-Vitals-aligned (slow >16ms,
 * frozen >700ms); the device refresh rate is recorded, not used to move the threshold.
 *
 * Only functions on devices with API 24+ (Android N) that support FrameMetrics.
 */
class TelemetryFrameDropCollector(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance(),
    // Seams for unit testing (spec §Test plan). Production leaves these null and reads the real
    // clock / the current activity.
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val screenOverride: (() -> String?)? = null,
    private val refreshRateOverride: (() -> Float)? = null,
) {
    // Store listener reference to enable removal
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    // Store current activity reference to track state (using WeakReference to prevent memory leaks)
    private var currentActivityRef: WeakReference<Activity>? = null

    // Window aggregator — guarded by @Synchronized (frame callback thread vs. stop() on main thread).
    private var windowOpen = false
    private var windowStart = 0L
    private var windowScreen: String? = null
    private var windowRefreshRate = 0f
    private var totalFrames = 0
    private var slowFrames = 0
    private var frozenFrames = 0
    private var maxTotalMs = 0.0
    private var maxBuildMs = 0.0
    private var maxRasterMs = 0.0

    @Synchronized
    fun start(activity: Activity) {
        // Prevent duplicate listeners - stop existing listener first
        if (frameMetricsListener != null) {
            stop()
        }

        // Check if activity window is available
        val window = activity.window
        if (window == null) {
            Log.w(TAG, "Activity window is null, cannot add frame metrics listener")
            return
        }

        // Create and store the listener (only if frame metrics are supported)
        try {
            frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                try {
                    val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                    val buildMs = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1_000_000.0
                    val rasterMs = metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1_000_000.0
                    onFrame(totalMs, buildMs, rasterMs)
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing frame metrics: ${e.localizedMessage}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create frame metrics listener: ${e.localizedMessage}")
            return
        }

        // Store activity reference before adding the listener so the first frame can attribute a screen
        currentActivityRef = WeakReference(activity)

        // Add listener to window
        frameMetricsListener?.let { listener ->
            window.addOnFrameMetricsAvailableListener(listener, null)
        }

        Log.d(TAG, "Frame metrics listener started for ${activity.javaClass.simpleName}")
    }

    /**
     * Internal seam: accumulate one frame and close the window on a screen change or the 10s cap.
     * The real FrameMetrics listener calls this; unit tests feed synthetic frames directly.
     */
    @Synchronized
    internal fun onFrame(totalMs: Double, buildMs: Double, rasterMs: Double) {
        if (!telemetryManager.isFrameTrackingEnabled()) return

        if (!windowOpen) openWindow()

        totalFrames++
        if (totalMs > SLOW_THRESHOLD_MS) slowFrames++
        if (totalMs > FROZEN_THRESHOLD_MS) frozenFrames++
        if (totalMs > maxTotalMs) maxTotalMs = totalMs
        if (buildMs > maxBuildMs) maxBuildMs = buildMs
        if (rasterMs > maxRasterMs) maxRasterMs = rasterMs

        if (currentScreen() != windowScreen || clock() - windowStart >= WINDOW_CAP_MS) {
            flushWindow()
        }
    }

    private fun openWindow() {
        windowOpen = true
        windowStart = clock()
        windowScreen = currentScreen()
        windowRefreshRate = currentRefreshRate()
        totalFrames = 0
        slowFrames = 0
        frozenFrames = 0
        maxTotalMs = 0.0
        maxBuildMs = 0.0
        maxRasterMs = 0.0
    }

    private fun flushWindow() {
        if (!windowOpen) return
        if (slowFrames > 0 && telemetryManager.isFrameTrackingEnabled()) {
            telemetryManager.recordEvent(
                eventName = "frame.summary",
                attributes = mapOf(
                    "frame.total_frames" to totalFrames,
                    "frame.slow_frames" to slowFrames,
                    "frame.frozen_frames" to frozenFrames,
                    "frame.slow_frame_rate" to slowFrames.toDouble() / totalFrames,
                    "frame.max_total_duration_ms" to maxTotalMs,
                    "frame.max_build_duration_ms" to maxBuildMs,
                    "frame.max_raster_duration_ms" to maxRasterMs,
                    "frame.window_duration_ms" to (clock() - windowStart).toDouble(),
                    "display.refresh_rate" to windowRefreshRate,
                    "screen.name" to (windowScreen ?: "")
                )
            )
        }
        windowOpen = false
    }

    // Production screen source is the process-wide NavigationStackTracker latch (spec §1) so the
    // window segments on real navigation across both Activity and single-Activity Compose apps. Falls
    // back to the activity name only before the first navigation event has been recorded.
    private fun currentScreen(): String? =
        screenOverride?.invoke()
            ?: NavigationStackTracker.currentScreen()
            ?: currentActivityRef?.get()?.let { screenNameOf(it) }

    private fun currentRefreshRate(): Float =
        refreshRateOverride?.invoke() ?: (currentActivityRef?.get()?.let { refreshRateOf(it) } ?: 0f)

    private fun screenNameOf(activity: Activity): String =
        if (activity.title?.isNotEmpty() == true) activity.title.toString() else activity.javaClass.simpleName

    private fun refreshRateOf(activity: Activity): Float = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.refreshRate ?: 0f
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }
    } catch (e: Exception) {
        0f
    }

    @Synchronized
    fun stop() {
        // Flush the trailing partial window before tearing down.
        flushWindow()

        val listener = frameMetricsListener
        val activity = currentActivityRef?.get()

        if (listener != null && activity != null) {
            try {
                activity.window?.removeOnFrameMetricsAvailableListener(listener)
                Log.d(TAG, "Frame metrics listener removed for ${activity.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove frame metrics listener: ${e.localizedMessage}")
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

    private companion object {
        const val TAG = "TelemetryFrameDropCollector"
        const val SLOW_THRESHOLD_MS = 16.0
        const val FROZEN_THRESHOLD_MS = 700.0
        const val WINDOW_CAP_MS = 10_000L
    }
}
