package com.androidtel.telemetry_library.core.navigation

import com.androidtel.telemetry_library.core.TelemetryTime
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class NavigationStackTracker {
    private val navigationStack = ArrayDeque<String>()
    private val lock = ReentrantReadWriteLock()
    
    fun push(screenName: String): NavigationEvent {
        return lock.write {
            val fromScreen = navigationStack.peekLast()
            navigationStack.addLast(screenName)
            sharedCurrentScreen = screenName
            NavigationEvent(
                fromScreen = fromScreen,
                toScreen = screenName,
                method = if (fromScreen == null) NavigationMethod.INITIAL else NavigationMethod.PUSH,
                timestamp = TelemetryTime.now()
            )
        }
    }
    
    fun pop(): NavigationEvent? {
        return lock.write {
            if (navigationStack.size < 2) return null
            val fromScreen = navigationStack.removeLast()
            val toScreen = navigationStack.peekLast() ?: return null
            sharedCurrentScreen = toScreen
            NavigationEvent(
                fromScreen = fromScreen,
                toScreen = toScreen,
                method = NavigationMethod.POP,
                timestamp = TelemetryTime.now()
            )
        }
    }
    
    fun replace(screenName: String): NavigationEvent {
        return lock.write {
            val fromScreen = if (navigationStack.isNotEmpty()) {
                navigationStack.removeLast()
            } else null
            navigationStack.addLast(screenName)
            sharedCurrentScreen = screenName
            NavigationEvent(
                fromScreen = fromScreen,
                toScreen = screenName,
                method = NavigationMethod.REPLACE,
                timestamp = TelemetryTime.now()
            )
        }
    }
    
    fun getCurrentScreen(): String? = lock.read { navigationStack.peekLast() }
    
    fun getPreviousScreen(): String? = lock.read {
        if (navigationStack.size >= 2) navigationStack.elementAt(navigationStack.size - 2) else null
    }

    companion object {
        // Process-wide latch of the most recently navigated screen, updated by every
        // push/pop/replace on any tracker instance. Lets components without their own tracker
        // (e.g. the frame collector, #54) read the current screen across both Activity and
        // single-Activity Compose navigation.
        @Volatile
        private var sharedCurrentScreen: String? = null

        fun currentScreen(): String? = sharedCurrentScreen
    }
}

data class NavigationEvent(
    val fromScreen: String?,
    val toScreen: String,
    val method: NavigationMethod,
    val timestamp: String
) {
    /** The catalogue `navigation` keys (processor #31). The event's own timestamp is the time. */
    fun toAttributes(routeType: String, hasArguments: Boolean): Map<String, Any> = buildMap {
        fromScreen?.let { put("navigation.from_screen", it) }
        put("navigation.to_screen", toScreen)
        put("navigation.method", method.toLowerCaseString())
        put("navigation.route_type", routeType)
        put("navigation.has_arguments", hasArguments)
    }
}

enum class NavigationMethod {
    // The catalogue's navigation.method enum: push, pop, replace, tab, deep_link, initial, unknown.
    PUSH, POP, REPLACE, INITIAL;
    
    fun toLowerCaseString(): String = name.lowercase()
}
