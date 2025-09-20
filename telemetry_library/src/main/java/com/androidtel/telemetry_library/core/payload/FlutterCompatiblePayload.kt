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
    val timestamp: String
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
        attributes: Map<String, String>
    ): CrashPayload {
        val timestamp = Instant.now().toString()
        
        return CrashPayload(
            timestamp = timestamp,
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
        events: List<EventData>
    ): EventBatchPayload {
        val timestamp = Instant.now().toString()
        
        return EventBatchPayload(
            timestamp = timestamp,
            data = EventBatchData(
                events = events,
                batch_size = events.size,
                timestamp = timestamp
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
        crashAttributes["crash.fingerprint"] = fingerprint
        crashAttributes["crash.breadcrumb_count"] = breadcrumbCount.toString()
        crashAttributes["error.timestamp"] = Instant.now().toString()
        crashAttributes["error.has_stack_trace"] = "true"
        crashAttributes["breadcrumbs"] = breadcrumbs
        
        // Add any additional attributes
        crashAttributes.putAll(additionalAttributes)
        
        return crashAttributes
    }
}
