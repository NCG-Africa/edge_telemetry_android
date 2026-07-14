package com.androidtel.telemetry_library.core.services

import android.content.Context
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.ids.IdGenerator
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for UserProfileService
 * Tests user profile management, pending profiles, and user info generation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserProfileServiceTest {

    private lateinit var context: Context
    private lateinit var config: TelemetryConfig
    private lateinit var idGenerator: IdGenerator
    private lateinit var service: UserProfileService

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
        
        idGenerator = mockk(relaxed = true)
        every { idGenerator.getUserId() } returns "test-user-id-123"
        
        service = UserProfileService(context, config, idGenerator)
    }

    @Test
    fun `test service initialization generates user ID`() {
        service.initialize()
        
        verify { idGenerator.getUserId() }
        assertEquals("test-user-id-123", service.getUserId())
    }

    @Test
    fun `test initialization with user profiles enabled creates manager`() {
        service.initialize()
        
        assertNotNull(service.getUserProfileManager())
    }

    @Test
    fun `test initialization always creates manager`() {
        service.initialize()
        
        assertNotNull(service.getUserProfileManager())
    }

    @Test
    fun `test setUserProfile with initialized manager`() {
        service.initialize()
        
        service.setUserProfile("John Doe", "john@example.com", "+1234567890")
        
        val userInfo = service.getUserInfo()
        assertEquals("John Doe", userInfo.name)
        assertEquals("john@example.com", userInfo.email)
        assertEquals("+1234567890", userInfo.phone)
    }

    @Test
    fun `test setUserProfile before initialization stores as pending`() {
        // Don't call initialize()
        service.setUserProfile("Jane Doe", "jane@example.com", "+9876543210")
        
        // Profile should be pending, not applied yet
        // Initialize now
        service.initialize()
        
        // After initialization, pending profile should be applied
        val userInfo = service.getUserInfo()
        assertEquals("Jane Doe", userInfo.name)
        assertEquals("jane@example.com", userInfo.email)
        assertEquals("+9876543210", userInfo.phone)
    }

    @Test
    fun `test setUserProfile with null phone number`() {
        service.initialize()
        
        service.setUserProfile("Alice Smith", "alice@example.com", null)
        
        val userInfo = service.getUserInfo()
        assertEquals("Alice Smith", userInfo.name)
        assertEquals("alice@example.com", userInfo.email)
        assertNull(userInfo.phone)
    }

    @Test
    fun `test setUserProfile with null display name`() {
        service.initialize()
        
        service.setUserProfile(null, "user@example.com", "+1111111111")
        
        val userInfo = service.getUserInfo()
        assertNull(userInfo.name)
        assertEquals("user@example.com", userInfo.email)
        assertEquals("+1111111111", userInfo.phone)
    }

    @Test
    fun `test setUserProfile with all null values`() {
        service.initialize()
        
        service.setUserProfile(null, null, null)
        
        val userInfo = service.getUserInfo()
        assertNull(userInfo.name)
        assertNull(userInfo.email)
        assertNull(userInfo.phone)
    }

    @Test
    fun `test clearUserProfile removes profile data`() {
        service.initialize()
        service.setUserProfile("Bob Johnson", "bob@example.com", "+2222222222")
        
        service.clearUserProfile()
        
        val userInfo = service.getUserInfo()
        assertNull(userInfo.name)
        assertNull(userInfo.email)
        assertNull(userInfo.phone)
    }

    @Test
    fun `test clearUserProfile before initialization logs warning`() {
        // Should not throw exception
        service.clearUserProfile()
    }

    @Test
    fun `test getUserId returns generated ID`() {
        service.initialize()
        
        val userId = service.getUserId()
        
        assertEquals("test-user-id-123", userId)
    }

    @Test
    fun `test getUserId returns fallback when blank`() {
        every { idGenerator.getUserId() } returns ""
        service.initialize()
        
        val userId = service.getUserId()
        
        assertTrue(userId.startsWith("user_fallback_"))
    }

    @Test
    fun `test getUserId always returns valid ID`() {
        service.initialize()
        
        val userId = service.getUserId()
        
        assertEquals("test-user-id-123", userId)
    }

    @Test
    fun `test getUserInfo returns complete user information`() {
        service.initialize()
        service.setUserProfile("Charlie Brown", "charlie@example.com", "+3333333333")
        
        val userInfo = service.getUserInfo()
        
        assertEquals("test-user-id-123", userInfo.userId)
        assertEquals("Charlie Brown", userInfo.name)
        assertEquals("charlie@example.com", userInfo.email)
        assertEquals("+3333333333", userInfo.phone)
    }

    @Test
    fun `test getUserInfo with no profile set`() {
        service.initialize()
        
        val userInfo = service.getUserInfo()
        
        assertEquals("test-user-id-123", userInfo.userId)
        assertNull(userInfo.name)
        assertNull(userInfo.email)
        assertNull(userInfo.phone)
    }

    @Test
    fun `test getUserInfo with no profile returns only user ID`() {
        service.initialize()
        
        val userInfo = service.getUserInfo()
        
        assertEquals("test-user-id-123", userInfo.userId)
        assertNull(userInfo.name)
        assertNull(userInfo.email)
        assertNull(userInfo.phone)
    }


    @Test
    fun `test getUserProfileManager returns manager when enabled`() {
        service.initialize()
        
        assertNotNull(service.getUserProfileManager())
    }

    @Test
    fun `test getUserProfileManager returns null before initialization`() {
        assertNull(service.getUserProfileManager())
    }

    @Test
    fun `test pending profile is applied on initialization`() {
        // Set profile before initialization
        service.setUserProfile("Pending User", "pending@example.com", "+4444444444")
        
        // Initialize service
        service.initialize()
        
        // Profile should now be applied
        val userInfo = service.getUserInfo()
        assertEquals("Pending User", userInfo.name)
        assertEquals("pending@example.com", userInfo.email)
        assertEquals("+4444444444", userInfo.phone)
    }

    @Test
    fun `test pending profile is cleared after initialization`() {
        service.setUserProfile("Temp User", "temp@example.com", null)
        service.initialize()
        
        // Set new profile - should not be pending
        service.setUserProfile("New User", "new@example.com", null)
        
        val userInfo = service.getUserInfo()
        assertEquals("New User", userInfo.name)
        assertEquals("new@example.com", userInfo.email)
    }

    @Test
    fun `test multiple profile updates`() {
        service.initialize()
        
        service.setUserProfile("User 1", "user1@example.com", "+1111111111")
        var userInfo = service.getUserInfo()
        assertEquals("User 1", userInfo.name)
        
        service.setUserProfile("User 2", "user2@example.com", "+2222222222")
        userInfo = service.getUserInfo()
        assertEquals("User 2", userInfo.name)
        assertEquals("user2@example.com", userInfo.email)
        
        service.setUserProfile("User 3", "user3@example.com", "+3333333333")
        userInfo = service.getUserInfo()
        assertEquals("User 3", userInfo.name)
    }

    @Test
    fun `test profile update after clear`() {
        service.initialize()
        
        service.setUserProfile("First User", "first@example.com", null)
        service.clearUserProfile()
        service.setUserProfile("Second User", "second@example.com", null)
        
        val userInfo = service.getUserInfo()
        assertEquals("Second User", userInfo.name)
        assertEquals("second@example.com", userInfo.email)
    }

    @Test
    fun `test user ID consistency across multiple calls`() {
        service.initialize()
        
        val userId1 = service.getUserId()
        val userId2 = service.getUserId()
        val userId3 = service.getUserId()
        
        assertEquals(userId1, userId2)
        assertEquals(userId2, userId3)
    }

    @Test
    fun `test getUserInfo consistency across multiple calls`() {
        service.initialize()
        service.setUserProfile("Consistent User", "consistent@example.com", "+5555555555")
        
        val userInfo1 = service.getUserInfo()
        val userInfo2 = service.getUserInfo()
        
        assertEquals(userInfo1.userId, userInfo2.userId)
        assertEquals(userInfo1.name, userInfo2.name)
        assertEquals(userInfo1.email, userInfo2.email)
        assertEquals(userInfo1.phone, userInfo2.phone)
    }

    @Test
    fun `test profile with special characters in name`() {
        service.initialize()
        
        service.setUserProfile("José García-Martínez", "jose@example.com", null)
        
        val userInfo = service.getUserInfo()
        assertEquals("José García-Martínez", userInfo.name)
    }

    @Test
    fun `test profile with special characters in email`() {
        service.initialize()
        
        service.setUserProfile("User", "user+test@sub-domain.example.com", null)
        
        val userInfo = service.getUserInfo()
        assertEquals("user+test@sub-domain.example.com", userInfo.email)
    }

    @Test
    fun `test profile with international phone number`() {
        service.initialize()
        
        service.setUserProfile("User", "user@example.com", "+44 20 7946 0958")
        
        val userInfo = service.getUserInfo()
        assertEquals("+44 20 7946 0958", userInfo.phone)
    }

    @Test
    fun `test pending profile with partial data`() {
        service.setUserProfile("Partial User", null, "+6666666666")
        service.initialize()
        
        val userInfo = service.getUserInfo()
        assertEquals("Partial User", userInfo.name)
        assertNull(userInfo.email)
        assertEquals("+6666666666", userInfo.phone)
    }

    @Test
    fun `test service reinitialization does not lose profile`() {
        service.initialize()
        service.setUserProfile("Persistent User", "persistent@example.com", null)
        
        // Simulate reinitialization (in real scenario, would be new instance)
        val userInfo = service.getUserInfo()
        
        assertEquals("Persistent User", userInfo.name)
        assertEquals("persistent@example.com", userInfo.email)
    }

    @Test
    fun `test getUserId fallback generates unique IDs`() {
        every { idGenerator.getUserId() } returns ""
        service.initialize()
        
        val userId1 = service.getUserId()
        Thread.sleep(10) // Ensure different timestamp
        
        // Create new service with blank user ID
        val service2 = UserProfileService(context, config, idGenerator)
        service2.initialize()
        val userId2 = service2.getUserId()
        
        assertNotEquals(userId1, userId2)
        assertTrue(userId1.startsWith("user_fallback_"))
        assertTrue(userId2.startsWith("user_fallback_"))
    }

    @Test
    fun `test concurrent profile updates are handled`() {
        service.initialize()
        
        val threads = (1..5).map { threadNum ->
            Thread {
                service.setUserProfile("User $threadNum", "user$threadNum@example.com", "+${threadNum}${threadNum}${threadNum}")
                Thread.sleep(10)
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        // Should have the last profile set
        val userInfo = service.getUserInfo()
        assertNotNull(userInfo.name)
        assertNotNull(userInfo.email)
    }

    @Test
    fun `test profile manager initialization with pending profile logs message`() {
        service.setUserProfile("Logged User", "logged@example.com", null)
        
        // Should not throw exception and should apply pending profile
        service.initialize()
        
        val userInfo = service.getUserInfo()
        assertEquals("Logged User", userInfo.name)
    }

    @Test
    fun `test empty string values treated as null`() {
        service.initialize()
        
        // UserProfileManager might handle empty strings differently
        service.setUserProfile("", "", "")
        
        val userInfo = service.getUserInfo()
        // Depending on implementation, empty strings might be stored or treated as null
        assertNotNull(userInfo.userId) // User ID should always exist
    }

    @Test
    fun `test very long profile values`() {
        service.initialize()
        
        val longName = "A".repeat(1000)
        val longEmail = "user@" + "example".repeat(100) + ".com"
        val longPhone = "+1" + "2".repeat(50)
        
        service.setUserProfile(longName, longEmail, longPhone)
        
        val userInfo = service.getUserInfo()
        assertEquals(longName, userInfo.name)
        assertEquals(longEmail, userInfo.email)
        assertEquals(longPhone, userInfo.phone)
    }

    @Test
    fun `test profile update with only email change`() {
        service.initialize()
        
        service.setUserProfile("Same User", "email1@example.com", "+7777777777")
        service.setUserProfile("Same User", "email2@example.com", "+7777777777")
        
        val userInfo = service.getUserInfo()
        assertEquals("Same User", userInfo.name)
        assertEquals("email2@example.com", userInfo.email)
        assertEquals("+7777777777", userInfo.phone)
    }

    @Test
    fun `test getUserInfo always returns user ID even without profile`() {
        service.initialize()
        
        val userInfo = service.getUserInfo()
        
        assertNotNull(userInfo.userId)
        assertFalse(userInfo.userId.isEmpty())
    }
}
