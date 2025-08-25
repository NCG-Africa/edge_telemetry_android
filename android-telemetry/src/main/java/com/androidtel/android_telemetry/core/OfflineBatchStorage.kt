package com.androidtel.android_telemetry.core


import android.content.Context
import com.androidtel.android_telemetry.core.models.TelemetryBatch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A simple implementation of offline storage using SharedPreferences.
// A more robust solution would use a local database like Room.
class OfflineBatchStorage(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val batchListType = object : TypeToken<List<TelemetryBatch>>() {}.type

    companion object {
        private const val PREFS_KEY = "stored_batches"
    }

    // Stores a single batch to the persistent storage.
    suspend fun storeBatch(batch: TelemetryBatch) = withContext(Dispatchers.IO) {
        val batches = getStoredBatches().toMutableList()
        batches.add(batch)
        val json = gson.toJson(batches)
        prefs.edit().putString(PREFS_KEY, json).apply()
    }

    // Retrieves all stored batches from the persistent storage.
    suspend fun getStoredBatches(): List<TelemetryBatch> = withContext(Dispatchers.IO) {
        val json = prefs.getString(PREFS_KEY, null)
        return@withContext if (json != null) {
            gson.fromJson(json, batchListType) ?: emptyList()
        } else {
            emptyList()
        }
    }

    // Removes a specific batch by its ID.
    suspend fun removeBatch(batchId: String) = withContext(Dispatchers.IO) {
        val batches = getStoredBatches().toMutableList()
        val updatedBatches = batches.filter { it.id != batchId }
        val json = gson.toJson(updatedBatches)
        prefs.edit().putString(PREFS_KEY, json).apply()
    }

    // Clears all stored batches.
    suspend fun clearAllBatches() = withContext(Dispatchers.IO) {
        prefs.edit().remove(PREFS_KEY).apply()
    }
}