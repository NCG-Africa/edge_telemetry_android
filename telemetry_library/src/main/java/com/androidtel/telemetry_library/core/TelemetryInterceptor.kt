package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.trace.TraceManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

class TelemetryInterceptor(
    private val telemetryManager: TelemetryManager = TelemetryManager.getInstance(),
    private val telemetryEndpoint: String? = null
) : Interceptor {

    /** Parsed collector URL; the self-request guard matches host + path prefix against it. */
    private val collectorUrl: HttpUrl? = telemetryEndpoint?.toHttpUrlOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestUrl = request.url.toString()
        
        // Skip tracking if this is the SDK's own telemetry request to avoid infinite loop
        if (isTelemetryRequest(request.url)) {
            return chain.proceed(request)
        }
        
        // Distributed trace v2 (#109): thin adapter. Hand TraceManager the two request facts (host +
        // any inbound traceparent); it resolves the whole outcome ladder (allowlist gate, adoption,
        // unattributed, attributed) and returns the header action + the attrs to stamp on http.request.
        val decision = TraceManager.onNetworkCall(request.url.host, request.header("traceparent"))
        val outgoing = decision?.newHeader?.let {
            request.newBuilder().header("traceparent", it).build()
        } ?: request

        val startTime = System.nanoTime()
        var response: Response? = null

        try {
            response = chain.proceed(outgoing)
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
                decision?.let { putAll(it.attrs) }
            }

            telemetryManager.recordEvent(eventName = "http.request", attributes = attributes)
        }
    }
    
    /**
     * True only for the SDK's own export calls, so instrumenting a client that also posts telemetry
     * can't loop. Matched on the parsed URL — host plus the collector's path prefix — because an app
     * may host its API on the same origin as the collector (`/voting-api/...` vs `/collector/...`).
     *
     * Not a substring test: `url.contains("/telemetry")` also matched the `//` in
     * `https://telemetry.example.com/anything`, so every request to a `telemetry.*` host was skipped
     * — no `traceparent` injected and no `http.request` event emitted (fixed in 2.2.2).
     */
    private fun isTelemetryRequest(url: HttpUrl): Boolean {
        val collector = collectorUrl ?: return false
        return url.host.equals(collector.host, ignoreCase = true) &&
            url.encodedPath.startsWith(collector.encodedPath)
    }
}
