package com.androidtel.telemetry_library.core.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import com.androidtel.telemetry_library.core.TelemetryManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Navigation Component tracker for XML navigation graphs
 * 
 * Usage:
 * ```kotlin
 * val tracker = NavigationComponentTracker(navController)
 * tracker.startTracking()
 * // Later when done
 * tracker.stopTracking()
 * ```
 */
class NavigationComponentTracker(
    private val navController: NavController,
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
        trackNavigation(destination, arguments)
    }

    fun startTracking() {
        navController.addOnDestinationChangedListener(listener)
    }

    fun stopTracking() {
        navController.removeOnDestinationChangedListener(listener)
    }

    private fun trackNavigation(destination: NavDestination, arguments: Bundle?) {
        val screenName = getScreenName(destination)
        val fromScreen = getCurrentScreen()
        
        // Track navigation event
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to fromScreen,
                "navigation.to_screen" to screenName,
                "navigation.method" to "push",
                "navigation.route_type" to "navigation_component",
                "navigation.has_arguments" to (arguments?.isEmpty == false),
                "navigation.destination_id" to destination.id.toString(),
                "navigation.timestamp" to dateFormat.format(Date())
            )
        )
        
        // Track screen view (if legacy events enabled)
        if (isLegacyScreenEventsEnabled()) {
            telemetryManager.recordScreenView(screenName)
        }
    }

    private fun getScreenName(destination: NavDestination): String {
        // Try to get label first, then fall back to destination ID
        return destination.label?.toString() ?: 
               "destination_${destination.id}"
    }

    private fun getCurrentScreen(): String {
        return navController.currentDestination?.let { destination ->
            getScreenName(destination)
        } ?: ""
    }

    private fun isLegacyScreenEventsEnabled(): Boolean {
        return try {
            val configField = telemetryManager.javaClass.getDeclaredField("config")
            configField.isAccessible = true
            val config = configField.get(telemetryManager)
            val enableLegacyField = config.javaClass.getDeclaredField("enableLegacyScreenEvents")
            enableLegacyField.isAccessible = true
            enableLegacyField.getBoolean(config)
        } catch (e: Exception) {
            false // Default to false if can't access config
        }
    }
}
