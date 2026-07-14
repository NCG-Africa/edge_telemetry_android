package com.androidtel.telemetry_library.core.payload

import com.google.gson.Gson
import java.time.Instant

/**
 * Payload structures that match the Flutter SDK exactly for backend compatibility
 */

/**
 * Main crash payload structure matching Flutter SDK
 */
data class CrashPayload(
    val timestamp: String,
    val device_id: String,  // Device ID at top level for easy filtering/routing
    val data: CrashData
) {
    fun toJson(): String = Gson().toJson(this)
}

/**
 * Crash data structure matching Flutter SDK
 */
data class CrashData(
    val type: String = "error",
    val error: String,
    val timestamp: String,
    val stackTrace: String,
    val fingerprint: String,
    val attributes: Map<String, String>
)

/**
 * Event batch payload structure matching Flutter SDK
 */
data class EventBatchPayload(
    val timestamp: String,
    val device_id: String,  // Device ID at top level for easy filtering/routing
    val data: EventBatchData
) {
    fun toJson(): String = Gson().toJson(this)
}

/**
 * Event batch data structure matching Flutter SDK
 */
data class EventBatchData(
    val type: String = "batch",
    val events: List<EventData>,
    val batch_size: Int,
    val timestamp: String,
    val location: String? = null
)

/**
 * Individual event data structure matching Flutter SDK
 */
data class EventData(
    val type: String, // "event", "metric", "navigation", etc.
    val eventName: String? = null,
    val metricName: String? = null,
    val value: Double? = null,
    val timestamp: String,
    val attributes: Map<String, String>
)

/**
 * Crash event attributes matching backend processor requirements
 * All fields have character limits enforced
 */
data class CrashEventAttributes(
    // Required fields
    val message: String,              // max 1000 chars
    val stacktrace: String,           // max 2000 chars (lowercase to match backend)
    val exception_type: String,       // max 255 chars
    
    // Optional but important fields
    val error_context: String? = null,    // max 500 chars
    val product_id: String? = null,       // max 255 chars
    val cause: String? = null,            // max 255 chars
    val is_fatal: Boolean = false,
    val user_action: String? = null,      // max 500 chars
    val error_code: String? = null        // max 100 chars
)

/**
 * Crash event data structure matching backend requirements
 * type: "event", eventName: "app.crash"
 */
data class CrashEventData(
    val type: String = "event",
    val eventName: String = "app.crash",
    val timestamp: String,
    val attributes: Map<String, String>  // Flattened attributes including crash fields + device/app/session/user
)

/**
 * Crash batch envelope matching backend processor requirements
 */
data class CrashBatchEnvelope(
    val timestamp: String,
    val device_id: String,
    val data: CrashBatchData
) {
    fun toJson(): String = Gson().toJson(this)
}

/**
 * Crash batch data structure
 */
data class CrashBatchData(
    val tenant_id: String? = null,  // Optional - can be set by backend
    val location: String? = null,
    val timestamp: String,
    val batch_size: Int = 1,
    val events: List<CrashEventData>
)

/**
 * Factory for creating Flutter-compatible payloads
 */
object FlutterPayloadFactory {
    
    /**
     * Create crash payload matching Flutter SDK structure
     */
    fun createCrashPayload(
        error: String,
        stackTrace: String,
        fingerprint: String,
        attributes: Map<String, String>,
        deviceId: String
    ): CrashPayload {
        val timestamp = Instant.now().toString()
        
        return CrashPayload(
            timestamp = timestamp,
            device_id = deviceId,
            data = CrashData(
                error = error,
                timestamp = timestamp,
                stackTrace = stackTrace,
                fingerprint = fingerprint,
                attributes = attributes
            )
        )
    }
    
    /**
     * Create event batch payload matching Flutter SDK structure
     */
    fun createEventBatchPayload(
        events: List<EventData>,
        deviceId: String,
        location: String? = null
    ): EventBatchPayload {
        val timestamp = Instant.now().toString()
        
        return EventBatchPayload(
            timestamp = timestamp,
            device_id = deviceId,
            data = EventBatchData(
                events = events,
                batch_size = events.size,
                timestamp = timestamp,
                location = location
            )
        )
    }
    
    /**
     * Create event data matching Flutter SDK structure
     */
    fun createEventData(
        type: String,
        eventName: String? = null,
        metricName: String? = null,
        value: Double? = null,
        attributes: Map<String, String> = emptyMap()
    ): EventData {
        return EventData(
            type = type,
            eventName = eventName,
            metricName = metricName,
            value = value,
            timestamp = Instant.now().toString(),
            attributes = attributes
        )
    }
    
    /**
     * Create enriched attributes with all required fields for Flutter SDK compatibility
     */
    fun createEnrichedAttributes(
        deviceAttributes: Map<String, String>,
        appAttributes: Map<String, String>,
        sessionAttributes: Map<String, String>,
        userAttributes: Map<String, String>,
        networkType: String,
        customAttributes: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val enriched = mutableMapOf<String, String>()
        
        // Add all attribute sets
        enriched.putAll(deviceAttributes)
        enriched.putAll(appAttributes)
        enriched.putAll(sessionAttributes)
        enriched.putAll(userAttributes)
        
        // Add network type
        enriched["network.type"] = networkType
        
        // Add custom attributes (these can override others)
        enriched.putAll(customAttributes)
        
        return enriched
    }
    
    /**
     * Create crash-specific attributes matching Flutter SDK
     */
    fun createCrashAttributes(
        baseAttributes: Map<String, String>,
        fingerprint: String,
        breadcrumbs: String,
        breadcrumbCount: Int,
        additionalAttributes: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val crashAttributes = baseAttributes.toMutableMap()
        
        // Add crash-specific fields
        // Note: crash.fingerprint and crash.breadcrumb_count removed - backend generates crash_hash and counts breadcrumbs
        crashAttributes["error.timestamp"] = Instant.now().toString()
        crashAttributes["error.has_stack_trace"] = "true"
        crashAttributes["breadcrumbs"] = breadcrumbs
        
        // Add any additional attributes
        crashAttributes.putAll(additionalAttributes)
        
        return crashAttributes
    }
    
    /**
     * Create new crash batch envelope matching backend processor requirements
     * This is the v2.0.0 structure
     */
    fun createCrashBatchEnvelope(
        throwable: Throwable,
        deviceId: String,
        baseAttributes: Map<String, String>,
        location: String? = null,
        productId: String? = null,
        userAction: String? = null,
        errorCode: String? = null,
        additionalAttributes: Map<String, String> = emptyMap()
    ): CrashBatchEnvelope {
        val timestamp = Instant.now().toString()
        val stackTrace = generateStackTrace(throwable)
        
        // Extract crash fields with character limits
        val message = "${throwable.javaClass.name}: ${throwable.message ?: ""}".take(1000)
        val stacktrace = stackTrace.take(2000)
        val exceptionType = throwable.javaClass.simpleName.take(255)
        val errorContext = extractErrorContext(stackTrace).take(500)
        val cause = (throwable.cause?.message ?: "unknown").take(255)
        val isFatal = isFatalException(throwable)
        
        // Build flattened attributes map
        val crashAttributes = mutableMapOf<String, String>()
        
        // Add base attributes (device, app, session, user)
        crashAttributes.putAll(baseAttributes)
        
        // Add crash-specific fields (backend requirements)
        crashAttributes["message"] = message
        crashAttributes["stacktrace"] = stacktrace
        crashAttributes["exception_type"] = exceptionType
        crashAttributes["error_context"] = errorContext
        crashAttributes["cause"] = cause
        crashAttributes["is_fatal"] = isFatal.toString()
        
        // Add optional fields if provided
        productId?.let { crashAttributes["product_id"] = it.take(255) }
        userAction?.let { crashAttributes["user_action"] = it.take(500) }
        errorCode?.let { crashAttributes["error_code"] = it.take(100) }
        
        // Add any additional attributes
        crashAttributes.putAll(additionalAttributes)
        
        // Create crash event
        val crashEvent = CrashEventData(
            timestamp = timestamp,
            attributes = crashAttributes
        )
        
        // Wrap in batch envelope
        return CrashBatchEnvelope(
            timestamp = timestamp,
            device_id = deviceId,
            data = CrashBatchData(
                timestamp = timestamp,
                location = location,
                events = listOf(crashEvent)
            )
        )
    }
    
    /**
     * Create crash batch envelope from message and stack trace
     */
    fun createCrashBatchEnvelope(
        message: String,
        stackTrace: String,
        deviceId: String,
        baseAttributes: Map<String, String>,
        location: String? = null,
        productId: String? = null,
        userAction: String? = null,
        errorCode: String? = null,
        additionalAttributes: Map<String, String> = emptyMap()
    ): CrashBatchEnvelope {
        val timestamp = Instant.now().toString()
        
        // Extract crash fields with character limits
        val truncatedMessage = message.take(1000)
        val truncatedStacktrace = stackTrace.take(2000)
        val exceptionType = extractExceptionTypeFromMessage(message).take(255)
        val errorContext = extractErrorContext(stackTrace).take(500)
        val cause = "unknown"
        
        // Build flattened attributes map
        val crashAttributes = mutableMapOf<String, String>()
        
        // Add base attributes (device, app, session, user)
        crashAttributes.putAll(baseAttributes)
        
        // Add crash-specific fields (backend requirements)
        crashAttributes["message"] = truncatedMessage
        crashAttributes["stacktrace"] = truncatedStacktrace
        crashAttributes["exception_type"] = exceptionType
        crashAttributes["error_context"] = errorContext
        crashAttributes["cause"] = cause
        crashAttributes["is_fatal"] = "false"
        
        // Add optional fields if provided
        productId?.let { crashAttributes["product_id"] = it.take(255) }
        userAction?.let { crashAttributes["user_action"] = it.take(500) }
        errorCode?.let { crashAttributes["error_code"] = it.take(100) }
        
        // Add any additional attributes
        crashAttributes.putAll(additionalAttributes)
        
        // Create crash event
        val crashEvent = CrashEventData(
            timestamp = timestamp,
            attributes = crashAttributes
        )
        
        // Wrap in batch envelope
        return CrashBatchEnvelope(
            timestamp = timestamp,
            device_id = deviceId,
            data = CrashBatchData(
                timestamp = timestamp,
                location = location,
                events = listOf(crashEvent)
            )
        )
    }
    
    /**
     * Generate stack trace from throwable
     */
    private fun generateStackTrace(throwable: Throwable): String {
        val stringWriter = java.io.StringWriter()
        val printWriter = java.io.PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        return stringWriter.toString()
    }
    
    /**
     * Extract error context from stack trace (ClassName.methodName from top frame)
     */
    private fun extractErrorContext(stackTrace: String): String {
        return try {
            val lines = stackTrace.lines()
            val firstFrame = lines.firstOrNull { it.trim().startsWith("at ") } ?: return "unknown"
            
            // Extract "at com.example.ClassName.methodName(File.kt:123)"
            val atIndex = firstFrame.indexOf("at ")
            if (atIndex == -1) return "unknown"
            
            val methodPart = firstFrame.substring(atIndex + 3).trim()
            val parenIndex = methodPart.indexOf("(")
            val fullMethod = if (parenIndex > 0) methodPart.substring(0, parenIndex) else methodPart
            
            // Get last two parts (ClassName.methodName)
            val parts = fullMethod.split(".")
            if (parts.size >= 2) {
                "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
            } else {
                fullMethod
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Extract exception type from error message
     */
    private fun extractExceptionTypeFromMessage(message: String): String {
        return try {
            val colonIndex = message.indexOf(":")
            if (colonIndex > 0) {
                val exceptionClass = message.substring(0, colonIndex).trim()
                val parts = exceptionClass.split(".")
                parts.lastOrNull() ?: "Exception"
            } else {
                "Exception"
            }
        } catch (e: Exception) {
            "Exception"
        }
    }
    
    /**
     * Determine if exception is fatal based on type
     */
    private fun isFatalException(throwable: Throwable): Boolean {
        val fatalTypes = listOf(
            "OutOfMemoryError",
            "StackOverflowError",
            "FatalException",
            "RuntimeException",
            "IllegalStateException"
        )
        return fatalTypes.any { throwable.javaClass.simpleName.contains(it) }
    }
}
