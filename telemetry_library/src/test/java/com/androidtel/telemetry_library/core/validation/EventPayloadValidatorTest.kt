package com.androidtel.telemetry_library.core.validation

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Event Payload Validation Tests
 * Comprehensive test suite for validating event payloads match backend requirements
 */
class EventPayloadValidatorTest {
    
    private val validTimestamp = "2024-03-23T12:34:56.789Z"
    
    @Test
    fun `validateHttpRequestEvent - valid payload passes`() {
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET",
            "http.status_code" to 200,
            "http.duration_ms" to 150L,
            "http.timestamp" to validTimestamp,
            "http.success" to true
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertTrue("Valid HTTP request should pass validation", result.isValid)
    }
    
    @Test
    fun `validateHttpRequestEvent - wrong event name fails`() {
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET",
            "http.status_code" to 200,
            "http.duration_ms" to 150L,
            "http.timestamp" to validTimestamp,
            "http.success" to true
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "network.request",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Wrong event name should fail", result.isValid)
        assertTrue(
            "Error should mention event name",
            (result as EventValidationResult.Invalid).errors.any { it.contains("http.request") }
        )
    }
    
    @Test
    fun `validateHttpRequestEvent - missing required attributes fails`() {
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET"
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Missing attributes should fail", result.isValid)
        val errors = (result as EventValidationResult.Invalid).errors
        assertTrue("Should report missing http.status_code", errors.any { it.contains("http.status_code") })
        assertTrue("Should report missing http.duration_ms", errors.any { it.contains("http.duration_ms") })
        assertTrue("Should report missing http.success", errors.any { it.contains("http.success") })
    }
    
    @Test
    fun `validateHttpRequestEvent - invalid HTTP method fails`() {
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "INVALID",
            "http.status_code" to 200,
            "http.duration_ms" to 150L,
            "http.timestamp" to validTimestamp,
            "http.success" to true
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Invalid HTTP method should fail", result.isValid)
        assertTrue(
            "Error should mention valid methods",
            (result as EventValidationResult.Invalid).errors.any { it.contains("GET") }
        )
    }
    
    @Test
    fun `validateHttpRequestEvent - invalid status code fails`() {
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET",
            "http.status_code" to 999,
            "http.duration_ms" to 150L,
            "http.timestamp" to validTimestamp,
            "http.success" to true
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Invalid status code should fail", result.isValid)
        assertTrue(
            "Error should mention status code range",
            (result as EventValidationResult.Invalid).errors.any { it.contains("100-599") }
        )
    }
    
    @Test
    fun `validateHttpRequestEvent - wrong attribute types fail`() {
        val attributes = mapOf(
            "http.url" to "https://api.example.com/users",
            "http.method" to "GET",
            "http.status_code" to "200",  // Should be Int
            "http.duration_ms" to 150,     // Should be Long
            "http.timestamp" to validTimestamp,
            "http.success" to "true"       // Should be Boolean
        )
        
        val result = EventPayloadValidator.validateHttpRequestEvent(
            eventName = "http.request",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Wrong types should fail", result.isValid)
    }
    
    @Test
    fun `validateSessionFinalizedEvent - valid payload passes`() {
        val attributes = mapOf(
            "session.id" to "550e8400-e29b-41d4-a716-446655440000",
            "session.start_time" to validTimestamp,
            "session.duration_ms" to 45000L,
            "session.event_count" to 25,
            "session.metric_count" to 10,
            "session.screen_count" to 5,
            "session.visited_screens" to "HomeScreen,ProfileScreen,SettingsScreen",
            "session.is_first_session" to false,
            "session.total_sessions" to 3,
            "network.type" to "wifi"
        )
        
        val result = EventPayloadValidator.validateSessionFinalizedEvent(
            eventName = "session.finalized",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertTrue("Valid session finalized event should pass", result.isValid)
    }
    
    @Test
    fun `validateSessionFinalizedEvent - wrong event name fails`() {
        val attributes = mapOf(
            "session.id" to "550e8400-e29b-41d4-a716-446655440000",
            "session.start_time" to validTimestamp,
            "session.duration_ms" to 45000L,
            "session.event_count" to 25,
            "session.metric_count" to 10,
            "session.screen_count" to 5,
            "session.visited_screens" to "HomeScreen",
            "session.is_first_session" to false,
            "session.total_sessions" to 3,
            "network.type" to "wifi"
        )
        
        val result = EventPayloadValidator.validateSessionFinalizedEvent(
            eventName = "session_end",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Wrong event name should fail", result.isValid)
        assertTrue(
            "Error should mention correct event name",
            (result as EventValidationResult.Invalid).errors.any { it.contains("session.finalized") }
        )
    }
    
    @Test
    fun `validateSessionFinalizedEvent - invalid UUID fails`() {
        val attributes = mapOf(
            "session.id" to "not-a-uuid",
            "session.start_time" to validTimestamp,
            "session.duration_ms" to 45000L,
            "session.event_count" to 25,
            "session.metric_count" to 10,
            "session.screen_count" to 5,
            "session.visited_screens" to "HomeScreen",
            "session.is_first_session" to false,
            "session.total_sessions" to 3,
            "network.type" to "wifi"
        )
        
        val result = EventPayloadValidator.validateSessionFinalizedEvent(
            eventName = "session.finalized",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Invalid UUID should fail", result.isValid)
        assertTrue(
            "Error should mention UUID format",
            (result as EventValidationResult.Invalid).errors.any { it.contains("UUID") }
        )
    }
    
    @Test
    fun `validateSessionFinalizedEvent - negative counts fail`() {
        val attributes = mapOf(
            "session.id" to "550e8400-e29b-41d4-a716-446655440000",
            "session.start_time" to validTimestamp,
            "session.duration_ms" to 45000L,
            "session.event_count" to -5,
            "session.metric_count" to 10,
            "session.screen_count" to 5,
            "session.visited_screens" to "HomeScreen",
            "session.is_first_session" to false,
            "session.total_sessions" to 3,
            "network.type" to "wifi"
        )
        
        val result = EventPayloadValidator.validateSessionFinalizedEvent(
            eventName = "session.finalized",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Negative count should fail", result.isValid)
        assertTrue(
            "Error should mention non-negative",
            (result as EventValidationResult.Invalid).errors.any { it.contains("non-negative") }
        )
    }
    
    @Test
    fun `validateNavigationEvent - valid payload passes`() {
        val attributes = mapOf(
            "navigation.from_screen" to "HomeScreen",
            "navigation.to_screen" to "ProfileScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to validTimestamp
        )
        
        val result = EventPayloadValidator.validateNavigationEvent(
            eventName = "navigation",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertTrue("Valid navigation event should pass", result.isValid)
    }
    
    @Test
    fun `validateNavigationEvent - null from_screen on app launch passes`() {
        val attributes = mapOf(
            "navigation.from_screen" to null,
            "navigation.to_screen" to "HomeScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "app_launch",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to validTimestamp
        )
        
        val result = EventPayloadValidator.validateNavigationEvent(
            eventName = "navigation",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertTrue("Null from_screen should be allowed", result.isValid)
    }
    
    @Test
    fun `validateNavigationEvent - invalid method fails`() {
        val attributes = mapOf(
            "navigation.from_screen" to "HomeScreen",
            "navigation.to_screen" to "ProfileScreen",
            "navigation.method" to "resumed",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to validTimestamp
        )
        
        val result = EventPayloadValidator.validateNavigationEvent(
            eventName = "navigation",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Invalid navigation method should fail", result.isValid)
        assertTrue(
            "Error should mention valid methods",
            (result as EventValidationResult.Invalid).errors.any { it.contains("push") }
        )
    }
    
    @Test
    fun `validateScreenDurationEvent - valid payload passes`() {
        val attributes = mapOf(
            "screen.name" to "HomeScreen",
            "screen.duration_ms" to 5000L,
            "screen.exit_method" to "navigation",
            "screen.timestamp" to validTimestamp
        )
        
        val result = EventPayloadValidator.validateScreenDurationEvent(
            eventName = "performance.screen_duration",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertTrue("Valid screen duration event should pass", result.isValid)
    }
    
    @Test
    fun `validateScreenDurationEvent - invalid exit method fails`() {
        val attributes = mapOf(
            "screen.name" to "HomeScreen",
            "screen.duration_ms" to 5000L,
            "screen.exit_method" to "invalid",
            "screen.timestamp" to validTimestamp
        )
        
        val result = EventPayloadValidator.validateScreenDurationEvent(
            eventName = "performance.screen_duration",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Invalid exit method should fail", result.isValid)
        assertTrue(
            "Error should mention valid exit methods",
            (result as EventValidationResult.Invalid).errors.any { it.contains("navigation") }
        )
    }
    
    @Test
    fun `validateScreenDurationEvent - negative duration fails`() {
        val attributes = mapOf(
            "screen.name" to "HomeScreen",
            "screen.duration_ms" to -100L,
            "screen.exit_method" to "navigation",
            "screen.timestamp" to validTimestamp
        )
        
        val result = EventPayloadValidator.validateScreenDurationEvent(
            eventName = "performance.screen_duration",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Negative duration should fail", result.isValid)
    }
    
    @Test
    fun `validateCrashEvent - valid payload passes`() {
        val attributes = mapOf(
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
        
        val result = EventPayloadValidator.validateCrashEvent(
            eventName = "app.crash",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertTrue("Valid crash event should pass", result.isValid)
    }
    
    @Test
    fun `validateCrashEvent - field length limits enforced`() {
        val attributes = mapOf(
            "error.message" to "x".repeat(1001),  // Exceeds 1000 char limit
            "error.stack_trace" to "stack trace",
            "error.exception_type" to "Exception",
            "error.context" to "context",
            "error.cause" to "cause",
            "error.severity_level" to "critical",
            "error.is_fatal" to true,
            "error.breadcrumbs" to "[]",
            "error.breadcrumb_count" to 0
        )
        
        val result = EventPayloadValidator.validateCrashEvent(
            eventName = "app.crash",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Exceeding field length should fail", result.isValid)
        assertTrue(
            "Error should mention max length",
            (result as EventValidationResult.Invalid).errors.any { it.contains("1000") }
        )
    }
    
    @Test
    fun `validateCrashEvent - invalid severity level fails`() {
        val attributes = mapOf(
            "error.message" to "Error message",
            "error.stack_trace" to "stack trace",
            "error.exception_type" to "Exception",
            "error.context" to "context",
            "error.cause" to "cause",
            "error.severity_level" to "invalid",
            "error.is_fatal" to true,
            "error.breadcrumbs" to "[]",
            "error.breadcrumb_count" to 0
        )
        
        val result = EventPayloadValidator.validateCrashEvent(
            eventName = "app.crash",
            attributes = attributes,
            timestamp = validTimestamp
        )
        
        assertFalse("Invalid severity level should fail", result.isValid)
        assertTrue(
            "Error should mention valid severity levels",
            (result as EventValidationResult.Invalid).errors.any { it.contains("critical") }
        )
    }
    
    @Test
    fun `validateTimestamp - ISO 8601 format validation`() {
        val validTimestamps = listOf(
            "2024-03-23T12:34:56.789Z",
            "2024-01-01T00:00:00.000Z",
            "2024-12-31T23:59:59.999Z",
            "2024-06-15T08:30:45.123Z"
        )
        
        validTimestamps.forEach { timestamp ->
            val attributes = mapOf(
                "http.url" to "https://api.example.com",
                "http.method" to "GET",
                "http.status_code" to 200,
                "http.duration_ms" to 100L,
                "http.timestamp" to timestamp,
                "http.success" to true
            )
            
            val result = EventPayloadValidator.validateHttpRequestEvent(
                eventName = "http.request",
                attributes = attributes,
                timestamp = timestamp
            )
            
            assertTrue("Timestamp $timestamp should be valid", result.isValid)
        }
    }
    
    @Test
    fun `validateTimestamp - invalid formats fail`() {
        val invalidTimestamps = listOf(
            "1711195496789",              // Unix timestamp
            "2024-03-23 12:34:56",        // Missing T separator
            "2024-03-23T12:34:56",        // Missing milliseconds and Z
            "03/23/2024 12:34:56",        // US date format
            "2024-03-23T12:34:56.789"     // Missing Z
        )
        
        invalidTimestamps.forEach { timestamp ->
            val attributes = mapOf(
                "http.url" to "https://api.example.com",
                "http.method" to "GET",
                "http.status_code" to 200,
                "http.duration_ms" to 100L,
                "http.timestamp" to timestamp,
                "http.success" to true
            )
            
            val result = EventPayloadValidator.validateHttpRequestEvent(
                eventName = "http.request",
                attributes = attributes,
                timestamp = timestamp
            )
            
            assertFalse("Timestamp $timestamp should be invalid", result.isValid)
        }
    }
    
    @Test
    fun `getErrorReport - provides readable output`() {
        val validResult = EventValidationResult.Valid
        assertEquals("✓ Event payload is valid", validResult.getErrorReport())
        
        val invalidResult = EventValidationResult.Invalid(
            listOf("Missing attribute 'http.url'", "Invalid method 'INVALID'")
        )
        val report = invalidResult.getErrorReport()
        assertTrue("Report should mention validation failed", report.contains("validation failed"))
        assertTrue("Report should list errors", report.contains("http.url"))
        assertTrue("Report should list errors", report.contains("INVALID"))
    }
}
