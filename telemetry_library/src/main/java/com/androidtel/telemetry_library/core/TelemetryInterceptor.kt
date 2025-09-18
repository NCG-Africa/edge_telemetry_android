package com.androidtel.telemetry_library.core

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelemetryInterceptor(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance(),
    private val telemetryEndpoint: String? = null
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestUrl = request.url.toString()
        
        // Skip tracking if this is the SDK's own telemetry request to avoid infinite loop
        if (isTelemetryRequest(requestUrl)) {
            return chain.proceed(request)
        }
        
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
                url = requestUrl,
                method = request.method,
                statusCode = statusCode,
                durationMs = durationMs,
                requestBodySize = requestBodySize,
                responseBodySize = responseBodySize,
                error = error?.message
            )
        }
    }
    
    /**
     * Check if the request URL is a telemetry endpoint to avoid tracking SDK's own requests
     */
    private fun isTelemetryRequest(url: String): Boolean {
        // Check against configured endpoint
        telemetryEndpoint?.let { endpoint ->
            if (url.startsWith(endpoint)) return true
        }
        
        // Check against default SDK endpoint
        if (url.contains("edgetelemetry.ncgafrica.com/collector/telemetry")) {
            return true
        }
        
        // Check for common telemetry endpoint patterns
        return url.contains("/telemetry") || url.contains("/collector")
    }
}
