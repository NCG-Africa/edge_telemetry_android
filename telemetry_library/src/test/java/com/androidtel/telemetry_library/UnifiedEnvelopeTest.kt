package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.BuildConfig
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
    fun `envelope is the closed 8-key contract 4_0_0 shape`() {
        val json = JsonParser.parseString(sendAndCaptureBody(createTestBatch())).asJsonObject

        assertEquals(
            setOf("type", "contract_version", "timestamp", "batch_size", "device_id", "sdk", "app", "events"),
            json.keySet()
        )
        assertEquals("telemetry_batch", json.get("type").asString)
        assertEquals("4.0.0", json.get("contract_version").asString)
        assertEquals(1, json.get("batch_size").asInt)
        assertEquals("test_device_123", json.get("device_id").asString)

        val sdk = json.getAsJsonObject("sdk")
        assertEquals(setOf("name", "version"), sdk.keySet())
        assertEquals("android", sdk.get("name").asString)
        assertEquals(BuildConfig.SDK_VERSION, sdk.get("version").asString)

        val app = json.getAsJsonObject("app")
        assertEquals(setOf("name", "package_name", "platform", "version", "build_number"), app.keySet())
        assertEquals("TestApp", app.get("name").asString)
        assertEquals("com.test.app", app.get("package_name").asString)
        assertEquals("android", app.get("platform").asString)
        assertEquals("1.0.0", app.get("version").asString)
        assertEquals("100", app.get("build_number").asString)
    }

    @Test
    fun `blank device id is never sent`() {
        val result = runBlocking { httpClient.sendBatch(createTestBatch(deviceId = " ")) }
        assertTrue(result.isFailure)
        assertEquals(0, mockWebServer.requestCount)
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
    fun `static device-context bundle is stamped on every event with native types`() {
        // Seam A (issue #93): the 9 static device.* keys minted once at init ride the transport's
        // enrichment and land on every flattened event with their exact names + native types.
        val staticContext = mapOf<String, Any?>(
            "device.cpu_abi" to "arm64-v8a",
            "device.cpu_cores" to 8,
            "device.low_ram" to false,
            "device.screen_density" to 420,
            "device.screen_width_px" to 1080,
            "device.screen_height_px" to 2340,
            "device.dark_mode" to true,
            "device.locale" to "en-US",
            "device.timezone" to "Africa/Nairobi"
        )
        val client = TelemetryHttpClient(
            telemetryUrl = mockWebServer.url("/telemetry").toString(),
            apiKey = testApiKey,
            debugMode = false,
            staticDeviceContext = staticContext
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        runBlocking { client.sendBatch(createTestBatch(events = 2)) }
        val body = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        client.getOkHttpClient().release()

        val events = JsonParser.parseString(body).asJsonObject.getAsJsonArray("events")
        assertTrue(events.size() > 0)
        events.forEach { el ->
            val attrs = el.asJsonObject.getAsJsonObject("attributes")

            assertEquals("arm64-v8a", attrs.get("device.cpu_abi").asString)
            assertEquals("en-US", attrs.get("device.locale").asString)
            assertEquals("Africa/Nairobi", attrs.get("device.timezone").asString)

            // Int keys must serialize as JSON numbers, not strings.
            listOf("device.cpu_cores", "device.screen_density",
                "device.screen_width_px", "device.screen_height_px").forEach { k ->
                val p = attrs.get(k).asJsonPrimitive
                assertTrue("$k must be a JSON number", p.isNumber)
            }
            assertEquals(8, attrs.get("device.cpu_cores").asInt)
            assertEquals(2340, attrs.get("device.screen_height_px").asInt)

            // Boolean keys must serialize as JSON booleans.
            assertTrue("device.low_ram must be a JSON boolean",
                attrs.get("device.low_ram").asJsonPrimitive.isBoolean)
            assertFalse(attrs.get("device.low_ram").asBoolean)
            assertTrue(attrs.get("device.dark_mode").asBoolean)
        }
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

    private fun createTestBatch(events: Int = 1, deviceId: String = "test_device_123"): TelemetryBatch {
        val attributes = EventAttributes(
            app = AppInfo("TestApp", "1.0.0", "100", "com.test.app"),
            device = DeviceInfo(
                deviceId = deviceId, platform = "android", platformVersion = "13",
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
