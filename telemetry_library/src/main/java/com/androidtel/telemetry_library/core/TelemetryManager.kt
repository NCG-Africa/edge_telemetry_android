package com.androidtel.telemetry_library.core

// This is the core class responsible for collecting, batching, and sending telemetry data.

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavController
import com.google.gson.Gson
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

// The custom TelemetryManager class handles all telemetry logic according to the specification.
class TelemetryManager private constructor(
    private val context: Context,
    private val httpClient: TelemetryHttpClient,
    private val offlineStorage: OfflineBatchStorage,
    private val screenTimingTracker: ScreenTimingTracker,
    private val batchSize: Int
) : DefaultLifecycleObserver
{

    private val gson = Gson()
    private val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private var batchSendJob: Job? = null

    // Core attributes for every event
    private val deviceId: String = getOrCreateDeviceId()
    private val appInfo = collectAppInfo()
    private val deviceInfo = collectDeviceInfo()
    private var sessionId = generateDeviceId()
    private var sessionStartTime = System.currentTimeMillis()
    private var userId: String? = null
    // Add additional user profile fields
    private var userName: String? = null
    private var userEmail: String? = null
    private var userPhone: String? = null
    private var userProfileVersion: Int? = null

companion object {
    @Volatile
    private var instance: TelemetryManager? = null

    /**
     * Call this once in Application.onCreate()
     */
    fun init(application: Application): TelemetryManager {
        return instance ?: synchronized(this) {
            instance ?: TelemetryManager(
                application,
                httpClient = TelemetryHttpClient(),
                offlineStorage = OfflineBatchStorage(application.applicationContext),
                screenTimingTracker = ScreenTimingTracker(),
                batchSize = 5
            ).also { manager ->
                instance = manager
                manager.register() // lifecycle + crash handling
            }
        }
    }

    /**
     * Access after init()
     */
    fun getInstance(): TelemetryManager {
        return instance
            ?: throw IllegalStateException("TelemetryManager not initialized. Call init(application) first.")
    }
}



    private fun register() {
        // Attach lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Setup uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordCrash(throwable)
            Log.e("TelemetryManager", "Uncaught exception on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }


    // This is called when the app comes to the foreground. The session is already active.
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.i("TelemetryManager", "App moved to foreground. Continuing session ID: $sessionId")
        // When the app comes back to the foreground, try to send any stored batches.
        scope.launch { sendStoredBatches() }
    }

    // This is called when the app goes into the background. We can track the session end here.
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        val durationMs = System.currentTimeMillis() - sessionStartTime
        val attributes = mapOf(
            "session_duration_ms" to durationMs
        )
        recordEvent(eventName = "session_end", attributes = attributes)
        Log.i("TelemetryManager", "App moved to background. Session ID: $sessionId ended after $durationMs ms.")

        // Flush any remaining events to the offline storage when the app goes to the background.
        scope.launch {
            if (batchSendJob?.isActive == true) {
                batchSendJob?.cancelAndJoin()
            }
            sendBatch(true)
        }
    }

    // Records a new metric event with the specified details.
    fun recordMetric(
        metricName: String,
        value: Double,
        attributes: Map<String, Any> = emptyMap()
    ) {
        val event = buildAttributes(attributes)?.let {
            TelemetryEvent(
                type = "metric",
                metricName = metricName,
                value = value,
                timestamp = dateFormat.format(Date()),
                attributes = it
            )
        }
        eventQueue.add(event)
        maybeSendBatch()
    }

    // Records a new general event with the specified details.
    fun recordEvent(
        eventName: String,
        attributes: Map<String, Any> = emptyMap()
    ) {
        val event = buildAttributes(attributes)?.let {
            TelemetryEvent(
                type = "event",
                eventName = eventName,
                timestamp = dateFormat.format(Date()),
                attributes = it
            )
        }
        eventQueue.add(event)
        maybeSendBatch()
    }

    // --- Network Request Tracking ---
    fun recordNetworkRequest(
        url: String,
        method: String,
        statusCode: Int,
        durationMs: Long,
        requestBodySize: Long = 0,
        responseBodySize: Long = 0,
        error: String? = null,
        attributes: Map<String, Any> = emptyMap()
    ) {
        val networkAttributes = mapOf(
            "url" to url,
            "method" to method,
            "status_code" to statusCode,
            "duration_ms" to durationMs,
            "request_body_size" to requestBodySize,
            "response_body_size" to responseBodySize,
            "error" to (error ?: "none")
        )
        val combinedAttributes = attributes + networkAttributes
        recordEvent(eventName = "network.request", attributes = combinedAttributes)
    }

    // --- Crash and Error Reporting ---
    fun recordCrash(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val attributes = mapOf(
            "stacktrace" to sw.toString(),
            "message" to (throwable.message ?: "No message"),
            "cause" to (throwable.cause?.javaClass?.simpleName ?: "unknown"),
            "exception_type" to throwable.javaClass.simpleName
        )
        recordEvent(eventName = "app.crash", attributes = attributes)
        scope.launch { sendBatch(forceSend = true, flushOffline = false) } // Send crash report immediately
    }

    fun recordError(throwable: Throwable, attributes: Map<String, Any> = emptyMap()) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val errorAttributes = mapOf(
            "stacktrace" to sw.toString(),
            "message" to (throwable.message ?: "No message"),
            "cause" to (throwable.cause?.javaClass?.simpleName ?: "unknown"),
            "exception_type" to throwable.javaClass.simpleName
        )
        val combinedAttributes = attributes + errorAttributes
        recordEvent(eventName = "app.error", attributes = combinedAttributes)
    }

    @Composable
    fun trackComposeScreens(navController: NavController) {
        TrackComposeScreen(navController, this)
    }

    fun trackActivities() {
        TelemetryActivityLifecycleObserver( this)
    }



    // --- Screen Navigation Tracking ---
    fun recordScreenView(screenName: String) {
        val attributes = mapOf(
            "screen_name" to screenName
        )
        recordEvent(eventName = "screen_view", attributes = attributes)
    }


    // --- Screen Navigation Tracking for Jetpack Compose ---
    fun recordComposeScreenView(screenRoute: String) {
        screenTimingTracker.startScreen(screenRoute)
        val attributes = mapOf(
            "screen_route" to screenRoute
        )
        recordEvent(eventName = "screen_view_compose", attributes = attributes)
    }

    fun recordComposeScreenEnd(screenRoute: String) {
        val durationMs = screenTimingTracker.endScreen(screenRoute)
        if (durationMs != null) {
            val attributes = mapOf(
                "screen_route" to screenRoute,
                "duration_ms" to durationMs
            )
            recordEvent(eventName = "screen_end_compose", attributes = attributes)
        }
    }

    // Sets the user ID for all subsequent events in the session.
    fun setUserId(id: String) {
        this.userId = id
    }

    // Expected format: user_1704067200123_abcd1234
    fun generateUserId(): String {
        val timestamp = System.currentTimeMillis()
        val randomPart = generateRandomString(8)
        return "user_${timestamp}_$randomPart"
    }


    fun generateRandomString(length: Int): String {
        val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        return (1..length)
            .map { charPool[random.nextInt(charPool.size)] }
            .joinToString("")
    }

    // Expected format: device_1704067200000_a8b9c2d1_android
    private fun generateDeviceId(): String {
        val timestamp = System.currentTimeMillis()
        val randomPart = generateRandomString(8) // 8 chars, alphanumeric lowercase
        val platform = "android" // Must be lowercase

        return "device_${timestamp}_${randomPart}_$platform"
    }

    // A new method to set additional user profile information.
    fun setUserProfile(name: String, email: String, phone: String, profileVersion: Int) {
        this.userName = name
        this.userEmail = email
        this.userPhone = phone
        this.userProfileVersion = profileVersion
    }

    // Builds the full set of attributes for an event by combining core attributes and event-specific ones.
    private fun buildAttributes(eventAttributes: Map<String, Any>): EventAttributes? {
        val sessionInfo = getSessionInfo()
        return appInfo?.let {
            EventAttributes(
                app = it,
                device = deviceInfo,
                user = UserInfo(
                    userId = userId,
                    name = userName,
                    email = userEmail,
                    phone = userPhone,
                    profileVersion = userProfileVersion
                ),
                session = sessionInfo,
                customAttributes = eventAttributes
            )
        }
    }

    // Checks the queue size and sends a batch if the threshold is met.
    private fun maybeSendBatch() {
        if (eventQueue.size >= batchSize) {
            batchSendJob = scope.launch { sendBatch() }
        }
    }

    // This method sends the buffered events as a single JSON batch.
    // It now uses the TelemetryHttpClient and OfflineBatchStorage.
    private suspend fun sendBatch(forceSend: Boolean = false, flushOffline: Boolean = true) {
        if (!forceSend && eventQueue.size < batchSize) {
            return
        }

        val eventsToSend = mutableListOf<TelemetryEvent>()
        repeat(eventQueue.size) { eventsToSend.add(eventQueue.remove()) }

        if (eventsToSend.isEmpty()) return

        val batch = TelemetryBatch(
            batchSize = eventsToSend.size,
            timestamp = dateFormat.format(Date()),
            events = eventsToSend
        )

        Log.i("TelemetryManager", "Attempting to send a batch of ${batch.batchSize} events.")
        val result = httpClient.sendBatch(batch)

        if (result.isSuccess) {
            Log.i("TelemetryManager", "Successfully sent batch.")
        } else {
            Log.e("TelemetryManager", "Failed to send batch. Storing offline.")
            if (flushOffline) {
                offlineStorage.storeBatch(batch)
            }
        }
    }

    // Method to send any batches stored in the offline queue.
    private suspend fun sendStoredBatches() {
        Log.i("TelemetryManager", "Checking for stored batches to send.")
        val storedBatches = offlineStorage.getStoredBatches()
        if (storedBatches.isNotEmpty()) {
            Log.i("TelemetryManager", "Found ${storedBatches.size} stored batches. Attempting to send.")
            storedBatches.forEach { batch ->
                val result = httpClient.sendBatch(batch)
                if (result.isSuccess) {
                    Log.i("TelemetryManager", "Successfully re-sent stored batch.")
                    offlineStorage.removeBatch(batch.id)
                } else {
                    Log.e("TelemetryManager", "Failed to re-send stored batch. Will try again later.")
                }
            }
        }
    }

    // Generates a UUID and stores it persistently in SharedPreferences.
    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device.id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device.id", deviceId).apply()
        }
        return deviceId
    }

    // Gathers and formats app-related information.
    private fun collectAppInfo(): AppInfo? {
        val packageManager = context.packageManager
        val packageName = context.packageName
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return packageInfo.versionName?.let {
            AppInfo(
                appName = context.getString(context.applicationInfo.labelRes),
                appVersion = it,
                appBuildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toString()
                } else {
                    packageInfo.versionCode.toString()
                },
                appPackageName = packageName
            )
        }
    }

    // Gathers and formats device-related information.
    private fun collectDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceId = deviceId,
            platform = "android",
            platformVersion = Build.VERSION.RELEASE,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            androidSdk = Build.VERSION.SDK_INT.toString(),
            androidRelease = Build.VERSION.RELEASE,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            product = Build.PRODUCT
        )
    }

    // Tracks session information. Note that this is a simplified example.
    private fun getSessionInfo(): SessionInfo {
        return SessionInfo(
            sessionId = sessionId,
            startTime = dateFormat.format(Date(sessionStartTime))
        )
    }
}

