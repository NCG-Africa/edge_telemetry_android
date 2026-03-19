package com.androidtel.telemetry_library.core.retry

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.androidtel.telemetry_library.core.interceptors.ApiKeyRedactionInterceptor
import com.google.gson.Gson
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
// Using milliseconds instead of Duration for Java 8 compatibility
import java.util.concurrent.TimeUnit

/**
 * Network-aware retry system for crash data with exponential backoff
 */
class CrashRetryManager(
    private val context: Context,
    private val apiKey: String,
    private val telemetryEndpoint: String,
    private val debugMode: Boolean = false
) {
    
    companion object {
        private const val TAG = "CrashRetryManager"
        private const val MAX_RETRIES = 3
        private const val OFFLINE_STORAGE_FILE = "pending_crashes.json"
        private const val WORK_TAG = "crash_retry_work"
    }
    
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(ApiKeyRedactionInterceptor(debugMode))
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (debugMode) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            redactHeader("X-API-Key")
        })
        .build()
    
    private val baseRetryDelayMs = 60_000L // 1 minute in milliseconds
    private val offlineStorageFile = File(context.cacheDir, OFFLINE_STORAGE_FILE)
    
    /**
     * Send crash data with retry mechanism
     */
    suspend fun sendCrashWithRetry(crashData: Map<String, Any>) {
        var attempt = 0
        var lastException: Exception? = null
        
        while (attempt < MAX_RETRIES) {
            try {
                sendCrashData(crashData)
                logCrashSuccess(crashData)
                return
            } catch (e: Exception) {
                lastException = e
                attempt++
                
                Log.w(TAG, "❌ Crash send attempt $attempt failed: ${e.message}")
                
                if (attempt < MAX_RETRIES) {
                    val delay = calculateRetryDelayMs(attempt)
                    Log.d(TAG, "⏳ Retrying in ${delay}ms...")
                    delay(delay)
                }
            }
        }
        
        // All retries failed, store offline
        Log.e(TAG, "💾 All retries failed, storing crash offline", lastException)
        storeCrashOffline(crashData)
        scheduleRetry()
    }
    
    /**
     * Send crash data via HTTP
     */
    private suspend fun sendCrashData(crashData: Map<String, Any>) {
        val json = gson.toJson(crashData)
        val requestBody = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(telemetryEndpoint)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "EdgeTelemetry-Android/1.2.0")
            .addHeader("X-API-Key", apiKey)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        
        response.close()
    }
    
    /**
     * Store crash data offline for later retry
     */
    private fun storeCrashOffline(crashData: Map<String, Any>) {
        try {
            val existingCrashes = loadOfflineCrashes().toMutableList()
            existingCrashes.add(crashData)
            
            val json = gson.toJson(existingCrashes)
            offlineStorageFile.writeText(json)
            
            Log.d(TAG, "💾 Crash stored offline (total: ${existingCrashes.size})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store crash offline", e)
        }
    }
    
    /**
     * Load offline crashes
     */
    private fun loadOfflineCrashes(): List<Map<String, Any>> {
        return try {
            if (offlineStorageFile.exists()) {
                val json = offlineStorageFile.readText()
                val type = object : com.google.gson.reflect.TypeToken<Array<Map<String, Any>>>() {}.type
                gson.fromJson<Array<Map<String, Any>>>(json, type).toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load offline crashes", e)
            emptyList()
        }
    }
    
    /**
     * Schedule retry using WorkManager
     */
    private fun scheduleRetry() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val inputData = workDataOf(
            "apiKey" to apiKey,
            "endpoint" to telemetryEndpoint,
            "debugMode" to debugMode
        )
        
        val retryWork = OneTimeWorkRequestBuilder<CrashRetryWorker>()
            .setConstraints(constraints)
            .setInitialDelay(baseRetryDelayMs / 60_000L, TimeUnit.MINUTES)
            .setInputData(inputData)
            .addTag(WORK_TAG)
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "crash_retry",
                ExistingWorkPolicy.REPLACE,
                retryWork
            )
        
        Log.d(TAG, "📅 Retry scheduled for ${baseRetryDelayMs / 60_000L} minutes")
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private fun calculateRetryDelayMs(attempt: Int): Long {
        val multiplier = Math.pow(2.0, (attempt - 1).toDouble()).toLong()
        return baseRetryDelayMs * multiplier
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check network availability", e)
            false
        }
    }
    
    /**
     * Log successful crash transmission
     */
    private fun logCrashSuccess(crashData: Map<String, Any>) {
        val errorType = (crashData["data"] as? Map<*, *>)?.get("type") ?: "unknown"
        Log.i(TAG, "✅ Crash data sent successfully: $errorType")
    }
    
    /**
     * Retry offline crashes
     */
    suspend fun retryOfflineCrashes() {
        val offlineCrashes = loadOfflineCrashes()
        
        if (offlineCrashes.isEmpty()) {
            Log.d(TAG, "📭 No offline crashes to retry")
            return
        }
        
        Log.i(TAG, "🔄 Retrying ${offlineCrashes.size} offline crashes")
        
        val successfulCrashes = mutableListOf<Map<String, Any>>()
        
        for (crashData in offlineCrashes) {
            try {
                sendCrashData(crashData)
                successfulCrashes.add(crashData)
                logCrashSuccess(crashData)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retry crash: ${e.message}")
            }
        }
        
        // Remove successful crashes from offline storage
        if (successfulCrashes.isNotEmpty()) {
            val remainingCrashes = offlineCrashes - successfulCrashes.toSet()
            
            if (remainingCrashes.isEmpty()) {
                offlineStorageFile.delete()
                Log.i(TAG, "🧹 All offline crashes sent successfully")
            } else {
                val json = gson.toJson(remainingCrashes)
                offlineStorageFile.writeText(json)
                Log.i(TAG, "📊 ${successfulCrashes.size} crashes sent, ${remainingCrashes.size} remaining")
            }
        }
    }
}

/**
 * WorkManager worker for retrying offline crashes
 */
class CrashRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "CrashRetryWorker"
    }
    
    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 Starting crash retry work")
            
            // Retrieve API key, endpoint, and debugMode from input data
            val apiKey = inputData.getString("apiKey")
            val endpoint = inputData.getString("endpoint")
            val debugMode = inputData.getBoolean("debugMode", false)
            
            if (apiKey.isNullOrBlank() || endpoint.isNullOrBlank()) {
                Log.e(TAG, "❌ Missing API key or endpoint in WorkManager input data")
                return Result.failure()
            }
            
            val retryManager = CrashRetryManager(applicationContext, apiKey, endpoint, debugMode)
            retryManager.retryOfflineCrashes()
            
            Log.d(TAG, "✅ Crash retry work completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Crash retry work failed", e)
            Result.retry()
        }
    }
}
