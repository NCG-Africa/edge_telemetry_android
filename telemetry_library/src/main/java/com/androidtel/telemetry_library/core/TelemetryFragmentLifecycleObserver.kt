package com.androidtel.telemetry_library.core

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker

class TelemetryFragmentLifecycleObserver(private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()) :
    FragmentManager.FragmentLifecycleCallbacks() {

    private val screenTimingTracker = ScreenTimingTracker()
    private val navigationTracker = NavigationStackTracker()

    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        super.onFragmentResumed(fm, f)
        val fragmentName = f.javaClass.simpleName
        Log.d("TelemetryObserver", "Fragment Resumed: $fragmentName")

        // Start tracking screen duration for this fragment
        screenTimingTracker.startScreen(fragmentName)

        // Track navigation with proper structure
        val navEvent = navigationTracker.push(fragmentName)
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to (navEvent.fromScreen ?: ""),
                "navigation.to_screen" to navEvent.toScreen,
                "navigation.method" to navEvent.method.toLowerCaseString(),
                "navigation.route_type" to "fragment_flow",
                "navigation.has_arguments" to (f.arguments?.isEmpty == false),
                "navigation.timestamp" to navEvent.timestamp
            )
        )
    }

    override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
        super.onFragmentPaused(fm, f)
        val fragmentName = f.javaClass.simpleName
        Log.d("TelemetryObserver", "Fragment Paused: $fragmentName")

        // End the screen timing and record the screen duration
        val durationMs = screenTimingTracker.endScreen(fragmentName)
        if (durationMs != null) {
            telemetryManager.recordMetric(
                metricName = "performance.screen_duration",
                value = durationMs.toDouble(),
                attributes = mapOf(
                    "screen.name" to fragmentName,
                    "screen.duration_ms" to durationMs,
                    "screen.exit_method" to "paused",
                    "screen.timestamp" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()),
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }
}
