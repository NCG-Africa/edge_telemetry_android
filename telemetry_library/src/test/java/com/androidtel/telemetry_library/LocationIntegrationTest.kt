package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.location.IpLocationProvider
import com.androidtel.telemetry_library.core.models.*
import com.androidtel.telemetry_library.core.payload.FlutterPayloadFactory
import com.androidtel.telemetry_library.core.payload.EventData
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocationIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var locationProvider: IpLocationProvider

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        
        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `location is included in event batch payload when available`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "Nairobi",
                "country": "KE"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true
        )

        val location = locationProvider.getLocation()
        val events = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = location
        )
        
        assertNotNull(payload.data.location)
        assertEquals("Nairobi/KE", payload.data.location)
    }

    @Test
    fun `location is null in payload when not provided`() {
        val events = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = null
        )
        
        assertNull(payload.data.location)
    }

    @Test
    fun `IP address is included in payload when API returns IP fallback`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "",
                "country": ""
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true
        )

        val location = locationProvider.getLocation()
        val events = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = location
        )
        
        assertNotNull(payload.data.location)
        assertEquals("105.163.0.47", payload.data.location)
    }

    @Test
    fun `Unknown-Unknown is included in payload when location fetch fails`() = runBlocking {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("Internal Server Error"))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = false
        )

        val location = locationProvider.getLocation()
        val events = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = location
        )
        
        assertNotNull(payload.data.location)
        assertEquals("Unknown/Unknown", payload.data.location)
    }

    @Test
    fun `payload serializes to JSON correctly with location field`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "Nairobi",
                "country": "KE"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true
        )

        val location = locationProvider.getLocation()
        val events = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = location
        )
        
        val jsonString = payload.toJson()
        val jsonObject = JSONObject(jsonString)
        
        assertTrue(jsonObject.has("data"))
        val dataObject = jsonObject.getJSONObject("data")
        assertTrue(dataObject.has("location"))
        assertEquals("Nairobi/KE", dataObject.getString("location"))
    }

    @Test
    fun `payload serializes to JSON correctly without location field when null`() {
        val events = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = null
        )
        
        val jsonString = payload.toJson()
        val jsonObject = JSONObject(jsonString)
        
        assertTrue(jsonObject.has("data"))
        val dataObject = jsonObject.getJSONObject("data")
        assertFalse(dataObject.has("location"))
    }

    @Test
    fun `cached location is reused across multiple payload creations`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "Nairobi",
                "country": "KE"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true
        )

        val location1 = locationProvider.getLocation()
        val location2 = locationProvider.getCachedLocation()
        
        val events1 = createTestEvents()
        val events2 = createTestEvents()
        val deviceId = "test_device_123"
        
        val payload1 = FlutterPayloadFactory.createEventBatchPayload(
            events = events1,
            deviceId = deviceId,
            location = location1
        )
        
        val payload2 = FlutterPayloadFactory.createEventBatchPayload(
            events = events2,
            deviceId = deviceId,
            location = location2
        )
        
        assertEquals("Nairobi/KE", payload1.data.location)
        assertEquals("Nairobi/KE", payload2.data.location)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `location field maintains correct format in batch with multiple events`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "Kisumu",
                "country": "KE"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true
        )

        val location = locationProvider.getLocation()
        val events = createMultipleTestEvents(5)
        val deviceId = "test_device_123"
        
        val payload = FlutterPayloadFactory.createEventBatchPayload(
            events = events,
            deviceId = deviceId,
            location = location
        )
        
        assertEquals(5, payload.data.batch_size)
        assertEquals("Kisumu/KE", payload.data.location)
    }

    private fun createTestEvents(): List<EventData> {
        return listOf(
            EventData(
                type = "event",
                eventName = "test.event",
                metricName = null,
                value = null,
                timestamp = "2025-09-22T13:42:05.100Z",
                attributes = mapOf(
                    "screen" to "test_screen",
                    "action" to "test_action"
                )
            )
        )
    }

    private fun createMultipleTestEvents(count: Int): List<EventData> {
        return (1..count).map { i ->
            EventData(
                type = "event",
                eventName = "test.event.$i",
                metricName = null,
                value = null,
                timestamp = "2025-09-22T13:42:05.100Z",
                attributes = mapOf(
                    "screen" to "test_screen",
                    "action" to "test_action_$i"
                )
            )
        }
    }


}
