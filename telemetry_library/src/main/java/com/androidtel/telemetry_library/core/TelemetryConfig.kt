package com.androidtel.telemetry_library.core

data class TelemetryConfig(
    val apiKey: String,
    val endpoint: String,
    val batchSize: Int = 50,
    val flushIntervalMs: Long = 30_000L,
    val sessionTimeoutMs: Long = 30 * 60 * 1000L,
    val enableScreenTracking: Boolean = true,
    val enableCrashReporting: Boolean = true,
    val enableNetworkTracking: Boolean = true,
    val enableLifecycleTracking: Boolean = true,
    val enableMemoryTracking: Boolean = true,
    val enableStorageTracking: Boolean = true,
    val enableFrameTracking: Boolean = true,
    val enableLegacyScreenEvents: Boolean = false,
    val enableUserInteractionEvents: Boolean = true,
    val enableCapabilityEvents: Boolean = true,
    val enableUserProfiles: Boolean = true,
    val enableSessionTracking: Boolean = true,
    val enableLocationTracking: Boolean = false
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(apiKey.startsWith("edge_")) { "apiKey must start with 'edge_'" }
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        require(batchSize > 0) { "batchSize must be > 0" }
        require(flushIntervalMs > 0) { "flushIntervalMs must be > 0" }
        require(sessionTimeoutMs > 0) { "sessionTimeoutMs must be > 0" }
    }
}
