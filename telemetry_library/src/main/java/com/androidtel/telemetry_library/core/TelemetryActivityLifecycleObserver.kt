package com.androidtel.telemetry_library.core

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker

class TelemetryActivityLifecycleObserver(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) : Application.ActivityLifecycleCallbacks {

    private val screenTimingTracker = ScreenTimingTracker()
    private val navigationTracker = NavigationStackTracker()
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

        // Track navigation with proper structure
        val navEvent = navigationTracker.push(screenName)
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to (navEvent.fromScreen ?: ""),
                "navigation.to_screen" to navEvent.toScreen,
                "navigation.method" to navEvent.method.toLowerCaseString(),
                "navigation.route_type" to detectRouteType(activity),
                "navigation.has_arguments" to hasIntentExtras(activity),
                "navigation.timestamp" to navEvent.timestamp
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

    private fun detectRouteType(activity: Activity): String {
        return when {
            activity.isTaskRoot -> "main_flow"
            activity.intent?.data != null -> "deeplink"
            else -> "main_flow"
        }
    }

    private fun hasIntentExtras(activity: Activity): Boolean {
        return activity.intent?.extras?.isEmpty == false
    }
}
