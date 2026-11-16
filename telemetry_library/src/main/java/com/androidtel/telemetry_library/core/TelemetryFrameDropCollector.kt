package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.androidtel.telemetry_library.core.log.TelemetryLog
import android.view.FrameMetrics
import android.view.Window
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker
import java.lang.ref.WeakReference

/**
 * Windowed frame-metric collector (issue #54, catalogue frames model processor #33).
 *
 * One `frame.summary` per screen-segment (≤10s), sent for every window that drew a frame, stamped at
 * the window's start. All aggregation is O(1)-memory counters updated inside the frame callback — no
 * timer, no external wiring.
 *
 * A window closes on whichever comes first: the current screen differs from the one captured at
 * window start, or 10s elapse. Slow = over the frame budget for the display's refresh rate (16.7 ms at
 * 60 Hz, 8.3 ms at 120 Hz; 60 Hz when unknown); frozen = ≥700 ms.
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
    private val epochClock: () -> Long = { System.currentTimeMillis() },
) {
    // Store listener reference to enable removal
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    // Window.addOnFrameMetricsAvailableListener rejects a null Handler (NPE: "handler and its looper
    // cannot be null"). Dispatch on our own thread, not the main looper — measuring jank on the main
    // thread adds to it. Mirrors androidx JankStats / FrameMetricsAggregator.
    private var callbackThread: HandlerThread? = null

    // Store current activity reference to track state (using WeakReference to prevent memory leaks)
    private var currentActivityRef: WeakReference<Activity>? = null

    // Window aggregator — guarded by @Synchronized (frame callback thread vs. stop() on main thread).
    private var windowOpen = false
    private var windowStart = 0L
    private var windowStartTime = ""
    private var slowBudgetMs = 0.0
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
        // Honour the config flag here, not just at emit time: no flag, no listener, no thread.
        if (!telemetryManager.isFrameTrackingEnabled()) return

        // Prevent duplicate listeners - stop existing listener first
        if (frameMetricsListener != null) {
            stop()
        }

        // Check if activity window is available
        val window = activity.window
        if (window == null) {
            TelemetryLog.w(TAG, "Activity window is null, cannot add frame metrics listener")
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
                    TelemetryLog.w(TAG, "Error processing frame metrics: ${e.localizedMessage}")
                }
            }
        } catch (e: Exception) {
            TelemetryLog.w(TAG, "Failed to create frame metrics listener: ${e.localizedMessage}")
            return
        }

        // Store activity reference before adding the listener so the first frame can attribute a screen
        currentActivityRef = WeakReference(activity)

        // Add listener to window
        frameMetricsListener?.let { listener ->
            val thread = HandlerThread(TAG).also { it.start() }
            callbackThread = thread
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }

        TelemetryLog.d(TAG, "Frame metrics listener started for ${activity.javaClass.simpleName}")
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
        if (totalMs > slowBudgetMs) slowFrames++
        if (totalMs >= FROZEN_THRESHOLD_MS) frozenFrames++
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
        windowStartTime = TelemetryTime.isoOf(epochClock())
        windowScreen = currentScreen()
        windowRefreshRate = currentRefreshRate()
        slowBudgetMs = 1000.0 / (windowRefreshRate.takeIf { it > 0f } ?: DEFAULT_REFRESH_HZ)
        totalFrames = 0
        slowFrames = 0
        frozenFrames = 0
        maxTotalMs = 0.0
        maxBuildMs = 0.0
        maxRasterMs = 0.0
    }

    private fun flushWindow() {
        if (!windowOpen) return
        if (totalFrames > 0 && telemetryManager.isFrameTrackingEnabled()) {
            telemetryManager.emit(
                "frame.summary",
                mapOf(
                    "frame.total_frames" to totalFrames,
                    "frame.slow_frames" to slowFrames,
                    "frame.frozen_frames" to frozenFrames,
                    "frame.slow_frame_rate" to slowFrames.toDouble() / totalFrames,
                    "frame.max_total_duration_ms" to maxTotalMs,
                    "frame.max_build_duration_ms" to maxBuildMs,
                    "frame.max_raster_duration_ms" to maxRasterMs,
                    "frame.window_duration_ms" to (clock() - windowStart),
                    "display.refresh_rate" to windowRefreshRate,
                    "screen.name" to (windowScreen ?: "")
                ),
                windowStartTime
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
            ?: currentActivityRef?.get()?.javaClass?.simpleName

    private fun currentRefreshRate(): Float =
        refreshRateOverride?.invoke() ?: (currentActivityRef?.get()?.let { refreshRateOf(it) } ?: 0f)

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
                TelemetryLog.d(TAG, "Frame metrics listener removed for ${activity.javaClass.simpleName}")
            } catch (e: Exception) {
                TelemetryLog.w(TAG, "Failed to remove frame metrics listener: ${e.localizedMessage}")
            }
        }

        callbackThread?.quitSafely()
        callbackThread = null

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
        const val DEFAULT_REFRESH_HZ = 60f
        const val FROZEN_THRESHOLD_MS = 700.0
        const val WINDOW_CAP_MS = 10_000L
    }
}
