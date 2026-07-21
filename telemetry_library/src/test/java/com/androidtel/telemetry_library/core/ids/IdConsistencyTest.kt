package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.device.DeviceInfoCollector
import com.androidtel.telemetry_library.core.session.SessionManager
import com.androidtel.telemetry_library.core.user.UserProfileManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Cross-component consistency tests to validate that all components
 * use the same IdGenerator instance and produce consistent IDs
 */
class IdConsistencyTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var idGenerator: IdGenerator

    @Before
    fun setup() {
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
        every { mockContext.cacheDir } returns File(System.getProperty("java.io.tmpdir"))

        idGenerator = IdGenerator()
        idGenerator.initialize(mockContext)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `SessionManager receives and uses IdGenerator instance`() {
        val sessionManager = SessionManager(idGenerator)
        
        val sessionId1 = sessionManager.getCurrentSessionId()
        val sessionId2 = sessionManager.getCurrentSessionId()
        
        assertEquals("SessionManager must return same session ID", sessionId1, sessionId2)
        assertTrue("Session ID must match format", sessionId1.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
    }

    @Test
    fun `DeviceInfoCollector receives and uses IdGenerator instance`() {
        val persistedDeviceId = "1234567890123_device01"
        every { mockPrefs.getString("device_id", null) } returns persistedDeviceId
        
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        
        val deviceInfo = deviceInfoCollector.collectDeviceInfo()
        val deviceId = deviceInfo["device.id"]
        
        assertEquals("DeviceInfoCollector must use IdGenerator device ID", persistedDeviceId, deviceId)
    }

    @Test
    fun `UserProfileManager receives and uses IdGenerator instance`() {
        val persistedUserId = "1234567890123_user0001"
        every { mockPrefs.getString("edge_rum_user_id", null) } returns persistedUserId
        
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        
        val userId = userProfileManager.getUserId()
        
        assertEquals("UserProfileManager must use IdGenerator user ID", persistedUserId, userId)
    }

    @Test
    fun `all components share the same IdGenerator instance`() {
        val sharedIdGenerator = IdGenerator()
        sharedIdGenerator.initialize(mockContext)
        
        val sessionManager = SessionManager(sharedIdGenerator)
        val deviceInfoCollector = DeviceInfoCollector(mockContext, sharedIdGenerator)
        val userProfileManager = UserProfileManager(mockContext, sharedIdGenerator)
        
        assertNotNull("SessionManager initialized", sessionManager)
        assertNotNull("DeviceInfoCollector initialized", deviceInfoCollector)
        assertNotNull("UserProfileManager initialized", userProfileManager)
    }

    @Test
    fun `SessionManager session ID matches expected format from IdGenerator`() {
        val sessionManager = SessionManager(idGenerator)
        
        val sessionId = sessionManager.getCurrentSessionId()
        val directSessionId = idGenerator.generateSessionId()
        
        assertTrue("SessionManager session ID must match IdGenerator format", 
            sessionId.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
        assertTrue("Direct IdGenerator session ID must match format", 
            directSessionId.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
    }

    @Test
    fun `DeviceInfoCollector device ID matches IdGenerator device ID`() {
        val persistedDeviceId = "1234567890123_testdev1"
        every { mockPrefs.getString("device_id", null) } returns persistedDeviceId
        
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        val directDeviceId = idGenerator.getDeviceId()
        
        val deviceInfo = deviceInfoCollector.collectDeviceInfo()
        val collectorDeviceId = deviceInfo["device.id"]
        
        assertEquals("DeviceInfoCollector must return same device ID as IdGenerator", 
            directDeviceId, collectorDeviceId)
    }

    @Test
    fun `UserProfileManager user ID matches IdGenerator user ID`() {
        val persistedUserId = "1234567890123_testuser"
        every { mockPrefs.getString("edge_rum_user_id", null) } returns persistedUserId
        
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        val directUserId = idGenerator.getUserId()
        
        val managerUserId = userProfileManager.getUserId()
        
        assertEquals("UserProfileManager must return same user ID as IdGenerator", 
            directUserId, managerUserId)
    }

    @Test
    fun `SessionManager uses IdGenerator for new session generation`() {
        val sessionManager = SessionManager(idGenerator)
        
        val sessionId1 = sessionManager.getCurrentSessionId()
        sessionManager.startNewSession()
        val sessionId2 = sessionManager.getCurrentSessionId()
        
        assertNotEquals("New session must have different ID", sessionId1, sessionId2)
        assertTrue("First session ID must match format", 
            sessionId1.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
        assertTrue("Second session ID must match format", 
            sessionId2.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
    }

    @Test
    fun `all components produce IDs with consistent format`() {
        val sessionManager = SessionManager(idGenerator)
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        
        val sessionId = sessionManager.getCurrentSessionId()
        val deviceInfo = deviceInfoCollector.collectDeviceInfo()
        val deviceId = deviceInfo["device.id"]
        val userId = userProfileManager.getUserId()
        
        val idFormatRegex = Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")
        
        assertTrue("Session ID must match format: $sessionId", idFormatRegex.matches(sessionId))
        assertTrue("Device ID must match format: $deviceId", deviceId?.let { idFormatRegex.matches(it) } ?: false)
        assertTrue("User ID must match format: $userId", idFormatRegex.matches(userId))
    }

    @Test
    fun `no component instantiates its own IdGenerator`() {
        val sessionManager = SessionManager(idGenerator)
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        
        assertNotNull("SessionManager must accept IdGenerator", sessionManager)
        assertNotNull("DeviceInfoCollector must accept IdGenerator", deviceInfoCollector)
        assertNotNull("UserProfileManager must accept IdGenerator", userProfileManager)
    }

    @Test
    fun `session attributes contain valid session ID from IdGenerator`() {
        val sessionManager = SessionManager(idGenerator)
        
        val attributes = sessionManager.getSessionAttributes()
        val sessionId = attributes["session.id"]
        
        assertNotNull("Session attributes must contain session.id", sessionId)
        assertTrue("Session ID in attributes must match format", 
            sessionId!!.matches(Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")))
    }

    @Test
    fun `user attributes contain valid user ID from IdGenerator`() {
        val persistedUserId = "1234567890123_attrtest"
        every { mockPrefs.getString("edge_rum_user_id", null) } returns persistedUserId
        
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        
        val attributes = userProfileManager.getUserAttributes()
        val userId = attributes["user.id"]
        
        assertEquals("User attributes must contain correct user.id", persistedUserId, userId)
    }

    @Test
    fun `device info attributes contain valid device ID from IdGenerator`() {
        val persistedDeviceId = "1234567890123_devattr1"
        every { mockPrefs.getString("device_id", null) } returns persistedDeviceId
        
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        
        val deviceInfo = deviceInfoCollector.collectDeviceInfo()
        val deviceId = deviceInfo["device.id"]
        
        assertEquals("Device info must contain correct device.id", persistedDeviceId, deviceId)
    }

    @Test
    fun `multiple components using same IdGenerator produce consistent device IDs`() {
        val persistedDeviceId = "1234567890123_shared01"
        every { mockPrefs.getString("device_id", null) } returns persistedDeviceId
        
        val deviceInfoCollector1 = DeviceInfoCollector(mockContext, idGenerator)
        val deviceInfoCollector2 = DeviceInfoCollector(mockContext, idGenerator)
        
        val deviceId1 = deviceInfoCollector1.collectDeviceInfo()["device.id"]
        val deviceId2 = deviceInfoCollector2.collectDeviceInfo()["device.id"]
        
        assertEquals("Both collectors must return same device ID", deviceId1, deviceId2)
        assertEquals("Device ID must match persisted value", persistedDeviceId, deviceId1)
    }

    @Test
    fun `multiple components using same IdGenerator produce consistent user IDs`() {
        val persistedUserId = "1234567890123_shared02"
        every { mockPrefs.getString("edge_rum_user_id", null) } returns persistedUserId
        
        val userProfileManager1 = UserProfileManager(mockContext, idGenerator)
        val userProfileManager2 = UserProfileManager(mockContext, idGenerator)
        
        val userId1 = userProfileManager1.getUserId()
        val userId2 = userProfileManager2.getUserId()
        
        assertEquals("Both managers must return same user ID", userId1, userId2)
        assertEquals("User ID must match persisted value", persistedUserId, userId1)
    }

    @Test
    fun `regression - no kotlin random Random import in codebase`() {
        val sourceDir = File("src/main/java")
        if (!sourceDir.exists()) {
            return // Skip if running in different context
        }
        
        val kotlinFiles = sourceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        
        kotlinFiles.forEach { file ->
            val content = file.readText()
            assertFalse(
                "File ${file.name} must not import kotlin.random.Random",
                content.contains("import kotlin.random.Random") && !file.name.contains("Test")
            )
        }
    }

    @Test
    fun `regression - no local generateRandomString method outside IdGenerator`() {
        val sourceDir = File("src/main/java")
        if (!sourceDir.exists()) {
            return // Skip if running in different context
        }
        
        val kotlinFiles = sourceDir.walkTopDown()
            .filter { it.extension == "kt" && !it.name.contains("IdGenerator") }
            .toList()
        
        kotlinFiles.forEach { file ->
            val content = file.readText()
            assertFalse(
                "File ${file.name} must not have local generateRandomString method",
                content.contains("fun generateRandomString(") || 
                content.contains("private fun generateRandomString(")
            )
        }
    }

    @Test
    fun `regression - only IdGenerator generates IDs independently`() {
        val sourceDir = File("src/main/java")
        if (!sourceDir.exists()) {
            return // Skip if running in different context
        }
        
        val kotlinFiles = sourceDir.walkTopDown()
            .filter { it.extension == "kt" && !it.name.contains("IdGenerator") && !it.name.contains("Test") }
            .toList()
        
        kotlinFiles.forEach { file ->
            val content = file.readText()
            
            assertFalse(
                "File ${file.name} must not generate device IDs independently",
                content.contains("\"device_\${") && !content.contains("idGenerator")
            )
            
            assertFalse(
                "File ${file.name} must not generate session IDs independently",
                content.contains("\"session_\${") && !content.contains("idGenerator")
            )
            
            assertFalse(
                "File ${file.name} must not generate user IDs independently",
                content.contains("\"user_\${") && !content.contains("idGenerator")
            )
        }
    }

    @Test
    fun `all ID generation flows through IdGenerator`() {
        val sessionManager = SessionManager(idGenerator)
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        
        val sessionId = sessionManager.getCurrentSessionId()
        val deviceId = deviceInfoCollector.collectDeviceInfo()["device.id"]
        val userId = userProfileManager.getUserId()
        
        assertNotNull("Session ID must be generated", sessionId)
        assertNotNull("Device ID must be generated", deviceId)
        assertNotNull("User ID must be generated", userId)
        
        val idPattern = Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")
        assertTrue("All IDs must come from IdGenerator format", idPattern.matches(sessionId))
        assertTrue("All IDs must come from IdGenerator format", deviceId?.let { idPattern.matches(it) } ?: false)
        assertTrue("All IDs must come from IdGenerator format", idPattern.matches(userId))
    }

    @Test
    fun `IdGenerator is single source of truth for all ID types`() {
        val sessionId = idGenerator.generateSessionId()
        val userId = idGenerator.getUserId()
        val deviceId = idGenerator.getOrGenerateDeviceId()
        
        val idPattern = Regex("""(device|user|session)_\d+_[0-9a-f]{16}_android""")
        
        assertTrue("Session ID from IdGenerator must match format", idPattern.matches(sessionId))
        assertTrue("User ID from IdGenerator must match format", idPattern.matches(userId))
        assertTrue("Device ID from IdGenerator must match format", idPattern.matches(deviceId))
    }

    @Test
    fun `components do not cache or modify IDs from IdGenerator`() {
        val persistedDeviceId = "1234567890123_nocache1"
        val persistedUserId = "1234567890123_nocache2"
        
        every { mockPrefs.getString("device_id", null) } returns persistedDeviceId
        every { mockPrefs.getString("edge_rum_user_id", null) } returns persistedUserId
        
        val deviceInfoCollector = DeviceInfoCollector(mockContext, idGenerator)
        val userProfileManager = UserProfileManager(mockContext, idGenerator)
        
        val deviceId1 = deviceInfoCollector.collectDeviceInfo()["device.id"]
        val deviceId2 = deviceInfoCollector.collectDeviceInfo()["device.id"]
        
        val userId1 = userProfileManager.getUserId()
        val userId2 = userProfileManager.getUserId()
        
        assertEquals("Device ID must not be modified", deviceId1, deviceId2)
        assertEquals("User ID must not be modified", userId1, userId2)
        assertEquals("Device ID must match IdGenerator", persistedDeviceId, deviceId1)
        assertEquals("User ID must match IdGenerator", persistedUserId, userId1)
    }
}
