package com.androidtel.telemetry_library.core.trace

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Δ6 — captures the trace context where the caller still owns the thread.
 *
 * OkHttp runs interceptors on its dispatcher pool (`RealCall.enqueue` hands an `AsyncCall` to the
 * pool, whose `run()` invokes `getResponseWithInterceptorChain()`), so an interceptor reading the
 * [ThreadLocal] carrier sees null for every Retrofit `suspend` function, every `enqueue()`, and every
 * callback-style call. `newCall()` runs on the **caller's** thread, so the context is still there.
 *
 * The tag is stamped unconditionally, null included — that is what makes a missing wiring detectable
 * (`injected_unwired`) without sniffing OkHttp thread names.
 *
 * Chosen over an `EventListener.Factory` capturing into a `Call`-keyed map: `eventListenerFactory` is
 * a single slot that would clobber any listener the app already set, and the map would need cleanup on
 * `callEnd`/`callFailed` or it leaks. A tag has no cleanup surface and survives retries and redirects.
 */
internal class TracingCallFactory(private val delegate: OkHttpClient) : Call.Factory {
    override fun newCall(request: Request): Call = delegate.newCall(
        request.newBuilder()
            .tag(TraceTag::class.java, TraceTag(TraceManager.current()))
            .build()
    )
}
