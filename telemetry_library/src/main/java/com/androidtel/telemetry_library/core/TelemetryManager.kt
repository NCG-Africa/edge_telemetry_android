package com.androidtel.telemetry_library.core

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavController
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.UserInfo
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
) : DefaultLifecycleObserver {

    private val gson = Gson()
    private val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val scope = CoroutineScope(Dispatchers.IO)
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
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

    // Session tracking state
    private var eventCount: Int = 0
    private var metricCount: Int = 0
    private var totalSessions: Int = 0
    private val visitedScreens: MutableSet<String> = mutableSetOf()


    // file name for persisted fatal crash batch
    private val persistedCrashFileName = "telemetry_pending_crash.json"
    private val persistedCrashFile: File
        get() = File(context.cacheDir, persistedCrashFileName)

    companion object {
        @Volatile
        private var instance: TelemetryManager? = null

        /**
         * Call this once in Application.onCreate()
         */
        fun initialize(
            application: Application,
            batchSize: Int = 5,
            endpoint: String = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
            debugMode: Boolean = false
        ): TelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager(
                    application,
                    httpClient = TelemetryHttpClient(
                        telemetryUrl = endpoint,
                        debugMode = debugMode
                    ),
                    offlineStorage = OfflineBatchStorage(application.applicationContext),
                    screenTimingTracker = ScreenTimingTracker(),
                    batchSize = batchSize,
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

        fun instance(): TelemetryManager {
            return instance
                ?: throw IllegalStateException("TelemetryManager not initialized. Call init(application) first.")
        }
    }


    private fun register() {
        // Attach lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Attempt to send any persisted crash batch(s) on start
        scope.launch {
            try {
                sendPersistedCrashIfAny()
            } catch (e: Exception) {
                Log.e(
                    "TelemetryManager",
                    "Error while sending persisted crash on init: ${e.localizedMessage}",
                    e
                )
            }
        }

        scope.launch {
            try {
                val prefs = context.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
                totalSessions = prefs.getInt("total_sessions", 0) + 1
                prefs.edit().putInt("total_sessions", totalSessions).apply()

            } catch (e: Exception) {
                Log.e(
                    "TelemetryManager",
                    "Errror storing events sessions: ${e.localizedMessage}",
                    e
                )
            }
        }
        // Setup uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Handle uncaught exception synchronously-ish: persist, attempt short send, then delegate to default handler
            handleUncaughtException(thread, throwable, defaultHandler)
        }
    }

    private fun handleUncaughtException(
        thread: Thread,
        throwable: Throwable,
        defaultHandler: Thread.UncaughtExceptionHandler?
    ) {
        try {
            // Build the crash attributes (stringified stacktrace etc.)
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val attributes = mapOf(
                "stacktrace" to sw.toString(),
                "message" to (throwable.message ?: "No message"),
                "cause" to (throwable.cause?.javaClass?.simpleName ?: "unknown"),
                "exception_type" to throwable.javaClass.simpleName
            )

            // Try to create a TelemetryEvent with full EventAttributes (includes app/device/session/user)
            val crashEvent = buildAttributes(attributes)?.let {
                TelemetryEvent(
                    type = "event",
                    eventName = "app.crash",
                    timestamp = dateFormat.format(Date()),
                    attributes = it
                )
            }

            // If we could build a TelemetryEvent, persist it as a TelemetryBatch synchronously
            if (crashEvent != null) {
                val batch = TelemetryBatch(
                    batchSize = 1,
                    timestamp = dateFormat.format(Date()),
                    events = listOf(crashEvent)
                )

                // Persist to disk synchronously — survives process death
                persistBatchSync(batch)

                // Best-effort synchronous send with short timeout (2 seconds). If success, delete file.
                try {
                    val resultWasSuccess = runBlocking(Dispatchers.IO) {
                        // try for up to 2 seconds
                        withTimeoutOrNull(2000) {
                            val result = httpClient.sendBatch(batch)
                            result.isSuccess
                        }
                    } == true

                    if (resultWasSuccess) {
                        // remove persisted file because it was delivered
                        deletePersistedBatch()
                    } else {
                        // if we couldn't send now, schedule to offline storage after process restarts (persisted file remains)
                        Log.w(
                            "TelemetryManager",
                            "Immediate crash send did not succeed — persisted for next launch."
                        )
                    }
                } catch (e: Exception) {
                    Log.w(
                        "TelemetryManager",
                        "Exception while attempting immediate crash send: ${e.localizedMessage}"
                    )
                    // leave persisted file for next launch
                }
            } else {
                // If buildAttributes failed for some reason, persist a minimal JSON so it can be inspected & uploaded later.
                persistRawCrashSync(
                    mapOf(
                        "timestamp" to dateFormat.format(Date()),
                        "message" to (throwable.message ?: ""),
                        "stacktrace" to sw.toString()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(
                "TelemetryManager",
                "Failed while handling uncaught exception: ${e.localizedMessage}",
                e
            )
        } finally {
            // Let the original handler proceed (will terminate the process)
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
        Log.i(
            "TelemetryManager",
            "App moved to background. Session ID: $sessionId ended after $durationMs ms."
        )

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
        metricCount++
        val event = buildAttributes(attributes)?.let {
            TelemetryEvent(
                type = "metric|event",
                metricName = metricName,
                value = value,
                timestamp = dateFormat.format(Date()),
                attributes = it
            )
        }
        event?.let { eventQueue.add(it) }
        maybeSendBatch()
    }

    // Records a new general event with the specified details.
    fun recordEvent(
        eventName: String,
        attributes: Map<String, Any> = emptyMap()
    ) {
        eventCount++
        val event = buildAttributes(attributes)?.let {
            TelemetryEvent(
                type = "event",
                eventName = eventName,
                timestamp = dateFormat.format(Date()),
                attributes = it
            )
        }
        event?.let { eventQueue.add(it) }
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

        // Build the TelemetryEvent and queue it (normal flow)
        val event = buildAttributes(attributes)?.let {
            TelemetryEvent(
                type = "event",
                eventName = "app.crash",
                timestamp = dateFormat.format(Date()),
                attributes = it
            )
        }
        event?.let {
            eventQueue.add(it)
            // Persist crash immediately so we don't lose it if a subsequent fatal crash happens
            val batch = TelemetryBatch(
                batchSize = 1,
                timestamp = dateFormat.format(Date()),
                events = listOf(it)
            )
            persistBatchSync(batch)
        }

        // Try to send asynchronously (best-effort). If the process terminates quickly, the persisted file will ensure delivery on next launch.
        scope.launch { sendBatch(forceSend = true, flushOffline = false) }
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
        TelemetryActivityLifecycleObserver(this)
    }

    // --- Screen Navigation Tracking ---
    fun recordScreenView(screenName: String) {
        visitedScreens.add(screenName)
        val attributes = mapOf(
            "screen_name" to screenName
        )
        recordEvent(eventName = "screen_view", attributes = attributes)
    }


    // --- Screen Navigation Tracking for Jetpack Compose ---
    fun recordComposeScreenView(screenRoute: String) {
        screenTimingTracker.startScreen(screenRoute)
        recordEvent(
            eventName = "navigation.route_change",
            attributes = mapOf(
                "navigation.to" to screenRoute,
                "navigation.method" to "entered",
                "navigation.type" to "compose_route",
                "screen.type" to "compose",
                "navigation.timestamp" to System.currentTimeMillis().toString()
            )
        )
    }

    fun recordComposeScreenEnd(screenRoute: String) {
        val durationMs = screenTimingTracker.endScreen(screenRoute)
        if (durationMs != null) {
            recordMetric(
                metricName = "performance.screen_duration",
                value = durationMs.toDouble(),
                attributes = mapOf(
                    "screen.name" to screenRoute,
                    "navigation.exit_method" to "disposed",
                    "metric.unit" to "milliseconds"
                )
            )
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
        val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
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
        repeat(eventQueue.size) {
            val ev = eventQueue.poll()
            if (ev != null) eventsToSend.add(ev)
        }

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
            Log.i(
                "TelemetryManager",
                "Found ${storedBatches.size} stored batches. Attempting to send."
            )
            storedBatches.forEach { batch ->
                val result = httpClient.sendBatch(batch)
                if (result.isSuccess) {
                    Log.i("TelemetryManager", "Successfully re-sent stored batch.")
                    offlineStorage.removeBatch(batch.id)
                } else {
                    Log.e(
                        "TelemetryManager",
                        "Failed to re-send stored batch. Will try again later."
                    )
                }
            }
        }
    }

    // Persist a TelemetryBatch to a small JSON file synchronously so it survives process death.
    private fun persistBatchSync(batch: TelemetryBatch) {
        try {
            val json = gson.toJson(batch)
            persistedCrashFile.parentFile?.mkdirs()
            persistedCrashFile.writeText(json)
            Log.i("TelemetryManager", "Persisted crash batch to ${persistedCrashFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Failed to persist crash batch: ${e.localizedMessage}", e)
        }
    }

    // Persist a raw minimal crash map (fallback)
    private fun persistRawCrashSync(raw: Map<String, Any>) {
        try {
            val json = gson.toJson(raw)
            persistedCrashFile.parentFile?.mkdirs()
            persistedCrashFile.writeText(json)
            Log.i("TelemetryManager", "Persisted raw crash to ${persistedCrashFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Failed to persist raw crash: ${e.localizedMessage}", e)
        }
    }

    // Read persisted crash batch (or null if invalid/missing)
    private fun readPersistedBatch(): TelemetryBatch? {
        return try {
            if (!persistedCrashFile.exists()) return null
            val text = persistedCrashFile.readText()
            gson.fromJson(text, TelemetryBatch::class.java)
        } catch (e: Exception) {
            Log.e(
                "TelemetryManager",
                "Failed to read persisted crash batch: ${e.localizedMessage}",
                e
            )
            null
        }
    }

    private fun deletePersistedBatch() {
        try {
            if (persistedCrashFile.exists()) {
                persistedCrashFile.delete()
                Log.i("TelemetryManager", "Deleted persisted crash file.")
            }
        } catch (e: Exception) {
            Log.e(
                "TelemetryManager",
                "Failed to delete persisted crash file: ${e.localizedMessage}",
                e
            )
        }
    }

    // Try to send persisted crash if exists (called on init)
    private suspend fun sendPersistedCrashIfAny() {
        val batch = readPersistedBatch()
        if (batch == null) {
            return
        }

        Log.i("TelemetryManager", "Found persisted crash batch; attempting to send.")
        try {
            val result = httpClient.sendBatch(batch)
            if (result.isSuccess) {
                Log.i("TelemetryManager", "Successfully sent persisted crash batch.")
                deletePersistedBatch()
            } else {
                Log.e(
                    "TelemetryManager",
                    "Failed to send persisted crash batch; moving to offline storage."
                )
                offlineStorage.storeBatch(batch)
                deletePersistedBatch()
            }
        } catch (e: Exception) {
            Log.e(
                "TelemetryManager",
                "Error sending persisted crash batch: ${e.localizedMessage}",
                e
            )
            // leave file for next attempt
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
    /*    private fun getSessionInfo(): SessionInfo {
            return SessionInfo(
                sessionId = sessionId,
                startTime = dateFormat.format(Date(sessionStartTime))
            )
        }*/

    // Example: Collects session information
    private fun getSessionInfo(): SessionInfo {
        val now = System.currentTimeMillis()
        val duration = now - sessionStartTime

        return SessionInfo(
            sessionId = sessionId,
            startTime = dateFormat.format(Date(sessionStartTime)),
            durationMs = duration,
            eventCount = eventCount,
            metricCount = metricCount,
            screenCount = visitedScreens.size,
            visitedScreens = visitedScreens.joinToString(","),
            isFirstSession = totalSessions == 1,
            totalSessions = totalSessions,
            networkType = getNetworkType(context)
        )
    }


    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo ?: return "unknown"

        return when (activeNetwork.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            else -> "other"
        }
    }


}


