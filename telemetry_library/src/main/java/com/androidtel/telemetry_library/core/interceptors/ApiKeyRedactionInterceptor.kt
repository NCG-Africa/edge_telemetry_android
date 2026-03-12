package com.androidtel.telemetry_library.core.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import android.util.Log

/**
 * OkHttp interceptor that redacts API keys in logs for security.
 * 
 * Shows only the first 4 and last 4 characters of the API key value,
 * replacing the middle portion with asterisks for security while
 * maintaining debuggability.
 * 
 * Example: "edge_1234567890abcdef_xyz123" -> "edge_**************_xyz1"
 * 
 * @property debugMode Whether to enable verbose logging
 */
class ApiKeyRedactionInterceptor(
    private val debugMode: Boolean = false
) : Interceptor {
    
    companion object {
        private const val TAG = "ApiKeyRedaction"
        private const val API_KEY_HEADER = "X-API-Key"
        private const val MIN_LENGTH_FOR_PARTIAL = 12
        private const val VISIBLE_CHARS = 4
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        if (debugMode) {
            val apiKey = originalRequest.header(API_KEY_HEADER)
            if (apiKey != null) {
                val redactedKey = redactApiKey(apiKey)
                Log.d(TAG, "API Key: $redactedKey")
            }
        }
        
        return chain.proceed(originalRequest)
    }
    
    /**
     * Redacts an API key by showing only first and last 4 characters.
     * 
     * @param apiKey The API key to redact
     * @return Redacted API key string
     */
    private fun redactApiKey(apiKey: String): String {
        return when {
            apiKey.length < MIN_LENGTH_FOR_PARTIAL -> {
                // Too short to partially redact, fully redact
                "████████"
            }
            else -> {
                val firstPart = apiKey.take(VISIBLE_CHARS)
                val lastPart = apiKey.takeLast(VISIBLE_CHARS)
                val middleLength = apiKey.length - (VISIBLE_CHARS * 2)
                val redactedMiddle = "*".repeat(middleLength.coerceAtLeast(4))
                "$firstPart$redactedMiddle$lastPart"
            }
        }
    }
}
