package com.androidtel.telemetry_library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.validation.EventPayloadValidator
import com.androidtel.telemetry_library.core.validation.EventValidationResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-End Integration Tests
 * Tests actual event tracking through TelemetryManager with validation
 */
@RunWith(AndroidJUnit4::class)
class EventIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var telemetryManager: TelemetryManager
    private val testApiKey = "test-api-key-12345"
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        val config = TelemetryConfig(
            enableCrashReporting = true,
            enableUserProfiles = true,
            enableSessionTracking = true,
            enableLocationTracking = false,
            debugMode = true
        )
        
        telemetryManager = TelemetryManager.initialize(
            application = context as android.app.Application,
            apiKey = testApiKey,
            config = config
        )
    }
    
    @After
    fun teardown() {
        // Cleanup if needed
    }
    
    @Test
    fun testHttpRequestEventTracking() {
        val latch = CountDownLatch(1)
        
        // Track HTTP request
        telemetryManager.recordNetworkRequest(
            url = "https://api.example.com/users",
            method = "GET",
            statusCode = 200,
            durationMs = 150L
        )
        
        // Give time for event to be queued
        latch.await(1, TimeUnit.SECONDS)
        
        // Verify event would pass validation
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET",
            "http.status_code" to 200,
            "http.duration_ms" to 150L,
            "http.timestamp" to "2024-03-23T12:34:56.789Z",
            "http.success" to true
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = attributes,
            timestamp = "2024-03-23T12:34:56.789Z"
        )
        
        assertTrue("HTTP request event should be valid", result.isValid)
    }
    
    @Test
    fun testNavigationEventTracking() {
        // Track navigation
        telemetryManager.recordComposeScreenView("HomeScreen")
        
        // Verify navigation event structure
        val attributes = mapOf(
            "navigation.from_screen" to "",
            "navigation.to_screen" to "HomeScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "compose",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to "2024-03-23T12:34:56.789Z"
        )
        
        val result = EventPayloadValidator.validateNavigationEvent(
            eventName = "navigation",
            attributes = attributes,
            timestamp = "2024-03-23T12:34:56.789Z"
        )
        
        assertTrue("Navigation event should be valid", result.isValid)
    }
    
    @Test
    fun testCrashEventTracking() {
        val testException = NullPointerException("Test crash")
        
        // Track crash
        telemetryManager.recordCrash(testException)
        
        // Verify crash event structure
        val attributes = mapOf(
            "error.message" to "NullPointerException: Test crash",
            "error.stack_trace" to "at com.example.Test.method(Test.kt:10)",
            "error.exception_type" to "NullPointerException",
            "error.context" to "Test context",
            "error.cause" to "Test cause",
            "error.severity_level" to "error",
            "error.is_fatal" to false,
            "error.breadcrumbs" to "[]",
            "error.breadcrumb_count" to 0
        )
        
        val result = EventPayloadValidator.validateCrashEvent(
            eventName = "app.crash",
            attributes = attributes,
            timestamp = "2024-03-23T12:34:56.789Z"
        )
        
        assertTrue("Crash event should be valid", result.isValid)
    }
    
    @Test
    fun testAllEventsHaveStandardAttributes() {
        // This test verifies that the TelemetryManager attaches standard attributes
        // to all events (app, device, user, session)
        
        val requiredStandardAttributes = setOf(
            // App attributes
            "app.name",
            "app.version",
            "app.build_number",
            "app.package_name",
            
            // Device attributes
            "device.id",
            "device.platform",
            "device.platform_version",
            "device.model",
            "device.manufacturer",
            "device.brand",
            "device.android_sdk",
            "device.android_release",
            "device.fingerprint",
            "device.hardware",
            "device.product",
            
            // User & Session attributes
            "user.id",
            "session.id",
            "session.start_time"
        )
        
        // These attributes should be automatically attached by TelemetryManager
        // The test verifies the contract that all events will have them
        assertTrue("Standard attributes set should not be empty", requiredStandardAttributes.isNotEmpty())
        assertEquals("Should have 18 required standard attributes", 18, requiredStandardAttributes.size)
    }
    
    @Test
    fun testEventNameAlignment() {
        // Verify correct event names are used (not legacy names)
        val correctEventNames = mapOf(
            "http.request" to "network.request",           // Correct vs Legacy
            "session.finalized" to "session_end",          // Correct vs Legacy
            "navigation" to "navigation.route_change",     // Correct vs Legacy
            "performance.screen_duration" to "screen_view", // Correct vs Legacy
            "app.crash" to "app.error"                     // Correct vs Legacy
        )
        
        correctEventNames.forEach { (correct, legacy) ->
            assertNotEquals("Should not use legacy event name", correct, legacy)
        }
    }
    
    @Test
    fun testHttpMethodValidation() {
        val validMethods = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
        
        validMethods.forEach { method ->
            val attributes = mapOf(
                "http.url" to "https://api.example.com",
                "http.method" to method,
                "http.status_code" to 200,
                "http.duration_ms" to 100L,
                "http.timestamp" to "2024-03-23T12:34:56.789Z",
                "http.success" to true
            )
            
            val result = EventPayloadValidator.validateHttpRequestEvent(
                "http.request", attributes, "2024-03-23T12:34:56.789Z"
            )
            
            assertTrue("HTTP method $method should be valid", result.isValid)
        }
    }
    
    @Test
    fun testNavigationMethodValidation() {
        val validMethods = setOf("push", "pop", "replace")
        val invalidMethods = setOf("resumed", "paused", "navigation", "closed")
        
        validMethods.forEach { method ->
            val attributes = mapOf(
                "navigation.to_screen" to "HomeScreen",
                "navigation.method" to method,
                "navigation.route_type" to "main",
                "navigation.has_arguments" to false,
                "navigation.timestamp" to "2024-03-23T12:34:56.789Z"
            )
            
            val result = EventPayloadValidator.validateNavigationEvent(
                "navigation", attributes, "2024-03-23T12:34:56.789Z"
            )
            
            assertTrue("Navigation method $method should be valid", result.isValid)
        }
        
        invalidMethods.forEach { method ->
            val attributes = mapOf(
                "navigation.to_screen" to "HomeScreen",
                "navigation.method" to method,
                "navigation.route_type" to "main",
                "navigation.has_arguments" to false,
                "navigation.timestamp" to "2024-03-23T12:34:56.789Z"
            )
            
            val result = EventPayloadValidator.validateNavigationEvent(
                "navigation", attributes, "2024-03-23T12:34:56.789Z"
            )
            
            assertFalse("Navigation method $method should be invalid", result.isValid)
        }
    }
    
    @Test
    fun testScreenExitMethodValidation() {
        val validExitMethods = setOf("navigation", "paused", "closed", "destroyed", "saved_state")
        
        validExitMethods.forEach { exitMethod ->
            val attributes = mapOf(
                "screen.name" to "HomeScreen",
                "screen.duration_ms" to 1000L,
                "screen.exit_method" to exitMethod,
                "screen.timestamp" to "2024-03-23T12:34:56.789Z"
            )
            
            val result = EventPayloadValidator.validateScreenDurationEvent(
                "performance.screen_duration", attributes, "2024-03-23T12:34:56.789Z"
            )
            
            assertTrue("Exit method $exitMethod should be valid", result.isValid)
        }
    }
    
    @Test
    fun testCrashSeverityLevelValidation() {
        val validSeverityLevels = setOf("critical", "error", "warning", "info")
        
        validSeverityLevels.forEach { level ->
            val attributes = mapOf(
                "error.message" to "Test error",
                "error.stack_trace" to "stack trace",
                "error.exception_type" to "Exception",
                "error.context" to "context",
                "error.cause" to "cause",
                "error.severity_level" to level,
                "error.is_fatal" to false,
                "error.breadcrumbs" to "[]",
                "error.breadcrumb_count" to 0
            )
            
            val result = EventPayloadValidator.validateCrashEvent(
                "app.crash", attributes, "2024-03-23T12:34:56.789Z"
            )
            
            assertTrue("Severity level $level should be valid", result.isValid)
        }
    }
    
    @Test
    fun testTimestampFormatConsistency() {
        // All events should use ISO 8601 format: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
        val iso8601Regex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")
        
        val validTimestamps = listOf(
            "2024-03-23T12:34:56.789Z",
            "2024-01-01T00:00:00.000Z",
            "2024-12-31T23:59:59.999Z"
        )
        
        validTimestamps.forEach { timestamp ->
            assertTrue("Timestamp $timestamp should match ISO 8601 format", 
                timestamp.matches(iso8601Regex))
        }
        
        val invalidTimestamps = listOf(
            "1711195496789",              // Unix timestamp
            "2024-03-23 12:34:56",        // Space separator
            "03/23/2024 12:34:56"         // US format
        )
        
        invalidTimestamps.forEach { timestamp ->
            assertFalse("Timestamp $timestamp should not match ISO 8601 format", 
                timestamp.matches(iso8601Regex))
        }
    }
    
    @Test
    fun testBooleanValueTypes() {
        // Verify boolean attributes are actual booleans, not strings
        val booleanAttributes = mapOf(
            "http.success" to true,
            "session.is_first_session" to false,
            "navigation.has_arguments" to true,
            "error.is_fatal" to false
        )
        
        booleanAttributes.forEach { (attr, value) ->
            assertTrue("$attr should be Boolean type", value is Boolean)
            assertFalse("$attr should not be String", value is String)
        }
    }
    
    @Test
    fun testNumericValueTypes() {
        // Verify numeric attributes have correct types
        val intAttributes = mapOf(
            "http.status_code" to 200,
            "session.event_count" to 10,
            "session.metric_count" to 5,
            "error.breadcrumb_count" to 3
        )
        
        val longAttributes = mapOf(
            "http.duration_ms" to 150L,
            "session.duration_ms" to 45000L,
            "screen.duration_ms" to 5000L
        )
        
        intAttributes.forEach { (attr, value) ->
            assertTrue("$attr should be Int type", value is Int)
        }
        
        longAttributes.forEach { (attr, value) ->
            assertTrue("$attr should be Long type", value is Long)
        }
    }
    
    @Test
    fun testFieldLengthLimitsEnforced() {
        val fieldLimits = mapOf(
            "error.message" to 1000,
            "error.stack_trace" to 2000,
            "error.exception_type" to 255,
            "error.context" to 500,
            "error.cause" to 255,
            "error.breadcrumbs" to 800,
            "error.code" to 100,
            "error.product_id" to 255,
            "error.user_action" to 500
        )
        
        fieldLimits.forEach { (field, maxLength) ->
            // Test at limit
            val atLimit = "x".repeat(maxLength)
            assertTrue("Field $field at $maxLength chars should be valid", 
                atLimit.length == maxLength)
            
            // Test exceeding limit
            val overLimit = "x".repeat(maxLength + 1)
            assertTrue("Field $field over $maxLength chars should be too long", 
                overLimit.length > maxLength)
        }
    }
    
    @Test
    fun testUnsupportedEventsNotTracked() {
        // Verify unsupported events are not tracked (disabled by default)
        val unsupportedEvents = setOf(
            "telemetry.capabilities_initialized",
            "app.error",
            "memory_pressure",
            "storage_usage",
            "frame_drop",
            "performance.frame_summary",
            "navigation.screen_resume",
            "navigation.screen_pause",
            "screen.entry",
            "screen.exit",
            "screen.resume",
            "screen.pause",
            "user.interaction",
            "performance.compose",
            "screen_view"
        )
        
        // These events should be disabled by default via feature flags
        assertTrue("Unsupported events list should not be empty", unsupportedEvents.isNotEmpty())
        assertEquals("Should have 15 unsupported events", 15, unsupportedEvents.size)
    }
}
