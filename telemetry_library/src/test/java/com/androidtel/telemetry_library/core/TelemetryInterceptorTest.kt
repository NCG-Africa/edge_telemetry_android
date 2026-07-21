package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.trace.TraceManager
import io.mockk.every
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
    private val telemetryManager: TelemetryManager = mockk(relaxed = true)
    private val recorded = slot<Map<String, Any>>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(TelemetryInterceptor(telemetryManager))
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        every { telemetryManager.recordEvent("http.request", capture(recorded)) } returns Unit
    }

    @After
    fun teardown() {
        server.shutdown()
        // Release non-daemon OkHttp threads so the suite doesn't hang (see takerequest gotcha).
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        TraceManager.traceSampleRate = 1.0
        TraceManager.onBackground()
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
        // Point client at a dead port so chain.proceed throws IOException.
        runCatching {
            client.newCall(Request.Builder().url("http://127.0.0.1:1/x").build()).execute()
        }
        assertEquals(0, recorded.captured["http.status_code"])
        assertEquals(false, recorded.captured["http.success"])
        assertFalse(recorded.captured.containsKey("http.error"))
    }

    @Test
    fun `sampled root injects traceparent and stamps child span attrs`() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.onBackground()
        val root = TraceManager.onInteraction(System.currentTimeMillis())!!
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val sent = server.takeRequest().getHeader("traceparent")!!
        assertTrue("well-formed traceparent", Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(sent))
        assertTrue("carries the root trace id", sent.contains(root["trace.id"] as String))

        val a = recorded.captured
        assertEquals(root["trace.id"], a["trace.id"])
        assertEquals(root["span.id"], a["parent.span.id"])
        assertTrue(sent.contains(a["span.id"] as String))
    }

    @Test
    fun `no active root injects no header and no trace attrs`() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.onBackground() // no root
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull("no header when no root", server.takeRequest().getHeader("traceparent"))
        assertFalse(recorded.captured.containsKey("trace.id"))
    }

    @Test
    fun `existing traceparent is not overwritten`() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.onBackground()
        TraceManager.onInteraction(System.currentTimeMillis()) // active root exists
        val appHeader = "00-abcdef01234567890abcdef012345678-1122334455667788-01"
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url(server.url("/x")).header("traceparent", appHeader).build()
        ).execute().close()

        assertEquals(appHeader, server.takeRequest().getHeader("traceparent"))
        assertFalse("we don't stamp our attrs when caller owns the header",
            recorded.captured.containsKey("trace.id"))
    }
}
