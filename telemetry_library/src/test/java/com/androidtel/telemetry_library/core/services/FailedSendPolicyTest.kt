package com.androidtel.telemetry_library.core.services

import android.content.Context
import com.androidtel.telemetry_library.core.CountedEventQueue
import com.androidtel.telemetry_library.core.OfflineBatchStorage
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.release
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * #138 R12 through the batch service: real transport and offline store against a scripted stub.
 * Retryable failures (429, 5xx, network) are queued offline; a rejection (other 4xx) is dropped,
 * never queued, and never blocks the replay queue behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FailedSendPolicyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var client: TelemetryHttpClient
    private lateinit var storage: OfflineBatchStorage
    private lateinit var service: BatchProcessingService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val url = server.url("/telemetry").toString()
        client = TelemetryHttpClient(url, "edge_test_key", false).apply { sleep = {} }
        context.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).edit().clear().commit()
        storage = OfflineBatchStorage(context)
        service = BatchProcessingService(
            TelemetryConfig(apiKey = "edge_test_key", endpoint = url), client, storage, CoroutineScope(Dispatchers.IO)
        ).apply { setIdsInitialized(true) }
    }

    @After
    fun tearDown() {
        client.getOkHttpClient().release()
        server.shutdown()
    }

    @Test
    fun `rejected batch is dropped, not queued`() = runBlocking {
        for (code in listOf(400, 401, 403, 413)) {
            server.enqueue(MockResponse().setResponseCode(code))
            service.sendBatch(queueOf("e$code"), forceSend = true)
            assertEquals("$code must not be stored", 0, storage.getStoredBatches().size)
        }
    }

    @Test
    fun `retryable failures are queued offline`() = runBlocking {
        for (code in listOf(429, 500, 503)) {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(code)) }
            service.sendBatch(queueOf("e$code"), forceSend = true)
        }
        assertEquals(listOf("e429", "e500", "e503"), storedNames())
    }

    @Test
    fun `network error is queued offline`() = runBlocking {
        val dead = TelemetryHttpClient("http://127.0.0.1:1/telemetry", "edge_test_key", false).apply { sleep = {} }
        val deadService = BatchProcessingService(
            TelemetryConfig(apiKey = "edge_test_key", endpoint = "http://127.0.0.1:1/telemetry"),
            dead, storage, CoroutineScope(Dispatchers.IO)
        ).apply { setIdsInitialized(true) }

        try {
            deadService.sendBatch(queueOf("offline"), forceSend = true)
            assertEquals(listOf("offline"), storedNames())
        } finally {
            dead.getOkHttpClient().release()
        }
    }

    @Test
    fun `stored rejected batch is removed and replay continues past it`() = runBlocking {
        listOf("a", "rejected", "c").forEach { storage.storeBatch(batchOf(it)) }
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))

        service.sendStoredBatches()

        assertEquals(listOf("a", "rejected", "c"), sentNames(3))
        assertEquals(emptyList<String>(), storedNames())
    }

    @Test
    fun `retryable failure on replay stops the tick and keeps the rest in order`() = runBlocking {
        listOf("a", "b", "c").forEach { storage.storeBatch(batchOf(it)) }
        server.enqueue(MockResponse().setResponseCode(200))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        service.sendStoredBatches()

        assertEquals(4, server.requestCount) // a, then b three times; c never tried
        assertEquals(listOf("b", "c"), storedNames())
    }

    // Flush ticks overlap when a replay outlives the interval (Retry-After waits); each must not
    // resend the same stored batch — the e2e run delivered one batch 7 times.
    @Test
    fun `overlapping replays send a stored batch once`() = runBlocking {
        storage.storeBatch(batchOf("once"))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(300, TimeUnit.MILLISECONDS)) }

        listOf(launch(Dispatchers.IO) { service.sendStoredBatches() }, launch(Dispatchers.IO) { service.sendStoredBatches() })
            .forEach { it.join() }

        assertEquals(1, server.requestCount)
        assertEquals(emptyList<String>(), storedNames())
    }

    // --- helpers ---

    private fun sentNames(n: Int) = List(n) {
        JsonParser.parseString(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).asJsonObject
            .getAsJsonArray("events")[0].asJsonObject.get("eventName").asString
    }

    private fun batchOf(name: String) =
        TelemetryBatch(batchSize = 1, timestamp = "2026-10-11T00:00:00.000Z", events = listOf(event(name)))

    private suspend fun storedNames() = storage.getStoredBatches().map { it.events.single().eventName }

    private fun queueOf(name: String) = CountedEventQueue().apply { enqueue(event(name)) }

    private fun event(name: String): TelemetryEvent = Gson().fromJson(
        """
        {"type":"event","eventName":"$name","timestamp":"2026-10-11T00:00:00.000Z",
         "attributes":{
           "app":{"appName":"App","appVersion":"1.0","appBuildNumber":"1","appPackageName":"com.test.app"},
           "device":{"deviceId":"device_1","platform":"android","platformVersion":"13","model":"M",
             "manufacturer":"Mf","brand":"B","androidSdk":"33","androidRelease":"13","fingerprint":"fp",
             "hardware":"hw","product":"p"},
           "user":{"userId":"user_1"},
           "session":{"sessionId":"session_1"},
           "customAttributes":{}}}
        """.trimIndent(), TelemetryEvent::class.java
    )
}
