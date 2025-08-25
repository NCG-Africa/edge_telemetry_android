package com.androidtel.telemetry_library.core.models

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.UUID

// Data class to represent a batch of telemetry events.
data class TelemetryBatch(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "telemetry_batch",
    val batchSize: Int,
    val timestamp: String,
    val events: List<TelemetryEvent>
) {
    // Convenience method for JSON serialization.
    fun toJson(): String {
        return Gson().toJson(this)
    }
}

// Data class to represent a single telemetry event.
data class TelemetryEvent(
    val type: String,
    val metricName: String? = null,
    val eventName: String? = null,
    val value: Double? = null,
    val timestamp: String,
    val attributes: EventAttributes
)

// Data class to hold all event attributes, including app, device, user, and session info.
data class EventAttributes(
    val app: AppInfo,
    val device: DeviceInfo,
    val user: UserInfo,
    val session: SessionInfo,
    val customAttributes: Map<String, Any>
)

// Data class for application information.
data class AppInfo(
    val appName: String,
    val appVersion: String,
    val appBuildNumber: String,
    val appPackageName: String
)

// Data class for device information.
data class DeviceInfo(
    val deviceId: String,
    val platform: String,
    val platformVersion: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val androidSdk: String,
    val androidRelease: String,
    val fingerprint: String,
    val hardware: String,
    val product: String
)

// Data class for user information.
data class UserInfo(
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val profileVersion: Int? = null
)

// Data class for session information.
data class SessionInfo(
    val sessionId: String,
    val startTime: String? = null
)


// Data class to represent a batch of telemetry events.
/*
data class TelemetryBatch(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "telemetry_batch",
    val batchSize: Int,
    val timestamp: String,
    val events: List<TelemetryEvent>
) {
    // Convenience method for JSON serialization.
    fun toJson(): String {
        return Gson().toJson(this)
    }
}

// Data class to represent a single telemetry event.
data class TelemetryEvent(
    val type: String,
    val metricName: String? = null,
    val eventName: String? = null,
    val value: Double? = null,
    val timestamp: String,
    val attributes: EventAttributes
)

// Data class to hold all event attributes, including app, device, user, and session info.
data class EventAttributes(
    val app: AppInfo,
    val device: DeviceInfo,
    val user: UserInfo,
    val session: SessionInfo,
    val customAttributes: Map<String, Any>
)

// Data class for application information.
data class AppInfo(
    val appName: String,
    val appVersion: String,
    val appBuildNumber: String,
    val appPackageName: String
)

// Data class for device information.
data class DeviceInfo(
    val deviceId: String,
    val platform: String,
    val platformVersion: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val androidSdk: String,
    val androidRelease: String,
    val fingerprint: String,
    val hardware: String,
    val product: String
)

// Data class for user information.
data class UserInfo(
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val profileVersion: Int? = null
)

// Data class for session information.
data class SessionInfo(
    val sessionId: String,
    val startTime: String? = null
)
*/

