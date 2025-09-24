package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.TelemetryManager

/**
 * EdgeTelemetry - Main facade class for the Edge Telemetry SDK
 * Provides a simplified interface to the underlying TelemetryManager
 */
object EdgeTelemetry {
    
    /**
     * Get the singleton instance of TelemetryManager
     */
    fun getInstance(): TelemetryManager {
        return TelemetryManager.getInstance()
    }
    
    /**
     * Initialize the EdgeTelemetry SDK
     */
    fun initialize(
        application: android.app.Application,
        endpoint: String,
        batchSize: Int = 10,
        enableDebugLogging: Boolean = false
    ) {
        TelemetryManager.initialize(application, batchSize, endpoint, enableDebugLogging)
    }
    
    /**
     * Track a custom event
     */
    fun trackEvent(eventName: String, attributes: Map<String, String>? = null) {
        getInstance().recordEvent(eventName, attributes ?: emptyMap())
    }
    
    /**
     * Track a custom metric
     */
    fun trackMetric(metricName: String, value: Double, attributes: Map<String, String>? = null) {
        getInstance().recordMetric(metricName, value, attributes ?: emptyMap())
    }
    
    /**
     * Add a breadcrumb
     */
    fun addBreadcrumb(
        message: String,
        category: String = "custom",
        level: String = "info",
        data: Map<String, String>? = null
    ) {
        getInstance().addBreadcrumb(message, category, level, data)
    }
    
    /**
     * Test crash reporting functionality
     */
    fun testCrashReporting(customMessage: String? = null) {
        getInstance().testCrashReporting(customMessage)
    }
    
    /**
     * Test connectivity
     */
    fun testConnectivity() {
        getInstance().testConnectivity()
    }
    
    /**
     * Start a new session (for testing)
     */
    fun startSession() {
        // Session management is handled automatically by TelemetryManager
        getInstance().recordEvent("session.start", mapOf("manual" to "true"))
    }
    
    /**
     * End current session (for testing)
     */
    fun endSession() {
        // Session management is handled automatically by TelemetryManager
        getInstance().recordEvent("session.end", mapOf("manual" to "true"))
    }
}