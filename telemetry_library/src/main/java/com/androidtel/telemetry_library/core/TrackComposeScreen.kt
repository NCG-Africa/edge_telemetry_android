package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.TelemetryManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun TrackComposeScreen(
    navController: NavController,
    screenName: String? = null,
    additionalData: Map<String, String>? = null,
    telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    DisposableEffect(backStackEntry) {
        val route = backStackEntry?.destination?.route ?: "unknown"
        val finalScreenName = screenName ?: route
        val startTime = System.currentTimeMillis()
        
        // Track screen entry
        val entryData = mutableMapOf<String, String>(
            "route" to route,
            "method" to "navigation",
            "type" to "screen_entry"
        )
        additionalData?.let { entryData.putAll(it) }
        
        // Add navigation breadcrumb
        telemetryManager.addBreadcrumb(
            message = "Navigated to $finalScreenName",
            category = "navigation",
            level = "info",
            data = entryData
        )
        
        // Track navigation event with standardized structure
        telemetryManager.recordEvent(
            "navigation",
            mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to finalScreenName,
                "navigation.method" to "push",
                "navigation.route_type" to "compose_route",
                "navigation.has_arguments" to (additionalData != null),
                "navigation.timestamp" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
            )
        )
        
        // Lifecycle observer removed - unsupported events (navigation.screen_resume, navigation.screen_pause)
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            // No lifecycle events tracked - these are not supported by backend
        }
        
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        
        onDispose {
            val duration = System.currentTimeMillis() - startTime
            
            // Track screen exit and duration
            val exitData = mapOf(
                "screen" to finalScreenName,
                "route" to route,
                "exit_method" to "navigation",
                "duration_ms" to duration.toString()
            )
            
            // Add exit breadcrumb
            telemetryManager.addBreadcrumb(
                message = "Exited $finalScreenName",
                category = "navigation",
                level = "info",
                data = exitData
            )
            
            // Track screen duration event with proper structure
            telemetryManager.recordScreenDuration(finalScreenName, duration, "navigation")
            
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
}