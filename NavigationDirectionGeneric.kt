package com.elmacentemobile.view.navigation

import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.androidtel.telemetry_library.core.TelemetryManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationDirection @Inject constructor(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) : NavigationDataSource {

    // Generic helper function
    private fun trackNavigationEvent(toScreen: String, fromScreen: String = "unknown") {
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to fromScreen,
                "navigation.to_screen" to toScreen,
                "navigation.method" to "push",
                "navigation.route_type" to "navigation_direction"
            )
        )
    }

    // Generic wrapper function
    private fun <T : NavDirections> trackNavigation(
        toScreen: String,
        fromScreen: String = "unknown",
        directions: () -> T
    ): T {
        trackNavigationEvent(toScreen, fromScreen)
        return directions()
    }

    // Now all your navigation functions become one-liners
    fun navigateToLogin(): NavDirections = trackNavigation("Login") {
        ActionOnlyNavDirections(R.id.action_nav_login)
    }

    fun navigateToWelcome(): NavDirections = trackNavigation("Welcome") {
        ActionOnlyNavDirections(R.id.action_nav_welcome)
    }

    fun navigateToHome(): NavDirections = trackNavigation("Home") {
        ActionOnlyNavDirections(R.id.action_nav_home)
    }

    // Example with fromScreen parameter
    fun navigateToProfile(fromScreen: String): NavDirections = trackNavigation(
        toScreen = "Profile",
        fromScreen = fromScreen
    ) {
        ActionOnlyNavDirections(R.id.action_nav_profile)
    }
}
