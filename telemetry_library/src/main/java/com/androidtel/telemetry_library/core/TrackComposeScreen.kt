package com.androidtel.telemetry_library.core

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
    telemetryManager: TelemetryManager = TelemetryManager.getInstance()
) {
    // This effect runs whenever the current back stack entry changes.
    // It is a simple way to track navigation events.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    DisposableEffect(currentRoute) {
        if (currentRoute != null) {
            telemetryManager.recordComposeScreenView(currentRoute)
        }
        onDispose {
            // Note: The screen end event is handled by the Lifecycle observer below.
        }
    }

    // We can also use a lifecycle observer for more accurate session-based tracking.
    // This tracks when the composable leaves the screen's active lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_STOP && currentRoute != null) {
                    telemetryManager.recordComposeScreenEnd(currentRoute)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}