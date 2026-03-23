package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Stage 2 Remediation Tests for IdGenerator
 * Verifies:
 * - userId is generated once using SecureRandom
 * - Stored in SharedPreferences with key "edge_rum_user_id"
 * - Never regenerated if key exists
 * - Always SDK-generated, never externally provided
 */
class IdGeneratorStage2Test {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var idGenerator: IdGenerator

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.applicationContext } returns context
        every { context.getSharedPreferences("edge_telemetry_ids", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
    }

    @Test
    fun `userId is generated and persisted on first access`() {
        // Given: No existing userId
        every { sharedPreferences.getString("edge_rum_user_id", null) } returns null

        // When: IdGenerator is initialized and getUserId is called
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        val userId = idGenerator.getUserId()

        // Then: userId should be generated and persisted
        assertNotNull("userId should not be null", userId)
        assertTrue("userId should follow timestamp_random format", userId.contains("_"))
        verify { editor.putString("edge_rum_user_id", userId) }
        verify { editor.apply() }
    }

    @Test
    fun `userId is restored from SharedPreferences on subsequent access`() {
        // Given: Existing userId in SharedPreferences
        val existingUserId = "1234567890_abcd1234"
        every { sharedPreferences.getString("edge_rum_user_id", null) } returns existingUserId

        // When: IdGenerator is initialized and getUserId is called
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        val userId = idGenerator.getUserId()

        // Then: Should return existing userId without regenerating
        assertEquals(existingUserId, userId)
        verify(exactly = 0) { editor.putString("edge_rum_user_id", any()) }
    }

    @Test
    fun `userId is never regenerated across multiple calls`() {
        // Given: No existing userId
        var storedUserId: String? = null
        every { sharedPreferences.getString("edge_rum_user_id", null) } answers { storedUserId }
        every { editor.putString("edge_rum_user_id", any()) } answers {
            storedUserId = secondArg()
            editor
        }

        // When: getUserId is called multiple times
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        val userId1 = idGenerator.getUserId()
        val userId2 = idGenerator.getUserId()
        val userId3 = idGenerator.getUserId()

        // Then: All calls should return the same userId
        assertEquals(userId1, userId2)
        assertEquals(userId2, userId3)
        verify(exactly = 1) { editor.putString("edge_rum_user_id", any()) }
    }

    @Test
    fun `userId uses same SharedPreferences file as deviceId`() {
        // Given: IdGenerator initialized
        every { sharedPreferences.getString(any(), any()) } returns null

        // When: Both deviceId and userId are accessed
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        idGenerator.getOrGenerateDeviceId()
        idGenerator.getUserId()

        // Then: Should use same SharedPreferences instance
        verify(exactly = 1) { context.getSharedPreferences("edge_telemetry_ids", Context.MODE_PRIVATE) }
    }

    @Test
    fun `userId format matches deviceId format - timestamp_random`() {
        // Given: No existing userId
        every { sharedPreferences.getString("edge_rum_user_id", null) } returns null

        // When: userId is generated
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        val userId = idGenerator.getUserId()

        // Then: Should follow timestamp_random format
        val parts = userId.split("_")
        assertEquals("userId should have 2 parts separated by _", 2, parts.size)
        assertTrue("First part should be numeric timestamp", parts[0].all { it.isDigit() })
        assertEquals("Second part should be 8 characters", 8, parts[1].length)
        assertTrue("Second part should be alphanumeric", parts[1].all { it.isLetterOrDigit() })
    }

    @Test
    fun `getUserId throws exception if not initialized`() {
        // Given: IdGenerator not initialized
        idGenerator = IdGenerator()

        // When/Then: Should throw exception
        assertThrows(IllegalStateException::class.java) {
            idGenerator.getUserId()
        }
    }
}
