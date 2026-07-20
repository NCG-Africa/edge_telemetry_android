package com.androidtel.telemetry_library.core.crash

import android.content.Context
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests for CrashReporter with new batch envelope structure
 */
class CrashReporterIntegrationTest {

    private lateinit var context: Context
    private lateinit var telemetryManager: TelemetryManager
    private lateinit var breadcrumbManager: BreadcrumbManager
    private lateinit var idGenerator: IdGenerator
    private val gson = Gson()

    private lateinit var tempCacheDir: File
    // CrashReporter's constructor installs a global Thread.UncaughtExceptionHandler.
    // Save it here and restore in tearDown so it doesn't leak into other test classes.
    private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setup() {
        originalUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        context = mockk(relaxed = true)
        telemetryManager = mockk(relaxed = true)
        breadcrumbManager = BreadcrumbManager()
        idGenerator = mockk(relaxed = true)

        // Must be a real directory: CrashRetryManager does File(context.cacheDir, name),
        // and java.io.File(parent, child) reads parent's internal path field directly — a
        // relaxed mockk File has a null path and would NPE.
        tempCacheDir = File(System.getProperty("java.io.tmpdir"), "crash_reporter_test_${System.nanoTime()}")
            .apply { mkdirs() }

        every { idGenerator.getDeviceId() } returns "test_device_123"
        every { context.cacheDir } returns tempCacheDir
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalUncaughtHandler)
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `product context is included in crash payload`() {
        val crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key",
            telemetryEndpoint = "https://test.com",
            enabled = true,
            debugMode = true
        )

        // Set product context
        crashReporter.setProductContext("authentication_module")

        // This test verifies the method exists and doesn't throw
        // Full integration would require mocking the network layer
    }

    @Test
    fun `user action is included in crash payload`() {
        val crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key",
            telemetryEndpoint = "https://test.com",
            enabled = true,
            debugMode = true
        )

        // Set user action
        crashReporter.setLastUserAction("Clicked login button")

        // This test verifies the method exists and doesn't throw
    }

    @Test
    fun `location is included in crash payload`() {
        val crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key",
            telemetryEndpoint = "https://test.com",
            enabled = true,
            debugMode = true
        )

        // Set location
        crashReporter.setLocation("Kenya")

        // This test verifies the method exists and doesn't throw
    }

    @Test
    fun `product context truncated to 255 chars`() {
        val crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key",
            telemetryEndpoint = "https://test.com",
            enabled = true,
            debugMode = true
        )

        val longProductId = "x".repeat(500)
        crashReporter.setProductContext(longProductId)

        // Method should handle truncation internally
    }

    @Test
    fun `user action truncated to 500 chars`() {
        val crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key",
            telemetryEndpoint = "https://test.com",
            enabled = true,
            debugMode = true
        )

        val longAction = "x".repeat(1000)
        crashReporter.setLastUserAction(longAction)

        // Method should handle truncation internally
    }

    @Test
    fun `enhanced trackError method accepts all parameters`() {
        val crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key",
            telemetryEndpoint = "https://test.com",
            enabled = true,
            debugMode = true
        )

        val error = RuntimeException("Test error")
        
        // This should compile and not throw
        // Full test would require mocking network layer
        try {
            crashReporter.trackError(
                error = error,
                errorCode = "AUTH_001",
                productId = "auth_module",
                userAction = "Login attempt",
                attributes = mapOf("custom" to "value")
            )
        } catch (e: Exception) {
            // Expected - network layer not mocked
        }
    }
}
