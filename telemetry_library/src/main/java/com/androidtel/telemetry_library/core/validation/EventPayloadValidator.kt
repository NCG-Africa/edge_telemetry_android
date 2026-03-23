package com.androidtel.telemetry_library.core.validation

import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Phase 4: Event Payload Validator
 * Validates event payloads match backend processor requirements
 * Ensures all required attributes are present with correct data types and formats
 */
object EventPayloadValidator {
    
    private const val TAG = "EventPayloadValidator"
    
    private val ISO_8601_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")
    
    /**
     * Validate HTTP request event payload
     */
    fun validateHttpRequestEvent(
        eventName: String,
        attributes: Map<String, Any?>,
        timestamp: String
    ): EventValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate event name
        if (eventName != "http.request") {
            errors.add("Event name must be 'http.request', got '$eventName'")
        }
        
        // Validate required attributes
        val requiredAttrs = mapOf(
            "http.url" to String::class.java,
            "http.method" to String::class.java,
            "http.status_code" to Int::class.java,
            "http.duration_ms" to Long::class.java,
            "http.timestamp" to String::class.java,
            "http.success" to Boolean::class.java
        )
        
        requiredAttrs.forEach { (attr, expectedType) ->
            validateAttribute(attributes, attr, expectedType, errors)
        }
        
        // Validate HTTP method values
        attributes["http.method"]?.let { method ->
            val validMethods = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
            if (method !is String || method !in validMethods) {
                errors.add("http.method must be one of $validMethods, got '$method'")
            }
        }
        
        // Validate status code range
        attributes["http.status_code"]?.let { code ->
            if (code is Int && (code < 100 || code > 599)) {
                errors.add("http.status_code must be between 100-599, got $code")
            }
        }
        
        // Validate timestamp format
        validateTimestamp(attributes["http.timestamp"], "http.timestamp", errors)
        validateTimestamp(timestamp, "event.timestamp", errors)
        
        return createValidationResult(errors)
    }
    
    /**
     * Validate session finalized event payload
     */
    fun validateSessionFinalizedEvent(
        eventName: String,
        attributes: Map<String, Any?>,
        timestamp: String
    ): EventValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate event name
        if (eventName != "session.finalized") {
            errors.add("Event name must be 'session.finalized', got '$eventName'")
        }
        
        // Validate required attributes
        val requiredAttrs = mapOf(
            "session.id" to String::class.java,
            "session.start_time" to String::class.java,
            "session.duration_ms" to Long::class.java,
            "session.event_count" to Int::class.java,
            "session.metric_count" to Int::class.java,
            "session.screen_count" to Int::class.java,
            "session.visited_screens" to String::class.java,
            "session.is_first_session" to Boolean::class.java,
            "session.total_sessions" to Int::class.java,
            "network.type" to String::class.java
        )
        
        requiredAttrs.forEach { (attr, expectedType) ->
            validateAttribute(attributes, attr, expectedType, errors)
        }
        
        // Validate session.id is UUID format
        attributes["session.id"]?.let { id ->
            if (id is String && !isValidUUID(id)) {
                errors.add("session.id must be valid UUID format, got '$id'")
            }
        }
        
        // Validate timestamps
        validateTimestamp(attributes["session.start_time"], "session.start_time", errors)
        validateTimestamp(timestamp, "event.timestamp", errors)
        
        // Validate counts are non-negative
        listOf("session.event_count", "session.metric_count", "session.screen_count", "session.total_sessions").forEach { attr ->
            attributes[attr]?.let { count ->
                if (count is Int && count < 0) {
                    errors.add("$attr must be non-negative, got $count")
                }
            }
        }
        
        return createValidationResult(errors)
    }
    
    /**
     * Validate navigation event payload
     */
    fun validateNavigationEvent(
        eventName: String,
        attributes: Map<String, Any?>,
        timestamp: String
    ): EventValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate event name
        if (eventName != "navigation") {
            errors.add("Event name must be 'navigation', got '$eventName'")
        }
        
        // Validate required attributes (from_screen is nullable)
        val requiredAttrs = mapOf(
            "navigation.to_screen" to String::class.java,
            "navigation.method" to String::class.java,
            "navigation.route_type" to String::class.java,
            "navigation.has_arguments" to Boolean::class.java,
            "navigation.timestamp" to String::class.java
        )
        
        requiredAttrs.forEach { (attr, expectedType) ->
            validateAttribute(attributes, attr, expectedType, errors)
        }
        
        // from_screen is optional (null on app launch)
        attributes["navigation.from_screen"]?.let { fromScreen ->
            if (fromScreen !is String) {
                errors.add("navigation.from_screen must be String if present, got ${fromScreen::class.java.simpleName}")
            }
        }
        
        // Validate navigation method values
        attributes["navigation.method"]?.let { method ->
            val validMethods = setOf("push", "pop", "replace")
            if (method !is String || method !in validMethods) {
                errors.add("navigation.method must be one of $validMethods, got '$method'")
            }
        }
        
        // Validate timestamp format
        validateTimestamp(attributes["navigation.timestamp"], "navigation.timestamp", errors)
        validateTimestamp(timestamp, "event.timestamp", errors)
        
        return createValidationResult(errors)
    }
    
    /**
     * Validate screen duration event payload
     */
    fun validateScreenDurationEvent(
        eventName: String,
        attributes: Map<String, Any?>,
        timestamp: String
    ): EventValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate event name
        if (eventName != "performance.screen_duration") {
            errors.add("Event name must be 'performance.screen_duration', got '$eventName'")
        }
        
        // Validate required attributes
        val requiredAttrs = mapOf(
            "screen.name" to String::class.java,
            "screen.duration_ms" to Long::class.java,
            "screen.exit_method" to String::class.java,
            "screen.timestamp" to String::class.java
        )
        
        requiredAttrs.forEach { (attr, expectedType) ->
            validateAttribute(attributes, attr, expectedType, errors)
        }
        
        // Validate exit method values
        attributes["screen.exit_method"]?.let { method ->
            val validMethods = setOf("navigation", "paused", "closed", "destroyed", "saved_state")
            if (method !is String || method !in validMethods) {
                errors.add("screen.exit_method must be one of $validMethods, got '$method'")
            }
        }
        
        // Validate duration is non-negative
        attributes["screen.duration_ms"]?.let { duration ->
            if (duration is Long && duration < 0) {
                errors.add("screen.duration_ms must be non-negative, got $duration")
            }
        }
        
        // Validate timestamp format
        validateTimestamp(attributes["screen.timestamp"], "screen.timestamp", errors)
        validateTimestamp(timestamp, "event.timestamp", errors)
        
        return createValidationResult(errors)
    }
    
    /**
     * Validate crash event payload
     */
    fun validateCrashEvent(
        eventName: String,
        attributes: Map<String, Any?>,
        timestamp: String
    ): EventValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate event name
        if (eventName != "app.crash") {
            errors.add("Event name must be 'app.crash', got '$eventName'")
        }
        
        // Validate required attributes
        val requiredAttrs = mapOf(
            "error.message" to String::class.java,
            "error.stack_trace" to String::class.java,
            "error.exception_type" to String::class.java,
            "error.context" to String::class.java,
            "error.cause" to String::class.java,
            "error.severity_level" to String::class.java,
            "error.is_fatal" to Boolean::class.java,
            "error.breadcrumbs" to String::class.java,
            "error.breadcrumb_count" to Int::class.java
        )
        
        requiredAttrs.forEach { (attr, expectedType) ->
            validateAttribute(attributes, attr, expectedType, errors)
        }
        
        // Validate field length limits
        validateFieldLength(attributes["error.message"], "error.message", 1000, errors)
        validateFieldLength(attributes["error.stack_trace"], "error.stack_trace", 2000, errors)
        validateFieldLength(attributes["error.exception_type"], "error.exception_type", 255, errors)
        validateFieldLength(attributes["error.context"], "error.context", 500, errors)
        validateFieldLength(attributes["error.cause"], "error.cause", 255, errors)
        validateFieldLength(attributes["error.breadcrumbs"], "error.breadcrumbs", 800, errors)
        
        // Validate optional fields if present
        attributes["error.code"]?.let { code ->
            if (code is String) {
                validateFieldLength(code, "error.code", 100, errors)
            }
        }
        attributes["error.product_id"]?.let { productId ->
            if (productId is String) {
                validateFieldLength(productId, "error.product_id", 255, errors)
            }
        }
        attributes["error.user_action"]?.let { userAction ->
            if (userAction is String) {
                validateFieldLength(userAction, "error.user_action", 500, errors)
            }
        }
        
        // Validate severity level values
        attributes["error.severity_level"]?.let { level ->
            val validLevels = setOf("critical", "error", "warning", "info")
            if (level !is String || level !in validLevels) {
                errors.add("error.severity_level must be one of $validLevels, got '$level'")
            }
        }
        
        // Validate timestamp format
        validateTimestamp(timestamp, "event.timestamp", errors)
        
        return createValidationResult(errors)
    }
    
    /**
     * Validate standard attributes (app, device, user, session)
     */
    fun validateStandardAttributes(attributes: Map<String, Any?>): EventValidationResult {
        return AttributeValidator.validatePhase2Attributes(attributes).let { result ->
            when (result) {
                is ValidationResult.Success -> EventValidationResult.Valid
                is ValidationResult.Failure -> EventValidationResult.Invalid(
                    result.missingAttributes + result.emptyAttributes.map { "$it is empty" }
                )
            }
        }
    }
    
    /**
     * Helper: Validate attribute exists and has correct type
     */
    private fun validateAttribute(
        attributes: Map<String, Any?>,
        attrName: String,
        expectedType: Class<*>,
        errors: MutableList<String>
    ) {
        val value = attributes[attrName]
        
        when {
            value == null -> errors.add("Required attribute '$attrName' is missing")
            !expectedType.isInstance(value) -> {
                errors.add("Attribute '$attrName' must be ${expectedType.simpleName}, got ${value::class.java.simpleName}")
            }
        }
    }
    
    /**
     * Helper: Validate timestamp is ISO 8601 format
     */
    private fun validateTimestamp(timestamp: Any?, fieldName: String, errors: MutableList<String>) {
        when {
            timestamp == null -> errors.add("Timestamp '$fieldName' is missing")
            timestamp !is String -> errors.add("Timestamp '$fieldName' must be String, got ${timestamp::class.java.simpleName}")
            !timestamp.matches(ISO_8601_REGEX) -> {
                errors.add("Timestamp '$fieldName' must be ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSS'Z'), got '$timestamp'")
            }
            else -> {
                // Additional validation: try to parse
                try {
                    Instant.parse(timestamp)
                } catch (e: DateTimeParseException) {
                    errors.add("Timestamp '$fieldName' is not a valid ISO 8601 timestamp: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Helper: Validate field length limit
     */
    private fun validateFieldLength(value: Any?, fieldName: String, maxLength: Int, errors: MutableList<String>) {
        if (value is String && value.length > maxLength) {
            errors.add("Field '$fieldName' exceeds max length of $maxLength chars (actual: ${value.length})")
        }
    }
    
    /**
     * Helper: Check if string is valid UUID
     */
    private fun isValidUUID(uuid: String): Boolean {
        return try {
            java.util.UUID.fromString(uuid)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
    
    /**
     * Helper: Create validation result from errors list
     */
    private fun createValidationResult(errors: List<String>): EventValidationResult {
        return if (errors.isEmpty()) {
            EventValidationResult.Valid
        } else {
            Log.w(TAG, "Validation failed: ${errors.joinToString("; ")}")
            EventValidationResult.Invalid(errors)
        }
    }
}

/**
 * Result of event payload validation
 */
sealed class EventValidationResult {
    object Valid : EventValidationResult()
    data class Invalid(val errors: List<String>) : EventValidationResult()
    
    val isValid: Boolean
        get() = this is Valid
    
    fun getErrorReport(): String {
        return when (this) {
            is Valid -> "✓ Event payload is valid"
            is Invalid -> {
                buildString {
                    appendLine("✗ Event payload validation failed:")
                    errors.forEach { error ->
                        appendLine("  - $error")
                    }
                }
            }
        }
    }
}
