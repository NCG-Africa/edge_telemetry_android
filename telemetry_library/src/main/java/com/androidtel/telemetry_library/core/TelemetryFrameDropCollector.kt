package com.androidtel.telemetry_library.core


import android.app.Activity
import android.os.Build
import android.view.FrameMetrics
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class TelemetryFrameDropCollector(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) {
    fun start(activity: Activity) {
        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
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
        }, null)
    }
}
