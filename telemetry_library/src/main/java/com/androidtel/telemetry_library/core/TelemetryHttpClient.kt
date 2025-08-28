package com.androidtel.telemetry_library.core

import android.util.Log
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryData
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.TelemetryPayload
import com.androidtel.telemetry_library.core.models.UserInfo
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

class TelemetryHttpClient(private val telemetryUrl:String, private val debugMode: Boolean ) {


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
        return sendWithRetry(batch.toPayload(), maxRetries = 3)
    }


    // Implementation of the retry strategy with exponential backoff.
    private suspend fun sendWithRetry( batch: TelemetryPayload, maxRetries: Int): Result<Unit> {
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


    fun TelemetryBatch.toPayload(): TelemetryPayload {
        return TelemetryPayload(
            timestamp = this.timestamp,
            data = TelemetryData(
                batchSize = this.batchSize,
                events = this.events.map { event ->
                    TelemetryEvent(
                        type = event.type,
                        eventName = event.eventName,
                        metricName = event.metricName,
                        value = event.value,
                        timestamp = event.timestamp,
                        attributes = EventAttributes(
                            app = AppInfo(
                                appBuildNumber = event.attributes.app.appBuildNumber,
                                appName = event.attributes.app.appName,
                                appPackageName = event.attributes.app.appPackageName,
                                appVersion = event.attributes.app.appVersion
                            ),
                            device = DeviceInfo(
                                androidRelease = event.attributes.device.androidRelease,
                                androidSdk = event.attributes.device.androidSdk,
                                brand = event.attributes.device.brand,
                                deviceId = event.attributes.device.deviceId,
                                fingerprint = event.attributes.device.fingerprint,
                                hardware = event.attributes.device.hardware,
                                manufacturer = event.attributes.device.manufacturer,
                                model = event.attributes.device.model,
                                platform = event.attributes.device.platform,
                                platformVersion = event.attributes.device.platformVersion,
                                product = event.attributes.device.product
                            ),
                            session = SessionInfo(
                                sessionId = event.attributes.session.sessionId,
                                startTime = event.attributes.session.startTime
                            ),
                            user = UserInfo(
                                email = event.attributes.user.email,
                                name = event.attributes.user.name,
                                phone = event.attributes.user.phone,
                                profileVersion = event.attributes.user.profileVersion,
                                userId = event.attributes.user.userId
                            ),
                            customAttributes = event.attributes.customAttributes
                        )
                    )
                }
            )
        )
    }

}