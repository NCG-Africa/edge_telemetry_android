package com.androidtel.telemetry_library.core

import android.content.Context
import com.androidtel.telemetry_library.BuildConfig
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import com.androidtel.telemetry_library.core.services.BatchProcessingService
import com.androidtel.telemetry_library.release
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * #135: batches persisted before a send (offline store, frozen crash/ANR — including ones written
 * by a 2.x build) go out as valid contract 4.0.0 envelopes, re-stamped at send (R13) and carrying
 * the identity of the build that recorded them (R14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EnvelopeReplayTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var client: TelemetryHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = TelemetryHttpClient(server.url("/telemetry").toString(), "edge_test_key", false)
        context.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).edit().clear().commit()
        FatalCrashStore.delete(context.filesDir)
        FatalCrashStore.delete(context.filesDir, FatalCrashStore.ANR_FILE_NAME)
    }

    @After
    fun tearDown() {
        client.getOkHttpClient().release()
        server.shutdown()
    }

    @Test
    fun `offline-stored 2x batch replays as a re-stamped 4_0_0 envelope`() {
        context.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).edit()
            .putString("stored_batches", "[${legacyBatch("event", "screen.view", "3.2.0", "32")}]").commit()
        val service = BatchProcessingService(
            TelemetryConfig(apiKey = "edge_test_key", endpoint = server.url("/telemetry").toString()),
            client, OfflineBatchStorage(context), CoroutineScope(Dispatchers.IO)
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val (env, before, after) = captureSend { service.sendStoredBatches() }

        assertValid4Envelope(env, version = "3.2.0", build = "32")
        assertRestamped(env, before, after)
    }

    @Test
    fun `frozen crash from an older build goes alone under the crashed build, sdk current`() {
        FatalCrashStore.writeBlocking(context.filesDir, legacyBatch("event", "app.crash", "1.0", "10"))
        // The live process is build 2.0 and sends its own batch first — no state may leak into replay.
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        runBlocking { client.sendBatch(liveBatch()) }
        server.takeRequest(5, TimeUnit.SECONDS)

        val (env, before, after) = captureSend { FatalCrashStore.replayOnce(context.filesDir, client) }

        assertValid4Envelope(env, version = "1.0", build = "10")
        assertEquals(1, env.get("batch_size").asInt)
        assertEquals(listOf("app.crash"), env.getAsJsonArray("events").map { it.asJsonObject.get("eventName").asString })
        assertRestamped(env, before, after)
    }

    @Test
    fun `frozen ANR replays re-stamped`() {
        FatalCrashStore.writeBlocking(
            context.filesDir, legacyBatch("event", "app.anr", "1.0", "10"), FatalCrashStore.ANR_FILE_NAME
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val (env, before, after) = captureSend {
            FatalCrashStore.replayOnce(context.filesDir, client, fileName = FatalCrashStore.ANR_FILE_NAME)
        }

        assertValid4Envelope(env, version = "1.0", build = "10")
        assertRestamped(env, before, after)
    }

    // --- helpers ---

    private data class Sent(val env: JsonObject, val before: Long, val after: Long)

    private fun captureSend(send: suspend () -> Unit): Sent {
        val before = System.currentTimeMillis()
        runBlocking { send() }
        val after = System.currentTimeMillis()
        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        return Sent(JsonParser.parseString(body).asJsonObject, before, after)
    }

    private fun assertValid4Envelope(env: JsonObject, version: String, build: String) {
        assertEquals(
            setOf("type", "contract_version", "timestamp", "batch_size", "device_id", "sdk", "app", "events"),
            env.keySet()
        )
        assertEquals("4.0.0", env.get("contract_version").asString)
        assertEquals("device_legacy_1", env.get("device_id").asString)
        assertEquals("android", env.getAsJsonObject("sdk").get("name").asString)
        assertEquals(BuildConfig.SDK_VERSION, env.getAsJsonObject("sdk").get("version").asString)
        val app = env.getAsJsonObject("app")
        assertEquals("Legacy App", app.get("name").asString)
        assertEquals("com.test.app", app.get("package_name").asString)
        assertEquals("android", app.get("platform").asString)
        assertEquals(version, app.get("version").asString)
        assertEquals(build, app.get("build_number").asString)
        env.getAsJsonArray("events").forEach { e ->
            val keys = e.asJsonObject.getAsJsonObject("attributes").keySet()
            assertTrue("identity leaked: $keys", keys.none { it.startsWith("sdk.") || it == "device.id" || it == "app.version" })
        }
    }

    private fun assertRestamped(env: JsonObject, before: Long, after: Long) {
        val ts = env.get("timestamp").asString
        assertTrue("UTC with Z: $ts", ts.endsWith("Z"))
        assertNotEquals(STORED_TIME, ts)
        val sentAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(ts)!!.time
        assertTrue("stamped at send: $ts", sentAt in before..after)
        assertEquals("event timestamps untouched", EVENT_TIME,
            env.getAsJsonArray("events")[0].asJsonObject.get("timestamp").asString)
    }

    private fun liveBatch() = com.google.gson.Gson().fromJson(
        legacyBatch("event", "screen.view", "2.0", "20"), com.androidtel.telemetry_library.core.models.TelemetryBatch::class.java
    )

    /** A TelemetryBatch exactly as the 2.x OfflineBatchStorage / FatalCrashStore persisted it (Gson). */
    private fun legacyBatch(type: String, name: String, version: String, build: String) = """
        {"id":"b-1","type":"telemetry_batch","batchSize":1,"timestamp":"$STORED_TIME",
         "events":[{"type":"$type","eventName":"$name","timestamp":"$EVENT_TIME",
           "attributes":{
             "app":{"appName":"Legacy App","appVersion":"$version","appBuildNumber":"$build","appPackageName":"com.test.app"},
             "device":{"deviceId":"device_legacy_1","platform":"android","platformVersion":"13","model":"M",
               "manufacturer":"Mf","brand":"B","androidSdk":"33","androidRelease":"13","fingerprint":"fp",
               "hardware":"hw","product":"p"},
             "user":{"userId":"user_legacy_1"},
             "session":{"sessionId":"session_legacy_1"},
             "customAttributes":{"is_fatal":true}}}]}
    """.trimIndent()

    private companion object {
        const val STORED_TIME = "2025-01-01T00:00:00.000Z"
        const val EVENT_TIME = "2024-12-31T23:59:59.000Z"
    }
}
