package com.androidtel.android_telemetry.core

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import java.time.Instant

class TelemetryActivityLifecycleObserver(private val telemetryManager: TelemetryManager = TelemetryManager.getInstance() ) : Application.ActivityLifecycleCallbacks {

    private val screenTimingTracker = ScreenTimingTracker()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.d("TelemetryObserver", "Activity Created: ${activity.javaClass.simpleName}")
        // Register Fragment observer for this activity
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResumed(activity: Activity) {
        val screenName = getScreenName(activity)
        Log.d("TelemetryObserver", "Activity Resumed: $screenName")

        // Start tracking screen duration for this screen
        screenTimingTracker.startScreen(screenName)

        // Record a navigation event, similar to Flutter's navigation observer
        telemetryManager.recordEvent(
            eventName = "navigation.route_change",
            attributes = mapOf(
                "navigation.to" to screenName,
                "navigation.method" to "resumed",
                "navigation.type" to "activity_change",
                "navigation.timestamp" to Instant.now().toString(),
                "screen.type" to "activity"
            )
        )
    }

    override fun onActivityPaused(activity: Activity) {
        val screenName = getScreenName(activity)
        Log.d("TelemetryObserver", "Activity Paused: $screenName")

        // End the screen timing and record the screen duration
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
        Log.d("TelemetryObserver", "Activity Stopped: ${activity.javaClass.simpleName}")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Log.d("TelemetryObserver", "Activity SaveInstanceState: ${activity.javaClass.simpleName}")
    }

    override fun onActivityDestroyed(activity: Activity) {
        Log.d("TelemetryObserver", "Activity Destroyed: ${activity.javaClass.simpleName}")
    }

    // Helper method to get a clean screen name from the Activity title or class name.
    private fun getScreenName(activity: Activity): String {
        return if (activity.title.isNotEmpty()) {
            activity.title.toString()
        } else {
            activity.javaClass.simpleName
        }
    }
}
