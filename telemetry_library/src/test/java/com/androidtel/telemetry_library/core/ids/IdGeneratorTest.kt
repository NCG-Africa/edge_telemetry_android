package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

class IdGeneratorTest {

    private lateinit var idGenerator: IdGenerator
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    private val idFormatRegex = Regex("""^(device|user|session)_\d+_[0-9a-f]{16}_android$""")

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.getSharedPreferences("edge_telemetry_ids", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.remove(anyString())).thenReturn(mockEditor)

        idGenerator = IdGenerator()
        idGenerator.initialize(mockContext)
    }

    @After
    fun tearDown() {
        reset(mockContext, mockPrefs, mockEditor)
    }

    @Test
    fun `generateSessionId returns valid format`() {
        val sessionId = idGenerator.generateSessionId()
        assertTrue("Session ID must match format: $sessionId", idFormatRegex.matches(sessionId))
    }

    @Test
    fun `getUserId returns valid format`() {
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)
        val userId = idGenerator.getUserId()
        assertTrue("User ID must match format: $userId", idFormatRegex.matches(userId))
    }

    @Test
    fun `getOrGenerateDeviceId returns valid format`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)

        val deviceId = idGenerator.getOrGenerateDeviceId()
        assertTrue("Device ID must match format: $deviceId", idFormatRegex.matches(deviceId))
    }

    @Test
    fun `all ID types use same format`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)

        val sessionId = idGenerator.generateSessionId()
        val userId = idGenerator.getUserId()
        val deviceId = idGenerator.getOrGenerateDeviceId()

        assertTrue("Session ID format mismatch", idFormatRegex.matches(sessionId))
        assertTrue("User ID format mismatch", idFormatRegex.matches(userId))
        assertTrue("Device ID format mismatch", idFormatRegex.matches(deviceId))
    }

    @Test
    fun `timestamp is 13 digits`() {
        val id = idGenerator.generateSessionId()
        val timestamp = id.split("_")[1]
        assertEquals("Timestamp must be 13 digits", 13, timestamp.length)
        assertTrue("Timestamp must be numeric", timestamp.all { it.isDigit() })
    }

    @Test
    fun `random part is 16 lowercase hex characters`() {
        val id = idGenerator.generateSessionId()
        val randomPart = id.split("_")[2]
        assertEquals("Random part must be 16 characters", 16, randomPart.length)
        assertTrue("Random part must be lowercase hex",
            randomPart.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generate 1000 session IDs with zero collisions`() {
        val ids = mutableSetOf<String>()
        repeat(1000) {
            val id = idGenerator.generateSessionId()
            ids.add(id)
        }
        assertEquals("Must generate 1000 unique session IDs", 1000, ids.size)
    }

    @Test
    fun `getUserId returns same ID when called multiple times`() {
        var storedUserId: String? = null
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenAnswer { storedUserId }
        `when`(mockEditor.putString(eq("edge_rum_user_id"), anyString())).thenAnswer {
            storedUserId = it.arguments[1] as String
            mockEditor
        }
        
        val userId1 = idGenerator.getUserId()
        val userId2 = idGenerator.getUserId()
        
        assertEquals("getUserId must return same ID on subsequent calls", userId1, userId2)
        assertTrue("User ID must match format", idFormatRegex.matches(userId1))
    }

    @Test
    fun `generate 1000 device IDs with zero collisions`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        
        val ids = mutableSetOf<String>()
        repeat(1000) {
            val generator = IdGenerator()
            generator.initialize(mockContext)
            val id = generator.generateSessionId()
            ids.add(id)
        }
        assertEquals("Must generate 1000 unique device IDs", 1000, ids.size)
    }

    @Test
    fun `device ID persists across re-instantiation`() {
        val persistedId = "1234567890123_abc12345"
        `when`(mockPrefs.getString("device_id", null)).thenReturn(persistedId)

        val newGenerator = IdGenerator()
        newGenerator.initialize(mockContext)
        val retrievedId = newGenerator.getOrGenerateDeviceId()

        assertEquals("Device ID must persist", persistedId, retrievedId)
        verify(mockEditor, never()).putString(eq("device_id"), anyString())
    }

    @Test
    fun `user ID persists across re-instantiation`() {
        val persistedId = "1234567890123_xyz98765"
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(persistedId)

        val newGenerator = IdGenerator()
        newGenerator.initialize(mockContext)
        val retrievedId = newGenerator.getUserId()

        assertEquals("User ID must persist", persistedId, retrievedId)
        verify(mockEditor, never()).putString(eq("edge_rum_user_id"), anyString())
    }

    @Test
    fun `device ID is generated and stored when not present`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)

        val deviceId = idGenerator.getOrGenerateDeviceId()

        assertTrue("Device ID must match format", idFormatRegex.matches(deviceId))
        verify(mockEditor).putString(eq("device_id"), eq(deviceId))
        verify(mockEditor).apply()
    }

    @Test
    fun `user ID is generated and stored when not present`() {
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)

        val userId = idGenerator.getUserId()

        assertTrue("User ID must match format", idFormatRegex.matches(userId))
        verify(mockEditor).putString(eq("edge_rum_user_id"), eq(userId))
        verify(mockEditor).apply()
    }

    // Test removed - setUserId() method no longer exists
    // User IDs are now auto-managed by getUserId()

    // Test removed - clearUserId() method no longer exists
    // User IDs are now auto-managed by getUserId()

    @Test
    fun `getDeviceId returns same as getOrGenerateDeviceId`() {
        val persistedId = "1234567890123_test1234"
        `when`(mockPrefs.getString("device_id", null)).thenReturn(persistedId)

        val id1 = idGenerator.getDeviceId()
        val id2 = idGenerator.getOrGenerateDeviceId()

        assertEquals("getDeviceId must return same as getOrGenerateDeviceId", id1, id2)
    }

    @Test
    fun `thread safety - 50 concurrent session ID calls return valid unique IDs`() {
        val threadCount = 50
        val latch = CountDownLatch(threadCount)
        val ids = ConcurrentHashMap.newKeySet<String>()
        val errors = AtomicInteger(0)

        repeat(threadCount) {
            thread {
                try {
                    val id = idGenerator.generateSessionId()
                    if (!idFormatRegex.matches(id)) {
                        errors.incrementAndGet()
                    }
                    ids.add(id)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()

        assertEquals("No errors should occur during concurrent generation", 0, errors.get())
        assertEquals("All IDs must be unique", threadCount, ids.size)
        ids.forEach { id ->
            assertTrue("All IDs must match format: $id", idFormatRegex.matches(id))
        }
    }

    @Test
    fun `thread safety - 50 concurrent getUserId calls return same ID`() {
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)
        
        val threadCount = 50
        val latch = CountDownLatch(threadCount)
        val ids = ConcurrentHashMap.newKeySet<String>()
        val errors = AtomicInteger(0)

        repeat(threadCount) {
            thread {
                try {
                    val id = idGenerator.getUserId()
                    if (!idFormatRegex.matches(id)) {
                        errors.incrementAndGet()
                    }
                    ids.add(id)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()

        assertEquals("No errors should occur during concurrent access", 0, errors.get())
        assertTrue("Should generate at least one valid ID", ids.size >= 1)
        ids.forEach { id ->
            assertTrue("All IDs must match format: $id", idFormatRegex.matches(id))
        }
    }

    @Test
    fun `thread safety - concurrent device ID access with persistence`() {
        val persistedId = "1234567890123_persist01"
        `when`(mockPrefs.getString("device_id", null)).thenReturn(persistedId)

        val threadCount = 50
        val latch = CountDownLatch(threadCount)
        val ids = ConcurrentHashMap.newKeySet<String>()
        val errors = AtomicInteger(0)

        repeat(threadCount) {
            thread {
                try {
                    val id = idGenerator.getOrGenerateDeviceId()
                    ids.add(id)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()

        assertEquals("No errors should occur during concurrent access", 0, errors.get())
        assertEquals("All calls must return the same persisted ID", 1, ids.size)
        assertEquals("Must return persisted ID", persistedId, ids.first())
    }

    @Test
    fun `thread safety - concurrent getUserId calls with auto-generation`() {
        `when`(mockPrefs.getString("user_id", null)).thenReturn(null)

        val threadCount = 50
        val latch = CountDownLatch(threadCount)
        val ids = ConcurrentHashMap.newKeySet<String>()
        val errors = AtomicInteger(0)

        repeat(threadCount) {
            thread {
                try {
                    val id = idGenerator.getUserId()
                    if (!idFormatRegex.matches(id)) {
                        errors.incrementAndGet()
                    }
                    ids.add(id)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()

        assertEquals("No errors should occur during concurrent access", 0, errors.get())
        assertTrue("Should generate at least one valid ID", ids.size >= 1)
        ids.forEach { id ->
            assertTrue("All IDs must match format: $id", idFormatRegex.matches(id))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `throws exception when not initialized - getOrGenerateDeviceId`() {
        val uninitializedGenerator = IdGenerator()
        uninitializedGenerator.getOrGenerateDeviceId()
    }

    @Test(expected = IllegalStateException::class)
    fun `throws exception when not initialized - getUserId`() {
        val uninitializedGenerator = IdGenerator()
        uninitializedGenerator.getUserId()
    }

    // Test removed - setUserId() method no longer exists

    // Test removed - clearUserId() method no longer exists

    @Test
    fun `kind prefix present per ID type`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)

        val sessionId = idGenerator.generateSessionId()
        val userId = idGenerator.getUserId()
        val deviceId = idGenerator.getOrGenerateDeviceId()

        assertTrue("Session ID must start with session_", sessionId.startsWith("session_"))
        assertTrue("User ID must start with user_", userId.startsWith("user_"))
        assertTrue("Device ID must start with device_", deviceId.startsWith("device_"))
    }

    @Test
    fun `android platform suffix present`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)

        val sessionId = idGenerator.generateSessionId()
        val userId = idGenerator.getUserId()
        val deviceId = idGenerator.getOrGenerateDeviceId()

        assertTrue("Session ID must end with _android", sessionId.endsWith("_android"))
        assertTrue("User ID must end with _android", userId.endsWith("_android"))
        assertTrue("Device ID must end with _android", deviceId.endsWith("_android"))
    }

    @Test
    fun `ID contains exactly three underscores`() {
        val id = idGenerator.generateSessionId()
        val underscoreCount = id.count { it == '_' }
        assertEquals("ID must contain exactly three underscores", 3, underscoreCount)
    }

    @Test
    fun `ID parts are in correct order - kind timestamp hex platform`() {
        val id = idGenerator.generateSessionId()
        val parts = id.split("_")

        assertEquals("ID must have exactly 4 parts", 4, parts.size)
        assertEquals("First part must be kind", "session", parts[0])
        assertTrue("Second part must be numeric timestamp", parts[1].all { it.isDigit() })
        assertTrue("Third part must be lowercase hex", parts[2].all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("Fourth part must be platform", "android", parts[3])
    }

    @Test
    fun `timestamp accuracy - within 1 second of System currentTimeMillis`() {
        val beforeGeneration = System.currentTimeMillis()
        val id = idGenerator.generateSessionId()
        val afterGeneration = System.currentTimeMillis()
        
        val timestamp = id.split("_")[1].toLong()

        assertTrue("Timestamp must be >= time before generation", timestamp >= beforeGeneration)
        assertTrue("Timestamp must be <= time after generation", timestamp <= afterGeneration)
        
        val timeDiff = abs(timestamp - beforeGeneration)
        assertTrue("Timestamp must be within 1000ms of generation time", timeDiff <= 1000)
    }

    @Test
    fun `timestamp accuracy - multiple IDs have increasing timestamps`() {
        val ids = mutableListOf<String>()
        repeat(10) {
            ids.add(idGenerator.generateSessionId())
            Thread.sleep(1) // Small delay to ensure different timestamps
        }
        
        val timestamps = ids.map { it.split("_")[1].toLong() }
        
        for (i in 1 until timestamps.size) {
            assertTrue(
                "Timestamps must be monotonically increasing or equal: ${timestamps[i-1]} vs ${timestamps[i]}",
                timestamps[i] >= timestamps[i-1]
            )
        }
    }

    @Test
    fun `character set validation - no uppercase letters in any ID`() = runTest {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)
        
        val sessionIds = (1..100).map { idGenerator.generateSessionId() }
        val userId = idGenerator.getUserId()
        val deviceIds = (1..100).map { 
            val gen = IdGenerator()
            gen.initialize(mockContext)
            gen.getOrGenerateDeviceId()
        }
        
        val allIds = sessionIds + listOf(userId) + deviceIds
        
        allIds.forEach { id ->
            assertFalse("ID must not contain uppercase letters: $id", id.any { it.isUpperCase() })
        }
    }

    @Test
    fun `character set validation - no special characters except underscore`() {
        val ids = (1..100).map { idGenerator.generateSessionId() }
        
        ids.forEach { id ->
            val allowedChars = ('a'..'z') + ('0'..'9') + '_'
            assertTrue(
                "ID must only contain lowercase alphanumeric and underscore: $id",
                id.all { it in allowedChars }
            )
        }
    }

    @Test
    fun `coroutine thread safety - 50 concurrent coroutines generate unique session IDs`() = runTest {
        val coroutineCount = 50
        val ids = ConcurrentHashMap.newKeySet<String>()
        
        val jobs = (1..coroutineCount).map {
            async {
                val id = idGenerator.generateSessionId()
                assertTrue("ID must match format: $id", idFormatRegex.matches(id))
                ids.add(id)
            }
        }
        
        jobs.awaitAll()
        
        assertEquals("All 50 coroutines must generate unique IDs", coroutineCount, ids.size)
        ids.forEach { id ->
            assertTrue("All IDs must match format: $id", idFormatRegex.matches(id))
        }
    }

    @Test
    fun `coroutine thread safety - 50 concurrent getUserId calls`() = runTest {
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)
        
        val coroutineCount = 50
        val ids = ConcurrentHashMap.newKeySet<String>()
        
        val jobs = (1..coroutineCount).map {
            async {
                val id = idGenerator.getUserId()
                assertTrue("ID must match format: $id", idFormatRegex.matches(id))
                ids.add(id)
            }
        }
        
        jobs.awaitAll()
        
        assertTrue("Should generate at least one valid ID", ids.size >= 1)
        ids.forEach { id ->
            assertTrue("All IDs must match format: $id", idFormatRegex.matches(id))
        }
    }

    @Test
    fun `coroutine thread safety - concurrent device ID access returns same persisted ID`() = runTest {
        val persistedId = "1234567890123_persist01"
        `when`(mockPrefs.getString("device_id", null)).thenReturn(persistedId)
        
        val coroutineCount = 50
        val ids = ConcurrentHashMap.newKeySet<String>()
        
        val jobs = (1..coroutineCount).map {
            async {
                val id = idGenerator.getOrGenerateDeviceId()
                ids.add(id)
            }
        }
        
        jobs.awaitAll()
        
        assertEquals("All coroutines must return the same persisted ID", 1, ids.size)
        assertEquals("Must return persisted ID", persistedId, ids.first())
    }

    @Test
    fun `uniqueness validation - 1000 session IDs with zero collisions`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        
        var storedUserId: String? = null
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenAnswer { storedUserId }
        `when`(mockEditor.putString(eq("edge_rum_user_id"), anyString())).thenAnswer {
            storedUserId = it.arguments[1] as String
            mockEditor
        }
        
        val allIds = mutableSetOf<String>()
        
        repeat(1000) {
            allIds.add(idGenerator.generateSessionId())
        }
        
        assertEquals("Must generate 1000 unique session IDs", 1000, allIds.size)
        
        // Verify getUserId returns same ID when called multiple times
        val userId1 = idGenerator.getUserId()
        val userId2 = idGenerator.getUserId()
        assertEquals("getUserId must return same ID", userId1, userId2)
    }

    @Test
    fun `format validation - all generated IDs match regex pattern`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)
        
        val sessionIds = (1..100).map { idGenerator.generateSessionId() }
        val userId = idGenerator.getUserId()
        val deviceIds = (1..100).map {
            val gen = IdGenerator()
            gen.initialize(mockContext)
            gen.getOrGenerateDeviceId()
        }
        
        (sessionIds + listOf(userId) + deviceIds).forEach { id ->
            assertTrue(
                "ID must match pattern <kind>_<epochMs>_<16hex>_android: $id",
                idFormatRegex.matches(id)
            )
        }
    }

    // --- Migration (#52): preserve persisted legacy IDs, new format only for newly-minted ---

    @Test
    fun `upgraded install - legacy device and user IDs preserved verbatim`() {
        val legacyDeviceId = "1736899200000_a3k9zq1m"
        val legacyUserId = "1736899200000_b7x2mq4p"
        `when`(mockPrefs.getString("device_id", null)).thenReturn(legacyDeviceId)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(legacyUserId)

        assertEquals("Legacy device ID must be returned unchanged", legacyDeviceId, idGenerator.getDeviceId())
        assertEquals("Legacy user ID must be returned unchanged", legacyUserId, idGenerator.getUserId())
        verify(mockEditor, never()).putString(eq("device_id"), anyString())
        verify(mockEditor, never()).putString(eq("edge_rum_user_id"), anyString())
    }

    @Test
    fun `upgraded install - session ID is still new format`() {
        // Sessions are never persisted, so an upgraded install still mints new-format session IDs.
        `when`(mockPrefs.getString("device_id", null)).thenReturn("1736899200000_a3k9zq1m")

        val sessionId = idGenerator.generateSessionId()
        assertTrue("Session ID must be new format: $sessionId", idFormatRegex.matches(sessionId))
        assertTrue("Session ID must start with session_", sessionId.startsWith("session_"))
    }

    @Test
    fun `fresh install - device user session all mint new format`() {
        `when`(mockPrefs.getString("device_id", null)).thenReturn(null)
        `when`(mockPrefs.getString("edge_rum_user_id", null)).thenReturn(null)

        assertTrue(idGenerator.getOrGenerateDeviceId().matches(Regex("""^device_\d+_[0-9a-f]{16}_android$""")))
        assertTrue(idGenerator.getUserId().matches(Regex("""^user_\d+_[0-9a-f]{16}_android$""")))
        assertTrue(idGenerator.generateSessionId().matches(Regex("""^session_\d+_[0-9a-f]{16}_android$""")))
    }
}
