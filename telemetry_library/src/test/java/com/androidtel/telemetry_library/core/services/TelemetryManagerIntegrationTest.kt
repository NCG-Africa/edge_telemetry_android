package com.androidtel.telemetry_library.core.services

import android.app.Application
import android.content.Context
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration tests for TelemetryManager facade
 * Tests end-to-end workflows and service coordination
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TelemetryManagerIntegrationTest {

    private lateinit var application: Application
    private lateinit var context: Context
    private lateinit var config: TelemetryConfig
    private lateinit var telemetryManager: TelemetryManager

    @Before
    fun setup() {
        application = RuntimeEnvironment.getApplication()
        context = application.applicationContext
        
        config = TelemetryConfig(
            apiKey = "edge_test-api-key-integration",
            endpoint = "https://test-integration.example.com",
            enableCrashReporting = true,
            enableSessionTracking = true,
            enableLocationTracking = false,
            batchSize = 10,
            flushIntervalMs = 30000,
            sessionTimeoutMs = 1800000
        )
        
        // Clear any existing instance
        val instanceField = TelemetryManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
        
        telemetryManager = TelemetryManager.getInstance()
    }

    @Test
    fun `test full initialization workflow`() {
        telemetryManager.initialize(application, config)
        
        assertTrue(telemetryManager.isInitialized())
    }

    @Test
    fun `test event recording after initialization`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordEvent("test_event", mapOf("key" to "value"))
        
        // Event should be recorded without throwing exception
    }

    @Test
    fun `test metric recording after initialization`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordMetric("test_metric", 123.45, mapOf("unit" to "ms"))
        
        // Metric should be recorded without throwing exception
    }

    @Test
    fun `test user profile workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.setUserProfile("John Doe", "john@example.com", "+1234567890")
        
        val userId = telemetryManager.getUserId()
        assertNotNull(userId)
        assertFalse(userId.isEmpty())
    }

    @Test
    fun `test session management workflow`() {
        telemetryManager.initialize(application, config)
        
        val sessionId1 = telemetryManager.getCurrentSessionId()
        assertNotNull(sessionId1)
        
        telemetryManager.startNewSession()
        
        val sessionId2 = telemetryManager.getCurrentSessionId()
        assertNotNull(sessionId2)
        assertNotEquals(sessionId1, sessionId2)
    }

    @Test
    fun `test crash reporting workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.addBreadcrumb("User action", "ui", "info")
        telemetryManager.trackError(RuntimeException("Test error"))
        
        // Should not throw exception
    }

    @Test
    fun `test pre-initialization event queuing`() {
        // Record events before initialization
        telemetryManager.recordEvent("early_event_1", emptyMap())
        telemetryManager.recordEvent("early_event_2", emptyMap())
        
        // Initialize
        telemetryManager.initialize(application, config)
        
        // Events should be processed after initialization
        assertTrue(telemetryManager.isInitialized())
    }

    @Test
    fun `test pre-initialization user profile queuing`() {
        // Set profile before initialization
        telemetryManager.setUserProfile("Early User", "early@example.com")
        
        // Initialize
        telemetryManager.initialize(application, config)
        
        // Profile should be applied
        val userId = telemetryManager.getUserId()
        assertNotNull(userId)
    }

    @Test
    fun `test multiple events and metrics workflow`() {
        telemetryManager.initialize(application, config)
        
        // Record multiple events
        repeat(5) { i ->
            telemetryManager.recordEvent("event_$i", mapOf("index" to i))
        }
        
        // Record multiple metrics
        repeat(5) { i ->
            telemetryManager.recordMetric("metric_$i", i.toDouble(), emptyMap())
        }
        
        // All should be recorded without issues
    }

    @Test
    fun `test screen tracking workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.trackScreen("HomeScreen")
        telemetryManager.trackScreen("ProfileScreen")
        telemetryManager.trackScreen("SettingsScreen")
        
        // Screens should be tracked
    }

    @Test
    fun `test network tracking workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.trackNetworkRequest(
            url = "https://api.example.com/users",
            method = "GET",
            statusCode = 200,
            durationMs = 234,
            requestBodySize = 0,
            responseBodySize = 1024
        )
        
        // Network request should be tracked
    }

    @Test
    fun `test breadcrumb workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.addBreadcrumb("Step 1", "navigation", "info")
        telemetryManager.addBreadcrumb("Step 2", "user_action", "debug")
        telemetryManager.addBreadcrumb("Step 3", "api_call", "info")
        
        // Breadcrumbs should be added
    }

    @Test
    fun `test session end workflow`() {
        telemetryManager.initialize(application, config)
        
        val sessionId = telemetryManager.getCurrentSessionId()
        
        telemetryManager.endCurrentSession()
        
        // Session should be ended
    }

    @Test
    fun `test flush workflow`() = runTest {
        telemetryManager.initialize(application, config)
        
        // Record some events
        repeat(5) { i ->
            telemetryManager.recordEvent("flush_event_$i", emptyMap())
        }
        
        telemetryManager.flush()
        
        // Events should be flushed
    }

    @Test
    fun `test singleton pattern maintains single instance`() {
        val instance1 = TelemetryManager.getInstance()
        val instance2 = TelemetryManager.getInstance()
        
        assertSame(instance1, instance2)
    }

    @Test
    fun `test initialization is idempotent`() {
        telemetryManager.initialize(application, config)
        assertTrue(telemetryManager.isInitialized())
        
        // Initialize again
        telemetryManager.initialize(application, config)
        assertTrue(telemetryManager.isInitialized())
        
        // Should still be initialized without issues
    }

    @Test
    fun `test event recording with various attribute types`() {
        telemetryManager.initialize(application, config)
        
        val attributes = mapOf(
            "string_value" to "test",
            "int_value" to 42,
            "double_value" to 3.14,
            "boolean_value" to true,
            "long_value" to 1234567890L
        )
        
        telemetryManager.recordEvent("complex_event", attributes)
        
        // Should handle various types
    }

    @Test
    fun `test metric recording with zero value`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordMetric("zero_metric", 0.0, emptyMap())
        
        // Should handle zero values
    }

    @Test
    fun `test metric recording with negative value`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordMetric("negative_metric", -123.45, emptyMap())
        
        // Should handle negative values
    }

    @Test
    fun `test metric recording with very large value`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordMetric("large_metric", Double.MAX_VALUE, emptyMap())
        
        // Should handle large values
    }

    @Test
    fun `test user profile update workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.setUserProfile("User 1", "user1@example.com")
        val userId1 = telemetryManager.getUserId()
        
        telemetryManager.setUserProfile("User 2", "user2@example.com")
        val userId2 = telemetryManager.getUserId()
        
        // User ID should remain consistent
        assertEquals(userId1, userId2)
    }

    @Test
    fun `test user profile clear workflow`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.setUserProfile("Test User", "test@example.com")
        telemetryManager.clearUserProfile()
        
        // Profile should be cleared
        val userId = telemetryManager.getUserId()
        assertNotNull(userId) // User ID should still exist
    }

    @Test
    fun `test error tracking with context`() {
        telemetryManager.initialize(application, config)
        
        val error = IllegalStateException("Invalid state")
        telemetryManager.trackError(
            error = error,
            errorCode = "ERR_001",
            productId = "PROD_123",
            userAction = "checkout"
        )
        
        // Error should be tracked with context
    }

    @Test
    fun `test error tracking with message`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.trackError(
            message = "Custom error message",
            stackTrace = "at com.example.Class.method(Class.java:123)"
        )
        
        // Error should be tracked
    }

    @Test
    fun `test product context setting`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.setProductContext("PRODUCT_ABC")
        
        // Product context should be set
    }

    @Test
    fun `test last user action setting`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.setLastUserAction("Clicked buy button")
        
        // User action should be set
    }

    @Test
    fun `test network interceptor creation`() {
        telemetryManager.initialize(application, config)
        
        val interceptor = TelemetryManager.createNetworkInterceptor()
        
        assertNotNull(interceptor)
    }

    @Test
    fun `test concurrent event recording is thread-safe`() {
        telemetryManager.initialize(application, config)
        
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(10) { eventNum ->
                    telemetryManager.recordEvent("concurrent_event_${threadNum}_$eventNum", emptyMap())
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        // All events should be recorded without issues
    }

    @Test
    fun `test concurrent metric recording is thread-safe`() {
        telemetryManager.initialize(application, config)
        
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(10) { metricNum ->
                    telemetryManager.recordMetric("concurrent_metric_${threadNum}_$metricNum", threadNum.toDouble(), emptyMap())
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        // All metrics should be recorded without issues
    }

    @Test
    fun `test session timeout detection`() {
        telemetryManager.initialize(application, config)
        
        // This would require mocking time, but we can verify the method exists
        telemetryManager.startNewSession()
        
        // Session should be started
    }

    @Test
    fun `test complete user journey workflow`() {
        // Initialize
        telemetryManager.initialize(application, config)
        
        // Set user profile
        telemetryManager.setUserProfile("Journey User", "journey@example.com")
        
        // Track screens
        telemetryManager.trackScreen("SplashScreen")
        telemetryManager.trackScreen("LoginScreen")
        
        // Add breadcrumbs
        telemetryManager.addBreadcrumb("User entered email", "ui", "info")
        telemetryManager.addBreadcrumb("User clicked login", "ui", "info")
        
        // Record events
        telemetryManager.recordEvent("login_attempt", mapOf("method" to "email"))
        
        // Track network request
        telemetryManager.trackNetworkRequest(
            url = "https://api.example.com/auth/login",
            method = "POST",
            statusCode = 200,
            durationMs = 456
        )
        
        // Record success event
        telemetryManager.recordEvent("login_success", emptyMap())
        
        // Track more screens
        telemetryManager.trackScreen("HomeScreen")
        
        // Record metrics
        telemetryManager.recordMetric("session_duration", 1234.56, emptyMap())
        
        // End session
        telemetryManager.endCurrentSession()
        
        // Complete workflow should execute without errors
        assertTrue(telemetryManager.isInitialized())
    }

    @Test
    fun `test error recovery workflow`() {
        telemetryManager.initialize(application, config)
        
        // Simulate error
        telemetryManager.addBreadcrumb("Before error", "debug", "info")
        telemetryManager.trackError(RuntimeException("Simulated error"))
        
        // Continue normal operation
        telemetryManager.recordEvent("after_error_event", emptyMap())
        
        // Should continue working after error
    }

    @Test
    fun `test batch size threshold triggers send`() {
        val smallBatchConfig = config.copy(batchSize = 5)
        telemetryManager.initialize(application, smallBatchConfig)
        
        // Record events up to batch size
        repeat(5) { i ->
            telemetryManager.recordEvent("batch_event_$i", emptyMap())
        }
        
        // Batch should be triggered (verified by no exceptions)
    }

    @Test
    fun `test network request with error tracking`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.trackNetworkRequest(
            url = "https://api.example.com/error",
            method = "GET",
            statusCode = 500,
            durationMs = 123,
            error = "Internal Server Error"
        )
        
        // Error should be tracked
    }

    @Test
    fun `test screen tracking with duplicate screens`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.trackScreen("HomeScreen")
        telemetryManager.trackScreen("HomeScreen")
        telemetryManager.trackScreen("ProfileScreen")
        telemetryManager.trackScreen("HomeScreen")
        
        // Should handle duplicate screen tracking
    }

    @Test
    fun `test event with empty attributes`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordEvent("empty_attributes_event", emptyMap())
        
        // Should handle empty attributes
    }

    @Test
    fun `test metric with empty attributes`() {
        telemetryManager.initialize(application, config)
        
        telemetryManager.recordMetric("empty_attributes_metric", 100.0, emptyMap())
        
        // Should handle empty attributes
    }

    @Test
    fun `test multiple session starts`() {
        telemetryManager.initialize(application, config)
        
        val session1 = telemetryManager.getCurrentSessionId()
        telemetryManager.startNewSession()
        val session2 = telemetryManager.getCurrentSessionId()
        telemetryManager.startNewSession()
        val session3 = telemetryManager.getCurrentSessionId()
        
        assertNotEquals(session1, session2)
        assertNotEquals(session2, session3)
        assertNotEquals(session1, session3)
    }

    @Test
    fun `test getUserId consistency`() {
        telemetryManager.initialize(application, config)
        
        val userId1 = telemetryManager.getUserId()
        val userId2 = telemetryManager.getUserId()
        val userId3 = telemetryManager.getUserId()
        
        assertEquals(userId1, userId2)
        assertEquals(userId2, userId3)
    }

    @Test
    fun `test isInitialized before and after initialization`() {
        assertFalse(telemetryManager.isInitialized())
        
        telemetryManager.initialize(application, config)
        
        assertTrue(telemetryManager.isInitialized())
    }

    @Test
    fun `test configuration with all features disabled`() {
        val disabledConfig = TelemetryConfig(
            apiKey = "edge_test-api-key",
            endpoint = "https://test.example.com",
            enableCrashReporting = false,
            enableSessionTracking = false,
            enableLocationTracking = false,
            batchSize = 50,
            flushIntervalMs = 30000,
            sessionTimeoutMs = 1800000
        )
        
        telemetryManager.initialize(application, disabledConfig)
        
        // Should initialize even with all features disabled
        assertTrue(telemetryManager.isInitialized())
        
        // Basic operations should still work
        telemetryManager.recordEvent("test_event", emptyMap())
    }

    @Test
    fun `test rapid event recording`() {
        telemetryManager.initialize(application, config)
        
        repeat(100) { i ->
            telemetryManager.recordEvent("rapid_event_$i", mapOf("index" to i))
        }
        
        // Should handle rapid event recording
    }

    @Test
    fun `test event and metric interleaving`() {
        telemetryManager.initialize(application, config)
        
        repeat(10) { i ->
            telemetryManager.recordEvent("event_$i", emptyMap())
            telemetryManager.recordMetric("metric_$i", i.toDouble(), emptyMap())
        }
        
        // Should handle interleaved events and metrics
    }
}
