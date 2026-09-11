package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.trace.TraceManager
import com.androidtel.telemetry_library.core.trace.TraceTag
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
        
        // Distributed trace v3 (#120): thin adapter. Hand TraceManager the three request facts (host,
        // any inbound traceparent, and the Δ6 tag stamped at newCall()); it resolves the whole outcome
        // ladder (allowlist gate, adoption, unwired, expired, unattributed, attributed) and returns the
        // header action, the attrs to stamp on http.request, and the root to extend on completion.
        val decision = TraceManager.onNetworkCall(
            request.url.host,
            request.header("traceparent"),
            request.tag(TraceTag::class.java)
        )
        val outgoing = decision?.newHeader?.let {
            request.newBuilder().header("traceparent", it).build()
        } ?: request

        // Δ10 — the span's START, captured as a real epoch instant. nanoTime is monotonic and NOT a
        // wall clock, so span.start_time can never be back-computed from the delta; the delta is still
        // the right clock for the duration.
        val startEpochMs = System.currentTimeMillis()
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
                decision?.let {
                    putAll(it.attrs)
                    // Δ10 — this event's timestamp is the span's END (it is emitted after the response),
                    // so both ends go on the wire explicitly. No consumer has to know the convention.
                    put("span.start_time", TelemetryTime.isoOf(startEpochMs))
                    put("span.duration_ms", durationMs)
                }
            }

            // Δ7 — completion-extension, written from OkHttp's pool thread. This is why the root's
            // last-activity field is an AtomicLong rather than anything ThreadLocal: a chained flow
            // (tap → A takes 4 s → B fires on A's success) must keep both calls under one root.
            decision?.root?.touch(TraceManager.clock())

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
