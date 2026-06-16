package com.androidtel.telemetry_library.core.retry

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.androidtel.telemetry_library.BuildConfig
import com.androidtel.telemetry_library.core.interceptors.ApiKeyRedactionInterceptor
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Network-aware retry system for crash data with exponential backoff
 */
class CrashRetryManager(
    private val context: Context,
    private val apiKey: String,
    private val telemetryEndpoint: String,
    private val debugMode: Boolean = false,
    private val enableWorkManager: Boolean = true
) {
    
    companion object {
        private const val TAG = "CrashRetryManager"
        private const val MAX_RETRIES = 3
        private const val OFFLINE_STORAGE_FILE = "pending_crashes.json"
        private const val WORK_TAG = "crash_retry_work"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L // 1 minute
        private const val MAX_STORAGE_FAILURES = 3
        
        // Shared circuit breaker state across all instances
        private val isRateLimited = AtomicBoolean(false)
        private val rateLimitUntil = AtomicLong(0L)
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
    
    private val baseRetryDelay = Duration.ofMinutes(1)
    private val offlineStorageFile = File(context.cacheDir, OFFLINE_STORAGE_FILE)
    private val fileLock = Any()
    private val isStoringCrash = AtomicBoolean(false)
    private val storageFailureCount = AtomicInteger(0)
    private val gsonLenient = GsonBuilder().setLenient().create()
    private val lockFile = File(context.cacheDir, "crash_storage.lock")
    
    /**
     * Send crash data with retry mechanism
     */
    suspend fun sendCrashWithRetry(crashData: Map<String, Any>) {
        // Check circuit breaker - if we're rate limited, skip immediately
        if (isRateLimited.get() && System.currentTimeMillis() < rateLimitUntil.get()) {
            Log.w(TAG, "🚫 Circuit breaker open - rate limited until ${rateLimitUntil.get() - System.currentTimeMillis()}ms")
            storeCrashOffline(crashData)
            return
        }
        
        // Reset circuit breaker if cooldown expired
        if (isRateLimited.get() && System.currentTimeMillis() >= rateLimitUntil.get()) {
            Log.i(TAG, "✅ Circuit breaker reset - rate limit cooldown expired")
            isRateLimited.set(false)
        }
        
        var attempt = 0
        var lastException: Exception? = null
        
        while (attempt < MAX_RETRIES) {
            try {
                sendCrashData(crashData)
                logCrashSuccess(crashData)
                return
            } catch (e: RateLimitException) {
                // HTTP 429 - activate circuit breaker and stop all retries
                val cooldownUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                isRateLimited.set(true)
                rateLimitUntil.set(cooldownUntil)
                
                Log.w(TAG, "⚠️ Rate limit hit (HTTP 429) - circuit breaker activated for ${RATE_LIMIT_COOLDOWN_MS}ms")
                storeCrashOffline(crashData)
                if (enableWorkManager) scheduleRetry()
                return
            } catch (e: Exception) {
                lastException = e
                attempt++
                
                Log.w(TAG, "❌ Crash send attempt $attempt failed: ${e.message}")
                
                if (attempt < MAX_RETRIES) {
                    val delay = calculateRetryDelay(attempt)
                    Log.d(TAG, "⏳ Retrying in ${delay.toMillis()}ms...")
                    delay(delay.toMillis())
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
            .addHeader("User-Agent", "EdgeTelemetryAndroid/${BuildConfig.SDK_VERSION}")
            .addHeader("X-API-Key", apiKey)
            .addHeader("X-SDK-Version", BuildConfig.SDK_VERSION)
            .addHeader("X-SDK-Platform", "android")
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_TOO_MANY_REQUESTS -> {
                    throw RateLimitException("Rate limit exceeded (HTTP 429)")
                }
                !response.isSuccessful -> {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
            }
        }
    }
    
    /**
     * Store crash data offline for later retry
     */
    private fun storeCrashOffline(crashData: Map<String, Any>) {
        // Storage circuit breaker
        if (storageFailureCount.get() >= MAX_STORAGE_FAILURES) {
            Log.e(TAG, "🚫 Storage circuit breaker open — dropping crash to prevent loop")
            return
        }
        // Recursion guard
        if (isStoringCrash.getAndSet(true)) {
            Log.w(TAG, "⚠️ Already storing crash — skipping to prevent recursion")
            return
        }
        try {
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    val existingCrashes = loadOfflineCrashesInternal(raf).toMutableList()
                    existingCrashes.add(crashData)
                    val json = gson.toJson(existingCrashes)
                    offlineStorageFile.writeText(json)
                    Log.d(TAG, "💾 Crash stored offline (total: ${existingCrashes.size})")
                }
            }
            storageFailureCount.set(0) // reset on success
        } catch (e: Exception) {
            storageFailureCount.incrementAndGet()
            Log.e(TAG, "❌ Failed to store crash offline (failures: ${storageFailureCount.get()}): ${e.message}")
        } finally {
            isStoringCrash.set(false)
        }
    }
    
    /**
     * Load offline crashes
     */
    private fun loadOfflineCrashes(): List<Map<String, Any>> {
        return try {
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    loadOfflineCrashesInternal(raf)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire lock for reading offline crashes", e)
            emptyList()
        }
    }
    
    private fun loadOfflineCrashesInternal(raf: RandomAccessFile? = null): List<Map<String, Any>> {
        return try {
            if (!offlineStorageFile.exists() || offlineStorageFile.length() == 0L) return emptyList()
            val json = offlineStorageFile.readText()
            if (json.isBlank()) return emptyList()

            val type = object : com.google.gson.reflect.TypeToken<Array<Map<String, Any>>>() {}.type
            val result: Array<Map<String, Any>>? = gsonLenient.fromJson(json, type)
            result?.toList() ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "🧩 JSON corrupted — deleting storage file", e)
            safeDeleteCorruptedFile()
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load offline crashes", e)
            safeDeleteCorruptedFile()
            emptyList()
        }
    }
    
    private fun safeDeleteCorruptedFile() {
        try {
            if (offlineStorageFile.exists()) {
                offlineStorageFile.delete()
                Log.w(TAG, "🗑️ Deleted corrupted offline storage file")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete corrupted storage file", e)
        }
    }
    
    /**
     * Schedule retry using WorkManager
     */
    private fun scheduleRetry() {
        if (!enableWorkManager) {
            Log.d(TAG, "⏭️ WorkManager disabled, skipping retry scheduling")
            return
        }
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
            .setInitialDelay(baseRetryDelay.toMinutes(), TimeUnit.MINUTES)
            .setInputData(inputData)
            .addTag(WORK_TAG)
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "crash_retry",
                ExistingWorkPolicy.REPLACE,
                retryWork
            )
        
        Log.d(TAG, "📅 Retry scheduled for ${baseRetryDelay.toMinutes()} minutes")
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private fun calculateRetryDelay(attempt: Int): Duration {
        val multiplier = Math.pow(2.0, (attempt - 1).toDouble()).toLong()
        return Duration.ofMillis(baseRetryDelay.toMillis() * multiplier)
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
            try {
                RandomAccessFile(lockFile, "rw").use { raf ->
                    raf.channel.lock().use {
                        val remainingCrashes = offlineCrashes - successfulCrashes.toSet()
                        if (remainingCrashes.isEmpty()) {
                            offlineStorageFile.delete()
                            Log.i(TAG, "🧹 All offline crashes sent successfully")
                        } else {
                            offlineStorageFile.writeText(gson.toJson(remainingCrashes))
                            Log.i(TAG, "📊 ${successfulCrashes.size} sent, ${remainingCrashes.size} remaining")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update offline crash storage after retry", e)
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

/**
 * Exception thrown when rate limit is hit
 */
class RateLimitException(message: String) : IOException(message)
