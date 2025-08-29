package com.androidtel.telemetry_library.core

import android.util.Log
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryDataOut
import com.androidtel.telemetry_library.core.models.TelemetryEventOut
import com.androidtel.telemetry_library.core.models.TelemetryPayload
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
import kotlin.random.Random

// Custom exceptions for clearer error handling.
class ClientException(code: Int, message: String) : IOException("Client error $code: $message")
class ServerException(code: Int, message: String) : IOException("Server error $code: $message")
class UnknownException(code: Int) : IOException("Unknown HTTP error code: $code")

class TelemetryHttpClient(private val telemetryUrl: String, private val debugMode: Boolean) {


    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level =
                if (debugMode) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    // Public method to send a batch with built-in retry logic.
    suspend fun sendBatch(batch: TelemetryBatch): Result<Unit> {
        return sendWithRetry(batch , maxRetries = 3)
    }


    // Implementation of the retry strategy with exponential backoff.
    private suspend fun sendWithRetry(batch: TelemetryBatch, maxRetries: Int): Result<Unit> {
        repeat(maxRetries) { attempt ->
            try {
                val jsonPayload = batch.toJson()
                val response = makeHttpRequest(jsonPayload, telemetryUrl)

                return when (response.code) {
                    in 200..299 -> Result.success(Unit)
                    in 400..499 -> {
                        Log.e(
                            "TelemetryHttpClient",
                            "Client error ${response.code}: ${response.message}"
                        )
                        Result.failure(ClientException(response.code, response.message))
                    }

                    in 500..599 -> {
                        Log.e(
                            "TelemetryHttpClient",
                            "Server error ${response.code}: ${response.message}. Retrying..."
                        )

                        while (attempt < maxRetries - 1) {
                            delay(calculateBackoffDelay(attempt))
                        }

                        return Result.failure(ServerException(response.code, response.message))
                    }

                    else -> Result.failure(UnknownException(response.code))
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
        return baseDelay * (2.0.pow(attempt).toLong()) + Random.nextLong(0, 1000)
    }

    // Performs the actual HTTP request.
    private fun makeHttpRequest(jsonPayload: String, telemetryUrl: String): Response {
        val request = Request.Builder()
            .url(telemetryUrl)
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "EdgeTelemetryAndroid/1.0.0")
            .build()
        return okHttpClient.newCall(request).execute()
    }


    // --- Extension: Convert Batch -> Outgoing JSON ---
    fun TelemetryBatch.toJson(): String {
        val out = TelemetryDataOut(
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
        return Gson().toJson(out)
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



        // Custom
        flat.putAll(attrs.customAttributes)

        return flat
    }

}