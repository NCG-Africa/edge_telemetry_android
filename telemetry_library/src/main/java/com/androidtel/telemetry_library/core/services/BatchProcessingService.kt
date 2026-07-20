package com.androidtel.telemetry_library.core.services

import android.util.Log
import com.androidtel.telemetry_library.core.CountedEventQueue
import com.androidtel.telemetry_library.core.OfflineBatchStorage
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.androidtel.telemetry_library.core.TelemetryTime
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
    private var flushTimer: ScheduledExecutorService? = null
    private var batchSendJob: Job? = null

    // Stored as a field (not a startFlushTimer param) so resumeFlushTimer() re-wires it after a stop.
    private var onFlush: (() -> Unit)? = null

    // Volatile so readers on the background flush coroutine see writes from the init thread without
    // a happens-before via an external lock.
    @Volatile
    private var idsInitialized: Boolean = false

    fun initialize(onFlush: () -> Unit) {
        this.onFlush = onFlush
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
        eventQueue: CountedEventQueue,
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
            timestamp = TelemetryTime.now(),
            events = eventsToSend,
            location = location
        )
        
        Log.i(TAG, "Attempting to send a batch of ${batch.batchSize} events")
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
     * Replay stored offline batches, oldest-first.
     *
     * Throttled to at most [REPLAY_BATCH_LIMIT] envelopes per call and stops on the first failure:
     * a device offline long enough to fill the store must not fire the whole store back-to-back on
     * reconnect, and a failure means the collector isn't ready for more. The flush timer paces the
     * rest across subsequent ticks. Replay goes offline-store → network directly (never back through
     * the in-memory queue).
     */
    suspend fun sendStoredBatches() {
        Log.i(TAG, "Checking for stored batches to send.")
        val storedBatches = offlineStorage.getStoredBatches()
        if (storedBatches.isEmpty()) return

        Log.i(TAG, "Found ${storedBatches.size} stored batches. Replaying up to $REPLAY_BATCH_LIMIT.")
        for (batch in storedBatches.take(REPLAY_BATCH_LIMIT)) {
            val result = httpClient.sendBatch(batch)
            if (result.isSuccess) {
                Log.i(TAG, "Successfully re-sent stored batch.")
                offlineStorage.removeBatch(batch.id)
            } else {
                Log.e(TAG, "Stored batch send failed; pausing replay until next flush tick.")
                return
            }
        }
    }

    /**
     * Trigger batch send asynchronously.
     */
    fun triggerBatchSend(
        eventQueue: CountedEventQueue,
        location: String? = null
    ): Job {
        batchSendJob = scope.launch {
            sendBatch(eventQueue, forceSend = false, flushOffline = true, location = location)
        }
        return batchSendJob!!
    }

    /**
     * Flush event queue to offline storage
     */
    suspend fun flushToOfflineStorage(eventQueue: CountedEventQueue) {
        try {
            val eventsToStore = mutableListOf<TelemetryEvent>()
            while (eventQueue.isNotEmpty()) {
                eventQueue.poll()?.let { eventsToStore.add(it) }
            }
            
            if (eventsToStore.isNotEmpty()) {
                val batch = TelemetryBatch(
                    batchSize = eventsToStore.size,
                    timestamp = TelemetryTime.now(),
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
                onFlush?.invoke()
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
        // Oldest envelopes drained per flush tick — caps reconnect burst; timer paces the rest.
        const val REPLAY_BATCH_LIMIT = 10
    }
}
