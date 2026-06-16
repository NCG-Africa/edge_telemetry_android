package com.androidtel.telemetry_library.core.validation

import android.util.Log
import com.androidtel.telemetry_library.core.models.TelemetryEvent

/**
 * Runtime Event Validator
 * Provides runtime validation of telemetry events before sending to backend
 * Can be enabled in debug mode to catch validation issues early
 */
class RuntimeEventValidator(
    private val debugMode: Boolean = false,
    private val strictMode: Boolean = false
) {
    
    companion object {
        private const val TAG = "RuntimeEventValidator"
    }
    
    /**
     * Validate a telemetry event before sending
     * 
     * @param event The telemetry event to validate
     * @return ValidationResult indicating success or failure
     */
    fun validateEvent(event: TelemetryEvent): EventValidationResult {
        val flattenedAttributes = flattenAttributes(event.attributes)
        
        // Validate based on event type
        val eventResult = when (event.eventName) {
            "http.request" -> EventPayloadValidator.validateHttpRequestEvent(
                eventName = event.eventName ?: "",
                attributes = flattenedAttributes,
                timestamp = event.timestamp
            )
            "session.finalized" -> EventPayloadValidator.validateSessionFinalizedEvent(
                eventName = event.eventName ?: "",
                attributes = flattenedAttributes,
                timestamp = event.timestamp
            )
            "navigation" -> EventPayloadValidator.validateNavigationEvent(
                eventName = event.eventName ?: "",
                attributes = flattenedAttributes,
                timestamp = event.timestamp
            )
            "screen.duration" -> EventPayloadValidator.validateScreenDurationEvent(
                eventName = event.eventName ?: "",
                attributes = flattenedAttributes,
                timestamp = event.timestamp
            )
            "app.crash" -> EventPayloadValidator.validateCrashEvent(
                eventName = event.eventName ?: "",
                attributes = flattenedAttributes,
                timestamp = event.timestamp
            )
            else -> {
                if (debugMode) {
                    Log.w(TAG, "Unknown event type: ${event.eventName}")
                }
                EventValidationResult.Valid // Allow unknown events in non-strict mode
            }
        }
        
        // Validate standard attributes for all events
        val standardResult = EventPayloadValidator.validateStandardAttributes(flattenedAttributes)
        
        // Combine results
        val combinedResult = combineResults(eventResult, standardResult)
        
        // Log validation results in debug mode
        if (debugMode && !combinedResult.isValid) {
            Log.w(TAG, "Event validation failed for ${event.eventName}:")
            Log.w(TAG, combinedResult.getErrorReport())
        }
        
        // In strict mode, throw exception on validation failure
        if (strictMode && !combinedResult.isValid) {
            throw EventValidationException(
                "Event validation failed for ${event.eventName}: ${combinedResult.getErrorReport()}"
            )
        }
        
        return combinedResult
    }
    
    /**
     * Validate multiple events in a batch
     */
    fun validateBatch(events: List<TelemetryEvent>): BatchValidationResult {
        val results = events.map { event ->
            event to validateEvent(event)
        }
        
        val failedEvents = results.filter { !it.second.isValid }
        
        return if (failedEvents.isEmpty()) {
            BatchValidationResult.AllValid(events.size)
        } else {
            BatchValidationResult.SomeInvalid(
                totalEvents = events.size,
                validCount = events.size - failedEvents.size,
                invalidEvents = failedEvents.map { (event, result) ->
                    InvalidEventInfo(
                        eventName = event.eventName ?: "unknown",
                        timestamp = event.timestamp,
                        errors = (result as EventValidationResult.Invalid).errors
                    )
                }
            )
        }
    }
    
    /**
     * Flatten nested EventAttributes to a simple map for validation
     */
    private fun flattenAttributes(attributes: com.androidtel.telemetry_library.core.models.EventAttributes): Map<String, Any?> {
        val flattened = mutableMapOf<String, Any?>()
        
        // App attributes
        val app = attributes.app
        flattened["app.name"] = app.appName
        flattened["app.version"] = app.appVersion
        flattened["app.build_number"] = app.appBuildNumber
        flattened["app.package_name"] = app.appPackageName
        
        // Device attributes
        val device = attributes.device
        flattened["device.id"] = device.deviceId
        flattened["device.platform"] = device.platform
        flattened["device.platform_version"] = device.platformVersion
        flattened["device.model"] = device.model
        flattened["device.manufacturer"] = device.manufacturer
        flattened["device.brand"] = device.brand
        flattened["device.android_sdk"] = device.androidSdk
        flattened["device.android_release"] = device.androidRelease
        flattened["device.fingerprint"] = device.fingerprint
        flattened["device.hardware"] = device.hardware
        flattened["device.product"] = device.product
        
        // User attributes
        val user = attributes.user
        flattened["user.id"] = user.userId
        
        // Session attributes
        val session = attributes.session
        flattened["session.id"] = session.sessionId
        session.startTime?.let { flattened["session.start_time"] = it }
        
        // Custom attributes
        attributes.customAttributes.forEach { (key, value) ->
            flattened[key] = value
        }
        
        return flattened
    }
    
    /**
     * Combine event-specific and standard attribute validation results
     */
    private fun combineResults(
        eventResult: EventValidationResult,
        standardResult: EventValidationResult
    ): EventValidationResult {
        return when {
            eventResult.isValid && standardResult.isValid -> EventValidationResult.Valid
            !eventResult.isValid && !standardResult.isValid -> {
                val eventErrors = (eventResult as EventValidationResult.Invalid).errors
                val standardErrors = (standardResult as EventValidationResult.Invalid).errors
                EventValidationResult.Invalid(eventErrors + standardErrors)
            }
            !eventResult.isValid -> eventResult
            else -> standardResult
        }
    }
}

/**
 * Result of batch validation
 */
sealed class BatchValidationResult {
    data class AllValid(val totalEvents: Int) : BatchValidationResult()
    data class SomeInvalid(
        val totalEvents: Int,
        val validCount: Int,
        val invalidEvents: List<InvalidEventInfo>
    ) : BatchValidationResult()
    
    val isValid: Boolean
        get() = this is AllValid
    
    fun getReport(): String {
        return when (this) {
            is AllValid -> "✓ All $totalEvents events are valid"
            is SomeInvalid -> {
                buildString {
                    appendLine("✗ Batch validation: $validCount/$totalEvents events valid")
                    invalidEvents.forEach { info ->
                        appendLine("  Event: ${info.eventName} at ${info.timestamp}")
                        info.errors.forEach { error ->
                            appendLine("    - $error")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Information about an invalid event
 */
data class InvalidEventInfo(
    val eventName: String,
    val timestamp: String,
    val errors: List<String>
)

/**
 * Exception thrown when event validation fails in strict mode
 */
class EventValidationException(message: String) : Exception(message)
