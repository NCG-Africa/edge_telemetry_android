package com.androidtel.telemetry_library.core.navigation

import androidx.fragment.app.Fragment
import com.androidtel.telemetry_library.core.TelemetryManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper for manual Fragment screen tracking
 * Use this when automatic tracking doesn't capture your specific navigation patterns
 */
object FragmentScreenTracker {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    
    /**
     * Track Fragment screen view manually
     * Call this in Fragment's onViewCreated or onResume
     */
    fun trackFragmentScreen(
        fragment: Fragment,
        screenName: String? = null,
        fromScreen: String? = null,
        attributes: Map<String, Any> = emptyMap()
    ) {
        val telemetryManager = TelemetryManager.getInstance()
        val finalScreenName = screenName ?: fragment.javaClass.simpleName
        
        // Track navigation event
        telemetryManager.recordEvent(
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to (fromScreen ?: ""),
                "navigation.to_screen" to finalScreenName,
                "navigation.method" to "push",
                "navigation.route_type" to "fragment",
                "navigation.has_arguments" to attributes.isNotEmpty(),
                "navigation.timestamp" to dateFormat.format(Date())
            ) + attributes
        )
        
        // Track screen duration start
        telemetryManager.recordComposeScreenView(finalScreenName)
    }
    
    /**
     * Track Fragment screen exit
     * Call this in Fragment's onDestroyView or onPause
     */
    fun trackFragmentScreenExit(
        fragment: Fragment,
        screenName: String? = null
    ) {
        val telemetryManager = TelemetryManager.getInstance()
        val finalScreenName = screenName ?: fragment.javaClass.simpleName
        
        // Track screen duration end
        telemetryManager.recordComposeScreenEnd(finalScreenName)
    }
}
