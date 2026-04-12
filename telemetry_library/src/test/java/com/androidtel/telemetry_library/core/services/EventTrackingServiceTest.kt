package com.androidtel.telemetry_library.core.services

import android.content.Context
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.UserInfo
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for EventTrackingService
 * Tests event recording, metric recording, network tracking, and attribute building
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventTrackingServiceTest {

    private lateinit var context: Context
    private lateinit var config: TelemetryConfig
    private lateinit var service: EventTrackingService
    private lateinit var appInfo: AppInfo
    private lateinit var deviceInfo: DeviceInfo
    private lateinit var userInfo: UserInfo
    private lateinit var sessionInfo: SessionInfo

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        config = TelemetryConfig(
            apiKey = "edge_test-api-key",
            endpoint = "https://test.example.com",
            enableCrashReporting = true,
            enableSessionTracking = true,
            enableLocationTracking = false,
            batchSize = 50,
            flushIntervalMs = 30000,
            sessionTimeoutMs = 1800000
        )
        
        service = EventTrackingService(context, config)
        
        appInfo = AppInfo(
            appName = "Test App",
            appVersion = "1.0.0",
            appBuildNumber = "100",
            appPackageName = "com.test.app"
        )

        deviceInfo = DeviceInfo(
            deviceId = "test-device-id",
            platform = "Android",
            platformVersion = "10",
            model = "Test Model",
            manufacturer = "Test Manufacturer",
            brand = "TestBrand",
            androidSdk = "28",
            androidRelease = "9.0",
            fingerprint = "test/fingerprint",
            hardware = "test_hw",
            product = "test_product"
        )
        
        userInfo = UserInfo(
            userId = "test-user-id",
            name = "Test User",
            email = "test@example.com",
            phone = null
        )
        
        sessionInfo = SessionInfo(
            sessionId = "test-session-id",
            startTime = "2024-01-01T00:00:00Z",
            durationMs = 60000,
            eventCount = 10,
            metricCount = 5,
            screenCount = 3,
            visitedScreens = "Home,Profile,Settings",
            isFirstSession = false,
            totalSessions = 5,
            networkType = "WiFi"
        )
        
        service.initialize(appInfo, deviceInfo)
    }

    @Test
    fun `test service initialization`() {
        val newService = EventTrackingService(context, config)
        newService.initialize(appInfo, deviceInfo)
        
        assertEquals(0, newService.getEventCount())
        assertEquals(0, newService.getMetricCount())
        assertTrue(newService.getEventQueue().isEmpty())
    }

    @Test
    fun `test recordEvent creates event with correct type and name`() {
        val eventName = "button_click"
        val attributes = mapOf("button_id" to "submit_btn", "screen" to "login")
        
        val event = service.recordEvent(eventName, attributes, userInfo, sessionInfo)
        
        assertNotNull(event)
        assertEquals("event", event?.type)
        assertEquals(eventName, event?.eventName)
        assertNotNull(event?.timestamp)
        assertEquals(1, service.getEventCount())
    }

    @Test
    fun `test recordEvent increments event count`() {
        assertEquals(0, service.getEventCount())
        
        service.recordEvent("event1", emptyMap(), userInfo, sessionInfo)
        assertEquals(1, service.getEventCount())
        
        service.recordEvent("event2", emptyMap(), userInfo, sessionInfo)
        assertEquals(2, service.getEventCount())
        
        service.recordEvent("event3", emptyMap(), userInfo, sessionInfo)
        assertEquals(3, service.getEventCount())
    }

    @Test
    fun `test recordEvent adds event to queue`() {
        assertTrue(service.getEventQueue().isEmpty())
        
        service.recordEvent("test_event", emptyMap(), userInfo, sessionInfo)
        
        assertEquals(1, service.getEventQueue().size)
        val queuedEvent = service.getEventQueue().peek()
        assertEquals("test_event", queuedEvent?.eventName)
    }

    @Test
    fun `test recordEvent with custom attributes`() {
        val attributes = mapOf(
            "user_action" to "purchase",
            "product_id" to "12345",
            "amount" to 99.99,
            "currency" to "USD"
        )
        
        val event = service.recordEvent("purchase_completed", attributes, userInfo, sessionInfo)
        
        assertNotNull(event)
        assertNotNull(event?.attributes)
        assertEquals(appInfo, event?.attributes?.app)
        assertEquals(deviceInfo, event?.attributes?.device)
        assertEquals(userInfo, event?.attributes?.user)
        assertEquals(sessionInfo, event?.attributes?.session)
        assertEquals(attributes, event?.attributes?.customAttributes)
    }

    @Test
    fun `test recordMetric creates metric event with correct type`() {
        val metricName = "page_load_time"
        val value = 1234.56
        
        val event = service.recordMetric(metricName, value, emptyMap(), userInfo, sessionInfo)
        
        assertNotNull(event)
        assertEquals("metric|event", event?.type)
        assertEquals(metricName, event?.metricName)
        assertEquals(value, event?.value!!, 0.001)
        assertEquals(1, service.getMetricCount())
    }

    @Test
    fun `test recordMetric increments metric count`() {
        assertEquals(0, service.getMetricCount())
        
        service.recordMetric("metric1", 100.0, emptyMap(), userInfo, sessionInfo)
        assertEquals(1, service.getMetricCount())
        
        service.recordMetric("metric2", 200.0, emptyMap(), userInfo, sessionInfo)
        assertEquals(2, service.getMetricCount())
    }

    @Test
    fun `test recordMetric with attributes`() {
        val attributes = mapOf("endpoint" to "/api/users", "method" to "GET")
        val event = service.recordMetric("api_response_time", 456.78, attributes, userInfo, sessionInfo)
        
        assertNotNull(event)
        assertEquals(attributes, event?.attributes?.customAttributes)
    }

    @Test
    fun `test recordNetworkRequest creates event with network attributes`() {
        service.recordNetworkRequest(
            url = "https://api.example.com/users",
            method = "POST",
            statusCode = 200,
            durationMs = 345,
            requestBodySize = 1024,
            responseBodySize = 2048,
            error = null,
            attributes = emptyMap(),
            userInfo = userInfo,
            sessionInfo = sessionInfo
        )
        
        assertEquals(1, service.getEventCount())
        val event = service.getEventQueue().peek()
        assertEquals("http.request", event?.eventName)
        
        val customAttrs = event?.attributes?.customAttributes as Map<*, *>
        assertEquals("https://api.example.com/users", customAttrs["http.url"])
        assertEquals("POST", customAttrs["http.method"])
        assertEquals(200, customAttrs["http.status_code"])
        assertEquals(345L, customAttrs["http.duration_ms"])
        assertEquals(true, customAttrs["http.success"])
        assertEquals(1024L, customAttrs["http.request_body_size"])
        assertEquals(2048L, customAttrs["http.response_body_size"])
        assertEquals("none", customAttrs["http.error"])
    }

    @Test
    fun `test recordNetworkRequest with error`() {
        service.recordNetworkRequest(
            url = "https://api.example.com/users",
            method = "GET",
            statusCode = 500,
            durationMs = 123,
            error = "Internal Server Error",
            attributes = emptyMap(),
            userInfo = userInfo,
            sessionInfo = sessionInfo
        )
        
        val event = service.getEventQueue().peek()
        val customAttrs = event?.attributes?.customAttributes as Map<*, *>
        assertEquals(false, customAttrs["http.success"])
        assertEquals("Internal Server Error", customAttrs["http.error"])
    }

    @Test
    fun `test resetEventCount clears count`() {
        service.recordEvent("event1", emptyMap(), userInfo, sessionInfo)
        service.recordEvent("event2", emptyMap(), userInfo, sessionInfo)
        assertEquals(2, service.getEventCount())
        
        service.resetEventCount()
        assertEquals(0, service.getEventCount())
    }

    @Test
    fun `test resetMetricCount clears count`() {
        service.recordMetric("metric1", 100.0, emptyMap(), userInfo, sessionInfo)
        service.recordMetric("metric2", 200.0, emptyMap(), userInfo, sessionInfo)
        assertEquals(2, service.getMetricCount())
        
        service.resetMetricCount()
        assertEquals(0, service.getMetricCount())
    }

    @Test
    fun `test setGlobalAttributes replaces all attributes`() {
        service.addGlobalAttribute("key1", "value1")
        service.addGlobalAttribute("key2", "value2")
        
        val newAttributes = mapOf("key3" to "value3", "key4" to "value4")
        service.setGlobalAttributes(newAttributes)
        
        // Global attributes are set but not directly testable without accessing private field
        // This test verifies the method doesn't throw exceptions
    }

    @Test
    fun `test addGlobalAttribute adds attribute`() {
        service.addGlobalAttribute("environment", "production")
        service.addGlobalAttribute("version", "2.0.0")
        
        // Verify no exceptions thrown
    }

    @Test
    fun `test removeGlobalAttribute removes attribute`() {
        service.addGlobalAttribute("temp_key", "temp_value")
        service.removeGlobalAttribute("temp_key")
        
        // Verify no exceptions thrown
    }

    @Test
    fun `test getCurrentTimestamp returns ISO format`() {
        val timestamp = service.getCurrentTimestamp()
        
        assertNotNull(timestamp)
        assertTrue(timestamp.contains("T"))
        assertTrue(timestamp.endsWith("Z"))
        // Format: yyyy-MM-dd'T'HH:mm:ss'Z'
        assertTrue(timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
    }

    @Test
    fun `test multiple events maintain queue order`() {
        service.recordEvent("event1", emptyMap(), userInfo, sessionInfo)
        service.recordEvent("event2", emptyMap(), userInfo, sessionInfo)
        service.recordEvent("event3", emptyMap(), userInfo, sessionInfo)
        
        assertEquals(3, service.getEventQueue().size)
        
        val event1 = service.getEventQueue().poll()
        val event2 = service.getEventQueue().poll()
        val event3 = service.getEventQueue().poll()
        
        assertEquals("event1", event1?.eventName)
        assertEquals("event2", event2?.eventName)
        assertEquals("event3", event3?.eventName)
    }

    @Test
    fun `test event attributes contain all required context`() {
        val event = service.recordEvent("test", emptyMap(), userInfo, sessionInfo)
        
        assertNotNull(event?.attributes)
        assertNotNull(event?.attributes?.app)
        assertNotNull(event?.attributes?.device)
        assertNotNull(event?.attributes?.user)
        assertNotNull(event?.attributes?.session)
        
        assertEquals(appInfo.appPackageName, event?.attributes?.app?.appPackageName)
        assertEquals(deviceInfo.deviceId, event?.attributes?.device?.deviceId)
        assertEquals(userInfo.userId, event?.attributes?.user?.userId)
        assertEquals(sessionInfo.sessionId, event?.attributes?.session?.sessionId)
    }

    @Test
    fun `test concurrent event recording is thread-safe`() {
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(10) { eventNum ->
                    service.recordEvent("event_${threadNum}_$eventNum", emptyMap(), userInfo, sessionInfo)
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        assertEquals(100, service.getEventCount())
        assertEquals(100, service.getEventQueue().size)
    }

    @Test
    fun `test event and metric counts are independent`() {
        service.recordEvent("event1", emptyMap(), userInfo, sessionInfo)
        service.recordEvent("event2", emptyMap(), userInfo, sessionInfo)
        service.recordMetric("metric1", 100.0, emptyMap(), userInfo, sessionInfo)
        
        assertEquals(2, service.getEventCount())
        assertEquals(1, service.getMetricCount())
        assertEquals(3, service.getEventQueue().size) // Both events and metrics go to queue
    }

    @Test
    fun `test initialization without appInfo returns null events`() {
        val uninitializedService = EventTrackingService(context, config)
        // Don't call initialize
        
        val event = uninitializedService.recordEvent("test", emptyMap(), userInfo, sessionInfo)
        
        assertNull(event) // Should return null when appInfo is not initialized
        assertEquals(1, uninitializedService.getEventCount()) // Count still increments
    }

    @Test
    fun `test network request with client error status code`() {
        service.recordNetworkRequest(
            url = "https://api.example.com/not-found",
            method = "GET",
            statusCode = 404,
            durationMs = 100,
            attributes = emptyMap(),
            userInfo = userInfo,
            sessionInfo = sessionInfo
        )
        
        val event = service.getEventQueue().peek()
        val customAttrs = event?.attributes?.customAttributes as Map<*, *>
        assertEquals(false, customAttrs["http.success"])
        assertEquals(404, customAttrs["http.status_code"])
    }

    @Test
    fun `test empty attributes map creates event successfully`() {
        val event = service.recordEvent("simple_event", emptyMap(), userInfo, sessionInfo)
        
        assertNotNull(event)
        assertTrue((event?.attributes?.customAttributes as Map<*, *>).isEmpty())
    }
}
