package com.androidtel.telemetry_library.core

import java.util.concurrent.ConcurrentHashMap

class ScreenTimingTracker {
    private val screenStartTimes = ConcurrentHashMap<String, Long>()

    // Starts the timer for a given screen.
    fun startScreen(screenName: String) {
        screenStartTimes[screenName] = System.currentTimeMillis()
    }

    // Ends the timer for a given screen and returns the duration in milliseconds.
    fun endScreen(screenName: String): Long? {
        val startTime = screenStartTimes.remove(screenName) ?: return null
        return System.currentTimeMillis() - startTime
    }

}
