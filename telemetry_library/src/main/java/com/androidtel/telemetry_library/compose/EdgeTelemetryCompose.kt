package com.androidtel.telemetry_library.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.androidtel.telemetry_library.EdgeTelemetry
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker
import com.androidtel.telemetry_library.core.TelemetryTime
import com.androidtel.telemetry_library.core.trace.TraceManager

/**
 * Enhanced Compose integration for EdgeTelemetry that provides automatic navigation tracking
 * and screen duration metrics with breadcrumb integration
 */

/**
 * Track Compose screen navigation automatically
 * 
 * @param navController The NavController to track
 * @param screenName Optional custom screen name (defaults to route)
 * @param additionalData Optional additional data to include in tracking
 */
@Composable
fun TrackComposeScreen(
    navController: NavController,
    screenName: String? = null,
    additionalData: Map<String, String>? = null
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // Use remember to maintain tracker across recompositions
    val navigationTracker = remember { NavigationStackTracker() }
    
    DisposableEffect(navBackStackEntry) {
        val route = navBackStackEntry?.destination?.route ?: "unknown"
        val finalScreenName = screenName ?: route
        val startTime = System.currentTimeMillis()
        
        // Track navigation with proper structure
        val navEvent = navigationTracker.push(finalScreenName)
        val hasArguments = navBackStackEntry?.arguments?.isEmpty == false
        
        // For breadcrumb (requires Map<String, String>)
        val breadcrumbData = mutableMapOf(
            "navigation.from_screen" to (navEvent.fromScreen ?: ""),
            "navigation.to_screen" to navEvent.toScreen,
            "navigation.method" to navEvent.method.toLowerCaseString(),
            "navigation.route_type" to (additionalData?.get("route_type") ?: "main_flow"),
            "navigation.has_arguments" to hasArguments.toString(),
            "navigation.timestamp" to navEvent.timestamp
        )
        
        // For event (can have mixed types)
        val eventData = mapOf(
            "navigation.from_screen" to (navEvent.fromScreen ?: ""),
            "navigation.to_screen" to navEvent.toScreen,
            "navigation.method" to navEvent.method.toLowerCaseString(),
            "navigation.route_type" to (additionalData?.get("route_type") ?: "main_flow"),
            "navigation.has_arguments" to hasArguments,
            "navigation.timestamp" to navEvent.timestamp
        )
        
        // Add navigation breadcrumb
        EdgeTelemetry.getInstance().addBreadcrumb(
            message = "Navigated to $finalScreenName",
            category = "navigation",
            level = "info",
            data = breadcrumbData
        )
        
        // Track navigation event — child of a recent interaction (tap→nav) else a new trace root (#59).
        val trace = TraceManager.onNavigation(System.currentTimeMillis()) ?: emptyMap()
        EdgeTelemetry.getInstance().recordEvent("navigation", eventData + trace)
        
        onDispose {
            val duration = System.currentTimeMillis() - startTime
            
            // Track screen exit and duration
            val exitData = mapOf(
                "screen" to finalScreenName,
                "route" to route,
                "exit_method" to "navigation",
                "duration_ms" to duration.toString(),
                "timestamp" to TelemetryTime.now()
            )

            // Add exit breadcrumb
            EdgeTelemetry.getInstance().addBreadcrumb(
                message = "Exited $finalScreenName",
                category = "navigation",
                level = "info",
                data = exitData
            )

            // Track screen duration event in the wire format the backend expects (eventName=screen.duration)
            EdgeTelemetry.getInstance().recordScreenDuration(finalScreenName, duration, "navigation")
        }
    }
}

/**
 * Extension function for NavController to enable automatic tracking
 */
fun NavController.trackWithEdgeTelemetry() {
    // This would be used in a Composable context
    // The actual implementation is in TrackComposeScreen
}

/**
 * Composable that tracks a specific screen with custom attributes
 * 
 * @param screenName The name of the screen to track
 * @param category Optional category for the screen (defaults to "screen")
 * @param attributes Optional additional attributes
 */
@Composable
fun TrackScreen(
    screenName: String,
    category: String = "screen",
    attributes: Map<String, String>? = null
) {
    DisposableEffect(screenName) {
        val startTime = System.currentTimeMillis()
        
        // Track screen entry
        val entryData = mutableMapOf<String, String>(
            "screen" to screenName,
            "category" to category,
            "type" to "screen_entry",
            "timestamp" to TelemetryTime.now()
        )
        attributes?.let { entryData.putAll(it) }
        
        // Add screen entry breadcrumb
        EdgeTelemetry.getInstance().addBreadcrumb(
            message = "Entered screen: $screenName",
            category = "navigation",
            level = "info",
            data = entryData
        )
        
        onDispose {
            val duration = System.currentTimeMillis() - startTime

            // Track screen exit
            val exitData = mutableMapOf<String, String>(
                "screen" to screenName,
                "category" to category,
                "duration_ms" to duration.toString(),
                "timestamp" to TelemetryTime.now()
            )
            attributes?.let { exitData.putAll(it) }

            // Add screen exit breadcrumb
            EdgeTelemetry.getInstance().addBreadcrumb(
                message = "Exited screen: $screenName",
                category = "navigation",
                level = "info",
                data = exitData
            )
        }
    }
}

/**
 * Track user interactions within Compose UI
 * 
 * @param action The action performed (e.g., "button_click", "swipe", "scroll")
 * @param target The target of the action (e.g., "login_button", "product_card")
 * @param attributes Optional additional attributes
 */
fun trackUserInteraction(
    action: String,
    target: String,
    attributes: Map<String, String>? = null
) {
    val interactionData = mutableMapOf<String, String>(
        "action" to action,
        "target" to target,
        "timestamp" to TelemetryTime.now()
    )
    attributes?.let { interactionData.putAll(it) }
    
    // Add user interaction breadcrumb
    EdgeTelemetry.getInstance().addBreadcrumb(
        message = "User $action on $target",
        category = "user",
        level = "info",
        data = interactionData
    )
    
    // Track user interaction event (only if user interaction events are enabled)
    if (EdgeTelemetry.getInstance().isUserInteractionEventsEnabled()) {
        EdgeTelemetry.getInstance().recordEvent("user.interaction", interactionData)
    }
}

/**
 * Track Compose performance metrics
 * 
 * @param metricName The name of the performance metric
 * @param value The metric value
 * @param unit The unit of measurement (e.g., "ms", "fps", "mb")
 * @param attributes Optional additional attributes
 */
fun trackComposePerformance(
    metricName: String,
    value: Double,
    unit: String,
    attributes: Map<String, String>? = null
) {
    // ponytail: no-op, kept for source compatibility. Its emission was gated behind the removed
    // enableLegacyScreenEvents flag (default off = never emitted, issue #53). Left inert rather than
    // introduce new telemetry outside the issue's scope — route to recordMetric() if perf metrics
    // are later wanted.
}
