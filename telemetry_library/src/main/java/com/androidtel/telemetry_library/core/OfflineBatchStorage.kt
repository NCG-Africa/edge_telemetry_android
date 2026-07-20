package com.androidtel.telemetry_library.core


import android.content.Context
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Offline storage using SharedPreferences: a 200-envelope, drop-oldest bounded buffer (D9).
//
// Room is deliberately NOT used — it is declared runtime+ktx only (no compiler, no ksp), i.e. dead
// weight, and this store fills only while offline (low write frequency). Upgrade path if offline
// depth becomes real: wire Room properly (@Entity per envelope, DELETE ... ORDER BY seq LIMIT).
class OfflineBatchStorage(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val batchListType = object : TypeToken<List<TelemetryBatch>>() {}.type

    // Serializes all mutations. storeBatch/removeBatch/clear do read-modify-write of the whole blob;
    // without this, two concurrent writes race and last-write-wins silently drops a batch.
    private val mutex = Mutex()

    companion object {
        private const val PREFS_KEY = "stored_batches"
        // Hub decision D9 (issue #33 spec): the durable offline buffer is 200 envelopes, drop-oldest.
        const val MAX_ENVELOPES = 200
    }

    // Stores a single batch, capping at MAX_ENVELOPES (drop-oldest).
    suspend fun storeBatch(batch: TelemetryBatch) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val batches = readBatches().toMutableList()
            batches.add(batch)
            // The list is append-ordered, so the front is oldest: drop from the front past the cap.
            val capped = if (batches.size > MAX_ENVELOPES)
                batches.subList(batches.size - MAX_ENVELOPES, batches.size) else batches
            prefs.edit().putString(PREFS_KEY, gson.toJson(capped)).apply()
        }
    }

    // Retrieves all stored batches, oldest-first (append order). Intentionally lock-free: only
    // mutations need serializing, so a concurrent reader may observe the pre-write blob — fine for
    // a soft offline buffer (it just replays those envelopes on the next tick).
    suspend fun getStoredBatches(): List<TelemetryBatch> = withContext(Dispatchers.IO) {
        readBatches()
    }

    // Removes a specific batch by its ID.
    suspend fun removeBatch(batchId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remaining = readBatches().filter { it.id != batchId }
            prefs.edit().putString(PREFS_KEY, gson.toJson(remaining)).apply()
        }
    }

    // Clears all stored batches.
    suspend fun clearAllBatches() = withContext(Dispatchers.IO) {
        mutex.withLock { prefs.edit().remove(PREFS_KEY).apply() }
    }

    private fun readBatches(): List<TelemetryBatch> {
        val json = prefs.getString(PREFS_KEY, null) ?: return emptyList()
        return gson.fromJson(json, batchListType) ?: emptyList()
    }
}
