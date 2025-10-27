package com.androidtel.telemetry_library.core.http

import android.util.Log
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.events.JsonEventTracker
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.time.Instant

/**
 * Enhanced HTTP Interceptor for EdgeTelemetry that tracks HTTP requests
 * and adds breadcrumbs with comprehensive monitoring
 */
class EdgeTelemetryInterceptor(
    private val eventTracker: JsonEventTracker,
    private val breadcrumbManager: BreadcrumbManager,
    private val telemetryEndpoint: String
) : Interceptor {
    
    companion object {
        private const val TAG = "EdgeTelemetryInterceptor"
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val url = request.url.toString()
        
        // Skip tracking SDK's own requests to avoid infinite loops
        if (url.contains(telemetryEndpoint)) {
            return chain.proceed(request)
        }
        
        // Add request breadcrumb
        breadcrumbManager.addNetwork(
            "HTTP ${request.method} started",
            mapOf(
                "url" to url,
                "method" to request.method,
                "timestamp" to Instant.now().toString()
            )
        )
        
        try {
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime
            
            // Track successful HTTP request
            trackHttpRequest(request, response, duration, null)
            
            // Add success breadcrumb
            breadcrumbManager.addNetwork(
                "HTTP ${request.method} completed",
                mapOf(
                    "url" to url,
                    "method" to request.method,
                    "status_code" to response.code.toString(),
                    "duration_ms" to duration.toString()
                )
            )
            
            Log.d(TAG, "✅ HTTP ${request.method} $url - ${response.code} (${duration}ms)")
            
            return response
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            
            // Track failed HTTP request
            trackHttpRequest(request, null, duration, e)
            
            // Add error breadcrumb
            breadcrumbManager.addNetwork(
                "HTTP ${request.method} failed",
                mapOf<String, String>(
                    "url" to url,
                    "method" to request.method,
                    "error" to (e.message ?: "Unknown error"),
                    "duration_ms" to duration.toString()
                )
            )
            
            Log.e(TAG, "❌ HTTP ${request.method} $url failed after ${duration}ms", e)
            
            throw e
        }
    }
    
    /**
     * Track HTTP request as telemetry event
     */
    private fun trackHttpRequest(
        request: okhttp3.Request,
        response: Response?,
        duration: Long,
        error: Exception?
    ) {
        val url = request.url.toString()
        val method = request.method
        
        val attributes = mutableMapOf<String, String>(
            "http.url" to url,
            "http.method" to method,
            "http.duration_ms" to duration.toString(),
            "network.type" to "http"
        )
        
        if (response != null) {
            attributes["http.status_code"] = response.code.toString()
            attributes["http.success"] = "true"
            
            // Track as performance metric
            eventTracker.trackMetric(
                "http.request_duration",
                duration.toDouble(),
                attributes
            )
            
            // Track as event
            eventTracker.trackEvent("http.request_completed", attributes)
        } else if (error != null) {
            attributes["http.success"] = "false"
            attributes["http.error"] = error.javaClass.simpleName
            attributes["http.error_message"] = error.message ?: "Unknown error"
            
            // Track as error event
            eventTracker.trackEvent("http.request_failed", attributes)
        }
    }
}
