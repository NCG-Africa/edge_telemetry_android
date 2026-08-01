package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.trace.TraceManager
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
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = emptySet()
        TraceManager.onBackground()
    }

    @After
    fun teardown() {
        server.shutdown()
        // Release non-daemon OkHttp threads so the suite doesn't hang (see takerequest gotcha).
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
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
        val root = TraceManager.onInteraction()!!
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val sent = server.takeRequest().getHeader("traceparent")!!
        assertTrue("well-formed", Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent))
        assertTrue("carries the root trace id", sent.contains(root["trace.id"] as String))

        val a = recorded.captured
        assertEquals(root["trace.id"], a["trace.id"])
        assertEquals(root["span.id"], a["parent.span.id"])
        assertEquals("injected_attributed", a["traceparent.outcome"])
        assertTrue(sent.contains(a["span.id"] as String))
    }

    @Test
    fun `off-allowlist suppresses the header but still stamps trace attrs`() {
        // allowlist empty → server host off-allowlist
        TraceManager.onInteraction()
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull("no header off-allowlist", server.takeRequest().getHeader("traceparent"))
        val a = recorded.captured
        assertTrue("still recorded locally", a.containsKey("trace.id"))
        assertEquals("skipped_off_allowlist", a["traceparent.outcome"])
    }

    @Test
    fun `no-action on-allowlist call injects an unattributed trace`() {
        allowServer() // no root active
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val sent = server.takeRequest().getHeader("traceparent")!!
        assertTrue(Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent))
        val a = recorded.captured
        assertTrue(a.containsKey("trace.id"))
        assertEquals("injected_unattributed", a["traceparent.outcome"])
        assertFalse(a.containsKey("rum.action.id"))
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
        assertEquals("injected_unattributed", recorded.captured["traceparent.outcome"])
    }
}
