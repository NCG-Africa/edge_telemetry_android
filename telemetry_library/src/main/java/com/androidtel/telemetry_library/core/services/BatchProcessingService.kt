package com.androidtel.telemetry_library.core.services

import android.util.Log
import com.androidtel.telemetry_library.core.OfflineBatchStorage
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * BatchProcessingService - Handles batch creation and sending
 * Extracted from TelemetryManager as part of Phase 2 refactoring
 * 
 * Responsibilities:
 * - Send batches of events to backend
 * - Manage offline storage
 * - Handle batch retry logic
 * - Manage flush timer
 */
internal class BatchProcessingService(
    private val config: TelemetryConfig,
    private val httpClient: TelemetryHttpClient,
    private val offlineStorage: OfflineBatchStorage,
    private val scope: CoroutineScope
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private var flushTimer: ScheduledExecutorService? = null
    private var batchSendJob: Job? = null
    
    // Volatile so readers on the background flush coroutine see writes from the init thread without
    // a happens-before via an external lock.
    @Volatile
    private var idsInitialized: Boolean = false
    
    fun initialize() {
        startFlushTimer()
        Log.d(TAG, "BatchProcessingService initialized")
    }
    
    /**
     * Mark IDs as initialized (required before sending batches)
     */
    fun setIdsInitialized(initialized: Boolean) {
        idsInitialized = initialized
    }
    
    /**
     * Check if batch should be sent based on queue size
     */
    fun shouldSendBatch(queueSize: Int): Boolean {
        return queueSize >= config.batchSize
    }
    
    /**
     * Send batch of events.
     *
     * User profile is snapshotted at event-creation time into each event's attributes; the
     * backend backfills `rum_users` from any event with user.* keys, so no per-batch override is
     * required.
     */
    suspend fun sendBatch(
        eventQueue: ConcurrentLinkedQueue<TelemetryEvent>,
        forceSend: Boolean = false,
        flushOffline: Boolean = true,
        location: String? = null
    ) {
        if (!idsInitialized) {
            Log.w(TAG, "Skipping batch send - IDs not properly initialized. Events remain queued (${eventQueue.size} events).")
            return
        }
        
        if (!forceSend && eventQueue.size < config.batchSize) {
            return
        }
        
        val eventsToSend = mutableListOf<TelemetryEvent>()
        repeat(eventQueue.size) {
            val ev = eventQueue.poll()
            if (ev != null) eventsToSend.add(ev)
        }
        
        if (eventsToSend.isEmpty()) return
        
        val batch = TelemetryBatch(
            batchSize = eventsToSend.size,
            timestamp = dateFormat.format(Date()),
            events = eventsToSend,
            location = location
        )
        
        Log.i(TAG, "Attempting to send a batch of ${batch.batchSize} events with location: $location")
        val result = httpClient.sendBatch(batch)

        if (result.isSuccess) {
            Log.i(TAG, "Successfully sent batch.")
        } else {
            Log.e(TAG, "Failed to send batch. Storing offline.")
            if (flushOffline) {
                offlineStorage.storeBatch(batch)
            }
        }
    }
    
    /**
     * Send stored batches from offline storage.
     */
    suspend fun sendStoredBatches() {
        Log.i(TAG, "Checking for stored batches to send.")
        val storedBatches = offlineStorage.getStoredBatches()
        if (storedBatches.isNotEmpty()) {
            Log.i(TAG, "Found ${storedBatches.size} stored batches. Attempting to send.")
            storedBatches.forEach { batch ->
                val result = httpClient.sendBatch(batch)
                if (result.isSuccess) {
                    Log.i(TAG, "Successfully re-sent stored batch.")
                    offlineStorage.removeBatch(batch.id)
                } else {
                    Log.e(TAG, "Failed to re-send stored batch. Will try again later.")
                }
            }
        }
    }
    
    /**
     * Trigger batch send asynchronously.
     */
    fun triggerBatchSend(
        eventQueue: ConcurrentLinkedQueue<TelemetryEvent>,
        location: String? = null
    ): Job {
        batchSendJob = scope.launch {
            sendBatch(eventQueue, forceSend = false, flushOffline = true, location = location)
        }
        return batchSendJob!!
    }
    
    /**
     * Restore offline batches to event queue
     */
    suspend fun restoreOfflineBatches(eventQueue: ConcurrentLinkedQueue<TelemetryEvent>) {
        try {
            val batches = offlineStorage.getStoredBatches()
            batches.forEach { batch ->
                batch.events.forEach { event ->
                    eventQueue.offer(event)
                }
                offlineStorage.removeBatch(batch.id)
            }
            if (batches.isNotEmpty()) {
                Log.d(TAG, "Restored ${batches.size} batches from offline storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring offline buffer", e)
        }
    }
    
    /**
     * Flush event queue to offline storage
     */
    suspend fun flushToOfflineStorage(eventQueue: ConcurrentLinkedQueue<TelemetryEvent>) {
        try {
            val eventsToStore = mutableListOf<TelemetryEvent>()
            while (eventQueue.isNotEmpty()) {
                eventQueue.poll()?.let { eventsToStore.add(it) }
            }
            
            if (eventsToStore.isNotEmpty()) {
                val batch = TelemetryBatch(
                    batchSize = eventsToStore.size,
                    timestamp = dateFormat.format(Date()),
                    events = eventsToStore
                )
                offlineStorage.storeBatch(batch)
                Log.d(TAG, "Flushed ${eventsToStore.size} events to offline storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing to offline storage", e)
        }
    }
    
    /**
     * Start flush timer
     */
    fun startFlushTimer() {
        flushTimer = Executors.newSingleThreadScheduledExecutor()
        flushTimer?.scheduleWithFixedDelay({
            try {
                // Timer will trigger batch send check in TelemetryManager
            } catch (e: Exception) {
                Log.e(TAG, "Error in flush timer", e)
            }
        }, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS)
        Log.d(TAG, "Flush timer started with interval: ${config.flushIntervalMs}ms")
    }
    
    /**
     * Stop flush timer
     */
    fun stopFlushTimer() {
        flushTimer?.shutdown()
        flushTimer = null
        Log.d(TAG, "Flush timer stopped")
    }
    
    /**
     * Resume flush timer
     */
    fun resumeFlushTimer() {
        if (flushTimer == null || flushTimer?.isShutdown == true) {
            startFlushTimer()
        }
    }
    
    /**
     * Get offline storage
     */
    fun getOfflineStorage(): OfflineBatchStorage = offlineStorage
    
    companion object {
        private const val TAG = "BatchProcessingService"
    }
}
