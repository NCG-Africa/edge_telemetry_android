package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.OfflineBatchStorage
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.crash.CrashReporter
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReportingService - Handles crash and error reporting
 * Extracted from TelemetryManager as part of Phase 2 refactoring
 * 
 * Responsibilities:
 * - Install crash handler
 * - Record crashes with stack traces
 * - Track errors manually
 * - Manage breadcrumbs
 * - Persist crash data
 */
internal class CrashReportingService(
    private val context: Context,
    private val config: TelemetryConfig,
    private val idGenerator: IdGenerator,
    private val httpClient: TelemetryHttpClient,
    private val offlineStorage: OfflineBatchStorage,
    private val scope: CoroutineScope,
    private val apiKey: String,
    private val telemetryEndpoint: String
) {
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    
    private var crashReporter: CrashReporter? = null
    private var breadcrumbManager: BreadcrumbManager? = null
    // Atomic so concurrent CrashReportingService.initialize() calls can't double-install the
    // Thread.UncaughtExceptionHandler (which would chain twice and double-report).
    private val crashHandlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    // Synchronizes access to the persisted-crash file so concurrent recordCrash() / read paths
    // can't corrupt the JSON. App's private storage = single-process, so a local lock suffices.
    private val persistedCrashFileLock = Any()

    private val persistedCrashFileName = "telemetry_pending_crash.json"
    private val persistedCrashFile: File
        get() = File(context.cacheDir, persistedCrashFileName)

    fun initialize() {
        breadcrumbManager = BreadcrumbManager()

        if (config.enableCrashReporting && crashHandlerInstalled.compareAndSet(false, true)) {
            crashReporter = CrashReporter(
                context = context,
                telemetryManager = null,
                breadcrumbManager = breadcrumbManager!!,
                idGenerator = idGenerator,
                apiKey = apiKey,
                telemetryEndpoint = telemetryEndpoint,
                enabled = true,
                debugMode = false
            )
            // Note: installGlobalExceptionHandler() is already called in CrashReporter.init when enabled=true
            Log.d(TAG, "Crash handler installed")
        }

        Log.d(TAG, "CrashReportingService initialized")
    }
    
    /**
     * Record a crash event
     */
    fun recordCrash(
        throwable: Throwable,
        buildAttributesFn: (Map<String, Any>) -> EventAttributes?,
        onEventCreated: (TelemetryEvent) -> Unit
    ) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        
        val breadcrumbs = breadcrumbManager?.getBreadcrumbsAsJson() ?: "[]"
        val breadcrumbCount = breadcrumbManager?.getBreadcrumbCount() ?: 0
        
        val attributes = mapOf(
            "error.message" to "${throwable.javaClass.name}: ${throwable.message ?: ""}".take(1000),
            "error.stack_trace" to stackTrace.take(2000),
            "error.exception_type" to throwable.javaClass.simpleName.take(255),
            "error.context" to extractErrorContext(stackTrace).take(500),
            "error.cause" to (throwable.cause?.message ?: "unknown").take(255),
            "error.severity_level" to determineSeverityLevel(throwable),
            "error.is_fatal" to true,
            "error.breadcrumbs" to breadcrumbs.take(800),
            "error.breadcrumb_count" to breadcrumbCount
        )
        
        val event = buildAttributesFn(attributes)?.let {
            TelemetryEvent(
                type = "event",
                eventName = "app.crash",
                timestamp = dateFormat.format(Date()),
                attributes = it
            )
        }
        
        event?.let {
            onEventCreated(it)
            
            val batch = TelemetryBatch(
                batchSize = 1,
                timestamp = dateFormat.format(Date()),
                events = listOf(it)
            )
            persistBatchSync(batch)
        }
        
        scope.launch { sendBatchAsync() }
    }
    
    /**
     * Add breadcrumb
     */
    fun addBreadcrumb(
        message: String,
        category: String = "custom",
        level: String = "info",
        data: Map<String, String>? = null
    ) {
        breadcrumbManager?.addBreadcrumb(message, category, level, data)
            ?: Log.w(TAG, "Breadcrumb manager not initialized")
    }
    
    /**
     * Track error manually
     */
    fun trackError(error: Throwable, attributes: Map<String, String>? = null) {
        if (config.enableCrashReporting && crashReporter != null) {
            crashReporter!!.trackError(error, attributes)
        } else {
            Log.w(TAG, "Crash reporting not enabled")
        }
    }
    
    /**
     * Track error with enhanced context
     */
    fun trackError(
        error: Throwable,
        errorCode: String? = null,
        productId: String? = null,
        userAction: String? = null,
        attributes: Map<String, String>? = null
    ) {
        if (config.enableCrashReporting && crashReporter != null) {
            crashReporter!!.trackError(error, errorCode, productId, userAction, attributes)
        } else {
            Log.w(TAG, "Crash reporting not enabled")
        }
    }
    
    /**
     * Track error with message
     */
    fun trackError(message: String, stackTrace: String? = null, attributes: Map<String, String>? = null) {
        if (config.enableCrashReporting && crashReporter != null) {
            crashReporter!!.trackError(message, stackTrace, attributes)
        } else {
            Log.w(TAG, "Crash reporting not enabled")
        }
    }
    
    /**
     * Set product context
     */
    fun setProductContext(productId: String) {
        if (config.enableCrashReporting && crashReporter != null) {
            crashReporter!!.setProductContext(productId)
        } else {
            Log.w(TAG, "Crash reporting not enabled")
        }
    }
    
    /**
     * Set last user action
     */
    fun setLastUserAction(action: String) {
        if (config.enableCrashReporting && crashReporter != null) {
            crashReporter!!.setLastUserAction(action)
        } else {
            Log.w(TAG, "Crash reporting not enabled")
        }
    }
    
    /**
     * Test crash reporting
     */
    fun testCrashReporting(customMessage: String? = null) {
        if (config.enableCrashReporting && crashReporter != null) {
            crashReporter!!.testCrashReporting(customMessage)
        } else {
            Log.w(TAG, "Crash reporting not enabled. Cannot test.")
        }
    }
    
    /**
     * Get breadcrumb manager
     */
    fun getBreadcrumbManager(): BreadcrumbManager? = breadcrumbManager
    
    /**
     * Get crash reporter
     */
    fun getCrashReporter(): CrashReporter? = crashReporter
    
    /**
     * Persist batch synchronously.
     *
     * Single intra-process mutex (`persistedCrashFileLock`) serializes all access to the persisted
     * crash file so concurrent recordCrash() / send / delete paths cannot race and corrupt the
     * JSON file. Sufficient because the file lives in app-private storage (single OS process).
     */
    private fun persistBatchSync(batch: TelemetryBatch) {
        synchronized(persistedCrashFileLock) {
            try {
                val json = gson.toJson(batch)
                persistedCrashFile.parentFile?.mkdirs()
                persistedCrashFile.writeText(json)
                Log.i(TAG, "Persisted crash batch to ${persistedCrashFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist crash batch: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Read persisted crash batch.
     */
    fun readPersistedBatch(): TelemetryBatch? {
        synchronized(persistedCrashFileLock) {
            return try {
                if (!persistedCrashFile.exists()) return null
                val text = persistedCrashFile.readText()
                gson.fromJson(text, TelemetryBatch::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read persisted crash batch: ${e.localizedMessage}", e)
                null
            }
        }
    }

    /**
     * Delete persisted batch.
     */
    fun deletePersistedBatch() {
        synchronized(persistedCrashFileLock) {
            try {
                if (persistedCrashFile.exists()) {
                    persistedCrashFile.delete()
                    Log.i(TAG, "Deleted persisted crash file.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete persisted crash file: ${e.localizedMessage}", e)
            }
            Unit
        }
    }
    
    /**
     * Send persisted crash if any.
     *
     * User profile is snapshotted into each event at recordCrash() time. Backend backfills
     * rum_users from event attributes, so no per-batch override is needed.
     */
    suspend fun sendPersistedCrashIfAny() {
        val batch = readPersistedBatch()
        if (batch == null) {
            return
        }

        Log.i(TAG, "Found persisted crash batch; attempting to send.")
        try {
            val result = httpClient.sendBatch(batch)
            if (result.isSuccess) {
                Log.i(TAG, "Successfully sent persisted crash batch.")
                deletePersistedBatch()
            } else {
                Log.e(TAG, "Failed to send persisted crash batch; moving to offline storage.")
                offlineStorage.storeBatch(batch)
                deletePersistedBatch()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending persisted crash batch: ${e.localizedMessage}", e)
        }
    }

    /**
     * Send batch asynchronously.
     */
    private suspend fun sendBatchAsync() {
        val batch = readPersistedBatch() ?: return
        val result = httpClient.sendBatch(batch)
        if (result.isSuccess) {
            deletePersistedBatch()
        }
    }
    
    /**
     * Extract error context from stack trace
     */
    private fun extractErrorContext(stackTrace: String): String {
        return try {
            val lines = stackTrace.lines()
            val firstFrame = lines.firstOrNull { it.trim().startsWith("at ") } ?: return "unknown"
            
            val atIndex = firstFrame.indexOf("at ")
            if (atIndex == -1) return "unknown"
            
            val methodPart = firstFrame.substring(atIndex + 3).trim()
            val parenIndex = methodPart.indexOf("(")
            val fullMethod = if (parenIndex > 0) methodPart.substring(0, parenIndex) else methodPart
            
            val parts = fullMethod.split(".")
            if (parts.size >= 2) {
                "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
            } else {
                fullMethod
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Determine severity level based on exception type
     */
    private fun determineSeverityLevel(throwable: Throwable): String {
        return when {
            throwable is OutOfMemoryError || throwable is StackOverflowError -> "critical"
            throwable is IllegalStateException || throwable is NullPointerException -> "error"
            throwable is IllegalArgumentException -> "warning"
            else -> "error"
        }
    }
    
    companion object {
        private const val TAG = "CrashReportingService"
    }
}
