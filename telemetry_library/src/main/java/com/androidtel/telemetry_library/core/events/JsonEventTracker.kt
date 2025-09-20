package com.androidtel.telemetry_library.core.events

import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.crash.CrashFingerprinter
import com.androidtel.telemetry_library.core.device.DeviceInfoCollector
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.session.SessionManager
import com.androidtel.telemetry_library.core.user.UserProfileManager
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JSON Event Tracker that handles event batching and immediate crash reporting
 * with exact payload structure matching Flutter SDK
 */
class JsonEventTracker(
    private val telemetryManager: TelemetryManager,
    private val sessionManager: SessionManager,
    private val userProfileManager: UserProfileManager,
    private val breadcrumbManager: BreadcrumbManager,
    private val idGenerator: IdGenerator,
    private val batchSize: Int = 30,
    private val batchTimeout: Duration = Duration.ofSeconds(5)
) {
    
    companion object {
        private const val TAG = "JsonEventTracker"
    }
    
    private val eventQueue = ConcurrentLinkedQueue<Map<String, Any>>()
    private val globalAttributes = mutableMapOf<String, String>()
    private val lock = ReentrantReadWriteLock()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val deviceInfoCollector = DeviceInfoCollector(telemetryManager.applicationContext, idGenerator)
    
    private var timeoutTimer: Timer? = null
    
    init {
        startBatchTimer()
    }
    
    /**
     * Track a custom event
     */
    fun trackEvent(eventName: String, attributes: Map<String, String>? = null) {
        val eventData = createEventPayload(
            type = "event",
            eventName = eventName,
            attributes = attributes
        )
        
        addToBatch(eventData)
        sessionManager.recordEvent()
        
        Log.d(TAG, "📊 Event tracked: $eventName")
    }
    
    /**
     * Track a metric
     */
    fun trackMetric(metricName: String, value: Double, attributes: Map<String, String>? = null) {
        val metricData = createEventPayload(
            type = "metric",
            metricName = metricName,
            value = value,
            attributes = attributes
        )
        
        addToBatch(metricData)
        sessionManager.recordMetric()
        
        Log.d(TAG, "📈 Metric tracked: $metricName = $value")
    }
    
    /**
     * Track an error (bypasses batching for immediate sending)
     */
    fun trackError(error: Throwable, stackTrace: String, attributes: Map<String, String>? = null) {
        val crashData = createCrashPayload(error, stackTrace, attributes)
        
        // Send immediately, bypass batching
        scope.launch {
            try {
                sendCrashImmediately(crashData)
                Log.d(TAG, "💥 Error sent immediately: ${error.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send error immediately", e)
            }
        }
    }
    
    /**
     * Set global attributes that are added to all events
     */
    fun setGlobalAttributes(attributes: Map<String, String>) {
        lock.write {
            globalAttributes.clear()
            globalAttributes.putAll(attributes)
        }
        Log.d(TAG, "🌍 Global attributes set: ${attributes.keys}")
    }
    
    /**
     * Add global attribute
     */
    fun addGlobalAttribute(key: String, value: String) {
        lock.write {
            globalAttributes[key] = value
        }
    }
    
    /**
     * Test connectivity by sending a test event
     */
    fun testConnectivity() {
        trackEvent("test.connectivity", mapOf(
            "test" to "true",
            "timestamp" to Instant.now().toString()
        ))
        Log.i(TAG, "🧪 Connectivity test event sent")
    }
    
    /**
     * Create event payload with enriched attributes
     */
    private fun createEventPayload(
        type: String,
        eventName: String? = null,
        metricName: String? = null,
        value: Double? = null,
        attributes: Map<String, String>? = null
    ): Map<String, Any> {
        return mapOf(
            "type" to type,
            "eventName" to eventName,
            "metricName" to metricName,
            "value" to value,
            "timestamp" to Instant.now().toString(),
            "attributes" to getEnrichedAttributes(attributes)
        ).filterValues { it != null }
    }
    
    /**
     * Create crash payload with exact structure
     */
    private fun createCrashPayload(
        error: Throwable,
        stackTrace: String,
        attributes: Map<String, String>? = null
    ): Map<String, Any> {
        val timestamp = Instant.now().toString()
        val fingerprint = generateCrashFingerprint(error, stackTrace)
        val breadcrumbs = breadcrumbManager.getBreadcrumbsAsJson()
        
        return mapOf(
            "timestamp" to timestamp,
            "data" to mapOf(
                "type" to "error",
                "error" to "${error.javaClass.name}: ${error.message ?: ""}",
                "timestamp" to timestamp,
                "stackTrace" to stackTrace,
                "fingerprint" to fingerprint,
                "attributes" to buildCrashAttributes(fingerprint, breadcrumbs, attributes)
            )
        )
    }
    
    /**
     * Get enriched attributes combining global, session, user, and custom attributes
     */
    private fun getEnrichedAttributes(customAttributes: Map<String, String>? = null): Map<String, String> {
        val enriched = mutableMapOf<String, String>()
        
        // Add global attributes
        lock.read {
            enriched.putAll(globalAttributes)
        }
        
        // Add session attributes
        enriched.putAll(sessionManager.getSessionAttributes())
        
        // Add user attributes
        enriched.putAll(userProfileManager.getUserAttributes())
        
        // Add device attributes (from existing telemetry manager)
        enriched.putAll(getDeviceAttributes())
        
        // Add custom attributes (override others)
        customAttributes?.let { enriched.putAll(it) }
        
        return enriched
    }
    
    /**
     * Build crash-specific attributes
     */
    private fun buildCrashAttributes(
        fingerprint: String,
        breadcrumbs: String,
        additionalAttributes: Map<String, String>? = null
    ): Map<String, String> {
        val attributes = getEnrichedAttributes(additionalAttributes).toMutableMap()
        
        // Add crash-specific attributes
        attributes["crash.fingerprint"] = fingerprint
        attributes["crash.breadcrumb_count"] = breadcrumbManager.getBreadcrumbCount().toString()
        attributes["error.timestamp"] = Instant.now().toString()
        attributes["error.has_stack_trace"] = "true"
        attributes["breadcrumbs"] = breadcrumbs
        
        return attributes
    }
    
    /**
     * Get device attributes from device info collector
     */
    private fun getDeviceAttributes(): Map<String, String> {
        return deviceInfoCollector.collectDeviceInfo()
    }
    
    /**
     * Generate crash fingerprint using CrashFingerprinter
     */
    private fun generateCrashFingerprint(error: Throwable, stackTrace: String): String {
        return CrashFingerprinter.generateCrashFingerprint(error, stackTrace)
    }
    
    /**
     * Add event to batch queue
     */
    private fun addToBatch(eventData: Map<String, Any>) {
        eventQueue.offer(eventData)
        
        if (eventQueue.size >= batchSize) {
            sendBatch()
        }
    }
    
    /**
     * Send current batch
     */
    private fun sendBatch() {
        if (eventQueue.isEmpty()) return
        
        val events = mutableListOf<Map<String, Any>>()
        
        // Drain the queue
        while (events.size < batchSize && eventQueue.isNotEmpty()) {
            eventQueue.poll()?.let { events.add(it) }
        }
        
        if (events.isNotEmpty()) {
            scope.launch {
                try {
                    sendEventBatch(events)
                    Log.d(TAG, "📦 Batch sent: ${events.size} events")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send batch", e)
                    // Re-queue events for retry
                    events.forEach { eventQueue.offer(it) }
                }
            }
        }
        
        resetBatchTimer()
    }
    
    /**
     * Send event batch via telemetry manager
     */
    private suspend fun sendEventBatch(events: List<Map<String, Any>>) {
        // This should integrate with the existing TelemetryManager
        // For now, just log the batch
        Log.d(TAG, "Sending batch of ${events.size} events")
    }
    
    /**
     * Send crash data immediately
     */
    private suspend fun sendCrashImmediately(crashData: Map<String, Any>) {
        // This should integrate with the CrashRetryManager
        Log.d(TAG, "Sending crash data immediately")
    }
    
    /**
     * Start batch timeout timer
     */
    private fun startBatchTimer() {
        resetBatchTimer()
    }
    
    /**
     * Reset batch timeout timer
     */
    private fun resetBatchTimer() {
        timeoutTimer?.cancel()
        timeoutTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (eventQueue.isNotEmpty()) {
                        sendBatch()
                    }
                }
            }, batchTimeout.toMillis())
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        timeoutTimer?.cancel()
        scope.cancel()
        
        // Send any remaining events
        if (eventQueue.isNotEmpty()) {
            sendBatch()
        }
    }
}
