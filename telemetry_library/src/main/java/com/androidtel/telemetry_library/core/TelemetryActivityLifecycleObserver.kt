package com.androidtel.telemetry_library.core

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity

class TelemetryActivityLifecycleObserver(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) : Application.ActivityLifecycleCallbacks {

    private val screenTimingTracker = ScreenTimingTracker()
    // Unified performance tracker - automatically selects appropriate implementation
    private val performanceTracker: PerformanceTracker = PerformanceTrackerFactory.createPerformanceTracker(telemetryManager)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.d("TelemetryObserver", "Activity Created: ${activity.javaClass.simpleName}")

        // Register Fragment observer
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                TelemetryFragmentLifecycleObserver(telemetryManager),
                true
            )
        }
    }

    override fun onActivityStarted(activity: Activity) {
        Log.d("TelemetryObserver", "Activity Started: ${activity.javaClass.simpleName}")
    }

    override fun onActivityResumed(activity: Activity) {
        val screenName = getScreenName(activity)
        Log.d("TelemetryObserver", "Activity Resumed: $screenName")

        // Start tracking screen duration
        screenTimingTracker.startScreen(screenName)

        // Start performance tracking (automatically uses appropriate implementation)
        performanceTracker.start(activity)

        // Record navigation event
        telemetryManager.recordEvent(
            eventName = "navigation.route_change",
            attributes = mapOf(
                "navigation.to" to screenName,
                "navigation.method" to "resumed",
                "navigation.type" to "activity_change",
                "navigation.timestamp" to System.currentTimeMillis().toString(),
                "screen.type" to "activity"
            )
        )
    }

    override fun onActivityPaused(activity: Activity) {
        val screenName = getScreenName(activity)
        Log.d("TelemetryObserver", "Activity Paused: $screenName")

        // Stop performance tracking to prevent memory leaks
        performanceTracker.stop()

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordMetric(
                metricName = "performance.screen_duration",
                value = durationMs.toDouble(),
                attributes = mapOf(
                    "screen.name" to screenName,
                    "navigation.exit_method" to "paused",
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val screenName = getScreenName(activity)
        Log.d("TelemetryObserver", "Activity Stopped: ${activity.javaClass.simpleName}")

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordMetric(
                metricName = "performance.screen_duration",
                value = durationMs.toDouble(),
                attributes = mapOf(
                    "screen.name" to screenName,
                    "navigation.exit_method" to "closed",
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Log.d("TelemetryObserver", "Activity SaveInstanceState: ${activity.javaClass.simpleName}")

        val screenName = getScreenName(activity)

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordMetric(
                metricName = "performance.screen_duration",
                value = durationMs.toDouble(),
                attributes = mapOf(
                    "screen.name" to screenName,
                    "navigation.exit_method" to "saved state",
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        val screenName = getScreenName(activity)
        Log.d("TelemetryObserver", "Activity Destroyed: ${activity.javaClass.simpleName}")

        // Stop performance tracking to prevent memory leaks
        performanceTracker.stop()

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordMetric(
                metricName = "performance.screen_duration",
                value = durationMs.toDouble(),
                attributes = mapOf(
                    "screen.name" to screenName,
                    "navigation.exit_method" to "destroyed",
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }

    private fun getScreenName(activity: Activity): String {
        return if (activity.title.isNotEmpty()) {
            activity.title.toString()
        } else {
            activity.javaClass.simpleName
        }
    }
}
