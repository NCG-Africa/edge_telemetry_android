package com.androidtel.telemetry_library.core.user

import android.content.Context
import android.content.SharedPreferences
import com.androidtel.telemetry_library.core.ids.IdGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for UserProfileManager
 * Verifies:
 * - Profile starts empty at init
 * - setUserProfile() contract works correctly
 * - name and email are optional enrichment
 */
class UserProfileManagerTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var idGenerator: IdGenerator
    private lateinit var userProfileManager: UserProfileManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        idGenerator = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit
        every { sharedPreferences.getString(any(), any()) } returns null
        every { idGenerator.getUserId() } returns "test_user_id_123"
    }

    @Test
    fun `profile starts empty at init when no persisted data`() {
        // Given: No persisted profile data
        every { sharedPreferences.getString("display_name", null) } returns null
        every { sharedPreferences.getString("email", null) } returns null
        every { sharedPreferences.getString("phone", null) } returns null

        // When: UserProfileManager is initialized
        userProfileManager = UserProfileManager(context, idGenerator)

        // Then: Profile should be empty
        val profile = userProfileManager.getUserProfile()
        assertNull("name should be null", profile.name)
        assertNull("email should be null", profile.email)
        assertNull("phone should be null", profile.phone)
    }

    @Test
    fun `profile loads persisted data at init`() {
        // Given: Persisted profile data exists
        every { sharedPreferences.getString("display_name", null) } returns "Alice"
        every { sharedPreferences.getString("email", null) } returns "alice@test.com"
        every { sharedPreferences.getString("phone", null) } returns "+1234567890"

        // When: UserProfileManager is initialized
        userProfileManager = UserProfileManager(context, idGenerator)

        // Then: Profile should contain persisted data
        val profile = userProfileManager.getUserProfile()
        assertEquals("Alice", profile.name)
        assertEquals("alice@test.com", profile.email)
        assertEquals("+1234567890", profile.phone)
    }

    @Test
    fun `setUserProfile replaces all fields`() {
        // Given: Empty profile
        every { sharedPreferences.getString(any(), any()) } returns null
        userProfileManager = UserProfileManager(context, idGenerator)

        // When: setUserProfile is called with all fields
        userProfileManager.setUserProfile("Bob", "bob@test.com", "+9876543210")

        // Then: Profile should be updated
        val profile = userProfileManager.getUserProfile()
        assertEquals("Bob", profile.name)
        assertEquals("bob@test.com", profile.email)
        assertEquals("+9876543210", profile.phone)

        // Verify persistence
        verify { editor.putString("display_name", "Bob") }
        verify { editor.putString("email", "bob@test.com") }
        verify { editor.putString("phone", "+9876543210") }
    }

    @Test
    fun `setUserProfile with null clears fields`() {
        // Given: Profile with data
        every { sharedPreferences.getString("display_name", null) } returns "Charlie"
        every { sharedPreferences.getString("email", null) } returns "charlie@test.com"
        every { sharedPreferences.getString("phone", null) } returns "+1111111111"
        userProfileManager = UserProfileManager(context, idGenerator)

        // When: setUserProfile is called with nulls
        userProfileManager.setUserProfile(null, null, null)

        // Then: Profile should be cleared
        val profile = userProfileManager.getUserProfile()
        assertNull(profile.name)
        assertNull(profile.email)
        assertNull(profile.phone)

        // Verify persistence removes keys
        verify { editor.remove("display_name") }
        verify { editor.remove("email") }
        verify { editor.remove("phone") }
    }

    @Test
    fun `setUserProfile fully replaces previous values - no merge`() {
        // Given: Profile with existing data
        every { sharedPreferences.getString(any(), any()) } returns null
        userProfileManager = UserProfileManager(context, idGenerator)
        userProfileManager.setUserProfile("Dave", "dave@test.com", "+2222222222")

        // When: setUserProfile is called with partial data
        userProfileManager.setUserProfile("Eve", null, null)

        // Then: Only new values should be present (no merge)
        val profile = userProfileManager.getUserProfile()
        assertEquals("Eve", profile.name)
        assertNull("email should be null, not merged from previous", profile.email)
        assertNull("phone should be null, not merged from previous", profile.phone)
    }

    @Test
    fun `clearUserProfile sets all fields to null`() {
        // Given: Profile with data
        every { sharedPreferences.getString(any(), any()) } returns null
        userProfileManager = UserProfileManager(context, idGenerator)
        userProfileManager.setUserProfile("Frank", "frank@test.com", "+3333333333")

        // When: clearUserProfile is called
        userProfileManager.clearUserProfile()

        // Then: All fields should be null
        val profile = userProfileManager.getUserProfile()
        assertNull(profile.name)
        assertNull(profile.email)
        assertNull(profile.phone)
    }

    @Test
    fun `getUserId always returns valid userId from IdGenerator`() {
        // Given: UserProfileManager initialized
        every { sharedPreferences.getString(any(), any()) } returns null
        userProfileManager = UserProfileManager(context, idGenerator)

        // When: getUserId is called
        val userId = userProfileManager.getUserId()

        // Then: Should return userId from IdGenerator
        assertEquals("test_user_id_123", userId)
        verify { idGenerator.getUserId() }
    }

    @Test
    fun `setUserProfile with only phone updates phone field`() {
        // Given: Empty profile
        every { sharedPreferences.getString(any(), any()) } returns null
        userProfileManager = UserProfileManager(context, idGenerator)

        // When: setUserProfile is called with only phone
        userProfileManager.setUserProfile(null, null, "+5555555555")

        // Then: Only phone should be set
        val profile = userProfileManager.getUserProfile()
        assertNull(profile.name)
        assertNull(profile.email)
        assertEquals("+5555555555", profile.phone)

        // Verify persistence
        verify { editor.remove("display_name") }
        verify { editor.remove("email") }
        verify { editor.putString("phone", "+5555555555") }
    }

    @Test
    fun `phone field persists independently of other fields`() {
        // Given: Profile with only phone
        every { sharedPreferences.getString("display_name", null) } returns null
        every { sharedPreferences.getString("email", null) } returns null
        every { sharedPreferences.getString("phone", null) } returns "+9999999999"
        userProfileManager = UserProfileManager(context, idGenerator)

        // Then: Only phone should be loaded
        val profile = userProfileManager.getUserProfile()
        assertNull(profile.name)
        assertNull(profile.email)
        assertEquals("+9999999999", profile.phone)
    }
}
