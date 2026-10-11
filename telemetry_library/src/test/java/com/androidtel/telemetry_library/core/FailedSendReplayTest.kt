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
 * rejected batch (401) is sent once, dropped, and never blocks what's queued behind it.
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
                        env.toString().contains("\"revoked\"") -> 401
                        outage -> 503
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
    fun `outage queues offline, rejection is dropped, recovery drains in order re-stamped`() {
        val tm = TelemetryManager.getInstance()

        tm.recordEvent("first")
        val firstFailed = await("first attempted during outage") { hit("first", 503) }
        tm.recordEvent("revoked")
        await("revoked attempted") { hit("revoked", 401) }
        tm.recordEvent("second")
        await("second attempted during outage") { hit("second", 503) }

        outage = false
        await("both delivered") { hit("first", 200) != null && hit("second", 200) != null }

        val delivered = hits.filter { it.code == 200 }.flatMap { it.names }
        assertTrue("in order: $delivered", delivered.indexOf("first") < delivered.indexOf("second"))
        assertEquals("rejected batch sent once, never replayed", 1, hits.count { "revoked" in it.names })
        val replayed = hit("first", 200)!!
        assertTrue("re-stamped at send, not the stored time", replayed.sentAt.isAfter(firstFailed!!.sentAt))
    }

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
