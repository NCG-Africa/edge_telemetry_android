package com.androidtel.telemetry_library

import android.app.Application
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryManager
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TelemetryManagerTest {

    private lateinit var mockApplication: Application

    @Before
    fun setUp() {
        mockApplication = mockk(relaxed = true)
        // Reset singleton instance before each test
        TelemetryManager.resetForTesting()
    }

    @After
    fun tearDown() {
        unmockkAll()
        TelemetryManager.resetForTesting()
    }

    @Test
    fun `initialize with blank API key throws IllegalArgumentException`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = ""
            )
        }
        assertEquals("API key cannot be blank", exception.message)
    }

    @Test
    fun `initialize with whitespace-only API key throws IllegalArgumentException`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = "   "
            )
        }
        assertEquals("API key cannot be blank", exception.message)
    }

    @Test
    fun `initialize with invalid API key format throws IllegalArgumentException`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = "invalid_key_format"
            )
        }
        assertEquals("API key is invalid", exception.message)
    }

    @Test
    fun `initialize with API key not starting with edge_ throws IllegalArgumentException`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = "api_key_12345"
            )
        }
        assertEquals("API key is invalid", exception.message)
    }

    @Test
    fun `initialize with valid API key succeeds`() {
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_valid_api_key_12345"
        )
        assertNotNull(manager)
    }

    @Test
    fun `initialize with TelemetryConfig and blank API key throws IllegalArgumentException`() {
        val config = TelemetryConfig.builder(mockApplication, "")
            .build()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(config)
        }
        assertEquals("API key cannot be blank", exception.message)
    }

    @Test
    fun `initialize with TelemetryConfig and invalid API key throws IllegalArgumentException`() {
        val config = TelemetryConfig.builder(mockApplication, "invalid_format")
            .build()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(config)
        }
        assertEquals("API key is invalid", exception.message)
    }

    @Test
    fun `initialize with TelemetryConfig and valid API key succeeds`() {
        val config = TelemetryConfig.builder(mockApplication, "edge_valid_key_123")
            .debugMode(true)
            .batchSize(50)
            .build()

        val manager = TelemetryManager.initialize(config)
        assertNotNull(manager)
    }

    @Test
    fun `singleton instance is maintained across multiple initialize calls`() {
        val manager1 = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_test_key_1"
        )
        val manager2 = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_test_key_2"
        )
        assertSame(manager1, manager2)
    }

    @Test
    fun `API key validation happens before any initialization`() {
        try {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = ""
            )
            fail("Expected IllegalArgumentException to be thrown")
        } catch (e: IllegalArgumentException) {
            assertEquals("API key cannot be blank", e.message)
        }
    }

    @Test
    fun `error message is helpful for blank API key`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = ""
            )
        }
        assertTrue(exception.message?.contains("API key") == true)
        assertTrue(exception.message?.contains("blank") == true)
    }

    @Test
    fun `error message is helpful for invalid API key format`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TelemetryManager.initialize(
                application = mockApplication,
                apiKey = "wrong_prefix_key"
            )
        }
        assertTrue(exception.message?.contains("API key") == true)
        assertTrue(exception.message?.contains("invalid") == true)
    }

    @Test
    fun `existing valid initializations continue to work`() {
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_valid_key_abc123",
            batchSize = 30,
            debugMode = false,
            enableCrashReporting = true
        )
        assertNotNull(manager)
    }

    @Test
    fun `API key with edge_ prefix and special characters is valid`() {
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_key-with_special.chars123"
        )
        assertNotNull(manager)
    }

    @Test
    fun `API key with only edge_ prefix is valid`() {
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_"
        )
        assertNotNull(manager)
    }
}
