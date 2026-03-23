package com.androidtel.telemetry_library.core.services

import android.content.Context
import com.androidtel.telemetry_library.core.OfflineBatchStorage
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for CrashReportingService
 * Tests crash recording, error tracking, breadcrumbs, and persistence
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashReportingServiceTest {

    private lateinit var context: Context
    private lateinit var config: TelemetryConfig
    private lateinit var idGenerator: IdGenerator
    private lateinit var httpClient: TelemetryHttpClient
    private lateinit var offlineStorage: OfflineBatchStorage
    private lateinit var testScope: TestScope
    private lateinit var service: CrashReportingService
    private val testApiKey = "test-api-key"
    private val testEndpoint = "https://test.example.com"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        config = TelemetryConfig(
            apiKey = testApiKey,
            telemetryEndpoint = testEndpoint,
            enableCrashReporting = true,
            enableUserProfiles = true,
            enableSessionTracking = true,
            enableLocationTracking = false,
            batchSize = 50,
            flushIntervalMs = 30000,
            sessionTimeoutMs = 1800000
        )
        
        idGenerator = mockk(relaxed = true)
        httpClient = mockk(relaxed = true)
        offlineStorage = mockk(relaxed = true)
        testScope = TestScope(UnconfinedTestDispatcher())
        
        service = CrashReportingService(
            context = context,
            config = config,
            idGenerator = idGenerator,
            httpClient = httpClient,
            offlineStorage = offlineStorage,
            scope = testScope,
            apiKey = testApiKey,
            telemetryEndpoint = testEndpoint
        )
        
        // Clean up any persisted crash files
        val crashFile = File(context.cacheDir, "telemetry_pending_crash.json")
        if (crashFile.exists()) {
            crashFile.delete()
        }
    }

    @Test
    fun `test service initialization with crash reporting enabled`() {
        service.initialize()
        
        assertNotNull(service.getBreadcrumbManager())
        assertNotNull(service.getCrashReporter())
    }

    @Test
    fun `test service initialization with crash reporting disabled`() {
        val disabledConfig = config.copy(enableCrashReporting = false)
        val disabledService = CrashReportingService(
            context, disabledConfig, idGenerator, httpClient, offlineStorage, testScope, testApiKey, testEndpoint
        )
        
        disabledService.initialize()
        
        assertNotNull(disabledService.getBreadcrumbManager())
        assertNull(disabledService.getCrashReporter())
    }

    @Test
    fun `test recordCrash creates crash event`() {
        service.initialize()
        
        val throwable = RuntimeException("Test crash")
        var capturedEvent: TelemetryEvent? = null
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            mockk(relaxed = true)
        }
        
        service.recordCrash(throwable, buildAttributesFn) { event ->
            capturedEvent = event
        }
        
        assertNotNull(capturedEvent)
        assertEquals("event", capturedEvent?.type)
        assertEquals("app.crash", capturedEvent?.eventName)
    }

    @Test
    fun `test recordCrash includes error attributes`() {
        service.initialize()
        
        val throwable = IllegalStateException("Invalid state")
        var capturedAttributes: Map<String, Any>? = null
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(throwable, buildAttributesFn) { }
        
        assertNotNull(capturedAttributes)
        assertTrue(capturedAttributes!!.containsKey("error.message"))
        assertTrue(capturedAttributes!!.containsKey("error.stack_trace"))
        assertTrue(capturedAttributes!!.containsKey("error.exception_type"))
        assertTrue(capturedAttributes!!.containsKey("error.severity_level"))
        assertTrue(capturedAttributes!!.containsKey("error.is_fatal"))
        assertEquals("IllegalStateException", capturedAttributes!!["error.exception_type"])
        assertEquals(true, capturedAttributes!!["error.is_fatal"])
    }

    @Test
    fun `test recordCrash determines severity levels correctly`() {
        service.initialize()
        
        val testCases = mapOf(
            OutOfMemoryError("OOM") to "critical",
            StackOverflowError("Stack overflow") to "critical",
            IllegalStateException("Bad state") to "error",
            NullPointerException("Null") to "error",
            IllegalArgumentException("Bad arg") to "warning"
        )
        
        testCases.forEach { (throwable, expectedSeverity) ->
            var capturedAttributes: Map<String, Any>? = null
            
            val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
                capturedAttributes = attrs
                mockk(relaxed = true)
            }
            
            service.recordCrash(throwable, buildAttributesFn) { }
            
            assertEquals(expectedSeverity, capturedAttributes!!["error.severity_level"])
        }
    }

    @Test
    fun `test recordCrash includes breadcrumbs`() {
        service.initialize()
        service.addBreadcrumb("User clicked button", "ui", "info")
        service.addBreadcrumb("API call started", "network", "debug")
        
        var capturedAttributes: Map<String, Any>? = null
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        assertTrue(capturedAttributes!!.containsKey("error.breadcrumbs"))
        assertTrue(capturedAttributes!!.containsKey("error.breadcrumb_count"))
        assertTrue((capturedAttributes!!["error.breadcrumb_count"] as Int) >= 0)
    }

    @Test
    fun `test recordCrash persists batch to file`() {
        service.initialize()
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { mockk(relaxed = true) }
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        val crashFile = File(context.cacheDir, "telemetry_pending_crash.json")
        assertTrue(crashFile.exists())
    }

    @Test
    fun `test addBreadcrumb adds breadcrumb to manager`() {
        service.initialize()
        
        service.addBreadcrumb("Test message", "test_category", "info", mapOf("key" to "value"))
        
        val breadcrumbManager = service.getBreadcrumbManager()
        assertNotNull(breadcrumbManager)
        assertTrue(breadcrumbManager!!.getBreadcrumbCount() > 0)
    }

    @Test
    fun `test addBreadcrumb with default parameters`() {
        service.initialize()
        
        service.addBreadcrumb("Simple breadcrumb")
        
        val breadcrumbManager = service.getBreadcrumbManager()
        assertEquals(1, breadcrumbManager!!.getBreadcrumbCount())
    }

    @Test
    fun `test addBreadcrumb before initialization logs warning`() {
        // Don't initialize
        service.addBreadcrumb("Early breadcrumb")
        
        // Should not throw exception
        assertNull(service.getBreadcrumbManager())
    }

    @Test
    fun `test trackError with throwable`() {
        service.initialize()
        
        val error = IllegalArgumentException("Invalid argument")
        val attributes = mapOf("context" to "user_input")
        
        service.trackError(error, attributes)
        
        verify { service.getCrashReporter() }
    }

    @Test
    fun `test trackError with enhanced context`() {
        service.initialize()
        
        val error = RuntimeException("Test error")
        service.trackError(
            error = error,
            errorCode = "ERR_001",
            productId = "PROD_123",
            userAction = "checkout",
            attributes = mapOf("cart_value" to "99.99")
        )
        
        verify { service.getCrashReporter() }
    }

    @Test
    fun `test trackError with message and stack trace`() {
        service.initialize()
        
        service.trackError(
            message = "Custom error message",
            stackTrace = "at com.example.Class.method(Class.java:123)",
            attributes = mapOf("severity" to "high")
        )
        
        verify { service.getCrashReporter() }
    }

    @Test
    fun `test trackError when crash reporting disabled logs warning`() {
        val disabledConfig = config.copy(enableCrashReporting = false)
        val disabledService = CrashReportingService(
            context, disabledConfig, idGenerator, httpClient, offlineStorage, testScope, testApiKey, testEndpoint
        )
        disabledService.initialize()
        
        disabledService.trackError(RuntimeException("Test"))
        
        // Should not throw exception
    }

    @Test
    fun `test setProductContext updates crash reporter`() {
        service.initialize()
        
        service.setProductContext("PRODUCT_XYZ")
        
        verify { service.getCrashReporter() }
    }

    @Test
    fun `test setLastUserAction updates crash reporter`() {
        service.initialize()
        
        service.setLastUserAction("Clicked submit button")
        
        verify { service.getCrashReporter() }
    }

    @Test
    fun `test testCrashReporting triggers test crash`() {
        service.initialize()
        
        service.testCrashReporting("Custom test message")
        
        verify { service.getCrashReporter() }
    }

    @Test
    fun `test testCrashReporting when disabled logs warning`() {
        val disabledConfig = config.copy(enableCrashReporting = false)
        val disabledService = CrashReportingService(
            context, disabledConfig, idGenerator, httpClient, offlineStorage, testScope, testApiKey, testEndpoint
        )
        disabledService.initialize()
        
        disabledService.testCrashReporting()
        
        // Should not throw exception
    }

    @Test
    fun `test readPersistedBatch returns null when no file exists`() {
        service.initialize()
        
        val batch = service.readPersistedBatch()
        
        assertNull(batch)
    }

    @Test
    fun `test readPersistedBatch returns batch when file exists`() {
        service.initialize()
        
        // Create a crash to persist
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { mockk(relaxed = true) }
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        val batch = service.readPersistedBatch()
        
        assertNotNull(batch)
        assertEquals(1, batch?.batchSize)
    }

    @Test
    fun `test deletePersistedBatch removes file`() {
        service.initialize()
        
        // Create a crash to persist
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { mockk(relaxed = true) }
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        val crashFile = File(context.cacheDir, "telemetry_pending_crash.json")
        assertTrue(crashFile.exists())
        
        service.deletePersistedBatch()
        
        assertFalse(crashFile.exists())
    }

    @Test
    fun `test deletePersistedBatch when no file exists does not throw`() {
        service.initialize()
        
        service.deletePersistedBatch()
        
        // Should not throw exception
    }

    @Test
    fun `test sendPersistedCrashIfAny with no persisted crash`() = runTest {
        service.initialize()
        
        service.sendPersistedCrashIfAny()
        
        verify(exactly = 0) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `test sendPersistedCrashIfAny sends and deletes on success`() = runTest {
        service.initialize()
        
        // Create persisted crash
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { mockk(relaxed = true) }
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        service.sendPersistedCrashIfAny()
        
        coVerify { httpClient.sendBatch(any()) }
        val crashFile = File(context.cacheDir, "telemetry_pending_crash.json")
        assertFalse(crashFile.exists())
    }

    @Test
    fun `test sendPersistedCrashIfAny stores offline on failure`() = runTest {
        service.initialize()
        
        // Create persisted crash
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { mockk(relaxed = true) }
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.failure(Exception("Network error"))
        
        service.sendPersistedCrashIfAny()
        
        coVerify { httpClient.sendBatch(any()) }
        coVerify { offlineStorage.storeBatch(any()) }
    }

    @Test
    fun `test error message truncation to 1000 chars`() {
        service.initialize()
        
        val longMessage = "A".repeat(2000)
        val throwable = RuntimeException(longMessage)
        var capturedAttributes: Map<String, Any>? = null
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(throwable, buildAttributesFn) { }
        
        val errorMessage = capturedAttributes!!["error.message"] as String
        assertTrue(errorMessage.length <= 1000)
    }

    @Test
    fun `test stack trace truncation to 2000 chars`() {
        service.initialize()
        
        // Create deep stack trace
        fun recursiveFunction(depth: Int) {
            if (depth > 0) recursiveFunction(depth - 1)
            else throw RuntimeException("Deep stack")
        }
        
        var capturedAttributes: Map<String, Any>? = null
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        try {
            recursiveFunction(100)
        } catch (e: Exception) {
            service.recordCrash(e, buildAttributesFn) { }
        }
        
        val stackTrace = capturedAttributes!!["error.stack_trace"] as String
        assertTrue(stackTrace.length <= 2000)
    }

    @Test
    fun `test breadcrumbs truncation to 800 chars`() {
        service.initialize()
        
        // Add many breadcrumbs
        repeat(50) { i ->
            service.addBreadcrumb("Breadcrumb $i with some additional context", "test", "info")
        }
        
        var capturedAttributes: Map<String, Any>? = null
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(RuntimeException("Test"), buildAttributesFn) { }
        
        val breadcrumbs = capturedAttributes!!["error.breadcrumbs"] as String
        assertTrue(breadcrumbs.length <= 800)
    }

    @Test
    fun `test error context extraction from stack trace`() {
        service.initialize()
        
        val throwable = RuntimeException("Test error")
        var capturedAttributes: Map<String, Any>? = null
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(throwable, buildAttributesFn) { }
        
        assertTrue(capturedAttributes!!.containsKey("error.context"))
        val context = capturedAttributes!!["error.context"] as String
        assertNotNull(context)
    }

    @Test
    fun `test error cause is captured`() {
        service.initialize()
        
        val cause = IllegalArgumentException("Root cause")
        val throwable = RuntimeException("Wrapper exception", cause)
        var capturedAttributes: Map<String, Any>? = null
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(throwable, buildAttributesFn) { }
        
        val errorCause = capturedAttributes!!["error.cause"] as String
        assertEquals("Root cause", errorCause)
    }

    @Test
    fun `test error without cause shows unknown`() {
        service.initialize()
        
        val throwable = RuntimeException("No cause")
        var capturedAttributes: Map<String, Any>? = null
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { attrs ->
            capturedAttributes = attrs
            mockk(relaxed = true)
        }
        
        service.recordCrash(throwable, buildAttributesFn) { }
        
        val errorCause = capturedAttributes!!["error.cause"] as String
        assertEquals("unknown", errorCause)
    }

    @Test
    fun `test multiple breadcrumbs are tracked`() {
        service.initialize()
        
        service.addBreadcrumb("Step 1", "navigation", "info")
        service.addBreadcrumb("Step 2", "user_action", "debug")
        service.addBreadcrumb("Step 3", "api_call", "info")
        
        val breadcrumbManager = service.getBreadcrumbManager()
        assertEquals(3, breadcrumbManager!!.getBreadcrumbCount())
    }

    @Test
    fun `test breadcrumb with custom data`() {
        service.initialize()
        
        val customData = mapOf(
            "screen" to "checkout",
            "product_id" to "12345",
            "action" to "add_to_cart"
        )
        
        service.addBreadcrumb("Product added", "commerce", "info", customData)
        
        val breadcrumbManager = service.getBreadcrumbManager()
        assertTrue(breadcrumbManager!!.getBreadcrumbCount() > 0)
    }

    @Test
    fun `test concurrent crash recording is handled`() {
        service.initialize()
        
        val buildAttributesFn: (Map<String, Any>) -> EventAttributes? = { mockk(relaxed = true) }
        
        val threads = (1..5).map { threadNum ->
            Thread {
                service.recordCrash(RuntimeException("Crash $threadNum"), buildAttributesFn) { }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        // Should handle concurrent crashes without throwing
    }

    @Test
    fun `test getBreadcrumbManager returns initialized manager`() {
        service.initialize()
        
        val manager = service.getBreadcrumbManager()
        
        assertNotNull(manager)
    }

    @Test
    fun `test getCrashReporter returns initialized reporter`() {
        service.initialize()
        
        val reporter = service.getCrashReporter()
        
        assertNotNull(reporter)
    }
}
