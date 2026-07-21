package com.androidtel.telemetry_library.core

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker
import com.androidtel.telemetry_library.core.trace.TraceManager

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
        // Child of a recent interaction (tap→nav) else a new trace root (#59).
        val trace = TraceManager.onNavigation(System.currentTimeMillis()) ?: emptyMap()
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to (navEvent.fromScreen ?: ""),
                "navigation.to_screen" to navEvent.toScreen,
                "navigation.method" to navEvent.method.toLowerCaseString(),
                "navigation.route_type" to "fragment_flow",
                "navigation.has_arguments" to (f.arguments?.isEmpty == false),
                "navigation.timestamp" to navEvent.timestamp
            ) + trace
        )
    }

    override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
        super.onFragmentPaused(fm, f)
        val fragmentName = f.javaClass.simpleName
        Log.d("TelemetryObserver", "Fragment Paused: $fragmentName")

        // End the screen timing and record the screen duration
        val durationMs = screenTimingTracker.endScreen(fragmentName)
        if (durationMs != null) {
            telemetryManager.recordScreenDuration(fragmentName, durationMs, "paused")
        }
    }
}
