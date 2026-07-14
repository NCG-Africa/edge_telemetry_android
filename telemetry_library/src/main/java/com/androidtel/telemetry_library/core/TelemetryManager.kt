package com.androidtel.telemetry_library.core

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.crash.CrashReporter
import com.androidtel.telemetry_library.core.device.DeviceInfoCollector
import com.androidtel.telemetry_library.core.events.JsonEventTracker
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.location.IpLocationProvider
import com.androidtel.telemetry_library.core.location.LocationProvider
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.services.EventTrackingService
import com.androidtel.telemetry_library.core.services.SessionService
import com.androidtel.telemetry_library.core.services.UserProfileService
import com.androidtel.telemetry_library.core.services.CrashReportingService
import com.androidtel.telemetry_library.core.services.BatchProcessingService
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
import okhttp3.Interceptor
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    private val scope = CoroutineScope(Dispatchers.IO)
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    // Pre-init call queue
    private val isReady = AtomicBoolean(false)
    private val preInitQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val PRE_INIT_QUEUE_MAX_SIZE = 50

    // Registration guards — atomic to avoid double-registration if initialize() is called
    // concurrently from multiple threads. compareAndSet ensures only one caller wins per flag.
    private val activityObserverRegistered = AtomicBoolean(false)
    private val processObserverRegistered = AtomicBoolean(false)

    // TelemetryInterceptor instance
    private var telemetryInterceptor: TelemetryInterceptor? = null

    // ID Generator - single source of truth for all IDs
    private lateinit var idGenerator: IdGenerator

    // Core attributes for every event
    private lateinit var deviceId: String
    private val appInfo = collectAppInfo()
    private lateinit var deviceInfo: DeviceInfo
    
    // Device capabilities for runtime feature detection
    private lateinit var deviceCapabilities: DeviceCapabilities
    private lateinit var networkCapabilityDetector: NetworkCapabilityDetector
    private lateinit var memoryCapabilityTracker: MemoryCapabilityTracker

    // Legacy components (for backward compatibility)
    private var deviceInfoCollector: DeviceInfoCollector? = null
    private var jsonEventTracker: JsonEventTracker? = null

    // Location tracking components
    private var locationProvider: LocationProvider? = null
    private var currentLocation: String? = null

    // Memory tracker — sampled on session boundaries and app-resume to keep traffic bounded
    private var memoryTracker: MemoryTracker? = null
    
    // ID validation state
    @Volatile
    private var idsInitialized: Boolean = false
    
    // ========================================
    // PHASE 2: Service-based Architecture
    // ========================================
    private lateinit var eventTrackingService: EventTrackingService
    private lateinit var sessionService: SessionService
    private lateinit var userProfileService: UserProfileService
    private lateinit var crashReportingService: CrashReportingService
    private lateinit var batchProcessingService: BatchProcessingService
    
    // Helper methods to check feature flags
    internal fun isMemoryTrackingEnabled(): Boolean = config.enableMemoryTracking
    internal fun isStorageTrackingEnabled(): Boolean = config.enableStorageTracking
    internal fun isFrameTrackingEnabled(): Boolean = config.enableFrameTracking
    internal fun isLegacyScreenEventsEnabled(): Boolean = config.enableLegacyScreenEvents
    internal fun isUserInteractionEventsEnabled(): Boolean = config.enableUserInteractionEvents

    companion object {
        @Volatile
        private var instance: TelemetryManager? = null

        /**
         * Initialize SDK with TelemetryConfig object
         * 
         * Example:
         * ```
         * val config = TelemetryConfig(
         *     apiKey = "edge_your_api_key",
         *     endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
         * )
         * TelemetryManager.initialize(application, config)
         * ```
         */
        fun initialize(application: Application, config: TelemetryConfig): TelemetryManager {
            // Step 1: Validate config (already done in TelemetryConfig.init)
            Log.i("TelemetryManager", "Starting SDK initialization")
            
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager(
                    application.applicationContext,
                    httpClient = TelemetryHttpClient(
                        telemetryUrl = config.endpoint,
                        apiKey = config.apiKey,
                        debugMode = false
                    ),
                    offlineStorage = OfflineBatchStorage(application.applicationContext),
                    screenTimingTracker = ScreenTimingTracker(),
                    batchSize = config.batchSize,
                    apiKey = config.apiKey,
                    telemetryEndpoint = config.endpoint,
                    debugMode = false,
                    config = config,
                ).also { manager ->
                    instance = manager
                    manager.performInitializationSequence()
                }
            }
        }

        /**
         * Initialize SDK with individual parameters (deprecated - use TelemetryConfig)
         */
        @Deprecated(
            message = "Use initialize(application, TelemetryConfig) instead",
            replaceWith = ReplaceWith("initialize(application, TelemetryConfig(apiKey, endpoint))")
        )
        fun initialize(
            application: Application,
            apiKey: String,
            batchSize: Int = 50,
            endpoint: String = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
        ): TelemetryManager {
            val config = TelemetryConfig(
                apiKey = apiKey,
                endpoint = endpoint,
                batchSize = batchSize
            )
            return initialize(application, config)
        }

        /**
         * Access after init()
         */
        fun getInstance(): TelemetryManager {
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
     * Enforced initialization sequence for SDK components
     * 
     * ENFORCED INIT SEQUENCE:
     * 1. Validate config — fail fast
     * 2. Restore or generate deviceId (SharedPreferences)
     * 3. Restore or generate userId (SharedPreferences)
     * 4. Collect and cache device info (once)
     * 5. Initialise UserProfileManager (empty profile)
     * 6. Initialise SessionManager
     * 7. Initialise in-memory event queue + offline buffer
     * 8. Start flush timer (config.flushIntervalMs)
     * 9. isReady.set(true)
     * 10. Drain preInitQueue (FIFO)
     * 11. if (enableScreenTracking)   → register ActivityLifecycleCallbacks
     * 12. if (enableCrashReporting)   → install CrashReporter
     * 13. if (enableLifecycleTracking)→ register ProcessLifecycleOwner observer
     * 14. if (enableNetworkTracking)  → instantiate TelemetryInterceptor
     */
    private fun performInitializationSequence() {
        try {
            // Step 1: Config already validated in TelemetryConfig.init
            Log.d("TelemetryManager", "Step 1: Config validated")
            
            // Step 2: Restore or generate deviceId
            idGenerator = IdGenerator()
            idGenerator.initialize(context)
            deviceId = idGenerator.getOrGenerateDeviceId()
            Log.d("TelemetryManager", "Step 2: Device ID initialized: $deviceId")
            
            // Step 3: Collect and cache device info
            deviceInfo = collectDeviceInfo()
            initializeCapabilities()
            Log.d("TelemetryManager", "Step 3: Device info collected")
            
            // Step 4: Initialize Services (Phase 2 Architecture)
            eventTrackingService = EventTrackingService(context, config)
            eventTrackingService.initialize(appInfo, deviceInfo)
            Log.d("TelemetryManager", "Step 4: EventTrackingService initialized")
            
            // Step 5: Initialize SessionService
            sessionService = SessionService(context, config, idGenerator)
            sessionService.initialize()
            Log.d("TelemetryManager", "Step 5: SessionService initialized")
            
            // Step 6: Initialize UserProfileService
            userProfileService = UserProfileService(context, config, idGenerator)
            userProfileService.initialize()
            Log.d("TelemetryManager", "Step 6: UserProfileService initialized")
            
            // Step 7: Initialize CrashReportingService
            crashReportingService = CrashReportingService(
                context, config, idGenerator, httpClient, offlineStorage, scope, apiKey, telemetryEndpoint
            )
            crashReportingService.initialize()
            Log.d("TelemetryManager", "Step 7: CrashReportingService initialized")
            
            // Step 8: Initialize BatchProcessingService
            batchProcessingService = BatchProcessingService(config, httpClient, offlineStorage, scope)
            batchProcessingService.initialize()
            Log.d("TelemetryManager", "Step 8: BatchProcessingService initialized")
            
            // Step 9: Initialize legacy components for backward compatibility
            deviceInfoCollector = DeviceInfoCollector(context, idGenerator)
            Log.d("TelemetryManager", "Step 9: Legacy components initialized")

            // Step 9b: Initialize memory tracker so memory metrics flow when sampled
            if (config.enableMemoryTracking) {
                memoryTracker = MemoryTrackerFactory.createMemoryTracker(this)
                Log.d("TelemetryManager", "Step 9b: MemoryTracker initialized")
            }
            
            // Step 10: Mark as ready
            idsInitialized = true
            batchProcessingService.setIdsInitialized(true)
            isReady.set(true)
            Log.d("TelemetryManager", "Step 10: SDK marked as ready")
            
            // Step 11: Drain pre-init queue
            drainPreInitQueue()
            Log.d("TelemetryManager", "Step 11: Pre-init queue drained")
            
            // Step 12: Register activity lifecycle callbacks if enabled
            if (config.enableScreenTracking && activityObserverRegistered.compareAndSet(false, true)) {
                val app = context as? Application
                if (app != null) {
                    val observer = TelemetryActivityLifecycleObserver(this)
                    app.registerActivityLifecycleCallbacks(observer)
                    Log.d("TelemetryManager", "Step 12: Activity lifecycle observer registered")
                } else {
                    // Roll back the CAS so a later retry with a proper Application context succeeds
                    activityObserverRegistered.set(false)
                    Log.w("TelemetryManager", "Step 12: Context is not Application, cannot register activity observer")
                }
            }

            // Step 13: Register ProcessLifecycleOwner observer if enabled
            if (config.enableLifecycleTracking && processObserverRegistered.compareAndSet(false, true)) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
                Log.d("TelemetryManager", "Step 13: Process lifecycle observer registered")
            }
            
            // Step 14: Instantiate TelemetryInterceptor if enabled
            if (config.enableNetworkTracking) {
                telemetryInterceptor = TelemetryInterceptor(this, telemetryEndpoint)
                Log.d("TelemetryManager", "Step 14: Network interceptor instantiated")
            }
            
            Log.i("TelemetryManager", "SDK initialization complete - All modules active")

            // Step 15: Emit session.started event for the initial session.
            // Backend dispatch: `event_processor.py` matches eventName == "session.started" and marks
            // the corresponding rum_sessions row as status=active.
            emitSessionStartedEvent()

        } catch (e: Exception) {
            Log.e("TelemetryManager", "Failed to complete initialization sequence", e)
            throw e
        }
    }

    /**
     * Emit a session.started event with the current session info. Idempotently safe — call after
     * each call to SessionService.startNewSession() or after initial session creation.
     */
    private fun emitSessionStartedEvent() {
        if (!::sessionService.isInitialized || !::eventTrackingService.isInitialized) return
        val sessionInfo = sessionService.getSessionInfo(
            eventTrackingService.getEventCount(),
            eventTrackingService.getMetricCount(),
            getNetworkType(context)
        )
        recordEvent(
            eventName = "session.started",
            attributes = mapOf(
                "session.id" to sessionInfo.sessionId,
                "session.start_time" to (sessionInfo.startTime ?: ""),
                "session.is_first_session" to (sessionInfo.isFirstSession ?: false),
                "session.total_sessions" to (sessionInfo.totalSessions ?: 0),
                "network.type" to (sessionInfo.networkType ?: "unknown")
            )
        )
        // Sample memory once at session boundary — bounded traffic, useful baseline for the backend
        sampleMemoryUsage()
    }

    /**
     * Sample memory once via the configured MemoryTracker. Safe to call before the tracker is
     * initialized (no-op). Frame metrics flow automatically via the activity lifecycle observer.
     */
    private fun sampleMemoryUsage() {
        try {
            memoryTracker?.recordMemoryUsage()
            if (config.enableStorageTracking) {
                memoryTracker?.recordStorageUsage()
            }
        } catch (e: Exception) {
            Log.w("TelemetryManager", "Memory sample failed: ${e.localizedMessage}")
        }
    }


    // This is called when the app comes to the foreground. The session is already active.
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (!config.enableLifecycleTracking) return

        Log.i("TelemetryManager", "App moved to foreground")

        // 1. Restore offline buffer back into in-memory event queue
        scope.launch {
            batchProcessingService.restoreOfflineBatches(eventTrackingService.getEventQueue())
        }

        // 2. Check session timeout and start new session if needed
        if (sessionService.hasSessionTimedOut()) {
            val lastActive = sessionService.getLastActiveTimestamp()
            if (lastActive > 0) {
                // Emit session.finalized with full session stats (event/metric/screen counts +
                // visited_screens + network.type) — these come from the standard session envelope.
                recordEvent("session.finalized", mapOf(
                    "session.reason" to "timeout"
                ))
            }
            sessionService.startNewSession()
            Log.i("TelemetryManager", "Started new session: ${sessionService.getCurrentSessionId()}")
            emitSessionStartedEvent()
        } else {
            val elapsed = System.currentTimeMillis() - sessionService.getLastActiveTimestamp()
            Log.i("TelemetryManager", "Resuming existing session (elapsed: ${elapsed}ms)")
        }
        
        // 3. Resume the flush timer
        batchProcessingService.resumeFlushTimer()

        // 4. Sample memory on each foreground transition — bounded by user-engagement cadence
        sampleMemoryUsage()
    }

    // This is called when the app goes into the background. We can track the session end here.
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        if (!config.enableLifecycleTracking) return
        
        Log.i("TelemetryManager", "App moved to background")
        
        // 1. Flush in-memory event queue to offline buffer
        scope.launch {
            batchProcessingService.flushToOfflineStorage(eventTrackingService.getEventQueue())
        }
        
        // 2. Persist lastActiveTimestamp
        sessionService.updateLastActiveTimestamp()
        
        // 3. Pause the flush timer
        batchProcessingService.stopFlushTimer()
    }

    // Records a new metric event with the specified details.
    fun recordMetric(
        metricName: String,
        value: Double,
        attributes: Map<String, Any> = emptyMap()
    ) {
        if (!isReady.get()) {
            offerToPreInitQueue { recordMetric(metricName, value, attributes) }
            return
        }
        
        val userInfo = userProfileService.getUserInfo()
        val sessionInfo = sessionService.getSessionInfo(
            eventTrackingService.getEventCount(),
            eventTrackingService.getMetricCount(),
            getNetworkType(context)
        )
        
        eventTrackingService.recordMetric(metricName, value, attributes, userInfo, sessionInfo)
        maybeSendBatch()
    }

    // Records a new general event with the specified details.
    fun recordEvent(
        eventName: String,
        attributes: Map<String, Any> = emptyMap()
    ) {
        if (!isReady.get()) {
            offerToPreInitQueue { recordEvent(eventName, attributes) }
            return
        }
        
        val userInfo = userProfileService.getUserInfo()
        val sessionInfo = sessionService.getSessionInfo(
            eventTrackingService.getEventCount(),
            eventTrackingService.getMetricCount(),
            getNetworkType(context)
        )
        
        eventTrackingService.recordEvent(eventName, attributes, userInfo, sessionInfo)
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
        if (!isReady.get()) {
            offerToPreInitQueue { recordNetworkRequest(url, method, statusCode, durationMs, requestBodySize, responseBodySize, error, attributes) }
            return
        }
        
        val userInfo = userProfileService.getUserInfo()
        val sessionInfo = sessionService.getSessionInfo(
            eventTrackingService.getEventCount(),
            eventTrackingService.getMetricCount(),
            getNetworkType(context)
        )
        
        eventTrackingService.recordNetworkRequest(
            url, method, statusCode, durationMs, requestBodySize, responseBodySize, error, attributes, userInfo, sessionInfo
        )
        maybeSendBatch()
    }

    // --- Crash and Error Reporting ---
    fun recordCrash(throwable: Throwable) {
        crashReportingService.recordCrash(
            throwable = throwable,
            buildAttributesFn = { attrs -> buildAttributes(attrs) },
            onEventCreated = { event -> eventTrackingService.getEventQueue().add(event) }
        )
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
        recordCrash(throwable)
    }

    // trackComposeScreens() omitted in the -java8 build (no Compose). Use
    // recordComposeScreenView(route) / recordComposeScreenEnd(route) manually from your nav callbacks.

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
        
        sessionService.addVisitedScreen(screenName)
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
            recordScreenDuration(screenRoute, durationMs, "disposed")
        }
    }

    /**
     * Emit a screen.duration event matching the backend's expected wire format.
     *
     * Backend dispatch: `event_processor.py` matches `eventName == "screen.duration"` and routes to
     * `rum_screen_durations` via `extract_screen_duration()`. Required attributes: `screen.name`,
     * `screen.duration_ms`. Optional: `screen.exit_method`, `screen.timestamp`.
     */
    fun recordScreenDuration(screenName: String, durationMs: Long, exitMethod: String) {
        recordEvent(
            eventName = "screen.duration",
            attributes = mapOf(
                "screen.name" to screenName,
                "screen.duration_ms" to durationMs,
                "screen.exit_method" to exitMethod,
                "screen.timestamp" to dateFormat.format(Date())
            )
        )
    }



    // Builds the full set of attributes for an event by combining core attributes and event-specific ones.
    private fun buildAttributes(eventAttributes: Map<String, Any>): EventAttributes? {
        val userInfo = userProfileService.getUserInfo()
        val sessionInfo = sessionService.getSessionInfo(
            eventTrackingService.getEventCount(),
            eventTrackingService.getMetricCount(),
            getNetworkType(context)
        )
        
        return appInfo?.let {
            EventAttributes(
                app = it,
                device = deviceInfo,
                user = userInfo,
                session = sessionInfo,
                customAttributes = eventAttributes
            )
        }
    }

    // Checks the queue size and sends a batch if the threshold is met.
    private fun maybeSendBatch() {
        if (batchProcessingService.shouldSendBatch(eventTrackingService.getEventQueue().size)) {
            val location = if (config.enableLocationTracking) {
                locationProvider?.getCachedLocation() ?: currentLocation
            } else {
                null
            }
            batchProcessingService.triggerBatchSend(eventTrackingService.getEventQueue(), location)
        }
    }

    // This method sends the buffered events as a single JSON batch.
    // Delegated to BatchProcessingService
    private suspend fun sendBatch(forceSend: Boolean = false, flushOffline: Boolean = true) {
        val location = if (config.enableLocationTracking) {
            locationProvider?.getCachedLocation() ?: currentLocation
        } else {
            null
        }

        batchProcessingService.sendBatch(
            eventTrackingService.getEventQueue(),
            forceSend,
            flushOffline,
            location
        )
    }

    // Method to send any batches stored in the offline queue.
    // Delegated to BatchProcessingService
    private suspend fun sendStoredBatches() {
        batchProcessingService.sendStoredBatches()
    }

    // Crash persistence methods delegated to CrashReportingService
    private suspend fun sendPersistedCrashIfAny() {
        crashReportingService.sendPersistedCrashIfAny()
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

    // Session information delegated to SessionService
    private fun getSessionInfo(): SessionInfo {
        return sessionService.getSessionInfo(
            eventTrackingService.getEventCount(),
            eventTrackingService.getMetricCount(),
            getNetworkType(context)
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
     * Set user profile information.
     *
     * Can be called before or after SDK.init(). Fully replaces previous values (no merge); passing
     * null for a field clears it. After persisting locally, emits a `user.profile.update` event
     * which the backend (`event_processor.py:219`) upserts into the `rum_users` table.
     */
    fun setUserProfile(name: String?, email: String?, phone: String? = null) {
        userProfileService.setUserProfile(name, email, phone)
        emitUserProfileUpdateEvent(name, email, phone)
    }

    /**
     * Clear user profile.
     *
     * Emits a `user.profile.update` event carrying null fields so the backend can reflect the
     * clear in `rum_users`.
     */
    fun clearUserProfile() {
        userProfileService.clearUserProfile()
        emitUserProfileUpdateEvent(null, null, null)
    }

    /**
     * Emit a `user.profile.update` event matching the backend wire format. Defers to the pre-init
     * queue if the SDK isn't ready yet.
     */
    private fun emitUserProfileUpdateEvent(name: String?, email: String?, phone: String?) {
        val attributes = buildMap<String, Any> {
            put("user.name", name ?: "")
            put("user.email", email ?: "")
            put("user.phone", phone ?: "")
            put("user.profile_updated_at", dateFormat.format(Date()))
        }
        recordEvent(eventName = "user.profile.update", attributes = attributes)
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
        crashReportingService.addBreadcrumb(message, category, level, data)
    }

    /**
     * Track error manually
     */
    fun trackError(error: Throwable, attributes: Map<String, String>? = null) {
        crashReportingService.trackError(error, attributes)
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
        crashReportingService.trackError(error, errorCode, productId, userAction, attributes)
    }

    /**
     * Track error with message
     */
    fun trackError(message: String, stackTrace: String? = null, attributes: Map<String, String>? = null) {
        crashReportingService.trackError(message, stackTrace, attributes)
    }
    
    /**
     * Set product context for crash reporting (v2.0.0)
     * This context will be included in all subsequent crash reports
     * 
     * @param productId Product/module identifier (max 255 chars)
     */
    fun setProductContext(productId: String) {
        crashReportingService.setProductContext(productId)
    }
    
    /**
     * Set last user action for crash context (v2.0.0)
     * This context will be included in all subsequent crash reports
     * 
     * @param action Description of user action (max 500 chars)
     */
    fun setLastUserAction(action: String) {
        crashReportingService.setLastUserAction(action)
    }

    /**
     * Start new session
     */
    fun startNewSession() {
        sessionService.startNewSession()
        eventTrackingService.resetEventCount()
        eventTrackingService.resetMetricCount()
        emitSessionStartedEvent()
    }

    /**
     * End current session.
     *
     * Emits a session.finalized event so the backend can mark the rum_sessions row as completed.
     * The standard session envelope on every event already carries duration_ms, event_count,
     * metric_count, screen_count, visited_screens, is_first_session, total_sessions, and
     * network.type — that's the full set the schema requires.
     */
    fun endCurrentSession() {
        if (::sessionService.isInitialized) {
            recordEvent("session.finalized", mapOf(
                "session.reason" to "manual"
            ))
        }
        sessionService.endCurrentSession()
    }

    /**
     * Get device ID
     */
    fun getDeviceId(): String {
        return if (::deviceId.isInitialized) deviceId else ""
    }

    /**
     * Get user ID
     * CRITICAL: Always returns a valid user ID, never null
     */
    fun getUserId(): String {
        return userProfileService.getUserId()
    }

    /**
     * Get session ID
     */
    fun getSessionId(): String {
        return sessionService.getCurrentSessionId()
    }

    /**
     * Test crash reporting
     */
    fun testCrashReporting(customMessage: String? = null) {
        crashReportingService.testCrashReporting(customMessage)
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
    internal fun getEnhancedSessionManager(): SessionManager? = sessionService.getEnhancedSessionManager()

    /**
     * Get breadcrumb manager (for internal use)
     */
    internal fun getBreadcrumbManager(): BreadcrumbManager? = crashReportingService.getBreadcrumbManager()

    /**
     * Get crash reporter (for internal use)
     */
    internal fun getCrashReporter(): CrashReporter? = crashReportingService.getCrashReporter()
    

    // ================================
    // Pre-Init Queue & Helper Methods
    // ================================

    /**
     * Offer action to pre-init queue with FIFO eviction if full
     */
    private fun offerToPreInitQueue(action: () -> Unit) {
        if (preInitQueue.size >= PRE_INIT_QUEUE_MAX_SIZE) {
            preInitQueue.poll()
        }
        preInitQueue.offer(action)
    }

    /**
     * Drain pre-init queue in FIFO order
     */
    private fun drainPreInitQueue() {
        var drained = 0
        while (preInitQueue.isNotEmpty()) {
            val action = preInitQueue.poll()
            try {
                action?.invoke()
                drained++
            } catch (e: Exception) {
                Log.e("TelemetryManager", "Error executing pre-init queued action", e)
            }
        }
        if (drained > 0) {
            Log.i("TelemetryManager", "Drained $drained pre-init queued calls")
        }
    }


    /**
     * Get network interceptor accessor
     */
    fun getInterceptor(): Interceptor {
        if (!config.enableNetworkTracking) {
            throw IllegalStateException("Network tracking is disabled in TelemetryConfig")
        }
        return telemetryInterceptor ?: throw IllegalStateException("Network interceptor not initialized")
    }
}
