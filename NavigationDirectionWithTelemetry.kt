// Your existing NavigationDirection class with simple telemetry integration
package com.elmacentemobile.view.navigation

import androidx.navigation.ActionOnlyNavDirections
import com.androidtel.telemetry_library.core.TelemetryManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationDirection @Inject constructor(
    // Add TelemetryManager injection
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) : NavigationDataSource {

    fun navigateToLogin(): ActionOnlyNavDirections {
        // Add telemetry tracking for this navigation
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to "unknown",
                "navigation.to_screen" to "Login",
                "navigation.method" to "push",
                "navigation.route_type" to "navigation_direction"
            )
        )
        
        return ActionOnlyNavDirections(R.id.action_nav_login)
    }

    fun navigateToWelcome(): ActionOnlyNavDirections {
        // Add telemetry tracking
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to "unknown",
                "navigation.to_screen" to "Welcome",
                "navigation.method" to "push",
                "navigation.route_type" to "navigation_direction"
            )
        )
        
        return ActionOnlyNavDirections(R.id.action_nav_welcome)
    }

    fun navigateToHome(): ActionOnlyNavDirections {
        // Add telemetry tracking
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to "unknown",
                "navigation.to_screen" to "Home",
                "navigation.method" to "push",
                "navigation.route_type" to "navigation_direction"
            )
        )
        
        return ActionOnlyNavDirections(R.id.action_nav_home)
    }
}
