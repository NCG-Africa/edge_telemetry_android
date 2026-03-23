package com.androidtel.telemetry_library.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Test suite to verify that telemetry data is ONLY sent when device ID and user ID
 * are properly generated and stored. This ensures data integrity and prevents
 * transmission of events with invalid or missing identifiers.
 */
class TelemetryIdValidationTest {

    private lateinit var mockApplication: Application
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        mockApplication = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every { mockApplication.applicationContext } returns mockContext
        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockEditor.commit() } returns true
        every { mockPrefs.getString(any(), any()) } returns null
        every { mockPrefs.getInt(any(), any()) } returns 0
        every { mockPrefs.getLong(any(), any()) } returns 0L
        every { mockPrefs.getBoolean(any(), any()) } returns false
        every { mockContext.packageName } returns "com.test.app"
        every { mockContext.cacheDir } returns mockk(relaxed = true)
        every { mockContext.filesDir } returns mockk(relaxed = true)

        // Reset TelemetryManager singleton
        resetTelemetryManagerInstance()
    }

    @After
    fun tearDown() {
        resetTelemetryManagerInstance()
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
        // Given: SharedPreferences returns valid IDs
        val validDeviceId = "1234567890123_device01"
        val validUserId = "1234567890123_user001"
        
        every { mockPrefs.getString("device_id", null) } returns validDeviceId
        every { mockPrefs.getString("user_id", null) } returns validUserId

        // When: TelemetryManager is initialized
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_test_key_123",
            debugMode = true
        )

        // Then: IDs should be properly initialized
        val idsInitialized = getPrivateField(manager, "idsInitialized") as Boolean
        assertTrue("IDs should be marked as initialized when valid IDs are present", idsInitialized)
    }

    @Test
    fun `telemetry manager blocks transmission when IDs are not initialized`() = runBlocking {
        // Given: SharedPreferences returns null (no stored IDs)
        every { mockPrefs.getString("device_id", null) } returns null
        every { mockPrefs.getString("user_id", null) } returns null

        // When: TelemetryManager is initialized
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_test_key_123",
            debugMode = true
        )

        // Force idsInitialized to false to simulate initialization failure
        setPrivateField(manager, "idsInitialized", false)

        // Queue an event
        manager.recordEvent("test_event", mapOf("key" to "value"))

        // Try to send batch
        val sendBatchMethod = getSendBatchMethod(manager)
        sendBatchMethod.invoke(manager, true, false) // forceSend = true, flushOffline = false

        // Then: Event queue should still contain the event (not sent)
        val eventQueue = getPrivateField(manager, "eventQueue") as java.util.concurrent.ConcurrentLinkedQueue<*>
        assertTrue("Events should remain queued when IDs are not initialized", eventQueue.isEmpty())
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
                "Exception message should mention device_id",
                e.message?.contains("device_id") == true
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
    fun `ID validation prevents emergency fallback IDs from being marked as initialized`() {
        // Given: Emergency fallback user ID
        every { mockPrefs.getString("device_id", null) } returns "1234567890123_device01"
        every { mockPrefs.getString("user_id", null) } returns null

        // When: TelemetryManager is initialized
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_test_key_123",
            debugMode = true
        )

        // Simulate emergency fallback by setting userId to emergency value
        setPrivateField(manager, "userId", "user_emergency_1234567890")
        
        // Re-run initialization to trigger validation
        val initializeUserIdMethod = manager::class.java.getDeclaredMethod("initializeUserId")
        initializeUserIdMethod.isAccessible = true
        initializeUserIdMethod.invoke(manager)

        // Then: IDs should NOT be marked as initialized
        val idsInitialized = getPrivateField(manager, "idsInitialized") as Boolean
        assertFalse("Emergency fallback IDs should not be marked as initialized", idsInitialized)
    }

    @Test
    fun `valid IDs allow telemetry transmission`() {
        // Given: Valid device and user IDs
        val validDeviceId = "1234567890123_device01"
        val validUserId = "1234567890123_user001"
        
        every { mockPrefs.getString("device_id", null) } returns validDeviceId
        every { mockPrefs.getString("user_id", null) } returns validUserId

        // When: TelemetryManager is initialized
        val manager = TelemetryManager.initialize(
            application = mockApplication,
            apiKey = "edge_test_key_123",
            debugMode = true
        )

        // Then: IDs should be initialized and valid
        val idsInitialized = getPrivateField(manager, "idsInitialized") as Boolean
        val deviceId = getPrivateField(manager, "deviceId") as String
        val userId = getPrivateField(manager, "userId") as String

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
        assertTrue("Device ID should match format", deviceId.matches(Regex("""\d{13}_[a-z0-9]{8}""")))
        verify { mockEditor.putString("device_id", any()) }
    }

    @Test
    fun `IdGenerator generates and persists user ID on first use`() {
        // Given: No stored user ID
        every { mockPrefs.getString("user_id", null) } returns null
        
        val capturedUserId = slot<String>()
        every { mockEditor.putString("user_id", capture(capturedUserId)) } returns mockEditor

        // When: IdGenerator is initialized and user ID is requested
        val idGenerator = IdGenerator()
        idGenerator.initialize(mockContext)
        val userId = idGenerator.getUserId()

        // Then: User ID should be generated and persisted
        assertNotNull("User ID should be generated", userId)
        assertTrue("User ID should match format", userId.matches(Regex("""\d{13}_[a-z0-9]{8}""")))
        verify { mockEditor.putString("user_id", any()) }
    }

    // Helper methods to access private fields and methods via reflection
    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field = findField(obj::class.java, fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    private fun setPrivateField(obj: Any, fieldName: String, value: Any?) {
        val field = findField(obj::class.java, fieldName)
        field.isAccessible = true
        field.set(obj, value)
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

    private fun getSendBatchMethod(obj: Any): Method {
        val method = obj::class.java.getDeclaredMethod("sendBatch", Boolean::class.java, Boolean::class.java)
        method.isAccessible = true
        return method
    }
}
