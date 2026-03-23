package com.androidtel.telemetry_library.core

import android.app.Application

/**
 * Centralized configuration for EdgeTelemetry SDK
 * 
 * Provides a type-safe, immutable configuration object for SDK initialization.
 * Supports both builder pattern and direct instantiation.
 * 
 * @property application Application context
 * @property apiKey API key for authentication (required, must start with "edge_")
 * @property batchSize Number of events to batch before sending (default: 30)
 * @property endpoint Telemetry endpoint URL
 * @property debugMode Enable debug logging (default: false)
 * @property enableCrashReporting Enable automatic crash reporting (default: true)
 * @property enableUserProfiles Enable user profile tracking (default: true)
 * @property enableSessionTracking Enable session tracking (default: true)
 * @property globalAttributes Global attributes to include in all events (default: empty)
 * @property enableLocationTracking Enable IP-based location tracking (default: true)
 * @property locationApiEndpoint API endpoint for location service (default: ipinfo.io)
 * @property locationCacheDuration Duration to cache location in milliseconds (default: 1 hour)
 * @property locationFallbackToIp Send IP address if location API fails (default: true)
 */
data class TelemetryConfig(
    val application: Application,
    val apiKey: String,
    val batchSize: Int = 30,
    val endpoint: String = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
    val debugMode: Boolean = false,
    val enableCrashReporting: Boolean = true,
    val enableUserProfiles: Boolean = true,
    val enableSessionTracking: Boolean = true,
    val globalAttributes: Map<String, String> = emptyMap(),
    val enableLocationTracking: Boolean = true,
    val locationApiEndpoint: String = "https://ipinfo.io/json",
    val locationCacheDuration: Long = 3600000,
    val locationFallbackToIp: Boolean = true
) {
    
    init {
        require(apiKey.isNotBlank()) { "API key cannot be blank" }
        require(apiKey.startsWith("edge_")) { "API key is invalid" }
        require(batchSize > 0) { "Batch size must be greater than 0" }
        require(endpoint.isNotBlank()) { "Endpoint cannot be blank" }
    }
    
    /**
     * Builder for TelemetryConfig with fluent API
     */
    class Builder(
        private val application: Application,
        private val apiKey: String
    ) {
        private var batchSize: Int = 30
        private var endpoint: String = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
        private var debugMode: Boolean = false
        private var enableCrashReporting: Boolean = true
        private var enableUserProfiles: Boolean = true
        private var enableSessionTracking: Boolean = true
        private var globalAttributes: Map<String, String> = emptyMap()
        private var enableLocationTracking: Boolean = true
        private var locationApiEndpoint: String = "https://ipinfo.io/json"
        private var locationCacheDuration: Long = 3600000
        private var locationFallbackToIp: Boolean = true
        
        fun batchSize(size: Int) = apply { this.batchSize = size }
        fun endpoint(url: String) = apply { this.endpoint = url }
        fun debugMode(enabled: Boolean) = apply { this.debugMode = enabled }
        fun enableCrashReporting(enabled: Boolean) = apply { this.enableCrashReporting = enabled }
        fun enableUserProfiles(enabled: Boolean) = apply { this.enableUserProfiles = enabled }
        fun enableSessionTracking(enabled: Boolean) = apply { this.enableSessionTracking = enabled }
        fun globalAttributes(attributes: Map<String, String>) = apply { this.globalAttributes = attributes }
        fun enableLocationTracking(enabled: Boolean) = apply { this.enableLocationTracking = enabled }
        fun locationApiEndpoint(endpoint: String) = apply { this.locationApiEndpoint = endpoint }
        fun locationCacheDuration(duration: Long) = apply { this.locationCacheDuration = duration }
        fun locationFallbackToIp(enabled: Boolean) = apply { this.locationFallbackToIp = enabled }
        
        fun build() = TelemetryConfig(
            application = application,
            apiKey = apiKey,
            batchSize = batchSize,
            endpoint = endpoint,
            debugMode = debugMode,
            enableCrashReporting = enableCrashReporting,
            enableUserProfiles = enableUserProfiles,
            enableSessionTracking = enableSessionTracking,
            globalAttributes = globalAttributes,
            enableLocationTracking = enableLocationTracking,
            locationApiEndpoint = locationApiEndpoint,
            locationCacheDuration = locationCacheDuration,
            locationFallbackToIp = locationFallbackToIp
        )
    }
    
    companion object {
        /**
         * Create a builder for TelemetryConfig
         */
        fun builder(application: Application, apiKey: String) = Builder(application, apiKey)
    }
}
