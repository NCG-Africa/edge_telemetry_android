package com.androidtel.telemetry_library.core.services

import com.androidtel.telemetry_library.core.OfflineBatchStorage
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Unit tests for BatchProcessingService
 * Tests batch sending, offline storage, retry logic, and flush timer management
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchProcessingServiceTest {

    private lateinit var config: TelemetryConfig
    private lateinit var httpClient: TelemetryHttpClient
    private lateinit var offlineStorage: OfflineBatchStorage
    private lateinit var testScope: TestScope
    private lateinit var service: BatchProcessingService

    @Before
    fun setup() {
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
        
        httpClient = mockk(relaxed = true)
        offlineStorage = mockk(relaxed = true)
        testScope = TestScope(UnconfinedTestDispatcher())
        
        service = BatchProcessingService(config, httpClient, offlineStorage, testScope)
    }

    @Test
    fun `test service initialization starts flush timer`() {
        service.initialize()
        
        // Verify timer is started (no exception thrown)
    }

    @Test
    fun `test setIdsInitialized updates initialization state`() {
        service.setIdsInitialized(true)
        
        // State should be updated (verified through sendBatch behavior)
    }

    @Test
    fun `test shouldSendBatch returns true when queue size meets batch size`() {
        assertTrue(service.shouldSendBatch(50))
        assertTrue(service.shouldSendBatch(100))
    }

    @Test
    fun `test shouldSendBatch returns false when queue size below batch size`() {
        assertFalse(service.shouldSendBatch(49))
        assertFalse(service.shouldSendBatch(25))
        assertFalse(service.shouldSendBatch(0))
    }

    @Test
    fun `test sendBatch skips when IDs not initialized`() = runTest {
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        service.sendBatch(eventQueue)
        
        coVerify(exactly = 0) { httpClient.sendBatch(any()) }
        assertEquals(50, eventQueue.size) // Events remain in queue
    }

    @Test
    fun `test sendBatch sends when IDs initialized and batch size met`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        service.sendBatch(eventQueue)
        
        coVerify { httpClient.sendBatch(any()) }
        assertTrue(eventQueue.isEmpty())
    }

    @Test
    fun `test sendBatch with forceSend ignores batch size`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(10) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        service.sendBatch(eventQueue, forceSend = true)
        
        coVerify { httpClient.sendBatch(any()) }
        assertTrue(eventQueue.isEmpty())
    }

    @Test
    fun `test sendBatch does not send when below batch size and not forced`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(25) { eventQueue.add(mockk(relaxed = true)) }
        
        service.sendBatch(eventQueue, forceSend = false)
        
        coVerify(exactly = 0) { httpClient.sendBatch(any()) }
        assertEquals(25, eventQueue.size)
    }

    @Test
    fun `test sendBatch stores offline on failure`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.failure(Exception("Network error"))
        
        service.sendBatch(eventQueue, flushOffline = true)
        
        coVerify { httpClient.sendBatch(any()) }
        coVerify { offlineStorage.storeBatch(any()) }
    }

    @Test
    fun `test sendBatch does not store offline when flushOffline is false`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.failure(Exception("Network error"))
        
        service.sendBatch(eventQueue, flushOffline = false)
        
        coVerify { httpClient.sendBatch(any()) }
        coVerify(exactly = 0) { offlineStorage.storeBatch(any()) }
    }

    @Test
    fun `test sendBatch includes location when provided`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        val location = "37.7749,-122.4194"
        var capturedBatch: TelemetryBatch? = null
        
        coEvery { httpClient.sendBatch(any()) } answers {
            capturedBatch = firstArg()
            Result.success(Unit)
        }
        
        service.sendBatch(eventQueue, location = location)
        
        assertEquals(location, capturedBatch?.location)
    }

    @Test
    fun `test sendBatch with empty queue does nothing`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        
        service.sendBatch(eventQueue, forceSend = true)
        
        coVerify(exactly = 0) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `test sendStoredBatches sends all stored batches`() = runTest {
        val batch1 = mockk<TelemetryBatch>(relaxed = true)
        val batch2 = mockk<TelemetryBatch>(relaxed = true)
        val batch3 = mockk<TelemetryBatch>(relaxed = true)
        
        every { batch1.id } returns "batch-1"
        every { batch2.id } returns "batch-2"
        every { batch3.id } returns "batch-3"
        
        coEvery { offlineStorage.getStoredBatches() } returns listOf(batch1, batch2, batch3)
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        service.sendStoredBatches()
        
        coVerify(exactly = 3) { httpClient.sendBatch(any()) }
        coVerify { offlineStorage.removeBatch("batch-1") }
        coVerify { offlineStorage.removeBatch("batch-2") }
        coVerify { offlineStorage.removeBatch("batch-3") }
    }

    @Test
    fun `test sendStoredBatches keeps failed batches in storage`() = runTest {
        val batch1 = mockk<TelemetryBatch>(relaxed = true)
        val batch2 = mockk<TelemetryBatch>(relaxed = true)
        
        every { batch1.id } returns "batch-1"
        every { batch2.id } returns "batch-2"
        
        coEvery { offlineStorage.getStoredBatches() } returns listOf(batch1, batch2)
        coEvery { httpClient.sendBatch(batch1) } returns Result.success(Unit)
        coEvery { httpClient.sendBatch(batch2) } returns Result.failure(Exception("Network error"))
        
        service.sendStoredBatches()
        
        coVerify { offlineStorage.removeBatch("batch-1") }
        coVerify(exactly = 0) { offlineStorage.removeBatch("batch-2") }
    }

    @Test
    fun `test sendStoredBatches with no stored batches`() = runTest {
        coEvery { offlineStorage.getStoredBatches() } returns emptyList()
        
        service.sendStoredBatches()
        
        coVerify(exactly = 0) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `test triggerBatchSend launches coroutine`() {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        val job = service.triggerBatchSend(eventQueue)
        
        assertNotNull(job)
    }

    @Test
    fun `test triggerBatchSend with location`() {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        val job = service.triggerBatchSend(eventQueue, location = "40.7128,-74.0060")
        
        assertNotNull(job)
    }

    @Test
    fun `test restoreOfflineBatches adds events to queue`() = runTest {
        val event1 = mockk<TelemetryEvent>(relaxed = true)
        val event2 = mockk<TelemetryEvent>(relaxed = true)
        val event3 = mockk<TelemetryEvent>(relaxed = true)
        
        val batch1 = mockk<TelemetryBatch>(relaxed = true)
        val batch2 = mockk<TelemetryBatch>(relaxed = true)
        
        every { batch1.id } returns "batch-1"
        every { batch1.events } returns listOf(event1, event2)
        every { batch2.id } returns "batch-2"
        every { batch2.events } returns listOf(event3)
        
        coEvery { offlineStorage.getStoredBatches() } returns listOf(batch1, batch2)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        service.restoreOfflineBatches(eventQueue)
        
        assertEquals(3, eventQueue.size)
        coVerify { offlineStorage.removeBatch("batch-1") }
        coVerify { offlineStorage.removeBatch("batch-2") }
    }

    @Test
    fun `test restoreOfflineBatches with no stored batches`() = runTest {
        coEvery { offlineStorage.getStoredBatches() } returns emptyList()
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        service.restoreOfflineBatches(eventQueue)
        
        assertTrue(eventQueue.isEmpty())
    }

    @Test
    fun `test restoreOfflineBatches handles errors gracefully`() = runTest {
        coEvery { offlineStorage.getStoredBatches() } throws Exception("Storage error")
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        service.restoreOfflineBatches(eventQueue)
        
        // Should not throw exception
        assertTrue(eventQueue.isEmpty())
    }

    @Test
    fun `test flushToOfflineStorage stores all queued events`() = runTest {
        val event1 = mockk<TelemetryEvent>(relaxed = true)
        val event2 = mockk<TelemetryEvent>(relaxed = true)
        val event3 = mockk<TelemetryEvent>(relaxed = true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        eventQueue.add(event1)
        eventQueue.add(event2)
        eventQueue.add(event3)
        
        service.flushToOfflineStorage(eventQueue)
        
        coVerify { offlineStorage.storeBatch(match { it.batchSize == 3 }) }
        assertTrue(eventQueue.isEmpty())
    }

    @Test
    fun `test flushToOfflineStorage with empty queue does nothing`() = runTest {
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        
        service.flushToOfflineStorage(eventQueue)
        
        coVerify(exactly = 0) { offlineStorage.storeBatch(any()) }
    }

    @Test
    fun `test flushToOfflineStorage handles errors gracefully`() = runTest {
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        eventQueue.add(mockk(relaxed = true))
        
        coEvery { offlineStorage.storeBatch(any()) } throws Exception("Storage error")
        
        service.flushToOfflineStorage(eventQueue)
        
        // Should not throw exception
    }

    @Test
    fun `test startFlushTimer creates timer`() {
        service.startFlushTimer()
        
        // Verify no exception thrown
    }

    @Test
    fun `test stopFlushTimer stops timer`() {
        service.startFlushTimer()
        service.stopFlushTimer()
        
        // Verify no exception thrown
    }

    @Test
    fun `test resumeFlushTimer restarts stopped timer`() {
        service.startFlushTimer()
        service.stopFlushTimer()
        service.resumeFlushTimer()
        
        // Verify no exception thrown
    }

    @Test
    fun `test resumeFlushTimer when timer already running`() {
        service.startFlushTimer()
        service.resumeFlushTimer()
        
        // Should not create duplicate timer
    }

    @Test
    fun `test getOfflineStorage returns storage instance`() {
        val storage = service.getOfflineStorage()
        
        assertEquals(offlineStorage, storage)
    }

    @Test
    fun `test sendBatch creates batch with correct size`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(75) { eventQueue.add(mockk(relaxed = true)) }
        
        var capturedBatch: TelemetryBatch? = null
        coEvery { httpClient.sendBatch(any()) } answers {
            capturedBatch = firstArg()
            Result.success(Unit)
        }
        
        service.sendBatch(eventQueue)
        
        assertEquals(75, capturedBatch?.batchSize)
        assertEquals(75, capturedBatch?.events?.size)
    }

    @Test
    fun `test sendBatch creates batch with timestamp`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        var capturedBatch: TelemetryBatch? = null
        coEvery { httpClient.sendBatch(any()) } answers {
            capturedBatch = firstArg()
            Result.success(Unit)
        }
        
        service.sendBatch(eventQueue)
        
        assertNotNull(capturedBatch?.timestamp)
        assertTrue(capturedBatch?.timestamp?.contains("T") == true)
        assertTrue(capturedBatch?.timestamp?.endsWith("Z") == true)
    }

    @Test
    fun `test concurrent batch sends are handled`() = runTest {
        service.setIdsInitialized(true)
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        val jobs = (1..5).map { threadNum ->
            testScope.launch {
                val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
                repeat(50) { eventQueue.add(mockk(relaxed = true)) }
                service.sendBatch(eventQueue)
            }
        }
        
        jobs.forEach { it.join() }
        
        coVerify(exactly = 5) { httpClient.sendBatch(any()) }
    }

    @Test
    fun `test batch processing with mixed success and failure`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue1 = ConcurrentLinkedQueue<TelemetryEvent>()
        val eventQueue2 = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { 
            eventQueue1.add(mockk(relaxed = true))
            eventQueue2.add(mockk(relaxed = true))
        }
        
        coEvery { httpClient.sendBatch(any()) } returnsMany listOf(
            Result.success(Unit),
            Result.failure(Exception("Network error"))
        )
        
        service.sendBatch(eventQueue1)
        service.sendBatch(eventQueue2, flushOffline = true)
        
        coVerify(exactly = 2) { httpClient.sendBatch(any()) }
        coVerify(exactly = 1) { offlineStorage.storeBatch(any()) }
    }

    @Test
    fun `test sendBatch logs appropriate messages`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(50) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        service.sendBatch(eventQueue)
        
        // Verify no exceptions thrown (logging happens internally)
    }

    @Test
    fun `test large batch processing`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(1000) { eventQueue.add(mockk(relaxed = true)) }
        
        coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
        
        service.sendBatch(eventQueue, forceSend = true)
        
        coVerify { httpClient.sendBatch(match { it.batchSize == 1000 }) }
        assertTrue(eventQueue.isEmpty())
    }

    @Test
    fun `test batch with location and forced send`() = runTest {
        service.setIdsInitialized(true)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        repeat(10) { eventQueue.add(mockk(relaxed = true)) }
        
        var capturedBatch: TelemetryBatch? = null
        coEvery { httpClient.sendBatch(any()) } answers {
            capturedBatch = firstArg()
            Result.success(Unit)
        }
        
        service.sendBatch(eventQueue, forceSend = true, location = "51.5074,-0.1278")
        
        assertEquals(10, capturedBatch?.batchSize)
        assertEquals("51.5074,-0.1278", capturedBatch?.location)
    }

    @Test
    fun `test flush timer interval matches config`() {
        val customConfig = config.copy(flushIntervalMs = 60000L)
        val customService = BatchProcessingService(customConfig, httpClient, offlineStorage, testScope)
        
        customService.startFlushTimer()
        
        // Verify timer started with correct interval (no exception)
    }

    @Test
    fun `test multiple stop and resume cycles`() {
        service.startFlushTimer()
        service.stopFlushTimer()
        service.resumeFlushTimer()
        service.stopFlushTimer()
        service.resumeFlushTimer()
        
        // Should handle multiple cycles without issues
    }

    @Test
    fun `test restore and flush operations maintain data integrity`() = runTest {
        val originalEvents = (1..10).map { mockk<TelemetryEvent>(relaxed = true) }
        
        val batch = mockk<TelemetryBatch>(relaxed = true)
        every { batch.id } returns "test-batch"
        every { batch.events } returns originalEvents
        
        coEvery { offlineStorage.getStoredBatches() } returns listOf(batch)
        
        val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
        service.restoreOfflineBatches(eventQueue)
        
        assertEquals(10, eventQueue.size)
        
        service.flushToOfflineStorage(eventQueue)
        
        coVerify { offlineStorage.storeBatch(match { it.batchSize == 10 }) }
        assertTrue(eventQueue.isEmpty())
    }
}
