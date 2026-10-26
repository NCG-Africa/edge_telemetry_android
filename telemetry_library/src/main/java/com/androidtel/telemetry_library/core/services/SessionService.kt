package com.androidtel.telemetry_library.core.services

import android.content.Context
import com.androidtel.telemetry_library.core.log.TelemetryLog
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
    private var finalizedSession: Map<String, Any>? = null
    private var resumedEventCount = 0
    private var resumedMetricCount = 0

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
            resumedEventCount = prefs.getInt(KEY_EVENT_COUNT, 0)
            resumedMetricCount = prefs.getInt(KEY_METRIC_COUNT, 0)
            initResumed = true
        } else {
            if (savedId != null && savedId != prefs.getString(KEY_FINALIZED_ID, null)) {
                // Prior session died in background and timed out — capture its OWN persisted state so
                // the manager can emit one session.finalized(reason=timeout) that overrides the new
                // session's envelope (#143: start/counts were the next cold start's). An id already
                // finalized (manual end, warm timeout) is never finalized again.
                initTimedOut = true
                prefs.edit().putString(KEY_FINALIZED_ID, savedId).apply()
                val savedStart = prefs.getLong("session_start", lastActive)
                val savedTotal = prefs.getInt("total_sessions", 1)
                finalizedSession = mapOf(
                    "session.id" to savedId,
                    "session.start_time" to TelemetryTime.isoOf(savedStart),
                    "session.duration_ms" to (lastActive - savedStart).coerceAtLeast(0L),
                    "session.event_count" to prefs.getInt(KEY_EVENT_COUNT, 0),
                    "session.metric_count" to prefs.getInt(KEY_METRIC_COUNT, 0),
                    "session.total_sessions" to savedTotal,
                    "session.is_first_session" to (savedTotal == 1)
                )
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
        // resume branch logs and does nothing. No double-decision, no double-mint. Persisting the
        // decided id now (not first at onStop) overwrites a finalized id, so a process that dies
        // before backgrounding can't resume or re-finalize it next launch (#143).
        persistSession(resumedEventCount, resumedMetricCount)

        TelemetryLog.d(TAG, "SessionService initialized - Session: $sessionId, Total: $totalSessions, " +
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
        persistSession(0, 0)

        // A live (warm) rotation is neither a resume nor an init-timeout.
        initResumed = false
        initTimedOut = false

        TelemetryLog.i(TAG, "New session started: $sessionId")
        return sessionId
    }
    
    /**
     * End current session
     */
    fun endCurrentSession() {
        if (config.enableSessionTracking && enhancedSessionManager != null) {
            enhancedSessionManager!!.endCurrentSession()
        }
        TelemetryLog.i(TAG, "Session ended: $sessionId")
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
    
    /** Mark user activity — the foreground inactivity timeout measures from here (#143). */
    fun updateLastActiveTimestamp() {
        prefs.edit().putLong("last_active_timestamp", System.currentTimeMillis()).apply()
    }

    /**
     * Persist the current session (issue #53) so it can be resumed across process death — with its
     * counts, so a later timed-out finalize reports this session's own data (#143).
     */
    fun persistSession(eventCount: Int, metricCount: Int) {
        prefs.edit()
            .putString("session_id", sessionId)
            .putLong("session_start", sessionStartTime)
            .putLong("last_active_timestamp", System.currentTimeMillis())
            .putInt(KEY_EVENT_COUNT, eventCount)
            .putInt(KEY_METRIC_COUNT, metricCount)
            .apply()
    }

    /** True if initialize() resumed a persisted session — suppresses session.started. */
    fun wasResumed(): Boolean = initResumed

    /** True if initialize() found a persisted session that had timed out — triggers finalize. */
    fun timedOutOnInit(): Boolean = initTimedOut

    /** The timed-out session's own session.* attrs captured at init, for a single session.finalized. */
    fun getFinalizedSessionAttrs(): Map<String, Any>? = finalizedSession

    fun getFinalizedSessionId(): String? = finalizedSession?.get("session.id") as String?

    /**
     * Claim the right to finalize the current session: true exactly once per id, persisted so neither
     * a later warm timeout nor a later cold start finalizes it again (#143).
     */
    fun claimFinalize(): Boolean {
        if (prefs.getString(KEY_FINALIZED_ID, null) == sessionId) return false
        prefs.edit().putString(KEY_FINALIZED_ID, sessionId).apply()
        return true
    }

    /** Counts of a session resumed at init — the manager seeds its counters with them. */
    fun getResumedEventCount(): Int = resumedEventCount
    fun getResumedMetricCount(): Int = resumedMetricCount

    /** Start → last activity: a timed-out session's duration excludes the idle gap that ended it. */
    fun getActiveDurationMs(): Long = (getLastActiveTimestamp() - sessionStartTime).coerceAtLeast(0L)
    
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
        private const val KEY_EVENT_COUNT = "session_event_count"
        private const val KEY_METRIC_COUNT = "session_metric_count"
        private const val KEY_FINALIZED_ID = "finalized_session_id"
    }
}
