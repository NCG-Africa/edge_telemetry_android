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
        
        // Track navigation event
        telemetryManager.trackEvent("navigation.route_change", entryData)
        
        // Set up lifecycle observer for screen duration tracking
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    telemetryManager.trackEvent("navigation.screen_resume", mapOf(
                        "screen" to finalScreenName,
                        "route" to route
                    ))
                }
                Lifecycle.Event.ON_PAUSE -> {
                    telemetryManager.trackEvent("navigation.screen_pause", mapOf(
                        "screen" to finalScreenName,
                        "route" to route
                    ))
                }
                else -> { /* No action needed */ }
            }
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
            
            // Track screen duration metric
            telemetryManager.trackEvent("performance.screen_duration", exitData)
            
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
}