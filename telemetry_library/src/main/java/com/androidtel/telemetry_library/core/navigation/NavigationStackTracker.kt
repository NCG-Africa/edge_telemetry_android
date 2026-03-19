package com.androidtel.telemetry_library.core.navigation

import com.androidtel.telemetry_library.utils.DateTimeUtils
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
            NavigationEvent(
                fromScreen = fromScreen,
                toScreen = screenName,
                method = NavigationMethod.PUSH,
                timestamp = DateTimeUtils.nowAsIso8601()
            )
        }
    }
    
    fun pop(): NavigationEvent? {
        return lock.write {
            if (navigationStack.size < 2) return null
            val fromScreen = navigationStack.removeLast()
            val toScreen = navigationStack.peekLast() ?: return null
            NavigationEvent(
                fromScreen = fromScreen,
                toScreen = toScreen,
                method = NavigationMethod.POP,
                timestamp = DateTimeUtils.nowAsIso8601()
            )
        }
    }
    
    fun replace(screenName: String): NavigationEvent {
        return lock.write {
            val fromScreen = if (navigationStack.isNotEmpty()) {
                navigationStack.removeLast()
            } else null
            navigationStack.addLast(screenName)
            NavigationEvent(
                fromScreen = fromScreen,
                toScreen = screenName,
                method = NavigationMethod.REPLACE,
                timestamp = DateTimeUtils.nowAsIso8601()
            )
        }
    }
    
    fun getCurrentScreen(): String? = lock.read { navigationStack.peekLast() }
    
    fun getPreviousScreen(): String? = lock.read { 
        if (navigationStack.size >= 2) navigationStack.elementAt(navigationStack.size - 2) else null
    }
}

data class NavigationEvent(
    val fromScreen: String?,
    val toScreen: String,
    val method: NavigationMethod,
    val timestamp: String
)

enum class NavigationMethod {
    PUSH, POP, REPLACE;
    
    fun toLowerCaseString(): String = name.lowercase()
}
