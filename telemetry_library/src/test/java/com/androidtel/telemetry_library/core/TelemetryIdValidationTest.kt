package com.androidtel.telemetry_library.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.services.BatchProcessingService
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import com.androidtel.telemetry_library.core.CountedEventQueue

/**
 * Test suite to verify that telemetry data is ONLY sent when device ID and user ID
 * are properly generated and stored. This ensures data integrity and prevents
 * transmission of events with invalid or missing identifiers.
 *
 * Uses a real Robolectric Application for TelemetryManager initialization (the established pattern
 * across this module); IdGenerator-only tests still use lightweight mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TelemetryIdValidationTest {

    // Real application/context for full TelemetryManager init.
    private lateinit var application: Application

    // Lightweight mocks for IdGenerator-only tests.
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    // IdGenerator persists into these prefs/keys.
    private val idPrefs: SharedPreferences
        get() = application.getSharedPreferences("edge_telemetry_ids", Context.MODE_PRIVATE)

    @Before
    fun setup() {
        application = RuntimeEnvironment.getApplication()

        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockPrefs.getString(any(), any()) } returns null

        // Start each test from a clean ID store and singleton.
        idPrefs.edit().clear().apply()
        resetTelemetryManagerInstance()
    }

    @After
    fun tearDown() {
        resetTelemetryManagerInstance()
        idPrefs.edit().clear().apply()
        clearAllMocks()
    }

    private fun resetTelemetryManagerInstance() {
        try {
            val instanceField = TelemetryManager::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            // Ignore if field doesn't exist or can't be reset
        }
    }

    @Test
    fun `telemetry manager initializes with valid device ID and user ID`() {
        // When: TelemetryManager is initialized
        val config = TelemetryConfig(
            apiKey = "edge_test_key_123",
            endpoint = "https://telemetry.ncgafrica.com/telemetry"
        )
        val manager = TelemetryManager.initialize(application, config)

        // Then: IDs should be properly initialized
        val idsInitialized = getPrivateField(manager, "idsInitialized") as Boolean
        assertTrue("IDs should be marked as initialized after successful init", idsInitialized)
    }

    @Test
    fun `telemetry manager blocks transmission when IDs are not initialized`() = runBlocking {
        // Given: A batch-processing service that has NOT been marked as IDs-initialized
        val httpClient = mockk<TelemetryHttpClient>(relaxed = true)
        val offlineStorage = mockk<OfflineBatchStorage>(relaxed = true)
        val config = TelemetryConfig(
            apiKey = "edge_test_key_123",
            endpoint = "https://telemetry.ncgafrica.com/telemetry"
        )
        val service = BatchProcessingService(
            config, httpClient, offlineStorage, CoroutineScope(Dispatchers.Unconfined)
        )
        // Note: setIdsInitialized(true) is deliberately NOT called.

        val eventQueue = CountedEventQueue()
        eventQueue.enqueue(mockk(relaxed = true))

        // When: a send is forced while IDs are not initialized
        service.sendBatch(eventQueue, forceSend = true, flushOffline = false)

        // Then: nothing is transmitted and events remain queued
        coVerify(exactly = 0) { httpClient.sendBatch(any()) }
        assertFalse("Events should remain queued when IDs are not initialized", eventQueue.isEmpty())
    }

    @Test
    fun `telemetry batch conversion throws exception when device ID is blank`() {
        // Given: A batch with blank device ID
        val httpClient = TelemetryHttpClient(
            telemetryUrl = "https://test.endpoint",
            apiKey = "edge_test_key",
            debugMode = true
        )

        val mockEvent = mockk<TelemetryEvent>(relaxed = true)
        val mockAttributes = mockk<com.androidtel.telemetry_library.core.models.EventAttributes>(relaxed = true)
        val mockDevice = mockk<com.androidtel.telemetry_library.core.models.DeviceInfo>(relaxed = true)

        every { mockEvent.attributes } returns mockAttributes
        every { mockAttributes.device } returns mockDevice
        every { mockDevice.deviceId } returns "" // Blank device ID

        val batch = TelemetryBatch(
            batchSize = 1,
            timestamp = "2024-01-01T00:00:00Z",
            events = listOf(mockEvent),
            location = null
        )

        // When/Then: Converting batch to JSON should throw exception
        try {
            httpClient.run { batch.toJson() }
            fail("Should throw IllegalStateException when device ID is blank")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message should mention device.id",
                e.message?.contains("device.id") == true
            )
        }
    }

    @Test
    fun `telemetry batch conversion throws exception when user ID is blank`() {
        // Given: A batch with blank user ID
        val httpClient = TelemetryHttpClient(
            telemetryUrl = "https://test.endpoint",
            apiKey = "edge_test_key",
            debugMode = true
        )

        val mockEvent = mockk<TelemetryEvent>(relaxed = true)
        val mockAttributes = mockk<com.androidtel.telemetry_library.core.models.EventAttributes>(relaxed = true)
        val mockDevice = mockk<com.androidtel.telemetry_library.core.models.DeviceInfo>(relaxed = true)
        val mockUser = mockk<com.androidtel.telemetry_library.core.models.UserInfo>(relaxed = true)

        every { mockEvent.attributes } returns mockAttributes
        every { mockAttributes.device } returns mockDevice
        every { mockAttributes.user } returns mockUser
        every { mockDevice.deviceId } returns "1234567890123_device01"
        every { mockUser.userId } returns "" // Blank user ID

        val batch = TelemetryBatch(
            batchSize = 1,
            timestamp = "2024-01-01T00:00:00Z",
            events = listOf(mockEvent),
            location = null
        )

        // When/Then: Converting batch to JSON should throw exception
        try {
            httpClient.run { batch.toJson() }
            fail("Should throw IllegalStateException when user ID is blank")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message should mention user.id",
                e.message?.contains("user.id") == true
            )
        }
    }

    @Test
    fun `valid IDs allow telemetry transmission`() {
        // Given: Valid device and user IDs already persisted in the ID store
        val validDeviceId = "1234567890123_device01"
        val validUserId = "1234567890123_user001"
        idPrefs.edit()
            .putString("device_id", validDeviceId)
            .putString("edge_rum_user_id", validUserId)
            .apply()

        // When: TelemetryManager is initialized
        val config = TelemetryConfig(
            apiKey = "edge_test_key_123",
            endpoint = "https://telemetry.ncgafrica.com/telemetry"
        )
        val manager = TelemetryManager.initialize(application, config)

        // Then: IDs should be initialized and valid
        val idsInitialized = getPrivateField(manager, "idsInitialized") as Boolean
        val deviceId = manager.getDeviceId()
        val userId = manager.getUserId()

        assertTrue("IDs should be marked as initialized", idsInitialized)
        assertEquals("Device ID should match", validDeviceId, deviceId)
        assertEquals("User ID should match", validUserId, userId)
        assertFalse("Device ID should not be emergency fallback", deviceId.startsWith("user_emergency_"))
        assertFalse("User ID should not be emergency fallback", userId.startsWith("user_emergency_"))
    }

    @Test
    fun `IdGenerator generates and persists device ID on first use`() {
        // Given: No stored device ID
        every { mockPrefs.getString("device_id", null) } returns null

        val capturedDeviceId = slot<String>()
        every { mockEditor.putString("device_id", capture(capturedDeviceId)) } returns mockEditor

        // When: IdGenerator is initialized and device ID is requested
        val idGenerator = IdGenerator()
        idGenerator.initialize(mockContext)
        val deviceId = idGenerator.getOrGenerateDeviceId()

        // Then: Device ID should be generated and persisted
        assertNotNull("Device ID should be generated", deviceId)
        assertTrue("Device ID should match format", deviceId.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
        verify { mockEditor.putString("device_id", any()) }
    }

    @Test
    fun `IdGenerator generates and persists user ID on first use`() {
        // Given: No stored user ID
        every { mockPrefs.getString("edge_rum_user_id", null) } returns null

        val capturedUserId = slot<String>()
        every { mockEditor.putString("edge_rum_user_id", capture(capturedUserId)) } returns mockEditor

        // When: IdGenerator is initialized and user ID is requested
        val idGenerator = IdGenerator()
        idGenerator.initialize(mockContext)
        val userId = idGenerator.getUserId()

        // Then: User ID should be generated and persisted
        assertNotNull("User ID should be generated", userId)
        assertTrue("User ID should match format", userId.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
        verify { mockEditor.putString("edge_rum_user_id", any()) }
    }

    // Helper methods to access private fields via reflection
    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field = findField(obj::class.java, fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field {
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found in class hierarchy")
    }
}
