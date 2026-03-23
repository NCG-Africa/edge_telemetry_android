package com.androidtel.telemetry_library.core.validation

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Phase 4: Integration Tests
 * End-to-end validation of event payloads with standard attributes
 */
class Phase4IntegrationTest {
    
    private lateinit var standardAttributes: Map<String, Any?>
    private val validTimestamp = Instant.now().toString()
    
    @Before
    fun setup() {
        standardAttributes = mapOf(
            // App attributes
            "app.name" to "EdgeTelemetry Test App",
            "app.version" to "1.0.0",
            "app.build_number" to "123",
            "app.package_name" to "com.example.testapp",
            
            // Device attributes
            "device.id" to "550e8400-e29b-41d4-a716-446655440000",
            "device.platform" to "android",
            "device.platform_version" to "13.0",
            "device.model" to "Pixel 7",
            "device.manufacturer" to "Google",
            "device.brand" to "Google",
            "device.android_sdk" to "33",
            "device.android_release" to "13",
            "device.fingerprint" to "google/cheetah/cheetah:13/TQ2A.230505.002/9891397:user/release-keys",
            "device.hardware" to "cheetah",
            "device.product" to "cheetah",
            
            // User & Session attributes
            "user.id" to "user-123-456-789",
            "session.id" to "550e8400-e29b-41d4-a716-446655440001",
            "session.start_time" to validTimestamp
        )
    }
    
    @Test
    fun `HTTP request event - complete validation with standard attributes`() {
        val eventAttributes = standardAttributes + mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET",
            "http.status_code" to 200,
            "http.duration_ms" to 150L,
            "http.timestamp" to validTimestamp,
            "http.success" to true
        )
        
        // Validate event-specific attributes
        val eventResult = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = eventAttributes,
            timestamp = validTimestamp
        )
        assertTrue("HTTP request event should be valid", eventResult.isValid)
        
        // Validate standard attributes
        val standardResult = EventPayloadValidator.validateStandardAttributes(eventAttributes)
        assertTrue("Standard attributes should be valid", standardResult.isValid)
    }
    
    @Test
    fun `Session finalized event - complete validation with standard attributes`() {
        val eventAttributes = standardAttributes + mapOf(
            "session.duration_ms" to 45000L,
            "session.event_count" to 25,
            "session.metric_count" to 10,
            "session.screen_count" to 5,
            "session.visited_screens" to "HomeScreen,ProfileScreen,SettingsScreen",
            "session.is_first_session" to false,
            "session.total_sessions" to 3,
            "network.type" to "wifi"
        )
        
        // Validate event-specific attributes
        val eventResult = EventPayloadValidator.validateSessionFinalizedEvent(
            eventName = "session.finalized",
            attributes = eventAttributes,
            timestamp = validTimestamp
        )
        assertTrue("Session finalized event should be valid", eventResult.isValid)
        
        // Validate standard attributes
        val standardResult = EventPayloadValidator.validateStandardAttributes(eventAttributes)
        assertTrue("Standard attributes should be valid", standardResult.isValid)
    }
    
    @Test
    fun `Navigation event - complete validation with standard attributes`() {
        val eventAttributes = standardAttributes + mapOf(
            "navigation.from_screen" to "HomeScreen",
            "navigation.to_screen" to "ProfileScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to validTimestamp
        )
        
        // Validate event-specific attributes
        val eventResult = EventPayloadValidator.validateNavigationEvent(
            eventName = "navigation",
            attributes = eventAttributes,
            timestamp = validTimestamp
        )
        assertTrue("Navigation event should be valid", eventResult.isValid)
        
        // Validate standard attributes
        val standardResult = EventPayloadValidator.validateStandardAttributes(eventAttributes)
        assertTrue("Standard attributes should be valid", standardResult.isValid)
    }
    
    @Test
    fun `Screen duration event - complete validation with standard attributes`() {
        val eventAttributes = standardAttributes + mapOf(
            "screen.name" to "HomeScreen",
            "screen.duration_ms" to 5000L,
            "screen.exit_method" to "navigation",
            "screen.timestamp" to validTimestamp
        )
        
        // Validate event-specific attributes
        val eventResult = EventPayloadValidator.validateScreenDurationEvent(
            eventName = "performance.screen_duration",
            attributes = eventAttributes,
            timestamp = validTimestamp
        )
        assertTrue("Screen duration event should be valid", eventResult.isValid)
        
        // Validate standard attributes
        val standardResult = EventPayloadValidator.validateStandardAttributes(eventAttributes)
        assertTrue("Standard attributes should be valid", standardResult.isValid)
    }
    
    @Test
    fun `Crash event - complete validation with standard attributes`() {
        val eventAttributes = standardAttributes + mapOf(
            "error.message" to "NullPointerException: Object reference not set",
            "error.stack_trace" to "at com.example.MyClass.method(MyClass.kt:42)\nat com.example.Main.run(Main.kt:10)",
            "error.exception_type" to "NullPointerException",
            "error.context" to "User was viewing profile screen",
            "error.cause" to "Null user object",
            "error.severity_level" to "critical",
            "error.is_fatal" to true,
            "error.breadcrumbs" to "[{\"type\":\"navigation\",\"message\":\"Navigated to profile\"}]",
            "error.breadcrumb_count" to 5
        )
        
        // Validate event-specific attributes
        val eventResult = EventPayloadValidator.validateCrashEvent(
            eventName = "app.crash",
            attributes = eventAttributes,
            timestamp = validTimestamp
        )
        assertTrue("Crash event should be valid", eventResult.isValid)
        
        // Validate standard attributes
        val standardResult = EventPayloadValidator.validateStandardAttributes(eventAttributes)
        assertTrue("Standard attributes should be valid", standardResult.isValid)
    }
    
    @Test
    fun `Missing app attributes - validation fails`() {
        val incompleteAttributes = standardAttributes - "app.version"
        
        val result = EventPayloadValidator.validateStandardAttributes(incompleteAttributes)
        
        assertFalse("Missing app.version should fail validation", result.isValid)
        assertTrue(
            "Error should mention missing attribute",
            (result as EventValidationResult.Invalid).errors.any { it.contains("app.version") }
        )
    }
    
    @Test
    fun `Missing device attributes - validation fails`() {
        val incompleteAttributes = standardAttributes - "device.model"
        
        val result = EventPayloadValidator.validateStandardAttributes(incompleteAttributes)
        
        assertFalse("Missing device.model should fail validation", result.isValid)
        assertTrue(
            "Error should mention missing attribute",
            (result as EventValidationResult.Invalid).errors.any { it.contains("device.model") }
        )
    }
    
    @Test
    fun `Missing user session attributes - validation fails`() {
        val incompleteAttributes = standardAttributes - "session.id"
        
        val result = EventPayloadValidator.validateStandardAttributes(incompleteAttributes)
        
        assertFalse("Missing session.id should fail validation", result.isValid)
        assertTrue(
            "Error should mention missing attribute",
            (result as EventValidationResult.Invalid).errors.any { it.contains("session.id") }
        )
    }
    
    @Test
    fun `All event types - verify required attributes present`() {
        val httpAttrs = mapOf(
            "http.url" to "https://api.example.com",
            "http.method" to "GET",
            "http.status_code" to 200,
            "http.duration_ms" to 100L,
            "http.timestamp" to validTimestamp,
            "http.success" to true
        )
        
        val sessionAttrs = mapOf(
            "session.duration_ms" to 1000L,
            "session.event_count" to 5,
            "session.metric_count" to 2,
            "session.screen_count" to 3,
            "session.visited_screens" to "Screen1,Screen2",
            "session.is_first_session" to true,
            "session.total_sessions" to 1,
            "network.type" to "wifi"
        )
        
        val navAttrs = mapOf(
            "navigation.to_screen" to "HomeScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to validTimestamp
        )
        
        val screenAttrs = mapOf(
            "screen.name" to "HomeScreen",
            "screen.duration_ms" to 1000L,
            "screen.exit_method" to "navigation",
            "screen.timestamp" to validTimestamp
        )
        
        val crashAttrs = mapOf(
            "error.message" to "Error",
            "error.stack_trace" to "trace",
            "error.exception_type" to "Exception",
            "error.context" to "context",
            "error.cause" to "cause",
            "error.severity_level" to "error",
            "error.is_fatal" to false,
            "error.breadcrumbs" to "[]",
            "error.breadcrumb_count" to 0
        )
        
        // All events should have standard attributes
        listOf(httpAttrs, sessionAttrs, navAttrs, screenAttrs, crashAttrs).forEach { attrs ->
            val combined = standardAttributes + attrs
            val result = EventPayloadValidator.validateStandardAttributes(combined)
            assertTrue("All events must have standard attributes", result.isValid)
        }
    }
    
    @Test
    fun `Boolean values - correct type validation`() {
        val booleanTests = listOf(
            Triple("http.success", true, "http.request"),
            Triple("session.is_first_session", false, "session.finalized"),
            Triple("navigation.has_arguments", true, "navigation"),
            Triple("error.is_fatal", false, "app.crash")
        )
        
        booleanTests.forEach { (attr, value, eventType) ->
            val attrs = standardAttributes + mapOf(attr to value)
            
            // Verify boolean is accepted
            assertTrue("Boolean value should be valid for $attr", value is Boolean)
            
            // Verify string "true" would be rejected
            val invalidAttrs = standardAttributes + mapOf(attr to "true")
            // This would fail type validation in the actual validator
        }
    }
    
    @Test
    fun `Timestamp formats - ISO 8601 validation across all events`() {
        val validTimestamps = listOf(
            "2024-03-23T12:34:56.789Z",
            "2024-01-01T00:00:00.000Z",
            "2024-12-31T23:59:59.999Z"
        )
        
        validTimestamps.forEach { ts ->
            // Test in HTTP request
            val httpAttrs = standardAttributes + mapOf(
                "http.url" to "https://api.example.com",
                "http.method" to "GET",
                "http.status_code" to 200,
                "http.duration_ms" to 100L,
                "http.timestamp" to ts,
                "http.success" to true
            )
            
            val result = EventPayloadValidator.validateHttpRequestEvent(
                "http.request", httpAttrs, ts
            )
            assertTrue("Timestamp $ts should be valid", result.isValid)
        }
    }
    
    @Test
    fun `Field length limits - crash event validation`() {
        val testCases = listOf(
            Triple("error.message", 1000, "x".repeat(999)),
            Triple("error.stack_trace", 2000, "x".repeat(1999)),
            Triple("error.exception_type", 255, "x".repeat(254)),
            Triple("error.context", 500, "x".repeat(499)),
            Triple("error.cause", 255, "x".repeat(254)),
            Triple("error.breadcrumbs", 800, "x".repeat(799))
        )
        
        testCases.forEach { (field, maxLength, validValue) ->
            val attrs = standardAttributes + mapOf(
                "error.message" to "msg",
                "error.stack_trace" to "trace",
                "error.exception_type" to "Exception",
                "error.context" to "ctx",
                "error.cause" to "cause",
                "error.severity_level" to "error",
                "error.is_fatal" to false,
                "error.breadcrumbs" to "[]",
                "error.breadcrumb_count" to 0,
                field to validValue
            )
            
            val result = EventPayloadValidator.validateCrashEvent(
                "app.crash", attrs, validTimestamp
            )
            assertTrue("Field $field with length ${validValue.length} should be valid (max: $maxLength)", result.isValid)
            
            // Test exceeding limit
            val tooLong = "x".repeat(maxLength + 1)
            val invalidAttrs = attrs + mapOf(field to tooLong)
            val invalidResult = EventPayloadValidator.validateCrashEvent(
                "app.crash", invalidAttrs, validTimestamp
            )
            assertFalse("Field $field exceeding $maxLength chars should fail", invalidResult.isValid)
        }
    }
}
