package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Seam 1 (issue #48): assert the serialized `telemetry_batch` envelope on the wire.
 *
 * Drives the single `sendBatch` transport, captures the POST body via MockWebServer, and asserts
 * the exact envelope shape + common `sdk.*` attrs + header set. This is the conformance contract
 * for the unified wire envelope.
 */
class UnifiedEnvelopeTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var httpClient: TelemetryHttpClient
    private val testApiKey = "edge_test_api_key_12345"

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        httpClient = TelemetryHttpClient(
            telemetryUrl = mockWebServer.url("/telemetry").toString(),
            apiKey = testApiKey,
            debugMode = false
        )
    }

    @After
    fun tearDown() {
        // Release OkHttp's non-daemon threads so the forked test JVM exits promptly (see release()).
        httpClient.getOkHttpClient().release()
        mockWebServer.shutdown()
    }

    private fun sendAndCaptureBody(batch: TelemetryBatch): String {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        runBlocking { httpClient.sendBatch(batch) }
        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        return request.body.readUtf8()
    }

    @Test
    fun `envelope has exactly the unified top-level keys and no legacy fields`() {
        val body = sendAndCaptureBody(createTestBatch())
        val json = JsonParser.parseString(body).asJsonObject

        assertEquals("telemetry_batch", json.get("type").asString)
        assertEquals(setOf("type", "timestamp", "batch_size", "events"), json.keySet())

        // Removed legacy fields (Path A + Path B leftovers).
        assertFalse("device_id must not be top-level", json.has("device_id"))
        assertFalse("location must not be top-level", json.has("location"))
        assertFalse("data wrapper must be gone", json.has("data"))
        assertFalse("tenant_id must be gone", json.has("tenant_id"))
    }

    @Test
    fun `every event carries sdk and identity common attrs`() {
        val body = sendAndCaptureBody(createTestBatch(events = 2))
        val events = JsonParser.parseString(body).asJsonObject.getAsJsonArray("events")
        assertTrue(events.size() > 0)

        events.forEach { el ->
            val attrs = el.asJsonObject.getAsJsonObject("attributes")
            assertTrue("sdk.version present", attrs.has("sdk.version"))
            assertFalse(attrs.get("sdk.version").asString.isBlank())
            assertEquals("android", attrs.get("sdk.platform").asString)
            assertTrue("device.id present", attrs.has("device.id"))
            assertTrue("session.id present", attrs.has("session.id"))
            assertTrue("user.id present", attrs.has("user.id"))
        }
    }

    @Test
    fun `session counters serialize as native JSON numbers`() {
        val body = sendAndCaptureBody(createTestBatch())
        val attrs = JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("events").get(0).asJsonObject
            .getAsJsonObject("attributes")

        val eventCount = attrs.get("session.event_count")
        assertTrue("session.event_count must be a JSON number, not a string",
            eventCount.isJsonPrimitive && eventCount.asJsonPrimitive.isNumber)
    }

    @Test
    fun `X-SDK headers are removed and X-API-Key stays`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        runBlocking { httpClient.sendBatch(createTestBatch()) }
        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!

        assertEquals(testApiKey, request.getHeader("X-API-Key"))
        assertNull(request.getHeader("X-SDK-Version"))
        assertNull(request.getHeader("X-SDK-Platform"))
    }

    private fun createTestBatch(events: Int = 1): TelemetryBatch {
        val attributes = EventAttributes(
            app = AppInfo("TestApp", "1.0.0", "100", "com.test.app"),
            device = DeviceInfo(
                deviceId = "test_device_123", platform = "android", platformVersion = "13",
                model = "TestModel", manufacturer = "TestManufacturer", brand = "TestBrand",
                androidSdk = "33", androidRelease = "13", fingerprint = "fp",
                hardware = "hw", product = "prod"
            ),
            user = UserInfo(userId = "test_user_123"),
            session = SessionInfo(
                sessionId = "test_session_123", startTime = "2026-07-20T00:00:00.000Z",
                durationMs = 1000L, eventCount = 3, metricCount = 0, screenCount = 1,
                visitedScreens = "TestScreen", isFirstSession = false, totalSessions = 5,
                networkType = "wifi"
            ),
            customAttributes = emptyMap()
        )
        val evList = (1..events).map { i ->
            TelemetryEvent(
                type = "event", eventName = "test.event.$i", metricName = null, value = null,
                timestamp = "2026-07-20T00:00:0$i.000Z", attributes = attributes
            )
        }
        return TelemetryBatch(batchSize = evList.size, timestamp = "2026-07-20T00:00:00.000Z", events = evList)
    }
}
