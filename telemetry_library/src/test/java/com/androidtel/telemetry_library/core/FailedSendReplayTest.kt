package com.androidtel.telemetry_library.core

import android.app.Application
import android.content.Context
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #138 through the public API (initialize + recordEvent + the flush timer) against a scripted stub:
 * an outage (503) queues events offline; recovery replays them in order, re-stamped at send; a
 * queued batch the collector then rejects (401) is dropped and never blocks what's behind it; a
 * batch rejected on first send is never queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FailedSendReplayTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer

    private data class Hit(val code: Int, val env: JsonObject) {
        val names get() = env.getAsJsonArray("events").map { it.asJsonObject.get("eventName")?.asString }
        val sentAt: Instant get() = Instant.parse(env.get("timestamp").asString)
    }

    private val hits = CopyOnWriteArrayList<Hit>()
    @Volatile private var outage = true

    @Before
    fun setUp() {
        app.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).edit().clear().commit()
        FatalCrashStore.delete(app.filesDir)
        FatalCrashStore.delete(app.filesDir, FatalCrashStore.ANR_FILE_NAME)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val env = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    val code = when {
                        outage -> 503
                        env.toString().contains("\"revoked\"") -> 401
                        else -> 200
                    }
                    hits += Hit(code, env)
                    // Retry-After: 0 keeps the in-process retries instant (and proves it's honoured).
                    return MockResponse().setResponseCode(code).setHeader("Retry-After", "0")
                }
            }
            start()
        }
        TelemetryManager.resetForTesting()
        TelemetryManager.initialize(
            app,
            TelemetryConfig(
                apiKey = "edge_test_key",
                endpoint = server.url("/telemetry").toString(),
                batchSize = 1_000, // only the flush timer sends
                flushIntervalMs = 200L,
                enableMemoryTracking = false,
                enableStorageTracking = false,
                enableFrameTracking = false,
                enableCapabilityEvents = false
            )
        )
    }

    @After
    fun tearDown() {
        TelemetryManager.resetForTesting()
        server.shutdown()
    }

    @Test
    fun `outage queues offline, recovery drains in order re-stamped past a rejected batch`() {
        val tm = TelemetryManager.getInstance()

        tm.recordEvent("first")
        await("first queued offline") { queued("first") }
        val firstFailed = hit("first", 503)
        tm.recordEvent("revoked") // queued during the outage, rejected once it's over
        await("revoked queued offline") { queued("revoked") }
        tm.recordEvent("second")
        // Flip only once second is in the offline queue: a live in-process retry landing after the
        // flip isn't the queue and may legitimately overtake it. (Server hit counts can't tell —
        // OkHttp transparently re-sends on a stale pooled connection.)
        await("second queued offline") { queued("second") }

        outage = false
        await("both delivered") { hit("first", 200) != null && hit("second", 200) != null }
        Thread.sleep(1_000) // several more flush ticks

        val delivered = hits.filter { it.code == 200 }.flatMap { it.names }
        assertTrue("in order: $delivered; hits=${hits.map { it.code to it.names }}", delivered.indexOf("first") < delivered.indexOf("second"))
        val revokedAfter = hits.filter { it.code != 503 && "revoked" in it.names }
        assertEquals("rejected once on replay, then dropped: $revokedAfter", listOf(401), revokedAfter.map { it.code })
        assertTrue("dropped batch didn't block second", hits.indexOf(revokedAfter.single()) < hits.indexOf(hit("second", 200)))
        val replayed = hit("first", 200)!!
        assertTrue("re-stamped at send, not the stored time", replayed.sentAt.isAfter(firstFailed!!.sentAt))
    }

    @Test
    fun `batch rejected on first send is sent once and never queued`() {
        outage = false
        TelemetryManager.getInstance().recordEvent("revoked")
        await("revoked rejected") { hit("revoked", 401) }
        Thread.sleep(1_000) // several more flush ticks

        assertEquals(1, hits.count { "revoked" in it.names })
        assertEquals(null, app.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).getString("stored_batches", null)
            ?.takeIf { "revoked" in it })
    }

    private fun queued(name: String) =
        runBlocking { OfflineBatchStorage(app).getStoredBatches() }.any { b -> b.events.any { it.eventName == name } }

    private fun hit(name: String, code: Int) = hits.firstOrNull { it.code == code && name in it.names }

    private fun <T> await(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.takeIf { it != false }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $what; hits=${hits.map { it.code to it.names }}")
    }
}
