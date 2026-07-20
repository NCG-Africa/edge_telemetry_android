package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.content.SharedPreferences
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.ids.IdGenerator
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for SessionService
 * Tests session lifecycle, timeout management, and session information tracking
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionServiceTest {

    private lateinit var context: Context
    private lateinit var config: TelemetryConfig
    private lateinit var idGenerator: IdGenerator
    private lateinit var service: SessionService
    private lateinit var sharedPrefs: SharedPreferences

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        config = TelemetryConfig(
            apiKey = "edge_test-api-key",
            endpoint = "https://test.example.com",
            enableCrashReporting = true,
            enableSessionTracking = true,
            enableLocationTracking = false,
            batchSize = 50,
            flushIntervalMs = 30000,
            sessionTimeoutMs = 1800000 // 30 minutes
        )
        
        idGenerator = mockk(relaxed = true)
        every { idGenerator.generateSessionId() } returns "test-session-id-123"
        every { idGenerator.getUserId() } returns "test-user-id"
        
        service = SessionService(context, config, idGenerator)
        sharedPrefs = context.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().apply()
    }

    @Test
    fun `test service initialization generates session ID`() {
        service.initialize()
        
        verify { idGenerator.generateSessionId() }
        assertEquals("test-session-id-123", service.getCurrentSessionId())
    }

    @Test
    fun `test initialization increments total sessions count`() {
        sharedPrefs.edit().putInt("total_sessions", 5).apply()
        
        service.initialize()
        
        assertEquals(6, service.getTotalSessions())
        assertEquals(6, sharedPrefs.getInt("total_sessions", 0))
    }

    @Test
    fun `test initialization with no previous sessions starts at 1`() {
        service.initialize()
        
        assertEquals(1, service.getTotalSessions())
    }

    @Test
    fun `test startNewSession generates new session ID`() {
        service.initialize()
        
        every { idGenerator.generateSessionId() } returns "new-session-id-456"
        val newSessionId = service.startNewSession()
        
        assertEquals("new-session-id-456", newSessionId)
        assertEquals("new-session-id-456", service.getCurrentSessionId())
    }

    @Test
    fun `test startNewSession increments total sessions`() {
        service.initialize()
        val initialCount = service.getTotalSessions()
        
        service.startNewSession()
        
        assertEquals(initialCount + 1, service.getTotalSessions())
    }

    @Test
    fun `test startNewSession clears visited screens`() {
        service.initialize()
        service.addVisitedScreen("Home")
        service.addVisitedScreen("Profile")
        
        assertEquals(2, service.getVisitedScreens().size)
        
        service.startNewSession()
        
        assertEquals(0, service.getVisitedScreens().size)
    }

    @Test
    fun `test startNewSession with enhanced session manager enabled`() {
        val enhancedConfig = config.copy(enableSessionTracking = true)
        val enhancedService = SessionService(context, enhancedConfig, idGenerator)
        
        every { idGenerator.generateSessionId() } returns "enhanced-session-id"
        enhancedService.initialize()
        
        val sessionId = enhancedService.startNewSession()
        
        assertNotNull(sessionId)
        assertNotNull(enhancedService.getEnhancedSessionManager())
    }

    @Test
    fun `test endCurrentSession with enhanced manager`() {
        val enhancedConfig = config.copy(enableSessionTracking = true)
        val enhancedService = SessionService(context, enhancedConfig, idGenerator)
        enhancedService.initialize()
        
        // Should not throw exception
        enhancedService.endCurrentSession()
    }

    @Test
    fun `test endCurrentSession without enhanced manager`() {
        val basicConfig = config.copy(enableSessionTracking = false)
        val basicService = SessionService(context, basicConfig, idGenerator)
        basicService.initialize()
        
        // Should not throw exception
        basicService.endCurrentSession()
    }

    @Test
    fun `test getCurrentSessionId returns correct ID`() {
        every { idGenerator.generateSessionId() } returns "specific-session-id"
        service.initialize()
        
        assertEquals("specific-session-id", service.getCurrentSessionId())
    }

    @Test
    fun `test getSessionInfo returns complete session information`() {
        service.initialize()
        Thread.sleep(100) // Small delay to ensure duration > 0
        
        val sessionInfo = service.getSessionInfo(
            eventCount = 10,
            metricCount = 5,
            networkType = "WiFi"
        )
        
        assertEquals("test-session-id-123", sessionInfo.sessionId)
        assertNotNull(sessionInfo.startTime)
        assertTrue(sessionInfo.durationMs!! >= 100)
        assertEquals(10, sessionInfo.eventCount)
        assertEquals(5, sessionInfo.metricCount)
        assertEquals(0, sessionInfo.screenCount)
        assertEquals("", sessionInfo.visitedScreens)
        assertEquals("WiFi", sessionInfo.networkType)
    }

    @Test
    fun `test getSessionInfo with visited screens`() {
        service.initialize()
        service.addVisitedScreen("Home")
        service.addVisitedScreen("Profile")
        service.addVisitedScreen("Settings")
        
        val sessionInfo = service.getSessionInfo(0, 0, "4G")
        
        assertEquals(3, sessionInfo.screenCount)
        assertTrue(sessionInfo.visitedScreens!!.contains("Home"))
        assertTrue(sessionInfo.visitedScreens!!.contains("Profile"))
        assertTrue(sessionInfo.visitedScreens!!.contains("Settings"))
    }

    @Test
    fun `test getSessionInfo marks first session correctly`() {
        service.initialize()
        
        val sessionInfo = service.getSessionInfo(0, 0, "WiFi")
        
        assertTrue(sessionInfo.isFirstSession!!)
        assertEquals(1, sessionInfo.totalSessions)
    }

    @Test
    fun `test getSessionInfo marks subsequent sessions correctly`() {
        sharedPrefs.edit().putInt("total_sessions", 5).apply()
        service.initialize()
        
        val sessionInfo = service.getSessionInfo(0, 0, "WiFi")
        
        assertFalse(sessionInfo.isFirstSession!!)
        assertEquals(6, sessionInfo.totalSessions)
    }

    @Test
    fun `test addVisitedScreen adds unique screens`() {
        service.initialize()
        
        service.addVisitedScreen("Home")
        service.addVisitedScreen("Profile")
        service.addVisitedScreen("Settings")
        
        val visitedScreens = service.getVisitedScreens()
        assertEquals(3, visitedScreens.size)
        assertTrue(visitedScreens.contains("Home"))
        assertTrue(visitedScreens.contains("Profile"))
        assertTrue(visitedScreens.contains("Settings"))
    }

    @Test
    fun `test addVisitedScreen handles duplicates`() {
        service.initialize()
        
        service.addVisitedScreen("Home")
        service.addVisitedScreen("Home")
        service.addVisitedScreen("Profile")
        service.addVisitedScreen("Home")
        
        val visitedScreens = service.getVisitedScreens()
        assertEquals(2, visitedScreens.size) // Set prevents duplicates
    }

    @Test
    fun `test hasSessionTimedOut returns false when no last active timestamp`() {
        service.initialize()
        
        assertFalse(service.hasSessionTimedOut())
    }

    @Test
    fun `test hasSessionTimedOut returns false when within timeout window`() {
        service.initialize()
        val currentTime = System.currentTimeMillis()
        sharedPrefs.edit().putLong("last_active_timestamp", currentTime - 60000).apply() // 1 minute ago
        
        assertFalse(service.hasSessionTimedOut())
    }

    @Test
    fun `test hasSessionTimedOut returns true when beyond timeout window`() {
        service.initialize()
        val currentTime = System.currentTimeMillis()
        val timeoutMs = config.sessionTimeoutMs
        sharedPrefs.edit().putLong("last_active_timestamp", currentTime - timeoutMs - 1000).apply()
        
        assertTrue(service.hasSessionTimedOut())
    }

    @Test
    fun `test updateLastActiveTimestamp updates timestamp`() {
        service.initialize()
        val beforeUpdate = System.currentTimeMillis()
        
        service.updateLastActiveTimestamp()
        
        val savedTimestamp = service.getLastActiveTimestamp()
        assertTrue(savedTimestamp >= beforeUpdate)
        assertTrue(savedTimestamp <= System.currentTimeMillis())
    }

    @Test
    fun `test initialize seeds last_active so first onStart is a no-op`() {
        // initialize() writes last_active=now (load-then-decide seed), so an immediately
        // following hasSessionTimedOut() check sees elapsed ~= 0 and does not rotate.
        val before = System.currentTimeMillis()
        service.initialize()

        val seeded = service.getLastActiveTimestamp()
        assertTrue(seeded >= before)
        assertFalse(service.hasSessionTimedOut())
    }

    // --- Issue #53: cold-start session correctness (load-then-decide) ---

    @Test
    fun `cold start after timeout mints exactly one session and increments total once`() {
        // Prior process persisted a session that then timed out in background.
        sharedPrefs.edit()
            .putString("session_id", "old-session")
            .putLong("session_start", 1_000L)
            .putLong("last_active_timestamp", System.currentTimeMillis() - config.sessionTimeoutMs - 1000)
            .putInt("total_sessions", 3)
            .apply()
        every { idGenerator.generateSessionId() } returns "fresh-session"

        service.initialize()

        assertEquals("fresh-session", service.getCurrentSessionId())
        assertEquals(4, service.getTotalSessions())          // incremented exactly once
        assertTrue(service.timedOutOnInit())
        assertFalse(service.wasResumed())
    }

    @Test
    fun `cold start within timeout resumes persisted id, no total increment, no session_started`() {
        sharedPrefs.edit()
            .putString("session_id", "persisted-session")
            .putLong("session_start", 5_000L)
            .putLong("last_active_timestamp", System.currentTimeMillis() - 60_000) // 1 min ago
            .putInt("total_sessions", 7)
            .apply()

        service.initialize()

        assertEquals("persisted-session", service.getCurrentSessionId())
        assertEquals(7, service.getTotalSessions())          // no increment
        assertEquals(5_000L, service.getSessionStartTime())  // resumed start
        assertTrue(service.wasResumed())                     // suppresses session.started
        assertFalse(service.timedOutOnInit())
    }

    @Test
    fun `timed-out cold start exposes old id and duration for finalize`() {
        val now = System.currentTimeMillis()
        val lastActive = now - config.sessionTimeoutMs - 1000
        sharedPrefs.edit()
            .putString("session_id", "old-session")
            .putLong("session_start", lastActive - 100_000)     // ran 100s before going idle
            .putLong("last_active_timestamp", lastActive)
            .putInt("total_sessions", 2)
            .apply()
        every { idGenerator.generateSessionId() } returns "new-session"

        service.initialize()

        assertEquals("old-session", service.getFinalizedSessionId())
        // duration = last_active - session_start, from persisted values
        assertEquals(100_000L, service.getFinalizedDurationMs())
        assertEquals("new-session", service.getCurrentSessionId())
    }

    @Test
    fun `test getLastActiveTimestamp returns saved value`() {
        service.initialize()
        val testTimestamp = 1234567890L
        sharedPrefs.edit().putLong("last_active_timestamp", testTimestamp).apply()
        
        assertEquals(testTimestamp, service.getLastActiveTimestamp())
    }

    @Test
    fun `test getSessionStartTime returns initialization time`() {
        val beforeInit = System.currentTimeMillis()
        service.initialize()
        val afterInit = System.currentTimeMillis()
        
        val startTime = service.getSessionStartTime()
        assertTrue(startTime >= beforeInit)
        assertTrue(startTime <= afterInit)
    }

    @Test
    fun `test getTotalSessions returns correct count`() {
        sharedPrefs.edit().putInt("total_sessions", 10).apply()
        service.initialize()
        
        assertEquals(11, service.getTotalSessions())
    }

    @Test
    fun `test getVisitedScreens returns immutable copy`() {
        service.initialize()
        service.addVisitedScreen("Home")
        
        val screens1 = service.getVisitedScreens()
        service.addVisitedScreen("Profile")
        val screens2 = service.getVisitedScreens()
        
        assertEquals(1, screens1.size)
        assertEquals(2, screens2.size)
    }

    @Test
    fun `test getEnhancedSessionManager returns null when disabled`() {
        val basicConfig = config.copy(enableSessionTracking = false)
        val basicService = SessionService(context, basicConfig, idGenerator)
        basicService.initialize()
        
        assertNull(basicService.getEnhancedSessionManager())
    }

    @Test
    fun `test getEnhancedSessionManager returns instance when enabled`() {
        val enhancedConfig = config.copy(enableSessionTracking = true)
        val enhancedService = SessionService(context, enhancedConfig, idGenerator)
        enhancedService.initialize()
        
        assertNotNull(enhancedService.getEnhancedSessionManager())
    }

    @Test
    fun `test session duration increases over time`() {
        service.initialize()
        
        val info1 = service.getSessionInfo(0, 0, "WiFi")
        Thread.sleep(100)
        val info2 = service.getSessionInfo(0, 0, "WiFi")
        
        assertTrue(info2.durationMs!! > info1.durationMs!!)
    }

    @Test
    fun `test multiple session starts update session ID`() {
        service.initialize()
        val firstSessionId = service.getCurrentSessionId()
        
        every { idGenerator.generateSessionId() } returns "second-session-id"
        service.startNewSession()
        val secondSessionId = service.getCurrentSessionId()
        
        every { idGenerator.generateSessionId() } returns "third-session-id"
        service.startNewSession()
        val thirdSessionId = service.getCurrentSessionId()
        
        assertNotEquals(firstSessionId, secondSessionId)
        assertNotEquals(secondSessionId, thirdSessionId)
        assertEquals("second-session-id", secondSessionId)
        assertEquals("third-session-id", thirdSessionId)
    }

    @Test
    fun `test session info timestamp format is ISO 8601`() {
        service.initialize()
        
        val sessionInfo = service.getSessionInfo(0, 0, "WiFi")
        
        assertTrue(sessionInfo.startTime!!.contains("T"))
        assertTrue(sessionInfo.startTime!!.endsWith("Z"))
        // TelemetryTime.isoOf emits millisecond precision (yyyy-MM-dd'T'HH:mm:ss.SSS'Z'); millis optional.
        assertTrue(sessionInfo.startTime!!.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z")))
    }

    @Test
    fun `test session timeout calculation with exact boundary`() {
        service.initialize()
        val currentTime = System.currentTimeMillis()
        val timeoutMs = config.sessionTimeoutMs
        
        // Exactly at timeout boundary
        sharedPrefs.edit().putLong("last_active_timestamp", currentTime - timeoutMs).apply()
        
        assertTrue(service.hasSessionTimedOut())
    }

    @Test
    fun `test session persistence across service instances`() {
        service.initialize()
        val sessionId = service.getCurrentSessionId()
        val totalSessions = service.getTotalSessions()
        
        // Create new service instance
        val newService = SessionService(context, config, idGenerator)
        every { idGenerator.generateSessionId() } returns "new-instance-session"
        newService.initialize()
        
        // Total sessions should increment
        assertEquals(totalSessions + 1, newService.getTotalSessions())
    }

    @Test
    fun `test visited screens cleared on new session maintains independence`() {
        service.initialize()
        service.addVisitedScreen("Screen1")
        service.addVisitedScreen("Screen2")
        
        val screensBeforeNewSession = service.getVisitedScreens()
        assertEquals(2, screensBeforeNewSession.size)
        
        service.startNewSession()
        
        val screensAfterNewSession = service.getVisitedScreens()
        assertEquals(0, screensAfterNewSession.size)
        
        // Original set should still have 2 (immutable copy)
        assertEquals(2, screensBeforeNewSession.size)
    }

    @Test
    fun `test concurrent screen additions are thread-safe`() {
        service.initialize()
        
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(5) { screenNum ->
                    service.addVisitedScreen("Screen_${threadNum}_$screenNum")
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        assertEquals(50, service.getVisitedScreens().size)
    }

    @Test
    fun `test session info with different network types`() {
        service.initialize()
        
        val wifiInfo = service.getSessionInfo(10, 5, "WiFi")
        assertEquals("WiFi", wifiInfo.networkType)
        
        val cellularInfo = service.getSessionInfo(15, 8, "4G")
        assertEquals("4G", cellularInfo.networkType)
        
        val offlineInfo = service.getSessionInfo(0, 0, "None")
        assertEquals("None", offlineInfo.networkType)
    }
}
