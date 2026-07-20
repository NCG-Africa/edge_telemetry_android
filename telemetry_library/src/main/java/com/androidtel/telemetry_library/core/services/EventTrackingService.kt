package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.CountedEventQueue
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.UserInfo
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.TelemetryTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * EventTrackingService - Handles event and metric recording
 * Extracted from TelemetryManager as part of Phase 2 refactoring
 * 
 * Responsibilities:
 * - Record events with attributes
 * - Record metrics with values
 * - Build event attributes with context
 * - Manage event queue
 * - Track event/metric counts
 */
internal class EventTrackingService(
    private val context: Context,
    private val config: TelemetryConfig
) {
    private val eventQueue = CountedEventQueue()
    private val eventCount = AtomicInteger(0)
    private val metricCount = AtomicInteger(0)
    
    private var appInfo: AppInfo? = null
    private var deviceInfo: DeviceInfo? = null
    private var globalAttributes = mutableMapOf<String, String>()
    
    fun initialize(appInfo: AppInfo?, deviceInfo: DeviceInfo) {
        this.appInfo = appInfo
        this.deviceInfo = deviceInfo
        Log.d(TAG, "EventTrackingService initialized")
    }
    
    /**
     * Record a general event
     */
    fun recordEvent(
        eventName: String,
        attributes: Map<String, Any> = emptyMap(),
        userInfo: UserInfo,
        sessionInfo: SessionInfo
    ): TelemetryEvent? {
        eventCount.incrementAndGet()
        
        val event = buildAttributes(attributes, userInfo, sessionInfo)?.let {
            TelemetryEvent(
                type = "event",
                eventName = eventName,
                timestamp = TelemetryTime.now(),
                attributes = it
            )
        }
        
        event?.let { eventQueue.enqueue(it) }
        return event
    }
    
    /**
     * Record a metric event
     */
    fun recordMetric(
        metricName: String,
        value: Double,
        attributes: Map<String, Any> = emptyMap(),
        userInfo: UserInfo,
        sessionInfo: SessionInfo
    ): TelemetryEvent? {
        metricCount.incrementAndGet()
        
        val event = buildAttributes(attributes, userInfo, sessionInfo)?.let {
            TelemetryEvent(
                type = "metric",
                metricName = metricName,
                value = value,
                timestamp = TelemetryTime.now(),
                attributes = it
            )
        }
        
        event?.let { eventQueue.enqueue(it) }
        return event
    }
    
    /**
     * Record network request event
     */
    fun recordNetworkRequest(
        url: String,
        method: String,
        statusCode: Int,
        durationMs: Long,
        requestBodySize: Long = 0,
        responseBodySize: Long = 0,
        error: String? = null,
        attributes: Map<String, Any> = emptyMap(),
        userInfo: UserInfo,
        sessionInfo: SessionInfo
    ) {
        val networkAttributes = mapOf(
            "http.url" to url,
            "http.method" to method,
            "http.status_code" to statusCode,
            "http.duration_ms" to durationMs,
            "http.timestamp" to TelemetryTime.now(),
            "http.success" to (statusCode < 400),
            "http.request_body_size" to requestBodySize,
            "http.response_body_size" to responseBodySize,
            "http.error" to (error ?: "none")
        )
        val combinedAttributes = attributes + networkAttributes
        recordEvent("http.request", combinedAttributes, userInfo, sessionInfo)
    }
    
    /**
     * Build event attributes with full context
     */
    private fun buildAttributes(
        eventAttributes: Map<String, Any>,
        userInfo: UserInfo,
        sessionInfo: SessionInfo
    ): EventAttributes? {
        return appInfo?.let {
            EventAttributes(
                app = it,
                device = deviceInfo!!,
                user = userInfo,
                session = sessionInfo,
                customAttributes = eventAttributes
            )
        }
    }
    
    /**
     * Get event queue for batch processing
     */
    fun getEventQueue(): CountedEventQueue = eventQueue
    
    /**
     * Get current event count
     */
    fun getEventCount(): Int = eventCount.get()
    
    /**
     * Get current metric count
     */
    fun getMetricCount(): Int = metricCount.get()
    
    /**
     * Reset event count (for session management)
     */
    fun resetEventCount() {
        eventCount.set(0)
    }
    
    /**
     * Reset metric count (for session management)
     */
    fun resetMetricCount() {
        metricCount.set(0)
    }
    
    /**
     * Set global attributes for all events
     */
    fun setGlobalAttributes(attributes: Map<String, String>) {
        globalAttributes.clear()
        globalAttributes.putAll(attributes)
    }
    
    /**
     * Add global attribute
     */
    fun addGlobalAttribute(key: String, value: String) {
        globalAttributes[key] = value
    }
    
    /**
     * Remove global attribute
     */
    fun removeGlobalAttribute(key: String) {
        globalAttributes.remove(key)
    }
    
    /**
     * Get current timestamp in ISO format
     */
    fun getCurrentTimestamp(): String = TelemetryTime.now()
    
    companion object {
        private const val TAG = "EventTrackingService"
    }
}
