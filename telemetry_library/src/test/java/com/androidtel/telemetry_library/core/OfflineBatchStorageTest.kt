package com.androidtel.telemetry_library.core

import android.content.Context
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for OfflineBatchStorage — the 200-envelope, drop-oldest, Mutex-serialized store (D9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OfflineBatchStorageTest {

    private lateinit var context: Context
    private lateinit var storage: OfflineBatchStorage

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = OfflineBatchStorage(context)
        runBlocking { storage.clearAllBatches() }
    }

    private fun batch(id: String): TelemetryBatch =
        TelemetryBatch(id = id, batchSize = 0, timestamp = "t", events = emptyList())

    @Test
    fun `caps at MAX_ENVELOPES dropping the oldest`() = runBlocking {
        repeat(OfflineBatchStorage.MAX_ENVELOPES + 1) { storage.storeBatch(batch("b$it")) }

        val stored = storage.getStoredBatches()
        assertEquals(OfflineBatchStorage.MAX_ENVELOPES, stored.size)
        // b0 (first/oldest) evicted; oldest survivor is b1, newest is the last stored.
        assertEquals("b1", stored.first().id)
        assertEquals("b${OfflineBatchStorage.MAX_ENVELOPES}", stored.last().id)
    }

    @Test
    fun `concurrent stores do not lose batches`() = runBlocking {
        val n = 50
        (0 until n).map { i -> async { storage.storeBatch(batch("c$i")) } }.awaitAll()

        // Without the Mutex, racing read-modify-write blobs would drop batches (last-write-wins).
        assertEquals(n, storage.getStoredBatches().size)
    }
}
