package com.androidtel.telemetry_library.core

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelemetryInterceptor(private val telemetryManager: TelemetryManager = TelemetryManager.getInstance()) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.nanoTime()
        var response: Response? = null
        var error: IOException? = null

        try {
            response = chain.proceed(request)
            return response
        } catch (e: IOException) {
            error = e
            throw e
        } finally {
            val endTime = System.nanoTime()
            val durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime)
            val requestBodySize = request.body?.contentLength() ?: 0
            val responseBodySize = response?.body?.contentLength() ?: 0
            val statusCode = response?.code ?: 0

            telemetryManager.recordNetworkRequest(
                url = request.url.toString(),
                method = request.method,
                statusCode = statusCode,
                durationMs = durationMs,
                requestBodySize = requestBodySize,
                responseBodySize = responseBodySize,
                error = error?.message
            )
        }
    }
}

/*

class TelemetryInterceptor(private val telemetryManager: TelemetryManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        try {
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startTime
            telemetryManager.recordNetworkRequest(
                url = request.url.toString(),
                method = request.method,
                statusCode = response.code,
                durationMs = durationMs,
                requestBodySize = request.body?.contentLength() ?: 0,
                responseBodySize = response.body?.contentLength() ?: 0
            )
            return response
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            telemetryManager.recordNetworkRequest(
                url = request.url.toString(),
                method = request.method,
                statusCode = 0, // Use 0 or a specific code for network errors
                durationMs = durationMs,
                requestBodySize = request.body?.contentLength() ?: 0,
                responseBodySize = 0,
                error = e.message
            )
            throw e
        }
    }
}*/
