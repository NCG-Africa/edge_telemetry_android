package com.androidtel.telemetry_library.core

import okhttp3.Interceptor
import okhttp3.Response
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

        try {
            response = chain.proceed(request)
            return response
        } finally {
            val endTime = System.nanoTime()
            val durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime)
            val statusCode = response?.code ?: 0

            val attributes = buildMap<String, Any> {
                put("http.url", requestUrl.substringBefore('?'))
                put("http.method", request.method)
                put("http.status_code", statusCode)
                put("http.duration_ms", durationMs)
                put("http.success", statusCode in 200..299)
                // Omit size fields when contentLength() < 0 (chunked/streamed): a true
                // "optional pair", so the backend column stays null rather than a false 0.
                request.body?.contentLength()?.takeIf { it >= 0 }?.let { put("http.request_size", it) }
                response?.body?.contentLength()?.takeIf { it >= 0 }?.let { put("http.response_size", it) }
            }

            telemetryManager.recordEvent(eventName = "http.request", attributes = attributes)
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
