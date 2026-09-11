package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.trace.TraceManager
import com.androidtel.telemetry_library.core.trace.TracingCallFactory
import io.mockk.mockk
import io.mockk.slot
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TelemetryInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var host: String
    private var appClient: OkHttpClient? = null
    private val telemetryManager: TelemetryManager = mockk(relaxed = true)
    private val recorded = slot<Map<String, Any>>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        host = server.hostName
        client = OkHttpClient.Builder()
            .addInterceptor(TelemetryInterceptor(telemetryManager))
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        io.mockk.every { telemetryManager.recordEvent("http.request", capture(recorded)) } returns Unit
        TraceManager.resetForTesting()
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = emptySet()
    }

    @After
    fun teardown() {
        server.shutdown()
        // Release non-daemon OkHttp threads so the suite doesn't hang (see takerequest gotcha).
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        appClient?.dispatcher?.executorService?.shutdown()
        appClient?.connectionPool?.evictAll()
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = emptySet()
        TraceManager.onBackground()
    }

    private fun allowServer() {
        TraceManager.traceHostAllowlist = setOf(host.lowercase())
    }

    @Test
    fun `auto HTTP emits http dot keys, 2xx success, no query string`() {
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(
            Request.Builder().url(server.url("/pay?token=SECRET&acct=123")).build()
        ).execute().close()

        val a = recorded.captured
        assertTrue("http.status_code present", a.containsKey("http.status_code"))
        assertEquals(200, a["http.status_code"])
        assertEquals(true, a["http.success"])
        assertFalse("no query on the wire", (a["http.url"] as String).contains("?"))
        assertFalse("no token leak", (a["http.url"] as String).contains("SECRET"))
        assertFalse("no legacy http.error key", a.containsKey("http.error"))
    }

    @Test
    fun `transport failure emits status 0, success false, still recorded`() {
        runCatching {
            client.newCall(Request.Builder().url("http://127.0.0.1:1/x").build()).execute()
        }
        assertEquals(0, recorded.captured["http.status_code"])
        assertEquals(false, recorded.captured["http.success"])
        assertFalse(recorded.captured.containsKey("http.error"))
    }

    @Test
    fun `on-allowlist active root injects traceparent and stamps child span attrs`() {
        allowServer()
        val root = TraceManager.onInteractionStart(null)!!
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val sent = server.takeRequest().getHeader("traceparent")!!
        assertTrue("well-formed", Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent))
        assertTrue("carries the root trace id", sent.contains(root.traceId))

        val a = recorded.captured
        assertEquals(root.traceId, a["trace.id"])
        assertEquals(root.spanId, a["parent.span.id"])
        assertEquals("injected_attributed", a["traceparent.outcome"])
        assertTrue(sent.contains(a["span.id"] as String))
        // Delta 10 - both ends of the span on the wire, since this event's timestamp is the END.
        assertTrue(a.containsKey("span.start_time"))
        assertTrue(a.containsKey("span.duration_ms"))
    }

    @Test
    fun `off-allowlist suppresses the header but still stamps trace attrs`() {
        // allowlist empty → server host off-allowlist
        TraceManager.onInteractionStart(null)
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull("no header off-allowlist", server.takeRequest().getHeader("traceparent"))
        val a = recorded.captured
        assertTrue("still recorded locally", a.containsKey("trace.id"))
        assertEquals("skipped_off_allowlist", a["traceparent.outcome"])
    }

    @Test
    fun `bare interceptor with no action reports unwired, not unattributed`() {
        allowServer() // no root active, and no Call.Factory wired
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val sent = server.takeRequest().getHeader("traceparent")!!
        assertTrue(Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent))
        val a = recorded.captured
        assertTrue(a.containsKey("trace.id"))
        // Delta 6 - no request tag AND no carrier means instrument() was never wired. That is a
        // different fact from "tag present, genuinely no action open", and the enum keeps them apart
        // so a misconfigured integration is visible rather than blamed on idle users.
        assertEquals("injected_unwired", a["traceparent.outcome"])
        assertFalse(a.containsKey("rum.action.id"))
    }

    @Test
    fun `instrumented factory with no action reports genuinely unattributed`() {
        allowServer()
        server.enqueue(MockResponse().setResponseCode(200))

        TracingCallFactory(client).newCall(Request.Builder().url(server.url("/x")).build())
            .execute().close()

        assertEquals("injected_unattributed", recorded.captured["traceparent.outcome"])
    }

    /**
     * Delta 6, the case that would have caught G1: a real dispatcher hop. The v2 tests called
     * onNetworkCall directly on the coroutine thread, which is exactly why the gap survived to
     * production -- they never crossed OkHttp's pool boundary.
     */
    @Test
    fun `enqueued call attributes to the action that started it`() {
        allowServer()
        val root = TraceManager.onInteractionStart("checkout")!!
        server.enqueue(MockResponse().setResponseCode(200))

        val latch = java.util.concurrent.CountDownLatch(1)
        // newCall() on THIS thread (where the root lives); the interceptor then runs on the pool.
        TracingCallFactory(client).newCall(Request.Builder().url(server.url("/x")).build())
            .enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = latch.countDown()
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                    latch.countDown()
                }
            })
        assertTrue("request completed", latch.await(5, TimeUnit.SECONDS))

        // Assert on the RECORDED request, never the one we built: a header on the outgoing Request
        // object proves only that we asked.
        val sent = server.takeRequest().getHeader("traceparent")!!
        assertEquals(root.traceId, sent.split("-")[1])
        assertEquals("injected_attributed", recorded.captured["traceparent.outcome"])
        assertEquals(root.spanId, recorded.captured["rum.action.id"])
    }

    @Test
    fun `valid inbound traceparent is left untouched and adopted`() {
        allowServer()
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val inbound = "00-$t-$p-01"
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url(server.url("/x")).header("traceparent", inbound).build()
        ).execute().close()

        assertEquals("header untouched", inbound, server.takeRequest().getHeader("traceparent"))
        val a = recorded.captured
        assertEquals(t, a["trace.id"])
        assertEquals(p, a["span.id"])
        assertEquals("adopted", a["traceparent.outcome"])
    }

    @Test
    fun `malformed inbound traceparent is replaced`() {
        allowServer()
        val malformed = "ff-${"a".repeat(32)}-${"b".repeat(16)}-01"
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url(server.url("/x")).header("traceparent", malformed).build()
        ).execute().close()

        val sent = server.takeRequest().getHeader("traceparent")!!
        assertTrue("replaced with a valid header", Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent))
        assertTrue("not the malformed one", sent != malformed)
        assertEquals("injected_unwired", recorded.captured["traceparent.outcome"])
    }
    /**
     * App client whose SDK interceptor is configured with a collector endpoint on the SAME origin as
     * the app's own API — the shape that broke in 2.2.0/2.2.1 (issue: `telemetry.ncgafrica.com` hosts
     * both `/collector/telemetry` and `/voting-api/...`).
     */
    private fun appClientSharingOriginWithCollector(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                TelemetryInterceptor(telemetryManager, server.url("/collector/telemetry").toString())
            )
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

    @Test
    fun `app call on the collector's own host is still traced`() {
        allowServer()
        appClient = appClientSharingOriginWithCollector()
        server.enqueue(MockResponse().setResponseCode(200))

        appClient!!.newCall(Request.Builder().url(server.url("/voting-api/auth/refresh")).build())
            .execute().close()

        val sent = server.takeRequest().getHeader("traceparent")
        assertTrue("app path must not hit the self-request guard", Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent ?: ""))
        assertTrue("http.request still emitted", recorded.isCaptured)
    }

    @Test
    fun `the SDK's own export call is skipped`() {
        allowServer()
        appClient = appClientSharingOriginWithCollector()
        server.enqueue(MockResponse().setResponseCode(200))

        appClient!!.newCall(Request.Builder().url(server.url("/collector/telemetry")).build())
            .execute().close()

        assertNull("no header on our own export", server.takeRequest().getHeader("traceparent"))
        assertFalse("no http.request event for our own export", recorded.isCaptured)
    }
}
