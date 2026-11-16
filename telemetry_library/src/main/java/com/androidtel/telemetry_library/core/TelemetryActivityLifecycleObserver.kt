package com.androidtel.telemetry_library.core

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.androidtel.telemetry_library.core.log.TelemetryLog
import androidx.fragment.app.FragmentActivity
import com.androidtel.telemetry_library.core.interaction.UserInteractionTracker
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker
import com.androidtel.telemetry_library.core.trace.TraceManager

class TelemetryActivityLifecycleObserver(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) : Application.ActivityLifecycleCallbacks {

    private val screenTimingTracker = ScreenTimingTracker()
    private val navigationTracker = NavigationStackTracker()
    private val screenLoadTracker = ScreenLoadTracker(telemetryManager)
    private val interactionTracker = UserInteractionTracker(telemetryManager, navigationTracker::getCurrentScreen)
    // Unified performance tracker - automatically selects appropriate implementation
    private val performanceTracker: PerformanceTracker = PerformanceTrackerFactory.createPerformanceTracker(telemetryManager)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        TelemetryLog.d("TelemetryObserver", "Activity Created: ${activity.javaClass.simpleName}")
        screenLoadTracker.onCreated(activity)

        // Register Fragment observer
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                TelemetryFragmentLifecycleObserver(telemetryManager),
                true
            )
        }
    }

    override fun onActivityStarted(activity: Activity) {
        TelemetryLog.d("TelemetryObserver", "Activity Started: ${activity.javaClass.simpleName}")
    }

    override fun onActivityResumed(activity: Activity) {
        val screenName = getScreenName(activity)
        TelemetryLog.d("TelemetryObserver", "Activity Resumed: $screenName")

        // Cold-start end marker (issue #95): first observed resume times app.start; later ones no-op.
        telemetryManager.notifyActivityResumed()

        // Delta 8 - the first resume ends the launch root's idle EXEMPTION, not the root itself. Both
        // Delta 7 clocks reset and the launch root decays normally from here. Closing it outright would
        // leave the navigation below minting its own root, stopping the launch trace short of the first
        // screen -- and the first screen's data fetches are usually the requests you most wanted.
        TraceManager.onFirstResume()

        // Start tracking screen duration
        screenTimingTracker.startScreen(screenName)

        // Start performance tracking (automatically uses appropriate implementation)
        performanceTracker.start(activity)

        // Capture user interactions (taps/swipes) by wrapping the window callback
        if (telemetryManager.isUserInteractionEventsEnabled()) {
            interactionTracker.attach(activity)
        }

        // Track navigation with proper structure
        val navEvent = navigationTracker.push(screenName)
        val navAttributes = navEvent.toAttributes(detectRouteType(activity), hasIntentExtras(activity)).toMutableMap()
        // Child of a recent interaction (tap→nav) else a new trace root (#59).
        TraceManager.onNavigation()?.let { navAttributes.putAll(it) }
        telemetryManager.emit(eventName = "navigation", attributes = navAttributes)
    }

    override fun onActivityPaused(activity: Activity) {
        val screenName = getScreenName(activity)
        TelemetryLog.d("TelemetryObserver", "Activity Paused: $screenName")

        // Restore the original window callback to avoid leaks / double-wrapping on re-resume
        interactionTracker.detach(activity)

        // Stop performance tracking to prevent memory leaks. Do NOT end screen timing here:
        // onActivityStopped owns endScreen so screen.duration (with exit_method) fires on the
        // pause→stop flow instead of being starved by a pause-path screen_view (issue #53, Part 2).
        performanceTracker.stop()
    }

    override fun onActivityStopped(activity: Activity) {
        val screenName = getScreenName(activity)
        TelemetryLog.d("TelemetryObserver", "Activity Stopped: ${activity.javaClass.simpleName}")

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordScreenDuration(screenName, durationMs, "closed")
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        TelemetryLog.d("TelemetryObserver", "Activity SaveInstanceState: ${activity.javaClass.simpleName}")

        val screenName = getScreenName(activity)

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordScreenDuration(screenName, durationMs, "saved_state")
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        val screenName = getScreenName(activity)
        TelemetryLog.d("TelemetryObserver", "Activity Destroyed: ${activity.javaClass.simpleName}")

        // Stop performance tracking to prevent memory leaks
        performanceTracker.stop()

        // End timing
        val durationMs = screenTimingTracker.endScreen(screenName)
        if (durationMs != null) {
            telemetryManager.recordScreenDuration(screenName, durationMs, "destroyed")
        }
    }

    // The class name, never the title: a title is often the app label or live content, and a screen
    // name must be a template (processor #31).
    private fun getScreenName(activity: Activity): String = activity.javaClass.simpleName

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
