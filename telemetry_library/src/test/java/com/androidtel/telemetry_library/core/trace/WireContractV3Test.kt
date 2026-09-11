package com.androidtel.telemetry_library.core.trace

import com.androidtel.telemetry_library.core.TelemetryInterceptor
import com.androidtel.telemetry_library.core.TelemetryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v3 wire-contract conformance: asserts the exact trace keys that leave the SDK, per event.
 *
 * This is the test #128's finding demands. The v2 contract was **write-only for two releases** — every
 * attribute Δ5 defined was emitted and stored nowhere — and nobody could tell, because no test asserted
 * what actually went out and no store held it. Asserting emission here separates "the SDK never sent
 * it" from "the backend never kept it", which is exactly the question that took a database hunt to
 * answer for `traceparent.outcome`.
 *
 * Each case prints its captured map, so the manifest is readable in the test output when handing the
 * contract to whoever writes the ingest side.
 */
class WireContractV3Test {

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
        TraceManager.resetForTesting()
        TraceManager.clock = { 1_000L }
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = setOf(server.hostName.lowercase())
    }

    @After
    fun teardown() {
        server.shutdown()
        // Non-daemon OkHttp threads hang the forked test JVM otherwise.
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        TraceManager.resetForTesting()
        TraceManager.traceHostAllowlist = emptySet()
    }

    private fun dump(label: String, attrs: Map<String, Any>) {
        println("── $label ──")
        attrs.toSortedMap().forEach { (k, v) -> println("   $k = $v") }
    }

    @Test
    fun `http_request emitted under a live action carries every v3 trace key`() {
        val root = TraceManager.onInteractionStart("checkout")!!
        server.enqueue(MockResponse().setResponseCode(200))

        TracingCallFactory(client).newCall(Request.Builder().url(server.url("/pay")).build())
            .execute().close()

        val a = recorded.captured
        dump("http.request (attributed)", a)

        assertTrue(
            "every v3 trace key must be on the wire",
            a.keys.containsAll(
                setOf(
                    "trace.id", "span.id", "parent.span.id", "rum.action.id",
                    "trace.root_type", "traceparent.outcome",
                    "span.start_time", "span.duration_ms"
                )
            )
        )
        assertEquals(root.traceId, a["trace.id"])
        assertEquals("the join key the backend groups by", root.spanId, a["rum.action.id"])
        assertEquals(root.spanId, a["parent.span.id"])
        assertEquals("interaction", a["trace.root_type"])
        assertEquals("injected_attributed", a["traceparent.outcome"])

        // The header and the recorded span id are the same value — that identity is what makes
        // "row in the SDK's store but no matching backend span ⇒ the header was stripped in transit"
        // a computable statement rather than a guess.
        assertEquals(
            "00-${a["trace.id"]}-${a["span.id"]}-01",
            server.takeRequest().getHeader("traceparent")
        )
    }

    @Test
    fun `http_request fired during launch is a child of the launch root`() {
        TraceManager.resetForTesting()
        TraceManager.clock = { 1_000L }
        TraceManager.traceHostAllowlist = setOf(server.hostName.lowercase())

        // The exact shape of the startup calls that came back unattributed in production: fired from
        // init, before any Activity resume.
        val launch = TraceManager.onLaunch(startElapsedRealtimeMs = 800L, importanceAtInit = 100)!!
        server.enqueue(MockResponse().setResponseCode(200))

        TracingCallFactory(client).newCall(Request.Builder().url(server.url("/auth/refresh")).build())
            .execute().close()

        val a = recorded.captured
        dump("http.request (launch child)", a)

        assertEquals(launch.traceId, a["trace.id"])
        assertEquals(launch.spanId, a["rum.action.id"])
        assertEquals("launch", a["trace.root_type"])
        assertEquals("injected_attributed", a["traceparent.outcome"])
    }

    @Test
    fun `interaction root, navigation child and launch root each carry their span keys`() {
        val root = TraceManager.onInteractionStart("vote")!!
        val interaction = TraceManager.onInteractionEmit()!!
        dump("ui.interaction (root)", interaction)
        assertEquals(
            setOf("trace.id", "span.id", "rum.action.id", "trace.root_type", "span.start_time"),
            interaction.keys
        )
        assertEquals(root.spanId, interaction["rum.action.id"])

        val nav = TraceManager.onNavigation()!!
        dump("navigation (child of the tap)", nav)
        assertEquals(
            setOf("trace.id", "span.id", "parent.span.id", "rum.action.id", "trace.root_type",
                "span.start_time"),
            nav.keys
        )
        assertEquals("children denormalize the root's type", "interaction", nav["trace.root_type"])

        TraceManager.resetForTesting()
        TraceManager.clock = { 1_000L }
        TraceManager.onLaunch(startElapsedRealtimeMs = 500L, importanceAtInit = 100)
        val appStart = TraceManager.launchRootAttrs()!!
        dump("app.start (launch root)", appStart)
        assertEquals(
            setOf("trace.id", "span.id", "rum.action.id", "trace.root_type", "span.start_time"),
            appStart.keys
        )
        assertEquals("launch", appStart["trace.root_type"])
    }

    @Test
    fun `crash and hang carry the action join keys`() {
        val root = TraceManager.onInteractionStart("checkout")!!
        val terminal = TraceManager.annotateTerminal()
        dump("app.crash / app.hang (annotation)", terminal)

        assertEquals(setOf("trace.id", "rum.action.id", "trace.root_type"), terminal.keys)
        assertEquals(root.traceId, terminal["trace.id"])
        assertEquals(root.spanId, terminal["rum.action.id"])
    }

    @Test
    fun `every outcome value the SDK can emit is declared in the contract`() {
        // Guards the enum the backend has to store: if a seventh value is ever added in code without
        // being added to OUTCOMES (and thence to the docs and the Postgres enum), this fails.
        assertEquals(
            setOf(
                "skipped_off_allowlist", "adopted", "injected_attributed",
                "injected_unwired", "injected_expired", "injected_unattributed"
            ),
            TraceManager.OUTCOMES
        )
        assertEquals(
            setOf("launch", "interaction", "navigation", "request"),
            TraceManager.ROOT_TYPES
        )
    }
}
