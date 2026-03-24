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
    private val telemetryManager: TelemetryManager?,
    private val breadcrumbManager: BreadcrumbManager,
    private val idGenerator: IdGenerator,
    private val apiKey: String,
    private val telemetryEndpoint: String,
    private val enabled: Boolean = true,
    private val debugMode: Boolean = false
) {
    
    companion object {
        private const val TAG = "CrashReporter"
    }
    
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val retryManager = CrashRetryManager(context, apiKey, telemetryEndpoint, debugMode)
    private val deviceInfoCollector = DeviceInfoCollector(context, idGenerator)
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private val handlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
    
    // Product context and user action tracking
    private var currentProductId: String? = null
    private var lastUserAction: String? = null
    private var currentLocation: String? = null
    
    init {
        if (enabled) {
            installGlobalExceptionHandler()
        }
    }
    
    /**
     * Install global exception handler for automatic crash detection
     */
    fun installGlobalExceptionHandler() {
        if (handlerInstalled.getAndSet(true)) {
            Log.d(TAG, "Global exception handler already installed — skipping")
            return
        }
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
                val crashData = createCrashBatchEnvelope(error, attributes)
                sendCrashData(crashData)
                
                Log.d(TAG, "✅ Error tracked successfully: ${error.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to track error", e)
            }
        }
    }
    
    /**
     * Track an error with enhanced context (v2.0.0)
     */
    fun trackError(
        error: Throwable,
        errorCode: String? = null,
        productId: String? = null,
        userAction: String? = null,
        attributes: Map<String, String>? = null
    ) {
        if (!enabled) return
        
        scope.launch {
            try {
                val crashData = createCrashBatchEnvelope(
                    error = error,
                    errorCode = errorCode,
                    productId = productId ?: currentProductId,
                    userAction = userAction ?: lastUserAction,
                    additionalAttributes = attributes
                )
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
                val crashData = createCrashBatchEnvelope(message, finalStackTrace, attributes)
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
                val crashData = createCrashBatchEnvelope(exception, additionalAttributes)
                sendCrashData(crashData)
                
                Log.d(TAG, "💥 Crash reported: ${exception.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to report crash", e)
            }
        }
    }
    
    /**
     * Create crash batch envelope with new v2.0.0 structure
     */
    private fun createCrashBatchEnvelope(
        error: Throwable,
        additionalAttributes: Map<String, String>? = null,
        errorCode: String? = null,
        productId: String? = null,
        userAction: String? = null
    ): Map<String, Any> {
        val baseAttributes = deviceInfoCollector.getCrashAttributes()
        val breadcrumbs = breadcrumbManager.getBreadcrumbsAsJson()
        
        // Add breadcrumbs to base attributes
        val enrichedAttributes = baseAttributes.toMutableMap()
        enrichedAttributes["breadcrumbs"] = breadcrumbs
        
        // Add additional attributes if provided
        additionalAttributes?.let { enrichedAttributes.putAll(it) }
        
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = error,
            deviceId = idGenerator.getDeviceId(),
            baseAttributes = enrichedAttributes,
            location = currentLocation,
            productId = productId ?: currentProductId,
            userAction = userAction ?: lastUserAction,
            errorCode = errorCode,
            additionalAttributes = additionalAttributes ?: emptyMap()
        )
        
        return gson.fromJson(envelope.toJson(), Map::class.java) as Map<String, Any>
    }
    
    /**
     * Create crash batch envelope from message and stack trace
     */
    private fun createCrashBatchEnvelope(
        message: String,
        stackTrace: String,
        additionalAttributes: Map<String, String>? = null
    ): Map<String, Any> {
        val baseAttributes = deviceInfoCollector.getCrashAttributes()
        val breadcrumbs = breadcrumbManager.getBreadcrumbsAsJson()
        
        // Add breadcrumbs to base attributes
        val enrichedAttributes = baseAttributes.toMutableMap()
        enrichedAttributes["breadcrumbs"] = breadcrumbs
        
        // Add additional attributes if provided
        additionalAttributes?.let { enrichedAttributes.putAll(it) }
        
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            message = message,
            stackTrace = stackTrace,
            deviceId = idGenerator.getDeviceId(),
            baseAttributes = enrichedAttributes,
            location = currentLocation,
            productId = currentProductId,
            userAction = lastUserAction,
            additionalAttributes = additionalAttributes ?: emptyMap()
        )
        
        return gson.fromJson(envelope.toJson(), Map::class.java) as Map<String, Any>
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
     * Set product context for crash reporting
     */
    fun setProductContext(productId: String) {
        this.currentProductId = productId.take(255)
        Log.d(TAG, "Product context set: $productId")
    }
    
    /**
     * Set last user action for crash context
     */
    fun setLastUserAction(action: String) {
        this.lastUserAction = action.take(500)
        Log.d(TAG, "User action set: $action")
    }
    
    /**
     * Set current location for crash reporting
     */
    fun setLocation(location: String?) {
        this.currentLocation = location
        Log.d(TAG, "Location set: $location")
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
