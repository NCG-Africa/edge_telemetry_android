package com.androidtel.telemetry_library.core.crash

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.device.DeviceInfoCollector
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.payload.FlutterPayloadFactory
import com.androidtel.telemetry_library.core.retry.CrashRetryManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Crash Reporter that handles automatic crash detection and manual error tracking
 * with exact payload structure matching the Flutter SDK
 */
class CrashReporter(
    private val context: Context,
    private val telemetryManager: TelemetryManager,
    private val breadcrumbManager: BreadcrumbManager,
    private val idGenerator: IdGenerator,
    private val enabled: Boolean = true
) {
    
    companion object {
        private const val TAG = "CrashReporter"
    }
    
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val retryManager = CrashRetryManager(context)
    private val deviceInfoCollector = DeviceInfoCollector(context, idGenerator)
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    
    init {
        if (enabled) {
            installGlobalExceptionHandler()
        }
    }
    
    /**
     * Install global exception handler for automatic crash detection
     */
    private fun installGlobalExceptionHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                handleCrash(exception, mapOf(
                    "crash.thread" to thread.name,
                    "crash.is_main_thread" to (thread == Thread.currentThread()).toString()
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle crash", e)
            } finally {
                // Call original handler to maintain normal crash behavior
                originalHandler?.uncaughtException(thread, exception)
            }
        }
    }
    
    /**
     * Track an error manually with Throwable
     */
    fun trackError(error: Throwable, attributes: Map<String, String>? = null) {
        if (!enabled) return
        
        scope.launch {
            try {
                val stackTrace = generateStackTrace(error)
                val crashData = createCrashPayload(error, stackTrace, attributes)
                sendCrashData(crashData)
                
                Log.d(TAG, "✅ Error tracked successfully: ${error.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to track error", e)
            }
        }
    }
    
    /**
     * Track an error manually with message and optional stack trace
     */
    fun trackError(message: String, stackTrace: String? = null, attributes: Map<String, String>? = null) {
        if (!enabled) return
        
        scope.launch {
            try {
                val finalStackTrace = stackTrace ?: generateCurrentStackTrace()
                val crashData = createCrashPayload(message, finalStackTrace, attributes)
                sendCrashData(crashData)
                
                Log.d(TAG, "✅ Error tracked successfully: $message")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to track error", e)
            }
        }
    }
    
    /**
     * Handle automatic crash detection
     */
    private fun handleCrash(exception: Throwable, additionalAttributes: Map<String, String> = emptyMap()) {
        scope.launch {
            try {
                val stackTrace = generateStackTrace(exception)
                val crashData = createCrashPayload(exception, stackTrace, additionalAttributes)
                sendCrashData(crashData)
                
                Log.d(TAG, "💥 Crash reported: ${exception.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to report crash", e)
            }
        }
    }
    
    /**
     * Create crash payload with exact structure matching Flutter SDK
     */
    private fun createCrashPayload(
        error: Throwable, 
        stackTrace: String, 
        additionalAttributes: Map<String, String>? = null
    ): Map<String, Any> {
        val errorMessage = "${error.javaClass.name}: ${error.message ?: ""}"
        val fingerprint = CrashFingerprinter.generateCrashFingerprint(error, stackTrace)
        val breadcrumbs = breadcrumbManager.getBreadcrumbsAsJson()
        
        val baseAttributes = deviceInfoCollector.getCrashAttributes()
        val crashAttributes = FlutterPayloadFactory.createCrashAttributes(
            baseAttributes = baseAttributes,
            fingerprint = fingerprint,
            breadcrumbs = breadcrumbs,
            breadcrumbCount = breadcrumbManager.getBreadcrumbCount(),
            additionalAttributes = additionalAttributes ?: emptyMap()
        )
        
        val payload = FlutterPayloadFactory.createCrashPayload(
            error = errorMessage,
            stackTrace = stackTrace,
            fingerprint = fingerprint,
            attributes = crashAttributes
        )
        
        return gson.fromJson(payload.toJson(), Map::class.java) as Map<String, Any>
    }
    
    /**
     * Create crash payload from message and stack trace
     */
    private fun createCrashPayload(
        message: String,
        stackTrace: String,
        additionalAttributes: Map<String, String>? = null
    ): Map<String, Any> {
        val fingerprint = CrashFingerprinter.generateCrashFingerprint(message, stackTrace)
        val breadcrumbs = breadcrumbManager.getBreadcrumbsAsJson()
        
        val baseAttributes = deviceInfoCollector.getCrashAttributes()
        val crashAttributes = FlutterPayloadFactory.createCrashAttributes(
            baseAttributes = baseAttributes,
            fingerprint = fingerprint,
            breadcrumbs = breadcrumbs,
            breadcrumbCount = breadcrumbManager.getBreadcrumbCount(),
            additionalAttributes = additionalAttributes ?: emptyMap()
        )
        
        val payload = FlutterPayloadFactory.createCrashPayload(
            error = message,
            stackTrace = stackTrace,
            fingerprint = fingerprint,
            attributes = crashAttributes
        )
        
        return gson.fromJson(payload.toJson(), Map::class.java) as Map<String, Any>
    }
    
    
    /**
     * Get device attributes from device info collector
     */
    private fun getDeviceAttributes(): Map<String, String> {
        return deviceInfoCollector.getCrashAttributes()
    }
    
    /**
     * Send crash data with retry mechanism
     */
    private suspend fun sendCrashData(crashData: Map<String, Any>) {
        try {
            retryManager.sendCrashWithRetry(crashData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send crash data", e)
        }
    }
    
    /**
     * Generate stack trace from throwable
     */
    private fun generateStackTrace(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        return stringWriter.toString()
    }
    
    /**
     * Generate current stack trace
     */
    private fun generateCurrentStackTrace(): String {
        val exception = Exception("Manual error tracking")
        return generateStackTrace(exception)
    }
    
    /**
     * Test crash reporting functionality
     */
    fun testCrashReporting(customMessage: String? = null) {
        val message = customMessage ?: "Test crash from EdgeTelemetry SDK"
        
        // Add test breadcrumb
        breadcrumbManager.addCustom("Test crash initiated", mapOf("test" to "true"))
        
        // Create and throw test exception
        val testException = RuntimeException(message)
        trackError(testException, mapOf("test.crash" to "true"))
        
        Log.i(TAG, "🧪 Test crash reported: $message")
    }
}
