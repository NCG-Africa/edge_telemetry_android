package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.session.SessionManager
import com.androidtel.telemetry_library.core.TelemetryTime
import java.util.concurrent.ConcurrentHashMap

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
    private val visitedScreens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    
    private var enhancedSessionManager: SessionManager? = null

    // Load-then-decide flags, valid immediately after initialize() (issue #53):
    private var initResumed = false
    private var initTimedOut = false
    private var finalizedSessionId: String? = null
    private var finalizedDurationMs: Long = 0L

    /**
     * Load-then-decide session lifecycle (issue #53).
     *
     * Resume the persisted session if it's still within timeout across process death; otherwise mint
     * a fresh one (capturing the timed-out id/duration for a single finalize). Seeds last_active=now
     * so the immediately-following first onStart is a no-op — no cold-start double-mint.
     */
    fun initialize() {
        val now = System.currentTimeMillis()
        val savedId = prefs.getString("session_id", null)
        val lastActive = prefs.getLong("last_active_timestamp", 0L)
        val withinTimeout = savedId != null && now - lastActive < config.sessionTimeoutMs

        if (withinTimeout) {
            // RESUME — silent continuation of the persisted session, no total increment.
            sessionId = savedId!!
            sessionStartTime = prefs.getLong("session_start", now)
            totalSessions = prefs.getInt("total_sessions", 1)
            initResumed = true
        } else {
            if (savedId != null) {
                // Prior session died in background and timed out — capture it so the manager can emit
                // one session.finalized(reason=timeout) before the new session starts.
                initTimedOut = true
                finalizedSessionId = savedId
                val savedStart = prefs.getLong("session_start", lastActive)
                finalizedDurationMs = (lastActive - savedStart).coerceAtLeast(0L)
            }
            sessionId = idGenerator.generateSessionId()
            sessionStartTime = now
            totalSessions = prefs.getInt("total_sessions", 0) + 1
            prefs.edit().putInt("total_sessions", totalSessions).apply()
        }

        if (config.enableSessionTracking) {
            enhancedSessionManager = SessionManager(idGenerator)
        }

        // Seed last_active=now: the first onStart after init sees elapsed ~= 0 → not timed out →
        // resume branch logs and does nothing. No double-decision, no double-mint.
        prefs.edit().putLong("last_active_timestamp", now).apply()

        Log.d(TAG, "SessionService initialized - Session: $sessionId, Total: $totalSessions, " +
            "resumed=$initResumed, timedOut=$initTimedOut")
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

        // A live (warm) rotation is neither a resume nor an init-timeout.
        initResumed = false
        initTimedOut = false

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
     * Get current session ID.
     *
     * The `sessionId` field is authoritative: initialize() sets it (resumed or freshly minted) and
     * startNewSession() syncs it from the enhanced manager on warm rotation. This is what makes the
     * persisted/resumed id (issue #53) actually flow to emitted events — the enhanced manager mints
     * its own id per process and is used only for in-memory stats.
     */
    fun getCurrentSessionId(): String = sessionId
    
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
     * Persist the current session on background (issue #53) so it can be resumed across process
     * death. Replaces the bare updateLastActiveTimestamp() on onStop.
     */
    fun persistSession() {
        prefs.edit()
            .putString("session_id", sessionId)
            .putLong("session_start", sessionStartTime)
            .putLong("last_active_timestamp", System.currentTimeMillis())
            .apply()
    }

    /** True if initialize() resumed a persisted session — suppresses session.started. */
    fun wasResumed(): Boolean = initResumed

    /** True if initialize() found a persisted session that had timed out — triggers finalize. */
    fun timedOutOnInit(): Boolean = initTimedOut

    /** Id of the timed-out session captured at init, for a single session.finalized. */
    fun getFinalizedSessionId(): String? = finalizedSessionId

    /** Duration (persisted session_start → last_active) of the timed-out session. */
    fun getFinalizedDurationMs(): Long = finalizedDurationMs
    
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
