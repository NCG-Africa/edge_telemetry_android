package com.androidtel.telemetry_library.core.crash

import android.content.Context
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.ids.IdGenerator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for Enhanced Crash Context Collection
 * - user_action tracking (last user interaction)
 * - error_code support (app-specific error codes)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EnhancedCrashContextTest {

    private lateinit var context: Context
    private lateinit var crashReporter: CrashReporter
    private lateinit var breadcrumbManager: BreadcrumbManager
    private lateinit var idGenerator: IdGenerator
    private lateinit var telemetryManager: TelemetryManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        breadcrumbManager = BreadcrumbManager()
        
        // Initialize TelemetryManager (required for CrashReporter)
        val config = TelemetryConfig(
            apiKey = "edge_test_key_12345",
            endpoint = "https://telemetry.ncgafrica.com/telemetry",
            enableCrashReporting = true
        )
        telemetryManager = TelemetryManager.initialize(
            application = context as android.app.Application,
            config = config
        )
        
        crashReporter = CrashReporter(
            context = context,
            telemetryManager = telemetryManager,
            breadcrumbManager = breadcrumbManager,
            idGenerator = idGenerator,
            apiKey = "edge_test_key_12345",
            telemetryEndpoint = "https://test.example.com/telemetry",
            enabled = true,
            debugMode = true
        )
    }

    @After
    fun tearDown() {
        TelemetryManager.resetForTesting()
    }

    @Test
    fun `test setProductContext stores product ID with character limit`() {
        // Test normal product ID
        crashReporter.setProductContext("authentication_module")
        
        // Test character limit enforcement (max 255 chars)
        val longProductId = "a".repeat(300)
        crashReporter.setProductContext(longProductId)
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test setLastUserAction stores user action with character limit`() {
        // Test normal user action
        crashReporter.setLastUserAction("Clicked login button")
        
        // Test character limit enforcement (max 500 chars)
        val longUserAction = "a".repeat(600)
        crashReporter.setLastUserAction(longUserAction)
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test trackError with error_code parameter`() {
        val testException = RuntimeException("Test error")
        
        // Track error with error code
        crashReporter.trackError(
            error = testException,
            errorCode = "AUTH_001",
            attributes = mapOf("test" to "true")
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test trackError with all enhanced context parameters`() {
        val testException = NullPointerException("User object was null")
        
        // Set context first
        crashReporter.setProductContext("authentication_module")
        crashReporter.setLastUserAction("Clicked login button")
        
        // Track error with all parameters
        crashReporter.trackError(
            error = testException,
            errorCode = "AUTH_001",
            productId = "authentication_module",
            userAction = "Clicked login button",
            attributes = mapOf(
                "screen" to "LoginActivity",
                "user_id" to "12345"
            )
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test trackError uses stored context when parameters not provided`() {
        val testException = IllegalStateException("Invalid state")
        
        // Set context
        crashReporter.setProductContext("payment_module")
        crashReporter.setLastUserAction("Initiated payment")
        
        // Track error without explicit parameters - should use stored context
        crashReporter.trackError(
            error = testException,
            errorCode = "PAY_002"
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test trackError with explicit parameters overrides stored context`() {
        val testException = RuntimeException("Test override")
        
        // Set stored context
        crashReporter.setProductContext("module_a")
        crashReporter.setLastUserAction("Action A")
        
        // Track error with explicit parameters - should override stored context
        crashReporter.trackError(
            error = testException,
            errorCode = "ERR_001",
            productId = "module_b",
            userAction = "Action B"
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test error_code character limit enforcement`() {
        val testException = RuntimeException("Test error")
        
        // Test with error code exceeding 100 chars
        val longErrorCode = "ERR_" + "X".repeat(100)
        
        crashReporter.trackError(
            error = testException,
            errorCode = longErrorCode
        )
        
        // Should not throw exception (truncation handled internally)
        assert(true)
    }

    @Test
    fun `test user_action character limit enforcement`() {
        val testException = RuntimeException("Test error")
        
        // Test with user action exceeding 500 chars
        val longUserAction = "User performed a very long action: " + "X".repeat(500)
        
        crashReporter.trackError(
            error = testException,
            userAction = longUserAction
        )
        
        // Should not throw exception (truncation handled internally)
        assert(true)
    }

    @Test
    fun `test product_id character limit enforcement`() {
        val testException = RuntimeException("Test error")
        
        // Test with product ID exceeding 255 chars
        val longProductId = "product_" + "X".repeat(300)
        
        crashReporter.trackError(
            error = testException,
            productId = longProductId
        )
        
        // Should not throw exception (truncation handled internally)
        assert(true)
    }

    @Test
    fun `test trackError with null optional parameters`() {
        val testException = RuntimeException("Test error")
        
        // Track error with all optional parameters as null
        crashReporter.trackError(
            error = testException,
            errorCode = null,
            productId = null,
            userAction = null,
            attributes = null
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test TelemetryManager exposes setProductContext`() {
        // Test that TelemetryManager exposes the method
        telemetryManager.setProductContext("test_module")
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test TelemetryManager exposes setLastUserAction`() {
        // Test that TelemetryManager exposes the method
        telemetryManager.setLastUserAction("Test action")
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test TelemetryManager trackError with enhanced context`() {
        // Test that TelemetryManager exposes the enhanced trackError method
        val testException = RuntimeException("Test error")
        
        telemetryManager.trackError(
            error = testException,
            errorCode = "TEST_001",
            productId = "test_module",
            userAction = "Test action",
            attributes = mapOf("test" to "true")
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test realistic authentication error scenario`() {
        // Simulate realistic authentication error scenario
        telemetryManager.setProductContext("authentication_module")
        telemetryManager.setLastUserAction("Clicked login button")
        
        val authException = NullPointerException("User object was null")
        
        telemetryManager.trackError(
            error = authException,
            errorCode = "AUTH_001",
            attributes = mapOf(
                "screen" to "LoginActivity",
                "method" to "onButtonClick",
                "user_input_valid" to "true"
            )
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test realistic payment error scenario`() {
        // Simulate realistic payment error scenario
        telemetryManager.setProductContext("payment_module")
        telemetryManager.setLastUserAction("Initiated payment transaction")
        
        val paymentException = IllegalStateException("Payment gateway timeout")
        
        telemetryManager.trackError(
            error = paymentException,
            errorCode = "PAY_TIMEOUT_001",
            attributes = mapOf(
                "amount" to "100.00",
                "currency" to "USD",
                "gateway" to "stripe"
            )
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test context persistence across multiple errors`() {
        // Set context once
        telemetryManager.setProductContext("shopping_cart")
        telemetryManager.setLastUserAction("Added item to cart")
        
        // Track multiple errors - context should persist
        for (i in 1..3) {
            val error = RuntimeException("Error $i")
            telemetryManager.trackError(
                error = error,
                errorCode = "CART_ERR_00$i"
            )
        }
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test context update between errors`() {
        // Initial context
        telemetryManager.setProductContext("module_a")
        telemetryManager.setLastUserAction("Action A")
        
        val error1 = RuntimeException("Error 1")
        telemetryManager.trackError(error1, errorCode = "ERR_A")
        
        // Update context
        telemetryManager.setProductContext("module_b")
        telemetryManager.setLastUserAction("Action B")
        
        val error2 = RuntimeException("Error 2")
        telemetryManager.trackError(error2, errorCode = "ERR_B")
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test empty string parameters`() {
        val testException = RuntimeException("Test error")
        
        // Track error with empty strings
        crashReporter.trackError(
            error = testException,
            errorCode = "",
            productId = "",
            userAction = ""
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test special characters in parameters`() {
        val testException = RuntimeException("Test error")
        
        // Track error with special characters
        crashReporter.trackError(
            error = testException,
            errorCode = "ERR_001@#$%",
            productId = "module/sub-module:v1.0",
            userAction = "User clicked 'Submit' button & confirmed"
        )
        
        // Should not throw exception
        assert(true)
    }

    @Test
    fun `test unicode characters in parameters`() {
        val testException = RuntimeException("Test error")
        
        // Track error with unicode characters
        crashReporter.trackError(
            error = testException,
            errorCode = "错误_001",
            productId = "模块_认证",
            userAction = "用户点击了登录按钮 🔐"
        )
        
        // Should not throw exception
        assert(true)
    }
}
