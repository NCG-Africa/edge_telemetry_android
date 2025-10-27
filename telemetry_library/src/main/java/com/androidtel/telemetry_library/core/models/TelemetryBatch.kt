package com.androidtel.telemetry_library.core.models

import com.google.gson.Gson
import java.util.UUID

// ---- Payload + Data ----
data class TelemetryPayload(
    val timestamp: String,
    val data: TelemetryDataOut
) {
    fun toJson(): String = Gson().toJson(this)
}

data class TelemetryDataOut(
    val type: String = "batch",
    val device_id: String,
    val events: List<TelemetryEventOut>,
    val batch_size: Int,
    val timestamp: String
)

// ---- Flattened Event ----
data class TelemetryEventOut(
    val type: String,
    val eventName: String? = null,
    val metricName: String? = null,
    val value: Double? = null,
    val timestamp: String,
    val attributes: Map<String, Any?>
)

// ---- Original Batch ----
data class TelemetryBatch(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "telemetry_batch",
    val batchSize: Int,
    val timestamp: String,
    val events: List<TelemetryEvent>
)

// ---- Original Event ----
data class TelemetryEvent(
    val type: String,
    val metricName: String? = null,
    val eventName: String? = null,
    val value: Double? = null,
    val timestamp: String,
    val attributes: EventAttributes
)

// ---- Original Attributes ----
data class EventAttributes(
    val app: AppInfo,
    val device: DeviceInfo,
    val user: UserInfo,
    val session: SessionInfo,
    val customAttributes: Map<String, Any>
)

data class AppInfo(
    val appName: String,
    val appVersion: String,
    val appBuildNumber: String,
    val appPackageName: String
)

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

data class UserInfo(
    val userId: String,  // Non-nullable - always required
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val profileVersion: Int? = null
)

data class SessionInfo(
    val sessionId: String,                       // "session.id"
    val startTime: String? = null,               // "session.start_time"
    val durationMs: Long? = null,                // "session.duration_ms"
    val eventCount: Int? = null,                 // "session.event_count"
    val metricCount: Int? = null,                // "session.metric_count"
    val screenCount: Int? = null,                // "session.screen_count"
    val visitedScreens: String? = null,          // "session.visited_screens"
    val isFirstSession: Boolean? = null,         // "session.is_first_session"
    val totalSessions: Int? = null,              // "session.total_sessions"
    val networkType: String? = null              // "network.type"
)

data class MemoryEventInfo(
    val usageMb: Double,                // "memory.usage_mb"
    val pressureLevel: String,          // "memory.pressure_level"
    val timestamp: String               // "memory.timestamp"
)

data class MemoryMetricInfo(
    val unit: String,                   // "metric.unit"
    val type: String,                   // "memory.type"
    val source: String                  // "memory.source"
)

