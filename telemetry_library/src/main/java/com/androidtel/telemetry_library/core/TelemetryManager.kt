package com.androidtel.telemetry_library.core

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavController
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.crash.CrashReporter
import com.androidtel.telemetry_library.core.device.DeviceInfoCollector
import com.androidtel.telemetry_library.core.events.JsonEventTracker
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.location.IpLocationProvider
import com.androidtel.telemetry_library.core.location.LocationProvider
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.UserInfo
import com.androidtel.telemetry_library.core.session.SessionManager
import com.androidtel.telemetry_library.core.user.UserProfileManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
    private val batchSize: Int,
    private val apiKey: String,
    private val telemetryEndpoint: String,
    private val debugMode: Boolean,
    private val config: TelemetryConfig,
) : DefaultLifecycleObserver {
    
    // Public getter for context
    val applicationContext: Context get() = context

    private val gson = Gson()
    private val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val scope = CoroutineScope(Dispatchers.IO)
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private var batchSendJob: Job? = null

    // ID Generator - single source of truth for all IDs
    private lateinit var idGenerator: IdGenerator

    // Core attributes for every event
    private lateinit var deviceId: String
    private val appInfo = collectAppInfo()
    private lateinit var deviceInfo: DeviceInfo
    private lateinit var sessionId: String
    private var sessionStartTime = System.currentTimeMillis()
    private lateinit var userId: String
    
    // Device capabilities for runtime feature detection
    private lateinit var deviceCapabilities: DeviceCapabilities
    private lateinit var networkCapabilityDetector: NetworkCapabilityDetector
    private lateinit var memoryCapabilityTracker: MemoryCapabilityTracker

    // Flutter-compatible components (initialized based on configuration)
    private var flutterIdGenerator: IdGenerator? = null
    private var breadcrumbManager: BreadcrumbManager? = null
    private var userProfileManager: UserProfileManager? = null
    private var enhancedSessionManager: SessionManager? = null
    private var crashReporter: CrashReporter? = null
    private var deviceInfoCollector: DeviceInfoCollector? = null
    private var jsonEventTracker: JsonEventTracker? = null

    // Location tracking components
    private var locationProvider: LocationProvider? = null
    private var currentLocation: String? = null

    // Configuration flags
    private var crashReportingEnabled = false
    private var userProfilesEnabled = false
    private var sessionTrackingEnabled = false
    private var locationTrackingEnabled = false
    private var globalAttributes = mutableMapOf<String, String>()

    // Legacy user profile fields (for backward compatibility)
    private var userName: String? = null
    private var userEmail: String? = null
    private var userPhone: String? = null
    private var userProfileVersion: Int? = null

    // Session tracking state
    private var eventCount: Int = 0
    private var metricCount: Int = 0
    private var totalSessions: Int = 0
    private val visitedScreens: MutableSet<String> = mutableSetOf()
    
    // ID validation state
    @Volatile
    private var idsInitialized: Boolean = false
    
    // Helper methods to check feature flags
    internal fun isMemoryTrackingEnabled(): Boolean = config.enableMemoryTracking
    internal fun isStorageTrackingEnabled(): Boolean = config.enableStorageTracking
    internal fun isFrameTrackingEnabled(): Boolean = config.enableFrameTracking
    internal fun isLegacyScreenEventsEnabled(): Boolean = config.enableLegacyScreenEvents
    internal fun isUserInteractionEventsEnabled(): Boolean = config.enableUserInteractionEvents


    // file name for persisted fatal crash batch
    private val persistedCrashFileName = "telemetry_pending_crash.json"
    private val persistedCrashFile: File
        get() = File(context.cacheDir, persistedCrashFileName)

    companion object {
        @Volatile
        private var instance: TelemetryManager? = null

        /**
         * Initialize SDK with TelemetryConfig object
         * 
         * Recommended approach for cleaner configuration management.
         * 
         * Example:
         * ```
         * val config = TelemetryConfig.builder(application, "edge_your_api_key")
         *     .debugMode(true)
         *     .batchSize(50)
         *     .build()
         * 
         * TelemetryManager.initialize(config)
         * ```
         */
        fun initialize(config: TelemetryConfig): TelemetryManager {
            // Validate API key before any initialization
            require(config.apiKey.isNotBlank()) { "API key cannot be blank" }
            require(config.apiKey.startsWith("edge_")) { "API key is invalid" }
            
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager(
                    config.application,
                    httpClient = TelemetryHttpClient(
                        telemetryUrl = config.endpoint,
                        apiKey = config.apiKey,
                        debugMode = config.debugMode
                    ),
                    offlineStorage = OfflineBatchStorage(config.application.applicationContext),
                    screenTimingTracker = ScreenTimingTracker(),
                    batchSize = config.batchSize,
                    apiKey = config.apiKey,
                    telemetryEndpoint = config.endpoint,
                    debugMode = config.debugMode,
                    config = config,
                ).also { manager ->
                    instance = manager
                    manager.initializeIdGenerator()
                    manager.initializeCapabilities()
                    manager.initializeUserId()
                    manager.initializeFlutterComponents(
                        config.enableCrashReporting,
                        config.enableUserProfiles,
                        config.enableSessionTracking,
                        config.globalAttributes,
                        config.enableLocationTracking,
                        config.locationApiEndpoint,
                        config.locationCacheDuration,
                        config.locationFallbackToIp
                    )
                    manager.register()
                }
            }
        }

        /**
         * Initialize SDK with individual parameters (legacy approach)
         * 
         * Call this once in Application.onCreate()
         */
        fun initialize(
            application: Application,
            apiKey: String,
            batchSize: Int = 30,
            endpoint: String = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
            debugMode: Boolean = false,
            enableCrashReporting: Boolean = true,
            enableUserProfiles: Boolean = true,
            enableSessionTracking: Boolean = true,
            globalAttributes: Map<String, String> = emptyMap(),
            enableLocationTracking: Boolean = true,
            locationApiEndpoint: String = "https://ipinfo.io/json",
            locationCacheDuration: Long = 3600000,
            locationFallbackToIp: Boolean = true
        ): TelemetryManager {
            // Create config object from parameters
            val config = TelemetryConfig(
                application = application,
                apiKey = apiKey,
                batchSize = batchSize,
                endpoint = endpoint,
                debugMode = debugMode,
                enableCrashReporting = enableCrashReporting,
                enableUserProfiles = enableUserProfiles,
                enableSessionTracking = enableSessionTracking,
                globalAttributes = globalAttributes,
                enableLocationTracking = enableLocationTracking,
                locationApiEndpoint = locationApiEndpoint,
                locationCacheDuration = locationCacheDuration,
                locationFallbackToIp = locationFallbackToIp
            )
            
            return initialize(config)
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
        
        /**
         * Creates a TelemetryInterceptor configured to avoid tracking SDK's own requests
         * Use this method to get a properly configured interceptor for your OkHttpClient
         */
        fun createNetworkInterceptor(): TelemetryInterceptor {
            val manager = getInstance()
            return TelemetryInterceptor(
                telemetryManager = manager,
                telemetryEndpoint = manager.telemetryEndpoint
            )
        }
        
        /**
         * Reset singleton instance for testing purposes only.
         * This method should NEVER be called in production code.
         */
        @JvmStatic
        internal fun resetForTesting() {
            instance = null
        }
    }

    /**
     * Initialize IdGenerator before any ID is accessed
     */
    private fun initializeIdGenerator() {
        idGenerator = IdGenerator()
        idGenerator.initialize(context)
        
        // Initialize core IDs
        deviceId = idGenerator.getOrGenerateDeviceId()
        sessionId = idGenerator.generateSessionId()
        deviceInfo = collectDeviceInfo()
        
        Log.i("TelemetryManager", "IdGenerator initialized - Device ID: $deviceId, Session ID: $sessionId")
    }

    /**
     * Initializes the user ID automatically during SDK setup.
     * Creates a new user ID if none exists, or loads existing one from SharedPreferences.
     * This ensures permanent user identity across app lifecycle with zero developer intervention.
     * CRITICAL: userId must NEVER be null or empty - uses fallback if generation fails.
     */
    private fun initializeUserId() {
        try {
            userId = idGenerator.getUserId()
            Log.i("TelemetryManager", "Loaded/generated user ID: $userId")
            
            // Mark IDs as initialized only if both device ID and user ID are valid
            if (::deviceId.isInitialized && deviceId.isNotBlank() && 
                ::userId.isInitialized && userId.isNotBlank() &&
                !deviceId.startsWith("user_emergency_") && 
                !userId.startsWith("user_emergency_")) {
                idsInitialized = true
                Log.i("TelemetryManager", "IDs successfully initialized and validated")
            } else {
                Log.w("TelemetryManager", "IDs initialized but validation failed")
            }
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Failed to initialize user ID: ${e.localizedMessage}", e)
            userId = "user_emergency_${System.currentTimeMillis()}"
            Log.w("TelemetryManager", "Using emergency fallback user ID: $userId")
            idsInitialized = false
        }
        
        // Final safety check - ensure userId is initialized
        if (!::userId.isInitialized || userId.isBlank()) {
            Log.e("TelemetryManager", "CRITICAL ERROR: userId not properly initialized. Using emergency fallback.")
            userId = "user_emergency_${System.currentTimeMillis()}"
            idsInitialized = false
        }
    }

    /**
     * Initialize Flutter-compatible components based on configuration
     */
    private fun initializeFlutterComponents(
        enableCrashReporting: Boolean,
        enableUserProfiles: Boolean,
        enableSessionTracking: Boolean,
        globalAttributes: Map<String, String>,
        enableLocationTracking: Boolean,
        locationApiEndpoint: String,
        locationCacheDuration: Long,
        locationFallbackToIp: Boolean
    ) {
        try {
            // Set configuration flags
            this.crashReportingEnabled = enableCrashReporting
            this.userProfilesEnabled = enableUserProfiles
            this.sessionTrackingEnabled = enableSessionTracking
            this.locationTrackingEnabled = enableLocationTracking
            this.globalAttributes.putAll(globalAttributes)

            // Initialize ID generator (always enabled for Flutter compatibility)
            flutterIdGenerator = IdGenerator().apply { initialize(context) }

            // Initialize device info collector (always enabled)
            deviceInfoCollector = DeviceInfoCollector(context, flutterIdGenerator!!)

            // Initialize breadcrumb manager (always enabled for crash reporting)
            breadcrumbManager = BreadcrumbManager()

            // Initialize user profile manager if enabled
            if (enableUserProfiles) {
                userProfileManager = UserProfileManager(context, flutterIdGenerator!!)
            }

            // Initialize enhanced session manager if enabled
            if (enableSessionTracking) {
                enhancedSessionManager = SessionManager(flutterIdGenerator!!)
            }

            // Initialize crash reporter if enabled
            if (enableCrashReporting) {
                crashReporter = CrashReporter(
                    context = context,
                    telemetryManager = this,
                    breadcrumbManager = breadcrumbManager!!,
                    idGenerator = flutterIdGenerator!!,
                    apiKey = apiKey,
                    telemetryEndpoint = telemetryEndpoint,
                    enabled = true,
                    debugMode = debugMode
                )
            }

            // Initialize JSON event tracker (always enabled)
            jsonEventTracker = JsonEventTracker(
                telemetryManager = this,
                sessionManager = enhancedSessionManager ?: createDummySessionManager(),
                userProfileManager = userProfileManager ?: createDummyUserProfileManager(),
                breadcrumbManager = breadcrumbManager!!,
                idGenerator = flutterIdGenerator!!,
                batchSize = batchSize
            )

            // Initialize location provider if enabled
            if (enableLocationTracking) {
                locationProvider = IpLocationProvider(
                    httpClient = httpClient.getOkHttpClient(),
                    apiEndpoint = locationApiEndpoint,
                    cacheDuration = locationCacheDuration,
                    fallbackToIp = locationFallbackToIp
                )
                
                // Fetch location asynchronously (don't block initialization)
                scope.launch {
                    try {
                        currentLocation = locationProvider?.getLocation()
                        Log.d("TelemetryManager", "Location initialized: $currentLocation")
                        
                        // Update crash reporter with location
                        crashReporter?.setLocation(currentLocation)
                    } catch (e: Exception) {
                        Log.w("TelemetryManager", "Failed to initialize location: ${e.message}")
                    }
                }
            }

            Log.i("TelemetryManager", "Flutter components initialized - Crash: $enableCrashReporting, Users: $enableUserProfiles, Sessions: $enableSessionTracking, Location: $enableLocationTracking")
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Failed to initialize Flutter components", e)
        }
    }

    private fun createDummySessionManager(): SessionManager {
        return SessionManager(flutterIdGenerator!!)
    }

    private fun createDummyUserProfileManager(): UserProfileManager {
        return UserProfileManager(context, flutterIdGenerator!!)
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

        trackActivities()
        trackMemoryUsage()
    }

    private fun handleUncaughtException(
        thread: Thread,
        throwable: Throwable,
        defaultHandler: Thread.UncaughtExceptionHandler?
    ) {
        val startTime = System.currentTimeMillis()
        try {
            // Build the crash attributes (stringified stacktrace etc.)
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            
            // Extract breadcrumbs if available
            val breadcrumbs = breadcrumbManager?.getBreadcrumbsAsJson() ?: "[]"
            val breadcrumbCount = breadcrumbManager?.getBreadcrumbCount() ?: 0
            
            val attributes = mapOf(
                "error.message" to "${throwable.javaClass.name}: ${throwable.message ?: ""}".take(1000),
                "error.stack_trace" to stackTrace.take(2000),
                "error.exception_type" to throwable.javaClass.simpleName.take(255),
                "error.context" to extractErrorContext(stackTrace).take(500),
                "error.cause" to (throwable.cause?.message ?: "unknown").take(255),
                "error.severity_level" to "critical",
                "error.is_fatal" to true,
                "error.breadcrumbs" to breadcrumbs.take(800),
                "error.breadcrumb_count" to breadcrumbCount,
                "crash.thread" to thread.name.take(255),
                "crash.is_main_thread" to (thread == Thread.currentThread())
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
                Log.i(
                    "TelemetryManager",
                    "Crash data persisted successfully. Will be sent on next app launch."
                )
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
            val executionTime = System.currentTimeMillis() - startTime
            Log.i(
                "TelemetryManager",
                "Crash handler completed in ${executionTime}ms"
            )
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
            "session.id" to sessionId,
            "session.start_time" to dateFormat.format(Date(sessionStartTime)),
            "session.duration_ms" to durationMs,
            "session.event_count" to eventCount,
            "session.metric_count" to metricCount,
            "session.screen_count" to visitedScreens.size,
            "session.visited_screens" to visitedScreens.joinToString(","),
            "session.is_first_session" to (totalSessions == 1),
            "session.total_sessions" to totalSessions,
            "network.type" to getNetworkType(context)
        )
        recordEvent(eventName = "session.finalized", attributes = attributes)
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
            "http.url" to url,
            "http.method" to method,
            "http.status_code" to statusCode,
            "http.duration_ms" to durationMs,
            "http.timestamp" to dateFormat.format(Date()),
            "http.success" to (statusCode < 400),
            "http.request_body_size" to requestBodySize,
            "http.response_body_size" to responseBodySize,
            "http.error" to (error ?: "none")
        )
        val combinedAttributes = attributes + networkAttributes
        recordEvent(eventName = "http.request", attributes = combinedAttributes)
    }

    // --- Crash and Error Reporting ---
    fun recordCrash(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        
        // Extract breadcrumbs if available
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

    @Deprecated(
        message = "Use recordCrash() instead. Backend only processes app.crash events.",
        replaceWith = ReplaceWith("recordCrash(throwable)"),
        level = DeprecationLevel.WARNING
    )
    fun recordError(throwable: Throwable, attributes: Map<String, Any> = emptyMap()) {
        if (debugMode) {
            Log.w("TelemetryManager", "recordError() is deprecated. Use recordCrash() instead. Recording as app.crash.")
        }
        
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        
        // Extract breadcrumbs if available
        val breadcrumbs = breadcrumbManager?.getBreadcrumbsAsJson() ?: "[]"
        val breadcrumbCount = breadcrumbManager?.getBreadcrumbCount() ?: 0
        
        val errorAttributes = mapOf(
            "error.message" to "${throwable.javaClass.name}: ${throwable.message ?: ""}".take(1000),
            "error.stack_trace" to stackTrace.take(2000),
            "error.exception_type" to throwable.javaClass.simpleName.take(255),
            "error.context" to extractErrorContext(stackTrace).take(500),
            "error.cause" to (throwable.cause?.message ?: "unknown").take(255),
            "error.severity_level" to determineSeverityLevel(throwable),
            "error.is_fatal" to false,
            "error.breadcrumbs" to breadcrumbs.take(800),
            "error.breadcrumb_count" to breadcrumbCount
        )
        val combinedAttributes = attributes + errorAttributes
        recordEvent(eventName = "app.crash", attributes = combinedAttributes)
    }

    @Composable
    fun trackComposeScreens(navController: NavController) {
        TrackComposeScreen(navController, telemetryManager = this)
    }

    fun trackActivities() {
        TelemetryActivityLifecycleObserver(this)
    }

    fun trackMemoryUsage() {
        TelemetryMemoryUsage(this)

    }

    // --- Screen Navigation Tracking ---
    @Deprecated(
        message = "Legacy screen_view event is not supported by backend. Use navigation events instead.",
        replaceWith = ReplaceWith("recordComposeScreenView(screenName)"),
        level = DeprecationLevel.WARNING
    )
    fun recordScreenView(screenName: String) {
        if (!config.enableLegacyScreenEvents) {
            if (debugMode) {
                Log.d("TelemetryManager", "Legacy screen events disabled - skipping screen_view for $screenName")
            }
            return
        }
        
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
            eventName = "navigation",
            attributes = mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to screenRoute,
                "navigation.method" to "push",
                "navigation.route_type" to "compose_route",
                "navigation.has_arguments" to false,
                "navigation.timestamp" to dateFormat.format(Date())
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
                    "screen.duration_ms" to durationMs,
                    "screen.exit_method" to "disposed",
                    "screen.timestamp" to dateFormat.format(Date()),
                    "metric.unit" to "milliseconds"
                )
            )
        }
    }


    // Sets the user ID for all subsequent events in the session.
    // Made private - SDK manages user ID automatically
    private fun setUserId(id: String) {
        this.userId = id
    }

    // A new method to set additional user profile information.
    // Made private - SDK manages user profile automatically
    private fun setUserProfile(name: String, email: String, phone: String, profileVersion: Int) {
        this.userName = name
        this.userEmail = email
        this.userPhone = phone
        this.userProfileVersion = profileVersion
    }

    // Builds the full set of attributes for an event by combining core attributes and event-specific ones.
    private fun buildAttributes(eventAttributes: Map<String, Any>): EventAttributes? {
        val sessionInfo = getSessionInfo()
        
        // Get user data from UserProfileManager if available, otherwise use legacy fields
        val userAttrs = if (userProfilesEnabled && userProfileManager != null) {
            userProfileManager!!.getUserAttributes()
        } else {
            emptyMap()
        }
        
        return appInfo?.let {
            EventAttributes(
                app = it,
                device = deviceInfo,
                user = UserInfo(
                    userId = userAttrs["user.id"] ?: userId,
                    name = userAttrs["user.name"] ?: userName,
                    email = userAttrs["user.email"] ?: userEmail,
                    phone = userAttrs["user.phone"] ?: userPhone,
                    profileVersion = userAttrs["user.profile_version"]?.toIntOrNull() ?: userProfileVersion
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
        // CRITICAL: Validate that device ID and user ID are properly initialized before sending
        if (!idsInitialized) {
            Log.w(
                "TelemetryManager",
                "Skipping batch send - IDs not properly initialized. Events remain queued (${eventQueue.size} events)."
            )
            return
        }
        
        if (!forceSend && eventQueue.size < batchSize) {
            return
        }

        val eventsToSend = mutableListOf<TelemetryEvent>()
        repeat(eventQueue.size) {
            val ev = eventQueue.poll()
            if (ev != null) eventsToSend.add(ev)
        }

        if (eventsToSend.isEmpty()) return

        // Get current location (from cache if available)
        val location = if (locationTrackingEnabled) {
            locationProvider?.getCachedLocation() ?: currentLocation
        } else {
            null
        }

        val batch = TelemetryBatch(
            batchSize = eventsToSend.size,
            timestamp = dateFormat.format(Date()),
            events = eventsToSend,
            location = location
        )

        Log.i("TelemetryManager", "Attempting to send a batch of ${batch.batchSize} events with location: $location")
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



    /**
     * Initialize device capabilities for runtime feature detection
     */
    private fun initializeCapabilities() {
        deviceCapabilities = DeviceCapabilities.getInstance(context)
        networkCapabilityDetector = NetworkCapabilityDetector(context, deviceCapabilities)
        memoryCapabilityTracker = MemoryCapabilityTracker(context, deviceCapabilities)
        
        Log.i("TelemetryManager", "Device capabilities initialized for API ${deviceCapabilities.apiLevel}")
        
        // Log comprehensive capability information
        logCapabilityDetails()
        
        // Record capability telemetry event
        recordCapabilityTelemetry()
    }
    
    /**
     * Log detailed capability information for debugging
     */
    private fun logCapabilityDetails() {
        Log.i("TelemetryManager", "=== Device Capability Report ===")
        Log.i("TelemetryManager", "API Level: ${deviceCapabilities.apiLevel} (Android ${deviceCapabilities.androidVersion})")
        
        Log.i("TelemetryManager", "Performance Features:")
        Log.i("TelemetryManager", "  Frame Metrics: ${if (deviceCapabilities.canCollectFrameMetrics) "✓ Enabled" else "✗ Disabled (API < 24)"}")
        Log.i("TelemetryManager", "  Advanced Memory: ${if (deviceCapabilities.canCollectAdvancedMemoryMetrics) "✓ Enabled" else "✗ Basic Mode"}")
        
        Log.i("TelemetryManager", "Network Features:")
        Log.i("TelemetryManager", "  Modern Networking: ${if (deviceCapabilities.canUseModernNetworkAPIs) "✓ Enabled" else "✗ Legacy Mode"}")
        Log.i("TelemetryManager", "  Network Callbacks: ${if (deviceCapabilities.supportsNetworkCallback) "✓ Enabled" else "✗ Not Available"}")
        
        Log.i("TelemetryManager", "System Features:")
        Log.i("TelemetryManager", "  Runtime Permissions: ${if (deviceCapabilities.canHandleRuntimePermissions) "✓ Enabled" else "✗ Manifest Only"}")
        Log.i("TelemetryManager", "  Scoped Storage: ${if (deviceCapabilities.canUseScopedStorage) "✓ Enabled" else "✗ Legacy Storage"}")
        Log.i("TelemetryManager", "  Notification Channels: ${if (deviceCapabilities.supportsNotificationChannels) "✓ Enabled" else "✗ Legacy Notifications"}")
        
        Log.i("TelemetryManager", "Hardware Features:")
        Log.i("TelemetryManager", "  Camera: ${if (deviceCapabilities.hasCamera) "✓" else "✗"}")
        Log.i("TelemetryManager", "  WiFi: ${if (deviceCapabilities.hasWifi) "✓" else "✗"}")
        Log.i("TelemetryManager", "  Cellular: ${if (deviceCapabilities.hasTelephony) "✓" else "✗"}")
        Log.i("TelemetryManager", "  GPS: ${if (deviceCapabilities.hasGps) "✓" else "✗"}")
        
        // Log current network status
        val networkType = networkCapabilityDetector.getCurrentNetworkType()
        val isConnected = networkCapabilityDetector.isConnected()
        Log.i("TelemetryManager", "Current Network: $networkType (Connected: $isConnected)")
        
        // Log memory status
        val memoryPressure = memoryCapabilityTracker.isUnderMemoryPressure()
        Log.i("TelemetryManager", "Memory Pressure: ${if (memoryPressure) "High" else "Normal"}")
        
        Log.i("TelemetryManager", "=== End Capability Report ===")
    }
    
    /**
     * Record device capabilities as telemetry event for analytics
     */
    private fun recordCapabilityTelemetry() {
        if (!config.enableCapabilityEvents) {
            if (debugMode) {
                Log.d("TelemetryManager", "Capability events disabled - skipping telemetry.capabilities_initialized")
            }
            return
        }
        
        try {
            val capabilitySummary = deviceCapabilities.getCapabilitiesSummary()
            val networkSummary = networkCapabilityDetector.getNetworkCapabilitiesSummary()
            val memorySummary = memoryCapabilityTracker.getMemoryUsageSummary()
            
            recordEvent(
                eventName = "telemetry.capabilities_initialized",
                attributes = mapOf(
                    "device_capabilities" to capabilitySummary,
                    "network_capabilities" to networkSummary,
                    "memory_status" to memorySummary,
                    "initialization_timestamp" to System.currentTimeMillis()
                )
            )
            
            Log.d("TelemetryManager", "Capability telemetry recorded")
        } catch (e: Exception) {
            Log.w("TelemetryManager", "Failed to record capability telemetry: ${e.localizedMessage}")
        }
    }
    
    /**
     * Get device capabilities (safe access after initialization)
     */
    fun getDeviceCapabilities(): DeviceCapabilities? {
        return if (::deviceCapabilities.isInitialized) deviceCapabilities else null
    }
    
    /**
     * Get memory capability tracker (safe access after initialization)
     */
    fun getMemoryCapabilityTracker(): MemoryCapabilityTracker? {
        return if (::memoryCapabilityTracker.isInitialized) memoryCapabilityTracker else null
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
                appBuildNumber = if (::deviceCapabilities.isInitialized && deviceCapabilities.supportsLongVersionCode) {
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
        return try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_NETWORK_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return "unknown" // App did not declare permission
            }

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo ?: return "unknown"

            when (activeNetwork.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                else -> "other"
            }
        } catch (e: SecurityException) {
            "unknown" // Prevent crash if permission check fails
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ================================
    // Flutter-Compatible Public API
    // ================================

    /**
     * Set user profile information (Flutter-compatible)
     */
    fun setUserProfile(
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        customAttributes: Map<String, String>? = null
    ) {
        if (userProfilesEnabled && userProfileManager != null) {
            userProfileManager!!.setUserProfile(name, email, phone, customAttributes)
        } else {
            Log.w("TelemetryManager", "User profiles not enabled. Call initialize() with enableUserProfiles = true")
        }
    }

    /**
     * Clear user profile (Flutter-compatible)
     */
    fun clearUserProfile() {
        if (userProfilesEnabled && userProfileManager != null) {
            userProfileManager!!.clearUserProfile()
        } else {
            Log.w("TelemetryManager", "User profiles not enabled")
        }
    }

    /**
     * Add breadcrumb (Flutter-compatible)
     */
    fun addBreadcrumb(
        message: String,
        category: String = "custom",
        level: String = "info",
        data: Map<String, String>? = null
    ) {
        breadcrumbManager?.addBreadcrumb(message, category, level, data)
            ?: Log.w("TelemetryManager", "Breadcrumb manager not initialized")
    }

    /**
     * Track error manually (Flutter-compatible)
     */
    fun trackError(error: Throwable, attributes: Map<String, String>? = null) {
        if (crashReportingEnabled && crashReporter != null) {
            crashReporter!!.trackError(error, attributes)
        } else {
            Log.w("TelemetryManager", "Crash reporting not enabled. Call initialize() with enableCrashReporting = true")
        }
    }
    
    /**
     * Track error with enhanced context (v2.0.0)
     * 
     * @param error The throwable to track
     * @param errorCode Optional app-specific error code (max 100 chars)
     * @param productId Optional product/module identifier (max 255 chars)
     * @param userAction Optional last user action (max 500 chars)
     * @param attributes Optional additional attributes
     */
    fun trackError(
        error: Throwable,
        errorCode: String? = null,
        productId: String? = null,
        userAction: String? = null,
        attributes: Map<String, String>? = null
    ) {
        if (crashReportingEnabled && crashReporter != null) {
            crashReporter!!.trackError(error, errorCode, productId, userAction, attributes)
        } else {
            Log.w("TelemetryManager", "Crash reporting not enabled. Call initialize() with enableCrashReporting = true")
        }
    }

    /**
     * Track error with message (Flutter-compatible)
     */
    fun trackError(message: String, stackTrace: String? = null, attributes: Map<String, String>? = null) {
        if (crashReportingEnabled && crashReporter != null) {
            crashReporter!!.trackError(message, stackTrace, attributes)
        } else {
            Log.w("TelemetryManager", "Crash reporting not enabled")
        }
    }
    
    /**
     * Set product context for crash reporting (v2.0.0)
     * This context will be included in all subsequent crash reports
     * 
     * @param productId Product/module identifier (max 255 chars)
     */
    fun setProductContext(productId: String) {
        if (crashReportingEnabled && crashReporter != null) {
            crashReporter!!.setProductContext(productId)
        } else {
            Log.w("TelemetryManager", "Crash reporting not enabled")
        }
    }
    
    /**
     * Set last user action for crash context (v2.0.0)
     * This context will be included in all subsequent crash reports
     * 
     * @param action Description of user action (max 500 chars)
     */
    fun setLastUserAction(action: String) {
        if (crashReportingEnabled && crashReporter != null) {
            crashReporter!!.setLastUserAction(action)
        } else {
            Log.w("TelemetryManager", "Crash reporting not enabled")
        }
    }

    /**
     * Start new session (Flutter-compatible)
     */
    fun startNewSession() {
        if (sessionTrackingEnabled && enhancedSessionManager != null) {
            enhancedSessionManager!!.startNewSession()
        } else {
            // Fallback to legacy session management
            sessionId = idGenerator.generateSessionId()
            sessionStartTime = System.currentTimeMillis()
            eventCount = 0
            metricCount = 0
            visitedScreens.clear()
            totalSessions++
        }
    }

    /**
     * End current session (Flutter-compatible)
     */
    fun endCurrentSession() {
        if (sessionTrackingEnabled && enhancedSessionManager != null) {
            enhancedSessionManager!!.endCurrentSession()
        }
    }

    /**
     * Get device ID (Flutter-compatible format)
     */
    fun getDeviceId(): String {
        return flutterIdGenerator?.getDeviceId() ?: deviceId
    }

    /**
     * Get user ID (Flutter-compatible)
     * CRITICAL: Always returns a valid user ID, never null
     */
    fun getUserId(): String {
        return if (userProfilesEnabled && userProfileManager != null) {
            userProfileManager!!.getUserId()
        } else {
            // Fallback to internal userId, ensure it's not empty
            if (userId.isBlank()) {
                Log.w("TelemetryManager", "User ID is blank, generating fallback")
                "user_fallback_${System.currentTimeMillis()}"
            } else {
                userId
            }
        }
    }

    /**
     * Get session ID (Flutter-compatible)
     */
    fun getSessionId(): String {
        return if (sessionTrackingEnabled && enhancedSessionManager != null) {
            enhancedSessionManager!!.getCurrentSessionId()
        } else {
            sessionId
        }
    }

    /**
     * Test crash reporting (Flutter-compatible)
     */
    fun testCrashReporting(customMessage: String? = null) {
        if (crashReportingEnabled && crashReporter != null) {
            crashReporter!!.testCrashReporting(customMessage)
        } else {
            Log.w("TelemetryManager", "Crash reporting not enabled. Cannot test.")
        }
    }

    /**
     * Test connectivity (Flutter-compatible)
     */
    fun testConnectivity() {
        jsonEventTracker?.testConnectivity()
            ?: Log.w("TelemetryManager", "Event tracker not initialized")
    }

    /**
     * Get enhanced session manager (for internal use)
     */
    internal fun getEnhancedSessionManager(): SessionManager? = enhancedSessionManager

    /**
     * Get breadcrumb manager (for internal use)
     */
    internal fun getBreadcrumbManager(): BreadcrumbManager? = breadcrumbManager

    /**
     * Get crash reporter (for internal use)
     */
    internal fun getCrashReporter(): CrashReporter? = crashReporter
    
    /**
     * Extract error context from stack trace (ClassName.methodName from top frame)
     */
    private fun extractErrorContext(stackTrace: String): String {
        return try {
            val lines = stackTrace.lines()
            val firstFrame = lines.firstOrNull { it.trim().startsWith("at ") } ?: return "unknown"
            
            // Extract "at com.example.ClassName.methodName(File.kt:123)"
            val atIndex = firstFrame.indexOf("at ")
            if (atIndex == -1) return "unknown"
            
            val methodPart = firstFrame.substring(atIndex + 3).trim()
            val parenIndex = methodPart.indexOf("(")
            val fullMethod = if (parenIndex > 0) methodPart.substring(0, parenIndex) else methodPart
            
            // Get last two parts (ClassName.methodName)
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
}
