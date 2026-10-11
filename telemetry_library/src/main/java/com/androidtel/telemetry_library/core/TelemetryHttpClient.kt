package com.androidtel.telemetry_library.core

import android.util.Log
import com.androidtel.telemetry_library.BuildConfig
import com.androidtel.telemetry_library.core.interceptors.ApiKeyRedactionInterceptor
import com.androidtel.telemetry_library.core.models.AppOut
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SdkOut
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryDataOut
import com.androidtel.telemetry_library.core.models.TelemetryEventOut
import com.google.gson.Gson
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import java.util.concurrent.ThreadLocalRandom

// Custom exceptions for clearer error handling.
class ClientException(code: Int, message: String) : IOException("Client error $code: $message")
class ServerException(code: Int, message: String) : IOException("Server error $code: $message")
class UnknownException(code: Int) : IOException("Unknown HTTP error code: $code")

class TelemetryHttpClient(
    private val telemetryUrl: String,
    private val apiKey: String,
    private val debugMode: Boolean,
    // Static device-context bundle (issue #93), minted once at init. Stamped verbatim on every
    // outgoing event in flattenAttributes — the single chokepoint every event/crash/offline batch
    // passes through. Native types (Int/Boolean), not stringified. Empty for callers that omit it.
    private val staticDeviceContext: Map<String, Any?> = emptyMap()
) {


    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(ApiKeyRedactionInterceptor(debugMode))
        .addInterceptor(HttpLoggingInterceptor().apply {
            level =
                if (debugMode) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            redactHeader("X-API-Key")
        })
        .build()
    
    /**
     * Expose OkHttpClient for location provider and other internal uses
     */
    fun getOkHttpClient(): OkHttpClient = okHttpClient

    // Public method to send a batch with built-in retry logic.
    //
    // User profile is snapshotted into each event at recordEvent() time; the backend backfills
    // rum_users from event attributes so there's no need to override at serialization.
    suspend fun sendBatch(batch: TelemetryBatch): Result<Unit> {
        return sendWithRetry(batch, maxRetries = 3)
    }


    // Implementation of the retry strategy with exponential backoff.
    private suspend fun sendWithRetry(batch: TelemetryBatch, maxRetries: Int): Result<Unit> {
        repeat(maxRetries) { attempt ->
            try {
                val jsonPayload = batch.toJson()
                makeHttpRequest(jsonPayload, telemetryUrl).use { response ->
                    when (response.code) {
                        in 200..299 -> return Result.success(Unit)
                        in 400..499 -> {
                            Log.e(
                                "TelemetryHttpClient",
                                "Client error ${response.code}: ${response.message}"
                            )
                            return Result.failure(ClientException(response.code, response.message))
                        }

                        in 500..599 -> {
                            Log.e(
                                "TelemetryHttpClient",
                                "Server error ${response.code}: ${response.message}. Retrying..."
                            )

                            if (attempt < maxRetries - 1) {
                                delay(calculateBackoffDelay(attempt))
                            } else {
                                return Result.failure(ServerException(response.code, response.message))
                            }
                        }

                        else -> return Result.failure(UnknownException(response.code))
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "TelemetryHttpClient",
                    "Network error on attempt ${attempt + 1}: ${e.message}. Retrying...",
                    e
                )
                if (attempt < maxRetries - 1) {
                    delay(calculateBackoffDelay(attempt))
                } else {
                    return Result.failure(e)
                }
            }
        }
        return Result.failure(Exception("Max retries exceeded"))
    }

    // Calculates the backoff delay with jitter.
    private fun calculateBackoffDelay(attempt: Int): Long {
        val baseDelay = 1000L // 1 second
        return baseDelay * (2.0.pow(attempt).toLong()) + ThreadLocalRandom.current().nextLong(0, 1000)
    }

    // Performs the actual HTTP request.
    private fun makeHttpRequest(jsonPayload: String, telemetryUrl: String): Response {
        val request = Request.Builder()
            .url(telemetryUrl)
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "EdgeTelemetryAndroid/${BuildConfig.SDK_VERSION}")
            .addHeader("X-API-Key", apiKey)
            .build()
        return okHttpClient.newCall(request).execute()
    }


    // --- Extension: Convert Batch -> Outgoing JSON ---
    // Serializes the contract 4.0.0 envelope (#135). Identity is derived from the batch's own first
    // event, never the live process, so an offline-stored or frozen-crash batch is sent under the
    // build that recorded it (R14) and a 2.x-stored TelemetryBatch re-serializes without migration.
    fun TelemetryBatch.toJson(): String {
        val identity = events.first().attributes
        // CRITICAL: device_id is required at the root (R9); never send without it.
        check(identity.device.deviceId.isNotBlank()) {
            "CRITICAL ERROR: device_id is blank. IDs were not properly validated before sending."
        }
        val out = TelemetryDataOut(
            timestamp = this.timestamp,
            batch_size = this.batchSize,
            device_id = identity.device.deviceId,
            sdk = SdkOut(name = "android", version = BuildConfig.SDK_VERSION),
            app = AppOut(
                name = identity.app.appName,
                package_name = identity.app.appPackageName,
                platform = "android",
                version = identity.app.appVersion,
                build_number = identity.app.appBuildNumber
            ),
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
        return Gson().toJson(out)
    }

    private fun isIdentityKey(key: String): Boolean =
        key.startsWith("sdk.") || key == "device.id" || key in APP_IDENTITY_KEYS

    // ---- Helper: Flatten attributes into map ----
    private fun flattenAttributes(attrs: EventAttributes): Map<String, Any?> {
        val flat = mutableMapOf<String, Any?>()

        // No sdk.*, app.* or device.id here: identity lives in the envelope only (#135 R1).
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

        // Static device-context bundle (issue #93) — 9 launch-time device.* keys, native types.
        flat.putAll(staticDeviceContext)

        // User - CRITICAL: user.id must never be null or empty
        // This should never happen due to validation in TelemetryManager.sendBatch()
        if (attrs.user.userId.isBlank()) {
            throw IllegalStateException(
                "CRITICAL ERROR: user.id is blank in event attributes. " +
                "This indicates IDs were not properly validated before sending."
            )
        }
        flat["user.id"] = attrs.user.userId

        // User profile is snapshotted at recordEvent() time. Backend backfills rum_users from event
        // attributes (see event_processor.py commit e6ab501 — profile persists on every event with
        // user.* keys), so the SDK no longer needs to overwrite at serialization.
        attrs.user.name?.let { flat["user.name"] = it }
        attrs.user.email?.let { flat["user.email"] = it }
        attrs.user.phone?.let { flat["user.phone"] = it }

        // Session (extended)
        flat["session.id"] = attrs.session.sessionId
        flat["session.start_time"] = attrs.session.startTime
        flat["session.duration_ms"] = attrs.session.durationMs
        flat["session.event_count"] = attrs.session.eventCount
        flat["session.metric_count"] = attrs.session.metricCount
        flat["session.screen_count"] = attrs.session.screenCount
        flat["session.visited_screens"] = attrs.session.visitedScreens
        flat["session.is_first_session"] = attrs.session.isFirstSession
        flat["session.total_sessions"] = attrs.session.totalSessions
        flat["network.type"] = attrs.session.networkType



        // Custom — identity keys are stripped, never sent: the envelope wins (R1). Exact app.* keys
        // only, so event attrs like app.start.type survive.
        attrs.customAttributes.filterKeys { !isIdentityKey(it) }.let(flat::putAll)

        return flat
    }

    private companion object {
        val APP_IDENTITY_KEYS = setOf(
            "app.name", "app.version", "app.build_number", "app.package_name", "app.platform", "app.environment"
        )
    }
}
