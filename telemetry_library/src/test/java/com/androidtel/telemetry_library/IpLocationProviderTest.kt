package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.location.IpLocationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class IpLocationProviderTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var mockIpifyServer: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var locationProvider: IpLocationProvider

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        
        mockIpifyServer = MockWebServer()
        mockIpifyServer.start()
        
        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        mockIpifyServer.shutdown()
    }

    @Test
    fun `getLocation returns City-Country format on successful API response`() = runBlocking {
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
        
        assertEquals("Nairobi/KE", location)
    }

    @Test
    fun `getLocation returns IP address when city or country is missing`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "",
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
        
        assertEquals("105.163.0.47", location)
    }

    @Test
    fun `getLocation returns Unknown-Unknown when fallbackToIp is false and city is missing`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "",
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
            fallbackToIp = false
        )

        val location = locationProvider.getLocation()
        
        assertEquals("Unknown/Unknown", location)
    }

    @Test
    fun `getLocation falls back to IP echo service on HTTP 429 rate limit`() = runBlocking {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setBody("Rate limit exceeded"))
        
        mockIpifyServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("105.163.0.47"))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true,
            ipEchoEndpoint = mockIpifyServer.url("/").toString()
        )

        val location = locationProvider.getLocation()

        assertEquals("105.163.0.47", location)
    }

    @Test
    fun `getLocation returns Unknown-Unknown on HTTP 429 when fallbackToIp is false`() = runBlocking {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setBody("Rate limit exceeded"))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = false
        )

        val location = locationProvider.getLocation()
        
        assertEquals("Unknown/Unknown", location)
    }

    @Test
    fun `getLocation returns Unknown-Unknown on API failure`() = runBlocking {
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
        
        assertEquals("Unknown/Unknown", location)
    }

    @Test
    fun `getLocation caches result and returns cached value on subsequent calls`() = runBlocking {
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
        val location2 = locationProvider.getLocation()
        
        assertEquals("Nairobi/KE", location1)
        assertEquals("Nairobi/KE", location2)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `getCachedLocation returns null when no location is cached`() {
        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = true
        )

        val cachedLocation = locationProvider.getCachedLocation()
        
        assertNull(cachedLocation)
    }

    @Test
    fun `getCachedLocation returns cached location after successful fetch`() = runBlocking {
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

        locationProvider.getLocation()
        val cachedLocation = locationProvider.getCachedLocation()
        
        assertEquals("Nairobi/KE", cachedLocation)
    }

    @Test
    fun `clearCache removes cached location`() = runBlocking {
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

        locationProvider.getLocation()
        locationProvider.clearCache()
        val cachedLocation = locationProvider.getCachedLocation()
        
        assertNull(cachedLocation)
    }

    @Test
    fun `cache expires after cacheDuration and fetches new location`() = runBlocking {
        val jsonResponse1 = """
            {
                "ip": "105.163.0.47",
                "city": "Nairobi",
                "country": "KE"
            }
        """.trimIndent()
        
        val jsonResponse2 = """
            {
                "ip": "105.163.0.48",
                "city": "Mombasa",
                "country": "KE"
            }
        """.trimIndent()
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse1))
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(jsonResponse2))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 100,
            fallbackToIp = true
        )

        val location1 = locationProvider.getLocation()
        assertEquals("Nairobi/KE", location1)
        
        Thread.sleep(150)
        
        val location2 = locationProvider.getLocation()
        assertEquals("Mombasa/KE", location2)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `getLocation handles malformed JSON gracefully`() = runBlocking {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("not valid json"))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = false
        )

        val location = locationProvider.getLocation()
        
        assertEquals("Unknown/Unknown", location)
    }

    @Test
    fun `getLocation handles empty response body`() = runBlocking {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(""))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = false
        )

        val location = locationProvider.getLocation()
        
        assertEquals("Unknown/Unknown", location)
    }

    @Test
    fun `getLocation handles network timeout gracefully`() = runBlocking {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("{}"))

        locationProvider = IpLocationProvider(
            httpClient = httpClient,
            apiEndpoint = mockWebServer.url("/json").toString(),
            cacheDuration = 3600000,
            fallbackToIp = false
        )

        val location = locationProvider.getLocation()
        
        assertEquals("Unknown/Unknown", location)
    }

    @Test
    fun `getLocation with full country name formats correctly`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "Nairobi",
                "country": "Kenya"
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
        
        assertEquals("Nairobi/Kenya", location)
    }

    @Test
    fun `getLocation handles special characters in city names`() = runBlocking {
        val jsonResponse = """
            {
                "ip": "105.163.0.47",
                "city": "São Paulo",
                "country": "BR"
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
        
        assertEquals("São Paulo/BR", location)
    }

    @Test
    fun `multiple concurrent calls use cache efficiently`() = runBlocking {
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
        val location3 = locationProvider.getCachedLocation()
        
        assertEquals("Nairobi/KE", location1)
        assertEquals("Nairobi/KE", location2)
        assertEquals("Nairobi/KE", location3)
        assertEquals(1, mockWebServer.requestCount)
    }
}
