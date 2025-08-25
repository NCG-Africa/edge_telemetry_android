package com.androidtel.android_telemetry.core

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.time.Instant

class TelemetryFragmentLifecycleObserver(private val telemetryManager: TelemetryManager) :
    FragmentManager.FragmentLifecycleCallbacks() {

    private val screenTimingTracker = ScreenTimingTracker()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        super.onFragmentResumed(fm, f)
        val fragmentName = f.javaClass.simpleName
        Log.d("TelemetryObserver", "Fragment Resumed: $fragmentName")

        // Start tracking screen duration for this fragment
        screenTimingTracker.startScreen(fragmentName)

        // Record a navigation event for the fragment change
        telemetryManager.recordEvent(
            eventName = "navigation.route_change",
            attributes = mapOf(
                "navigation.to" to fragmentName,
                "navigation.method" to "resumed",
                "navigation.type" to "fragment_change",
                "navigation.timestamp" to Instant.now().toString(),
                "screen.type" to "fragment"
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
                    "navigation.exit_method" to "paused",
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }
}
