package com.androidtel.telemetry_library.core.session

import android.util.Log
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.utils.DateTimeUtils
import java.util.Date
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Session Manager that handles session lifecycle and statistics
 */
class SessionManager(private val idGenerator: IdGenerator) {
    
    companion object {
        private const val TAG = "SessionManager"
    }
    
    private val lock = ReentrantReadWriteLock()
    
    // Current session state
    private var currentSessionId: String? = null
    private var sessionStartTime: Long = 0
    private var eventCount = 0
    private var metricCount = 0
    private val visitedScreens = mutableSetOf<String>()
    
    // Session statistics
    private var totalSessions = 0
    private var isFirstSession = true
    
    init {
        startNewSession()
    }
    
    /**
     * Start a new session
     */
    fun startNewSession() {
        lock.write {
            currentSessionId = idGenerator.generateSessionId()
            sessionStartTime = System.currentTimeMillis()
            eventCount = 0
            metricCount = 0
            visitedScreens.clear()
            totalSessions++
            
            Log.i(TAG, "🚀 New session started: $currentSessionId")
        }
    }
    
    /**
     * End current session
     */
    fun endCurrentSession() {
        lock.read {
            val sessionId = currentSessionId
            val duration = System.currentTimeMillis() - sessionStartTime
            
            Log.i(TAG, "🏁 Session ended: $sessionId (duration: ${duration}ms)")
        }
    }
    
    /**
     * Record an event in the current session
     */
    fun recordEvent() {
        lock.write {
            eventCount++
        }
    }
    
    /**
     * Record a metric in the current session
     */
    fun recordMetric() {
        lock.write {
            metricCount++
        }
    }
    
    /**
     * Record a visited screen
     */
    fun recordScreen(screenName: String) {
        lock.write {
            visitedScreens.add(screenName)
        }
    }
    
    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String {
        return lock.read {
            currentSessionId ?: run {
                startNewSession()
                currentSessionId!!
            }
        }
    }
    
    /**
     * Get session attributes for telemetry events
     */
    fun getSessionAttributes(): Map<String, String> {
        return lock.read {
            val duration = System.currentTimeMillis() - sessionStartTime
            
            mapOf(
                "session.id" to getCurrentSessionId(),
                "session.start_time" to DateTimeUtils.formatToIso8601(Date(sessionStartTime)),
                "session.duration_ms" to duration.toString(),
                "session.event_count" to eventCount.toString(),
                "session.metric_count" to metricCount.toString(),
                "session.screen_count" to visitedScreens.size.toString(),
                "session.visited_screens" to visitedScreens.joinToString(","),
                "session.is_first_session" to isFirstSession.toString(),
                "session.total_sessions" to totalSessions.toString()
            )
        }
    }
    
    /**
     * Get session statistics
     */
    fun getSessionStats(): Map<String, Any> {
        return lock.read {
            val duration = System.currentTimeMillis() - sessionStartTime
            
            mapOf(
                "sessionId" to getCurrentSessionId(),
                "startTime" to DateTimeUtils.formatToIso8601(Date(sessionStartTime)),
                "durationMs" to duration,
                "eventCount" to eventCount,
                "metricCount" to metricCount,
                "screenCount" to visitedScreens.size,
                "visitedScreens" to visitedScreens.toList(),
                "isFirstSession" to isFirstSession,
                "totalSessions" to totalSessions
            )
        }
    }
    
    /**
     * Mark that this is no longer the first session
     */
    fun markNotFirstSession() {
        lock.write {
            isFirstSession = false
        }
    }
}
