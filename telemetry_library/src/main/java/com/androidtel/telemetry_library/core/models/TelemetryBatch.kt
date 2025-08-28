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
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val profileVersion: Int? = null
)

data class SessionInfo(
    val sessionId: String,
    val startTime: String? = null
)


// ---- Extension: Convert Batch -> Payload ----
fun TelemetryBatch.toPayload(): TelemetryPayload {
    return TelemetryPayload(
        timestamp = this.timestamp,
        data = TelemetryDataOut(
            type = "batch",
            batch_size = this.batchSize,
            timestamp = this.timestamp,
            events = this.events.map { event ->
                TelemetryEventOut(
                    type = event.type,
                    eventName = event.eventName,
                    metricName = event.metricName,
                    value = event.value,
                    timestamp = event.timestamp,
                    attributes = flattenAttributes(event.attributes)
                )
            }
        )
    )
}

// ---- Helper: Flatten attributes into map ----
private fun flattenAttributes(attrs: EventAttributes): Map<String, Any?> {
    val flat = mutableMapOf<String, Any?>()

    // App
    flat["app.name"] = attrs.app.appName
    flat["app.version"] = attrs.app.appVersion
    flat["app.build_number"] = attrs.app.appBuildNumber
    flat["app.package_name"] = attrs.app.appPackageName

    // Device
    flat["device.id"] = attrs.device.deviceId
    flat["device.platform"] = attrs.device.platform
    flat["device.platform_version"] = attrs.device.platformVersion
    flat["device.model"] = attrs.device.model
    flat["device.manufacturer"] = attrs.device.manufacturer
    flat["device.brand"] = attrs.device.brand
    flat["device.android_sdk"] = attrs.device.androidSdk
    flat["device.android_release"] = attrs.device.androidRelease
    flat["device.fingerprint"] = attrs.device.fingerprint
    flat["device.hardware"] = attrs.device.hardware
    flat["device.product"] = attrs.device.product

    // User
    flat["user.id"] = attrs.user.userId
    flat["user.name"] = attrs.user.name
    flat["user.email"] = attrs.user.email
    flat["user.phone"] = attrs.user.phone
    flat["user.profile_version"] = attrs.user.profileVersion

    // Session
    flat["session.id"] = attrs.session.sessionId
    flat["session.start_time"] = attrs.session.startTime

    // Custom
    flat.putAll(attrs.customAttributes)

    return flat
}
