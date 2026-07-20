package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TelemetryHttpClientTest {

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

    @Test
    fun `sendBatch includes X-API-Key header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(testApiKey, request.getHeader("X-API-Key"))
    }

    @Test
    fun `sendBatch includes User-Agent header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertNotNull(request.getHeader("User-Agent"))
        assertTrue(request.getHeader("User-Agent")?.contains("EdgeTelemetryAndroid") == true)
    }

    @Test
    fun `sendBatch includes Content-Type header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        // OkHttp's String.toRequestBody appends "; charset=utf-8" — match the media type, not the exact string.
        assertTrue(request.getHeader("Content-Type")?.startsWith("application/json") == true)
    }

    @Test
    fun `sendBatch sends POST request`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
    }

    @Test
    fun `sendBatch with valid response returns success`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        val result = httpClient.sendBatch(batch)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendBatch with 201 response returns success`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(201))

        val batch = createTestBatch()
        val result = httpClient.sendBatch(batch)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendBatch with 400 client error returns failure`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        val batch = createTestBatch()
        val result = httpClient.sendBatch(batch)

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendBatch with 401 unauthorized returns failure`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val batch = createTestBatch()
        val result = httpClient.sendBatch(batch)

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendBatch with 500 server error retries and eventually fails`() = runBlocking {
        // Enqueue 3 server errors (max retries = 3)
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val batch = createTestBatch()
        val result = httpClient.sendBatch(batch)

        assertTrue(result.isFailure)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `sendBatch with server error then success returns success`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        val result = httpClient.sendBatch(batch)

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `API key value is correct in header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        val apiKeyHeader = request.getHeader("X-API-Key")
        
        assertEquals(testApiKey, apiKeyHeader)
        assertTrue(apiKeyHeader?.startsWith("edge_") == true)
    }

    @Test
    fun `different API keys are sent correctly`() = runBlocking {
        val customApiKey = "edge_custom_key_xyz789"
        val customClient = TelemetryHttpClient(
            telemetryUrl = mockWebServer.url("/telemetry").toString(),
            apiKey = customApiKey,
            debugMode = false
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        customClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(customApiKey, request.getHeader("X-API-Key"))
        customClient.getOkHttpClient().release()  // local client — release its threads too
    }

    @Test
    fun `request body contains valid JSON`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        
        assertNotNull(body)
        assertTrue(body.isNotEmpty())
        assertTrue(body.contains("\"type\":\"telemetry_batch\""))
        assertTrue(body.contains("\"timestamp\""))
    }

    @Test
    fun `all required headers are present`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val batch = createTestBatch()
        httpClient.sendBatch(batch)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        
        assertNotNull(request.getHeader("X-API-Key"))
        assertNotNull(request.getHeader("Content-Type"))
        assertNotNull(request.getHeader("User-Agent"))
    }

    private fun createTestBatch(): TelemetryBatch {
        val appInfo = AppInfo(
            appName = "TestApp",
            appVersion = "1.0.0",
            appBuildNumber = "100",
            appPackageName = "com.test.app"
        )

        val deviceInfo = DeviceInfo(
            deviceId = "test_device_123",
            platform = "Android",
            platformVersion = "13",
            model = "TestModel",
            manufacturer = "TestManufacturer",
            brand = "TestBrand",
            androidSdk = "33",
            androidRelease = "13",
            fingerprint = "test_fingerprint",
            hardware = "test_hardware",
            product = "test_product"
        )

        val userInfo = UserInfo(
            userId = "test_user_123",
            name = null,
            email = null,
            phone = null
        )

        val sessionInfo = SessionInfo(
            sessionId = "test_session_123",
            startTime = System.currentTimeMillis().toString(),
            durationMs = 1000L,
            eventCount = 1,
            metricCount = 0,
            screenCount = 1,
            visitedScreens = "TestScreen",
            isFirstSession = false,
            totalSessions = 5,
            networkType = "WiFi"
        )

        val attributes = EventAttributes(
            app = appInfo,
            device = deviceInfo,
            user = userInfo,
            session = sessionInfo,
            customAttributes = emptyMap()
        )

        val event = TelemetryEvent(
            type = "event",
            eventName = "test.event",
            metricName = null,
            value = null,
            timestamp = System.currentTimeMillis().toString(),
            attributes = attributes
        )

        return TelemetryBatch(
            batchSize = 1,
            timestamp = System.currentTimeMillis().toString(),
            events = listOf(event)
        )
    }
}
