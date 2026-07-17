package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.session.SessionManager
import com.androidtel.telemetry_library.core.TelemetryTime

/**
 * SessionService - Handles session lifecycle and tracking
 * Extracted from TelemetryManager as part of Phase 2 refactoring
 * 
 * Responsibilities:
 * - Start/end sessions
 * - Track session duration and metrics
 * - Manage session timeout
 * - Provide session information for events
 */
internal class SessionService(
    private val context: Context,
    private val config: TelemetryConfig,
    private val idGenerator: IdGenerator
) {
    private val prefs = context.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
    
    private lateinit var sessionId: String
    private var sessionStartTime = System.currentTimeMillis()
    private var totalSessions: Int = 0
    private val visitedScreens: MutableSet<String> = mutableSetOf()
    
    private var enhancedSessionManager: SessionManager? = null
    
    fun initialize() {
        sessionId = idGenerator.generateSessionId()
        totalSessions = prefs.getInt("total_sessions", 0) + 1
        prefs.edit().putInt("total_sessions", totalSessions).apply()
        
        if (config.enableSessionTracking) {
            enhancedSessionManager = SessionManager(idGenerator)
        }
        
        Log.d(TAG, "SessionService initialized - Session: $sessionId, Total sessions: $totalSessions")
    }
    
    /**
     * Start a new session
     */
    fun startNewSession(): String {
        if (config.enableSessionTracking && enhancedSessionManager != null) {
            enhancedSessionManager!!.startNewSession()
            sessionId = enhancedSessionManager!!.getCurrentSessionId()
        } else {
            sessionId = idGenerator.generateSessionId()
        }
        
        sessionStartTime = System.currentTimeMillis()
        visitedScreens.clear()
        totalSessions++
        prefs.edit().putInt("total_sessions", totalSessions).apply()
        
        Log.i(TAG, "New session started: $sessionId")
        return sessionId
    }
    
    /**
     * End current session
     */
    fun endCurrentSession() {
        if (config.enableSessionTracking && enhancedSessionManager != null) {
            enhancedSessionManager!!.endCurrentSession()
        }
        Log.i(TAG, "Session ended: $sessionId")
    }
    
    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String {
        return if (config.enableSessionTracking && enhancedSessionManager != null) {
            enhancedSessionManager!!.getCurrentSessionId()
        } else {
            sessionId
        }
    }
    
    /**
     * Get session information for event attributes
     */
    fun getSessionInfo(eventCount: Int, metricCount: Int, networkType: String): SessionInfo {
        val now = System.currentTimeMillis()
        val duration = now - sessionStartTime
        
        return SessionInfo(
            sessionId = getCurrentSessionId(),
            startTime = TelemetryTime.isoOf(sessionStartTime),
            durationMs = duration,
            eventCount = eventCount,
            metricCount = metricCount,
            screenCount = visitedScreens.size,
            visitedScreens = visitedScreens.joinToString(","),
            isFirstSession = totalSessions == 1,
            totalSessions = totalSessions,
            networkType = networkType
        )
    }
    
    /**
     * Add visited screen to session tracking
     */
    fun addVisitedScreen(screenName: String) {
        visitedScreens.add(screenName)
    }
    
    /**
     * Check if session has timed out
     */
    fun hasSessionTimedOut(): Boolean {
        val lastActive = prefs.getLong("last_active_timestamp", 0L)
        if (lastActive == 0L) return false
        
        val elapsed = System.currentTimeMillis() - lastActive
        return elapsed >= config.sessionTimeoutMs
    }
    
    /**
     * Update last active timestamp
     */
    fun updateLastActiveTimestamp() {
        prefs.edit().putLong("last_active_timestamp", System.currentTimeMillis()).apply()
    }
    
    /**
     * Get last active timestamp
     */
    fun getLastActiveTimestamp(): Long {
        return prefs.getLong("last_active_timestamp", 0L)
    }
    
    /**
     * Get session start time
     */
    fun getSessionStartTime(): Long = sessionStartTime
    
    /**
     * Get total sessions count
     */
    fun getTotalSessions(): Int = totalSessions
    
    /**
     * Get visited screens
     */
    fun getVisitedScreens(): Set<String> = visitedScreens.toSet()
    
    /**
     * Get enhanced session manager (for internal use)
     */
    fun getEnhancedSessionManager(): SessionManager? = enhancedSessionManager
    
    companion object {
        private const val TAG = "SessionService"
    }
}
