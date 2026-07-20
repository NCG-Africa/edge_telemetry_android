package com.androidtel.telemetry_library

import android.content.Context
import com.androidtel.telemetry_library.core.retry.CrashRetryManager
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CrashRetryManagerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var crashRetryManager: CrashRetryManager
    private lateinit var context: Context
    private val testApiKey = "edge_test_crash_key_123"

    @Before
    fun setUp() {
        // Circuit breaker is static/shared; reset it so a 429 in one test can't suppress the next.
        CrashRetryManager.resetCircuitBreakerForTesting()
        context = RuntimeEnvironment.getApplication()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        crashRetryManager = CrashRetryManager(
            context = context,
            apiKey = testApiKey,
            telemetryEndpoint = mockWebServer.url("/telemetry").toString(),
            debugMode = false,
            enableWorkManager = false  // Disable WorkManager to prevent hanging in tests
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    @Test
    fun `sendCrashWithRetry includes X-API-Key header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(testApiKey, request.getHeader("X-API-Key"))
    }

    @Test
    fun `sendCrashWithRetry includes User-Agent header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertNotNull(request.getHeader("User-Agent"))
        assertTrue(request.getHeader("User-Agent")?.contains("EdgeTelemetryAndroid") == true)
    }

    @Test
    fun `sendCrashWithRetry includes Content-Type header`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        // OkHttp's String.toRequestBody appends "; charset=utf-8" — match the media type, not the exact string.
        assertTrue(request.getHeader("Content-Type")?.startsWith("application/json") == true)
    }

    @Test
    fun `sendCrashWithRetry sends POST request`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
    }

    @Test
    fun `sendCrashWithRetry with successful response completes without retry`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `sendCrashWithRetry retries on server error`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `sendCrashWithRetry retries up to MAX_RETRIES times`() = runBlocking {
        // Enqueue 3 failures (MAX_RETRIES = 3)
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `API key is correct in crash request`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        val apiKeyHeader = request.getHeader("X-API-Key")
        
        assertEquals(testApiKey, apiKeyHeader)
        assertTrue(apiKeyHeader?.startsWith("edge_") == true)
    }

    @Test
    fun `different API keys are sent correctly in crash requests`() = runBlocking {
        val customApiKey = "edge_custom_crash_key_xyz"
        val customManager = CrashRetryManager(
            context = context,
            apiKey = customApiKey,
            telemetryEndpoint = mockWebServer.url("/telemetry").toString(),
            debugMode = false
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        customManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(customApiKey, request.getHeader("X-API-Key"))
    }

    @Test
    fun `crash request body contains valid JSON`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        
        assertNotNull(body)
        assertTrue(body.isNotEmpty())
        assertTrue(body.contains("\"type\""))
        assertTrue(body.contains("\"data\""))
    }

    @Test
    fun `all required headers are present in crash request`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        
        assertNotNull(request.getHeader("X-API-Key"))
        assertNotNull(request.getHeader("Content-Type"))
        assertNotNull(request.getHeader("User-Agent"))
    }

    @Test
    fun `retryOfflineCrashes includes API key in requests`() = runBlocking {
        // First, store a crash offline by failing all retries
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        // Clear previous requests
        repeat(3) { mockWebServer.takeRequest(5, TimeUnit.SECONDS) }

        // Now retry offline crashes
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        crashRetryManager.retryOfflineCrashes()

        val retryRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(testApiKey, retryRequest.getHeader("X-API-Key"))
    }

    @Test
    fun `endpoint URL is used correctly`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.path?.contains("/telemetry") == true)
    }

    @Test
    fun `custom endpoint is used when provided`() = runBlocking {
        val customEndpoint = mockWebServer.url("/custom/crash/endpoint").toString()
        val customManager = CrashRetryManager(
            context = context,
            apiKey = testApiKey,
            telemetryEndpoint = customEndpoint,
            debugMode = false
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        customManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.path?.contains("/custom/crash/endpoint") == true)
    }

    @Test
    fun `crash data is properly serialized to JSON`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = mapOf(
            "type" to "crash",
            "data" to mapOf(
                "error_type" to "RuntimeException",
                "message" to "Test crash",
                "stack_trace" to "at com.test.Class.method(Class.kt:123)"
            ),
            "timestamp" to System.currentTimeMillis()
        )

        crashRetryManager.sendCrashWithRetry(crashData)

        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        
        assertTrue(body.contains("\"type\":\"crash\""))
        assertTrue(body.contains("\"error_type\":\"RuntimeException\""))
        assertTrue(body.contains("\"message\":\"Test crash\""))
    }

    @Test
    fun `HTTP 429 stops retries immediately and stores offline`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        // Should only make 1 request, not retry on 429
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `circuit breaker prevents subsequent requests after 429`() = runBlocking {
        // First request gets 429
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        val crashData1 = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData1)

        // Second request should be blocked by circuit breaker
        val crashData2 = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData2)

        // Only 1 HTTP request should have been made
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `regular errors still retry normally`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val crashData = createTestCrashData()
        crashRetryManager.sendCrashWithRetry(crashData)

        // Should retry 3 times for non-429 errors
        assertEquals(3, mockWebServer.requestCount)
    }

    private fun createTestCrashData(): Map<String, Any> {
        return mapOf(
            "type" to "crash",
            "data" to mapOf(
                "error_type" to "TestException",
                "message" to "Test crash message",
                "stack_trace" to "at com.test.TestClass.testMethod(TestClass.kt:42)"
            ),
            "timestamp" to System.currentTimeMillis(),
            "device_id" to "test_device_123",
            "user_id" to "test_user_123"
        )
    }
}
