# Stage 9 Implementation Summary: TelemetryConfig + Automatic Instrumentation

## Overview
Stage 9 refactors the EdgeRUM SDK to implement a simplified TelemetryConfig, pre-init call queue mechanism, automatic instrumentation modules, and an enforced initialization sequence.

## Changes Implemented

### 1. TelemetryConfig Refactoring ✅

**File:** `TelemetryConfig.kt`

**Changes:**
- Removed `Application` context dependency from config
- Simplified to core required fields only
- All instrumentation flags default to `true` (opt-out model)

**New Structure:**
```kotlin
data class TelemetryConfig(
    val apiKey: String,                                   // required
    val endpoint: String,                                 // required
    val batchSize: Int = 50,                              // optional
    val flushIntervalMs: Long = 30_000L,                  // optional
    val sessionTimeoutMs: Long = 30 * 60 * 1000L,        // optional
    val enableScreenTracking: Boolean = true,             // optional
    val enableCrashReporting: Boolean = true,             // optional
    val enableNetworkTracking: Boolean = true,            // optional
    val enableLifecycleTracking: Boolean = true           // optional
)
```

**Validation:**
- `apiKey` must not be blank and must start with `"edge_"`
- `endpoint` must not be blank
- `batchSize`, `flushIntervalMs`, `sessionTimeoutMs` must be > 0

### 2. Pre-Init Call Queue ✅

**File:** `TelemetryManager.kt`

**Implementation:**
- Added `isReady: AtomicBoolean` flag
- Added `preInitQueue: ConcurrentLinkedQueue<() -> Unit>`
- Queue capacity: 50 items (FIFO eviction when full)
- All public SDK methods check `isReady` before execution
- Queued calls are drained after `isReady.set(true)` in init sequence

**Protected Methods:**
- `recordEvent()`
- `recordMetric()`
- `recordNavigation()`
- `recordHttpRequest()`
- `recordNetworkRequest()`

**Example:**
```kotlin
fun recordEvent(eventName: String, attributes: Map<String, Any>) {
    if (!isReady.get()) {
        offerToPreInitQueue { recordEvent(eventName, attributes) }
        return
    }
    // ... normal execution
}
```

### 3. Automatic Screen Tracking ✅

**File:** `TelemetryActivityLifecycleObserver.kt`

**Condition:** Only when `config.enableScreenTracking == true`

**Implementation:**
- Registered via `Application.registerActivityLifecycleCallbacks()` during init
- Tracks screen entry in `onActivityResumed()`
- Emits `screen_view` event in `onActivityPaused()`
- Guard flag `activityObserverRegistered` prevents duplicate registration

**Event Payload:**
```kotlin
{
    "event_name": "screen_view",
    "screen_name": "MainActivity",
    "duration_ms": 5432,
    "session_id": "sess_abc123",
    "timestamp": 1234567890
}
```

### 4. Automatic Crash Reporting ✅

**File:** `CrashReporter.kt`

**Condition:** Only when `config.enableCrashReporting == true`

**Implementation:**
- `installGlobalExceptionHandler()` made public
- Saves original `Thread.defaultUncaughtExceptionHandler` before replacing
- Captures exception type, message, and stack trace (truncated to 64 frames)
- Emits crash event synchronously (blocking network call acceptable)
- Calls original handler after emitting to preserve default crash behavior
- Guard flag `crashHandlerInstalled` prevents duplicate installation

**Crash Event Payload:**
```kotlin
{
    "event_name": "crash",
    "exception_type": "NullPointerException",
    "message": "Attempt to invoke virtual method...",
    "stack_trace": "at com.example.MainActivity.onCreate...",
    "session_id": "sess_abc123",
    "timestamp": 1234567890
}
```

### 5. App Lifecycle Tracking ✅

**File:** `TelemetryManager.kt` (ProcessLifecycleOwner observer)

**Condition:** Only when `config.enableLifecycleTracking == true`

**Implementation:**
- Registered via `ProcessLifecycleOwner.get().lifecycle.addObserver(this)`
- Guard flag `processObserverRegistered` prevents duplicate registration

**ON_START (App Foregrounded):**
1. Restore offline buffer back into in-memory event queue
2. Read `lastActiveTimestamp` from SharedPreferences
3. If elapsed < `config.sessionTimeoutMs` → resume existing sessionId
4. If elapsed >= `config.sessionTimeoutMs` → emit `session_end`, generate new sessionId
5. Resume flush timer

**ON_STOP (App Backgrounded):**
1. Flush in-memory event queue to offline buffer
2. Persist `lastActiveTimestamp = System.currentTimeMillis()` to SharedPreferences
3. Pause (cancel) flush timer

### 6. Network Tracking ✅

**File:** `TelemetryInterceptor.kt`

**Condition:** Only when `config.enableNetworkTracking == true`

**Implementation:**
- Instantiated during init and held as internal reference
- Exposed via `getInterceptor(): Interceptor` accessor
- Host app adds it to OkHttpClient manually (no auto-injection)
- Throws `IllegalStateException` if `enableNetworkTracking == false`

**Tracked Per Request:**
- `url` (query params stripped by default)
- `method`
- `response_code`
- `latency_ms`
- `request_size_bytes`
- `response_size_bytes`

**Event Emission:**
```kotlin
recordEvent("http_request", mapOf(
    "url" to "https://api.example.com/users",
    "method" to "GET",
    "response_code" to 200,
    "latency_ms" to 245,
    "request_size_bytes" to 0,
    "response_size_bytes" to 1024
))
```

### 7. Flush Timer ✅

**File:** `TelemetryManager.kt`

**Implementation:**
- Uses `ScheduledExecutorService` with fixed delay
- Interval: `config.flushIntervalMs` (default: 30,000ms)
- Started after event queue initialization (before `isReady` is set)
- Flushes on each tick if queue size > 0
- Also flushes immediately when queue size reaches `config.batchSize`
- Paused on `ON_STOP`, resumed on `ON_START`

**Methods:**
- `startFlushTimer()` - Creates and schedules executor
- `stopFlushTimer()` - Shuts down executor
- `resumeFlushTimer()` - Restarts if shutdown

### 8. Enforced Init Sequence ✅

**File:** `TelemetryManager.kt` - `performStage9InitSequence()`

**Sequence:**
```
1. Validate config (done in TelemetryConfig.init)
2. Restore or generate deviceId (SharedPreferences)
3. Restore or generate userId (SharedPreferences)
4. Collect and cache device info (once)
5. Initialize UserProfileManager (empty profile)
6. Initialize SessionManager
7. Initialize in-memory event queue + offline buffer
8. Start flush timer (config.flushIntervalMs)
9. isReady.set(true)
10. Drain preInitQueue (FIFO)
11. if (enableScreenTracking)   → register ActivityLifecycleCallbacks
12. if (enableCrashReporting)   → install CrashReporter
13. if (enableLifecycleTracking)→ register ProcessLifecycleOwner observer
14. if (enableNetworkTracking)  → instantiate TelemetryInterceptor
```

**Critical:** Steps 11-14 execute **after** step 9. No instrumentation module is active before `isReady` is true.

## API Changes

### Initialization (New)

```kotlin
// Stage 9 approach
val config = TelemetryConfig(
    apiKey = "edge_your_api_key",
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
    batchSize = 50,
    flushIntervalMs = 30_000L,
    sessionTimeoutMs = 30 * 60 * 1000L,
    enableScreenTracking = true,
    enableCrashReporting = true,
    enableNetworkTracking = true,
    enableLifecycleTracking = true
)

TelemetryManager.initialize(application, config)
```

### Initialization (Legacy - Deprecated)

```kotlin
// Old approach (still works but deprecated)
TelemetryManager.initialize(
    application = application,
    apiKey = "edge_your_api_key",
    batchSize = 50,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
)
```

### Network Interceptor Access

```kotlin
// Get interceptor for OkHttpClient
val interceptor = TelemetryManager.getInstance().getInterceptor()

val client = OkHttpClient.Builder()
    .addInterceptor(interceptor)
    .build()

// Throws IllegalStateException if enableNetworkTracking == false
```

## Verification Checklist

### ✅ Required Tests

1. **SDK.init() with all defaults**
   - Confirm all four modules activate automatically
   - Check logs for all 14 init steps

2. **SDK.init() with enableCrashReporting=false**
   - Confirm no UncaughtExceptionHandler is installed
   - Verify `crashHandlerInstalled` remains false

3. **recordEvent() called before SDK.init()**
   - Confirm event is buffered in preInitQueue
   - Verify event is replayed after init completes

4. **Pre-init buffer overflow**
   - Queue 51 items before init
   - Confirm only 50 are replayed
   - Verify oldest item is dropped (FIFO eviction)

5. **Navigate between two Activities**
   - Confirm `screen_view` events emitted automatically
   - Verify duration_ms is calculated correctly

6. **Trigger uncaught exception**
   - Confirm crash event emitted
   - Verify original handler is called
   - Check stack trace truncation (64 frames max)

7. **Background app**
   - Confirm `lastActiveTimestamp` persisted
   - Verify queue flushed to disk
   - Check flush timer stopped

8. **Foreground within sessionTimeoutMs**
   - Confirm same sessionId resumed
   - Verify offline buffer restored

9. **Foreground after sessionTimeoutMs**
   - Mock timestamp to exceed timeout
   - Confirm new session created
   - Verify `session_end` event emitted for old session

10. **getInterceptor() with enableNetworkTracking=true**
    - Returns valid Interceptor instance
    - Verify http_request events emitted

11. **getInterceptor() with enableNetworkTracking=false**
    - Throws IllegalStateException with correct message

12. **Flush timer**
    - Confirm events flush at `config.flushIntervalMs`
    - Verify flush occurs even if batchSize not reached

## Files Modified

1. `TelemetryConfig.kt` - Complete refactor
2. `TelemetryManager.kt` - Major refactor with Stage 9 init sequence
3. `TelemetryActivityLifecycleObserver.kt` - Updated to emit screen_view events
4. `CrashReporter.kt` - Made installGlobalExceptionHandler() public
5. `TelemetryInterceptor.kt` - Updated to emit correct http_request events

## Breaking Changes

### TelemetryConfig
- **Removed:** `application: Application` parameter
- **Removed:** `debugMode`, `enableUserProfiles`, `enableSessionTracking`, `globalAttributes`, `enableLocationTracking`, and all legacy feature flags
- **Changed:** `batchSize` default from 30 to 50
- **Added:** `flushIntervalMs`, `sessionTimeoutMs`
- **Added:** `enableScreenTracking`, `enableNetworkTracking`, `enableLifecycleTracking`

### TelemetryManager.initialize()
- **New signature:** `initialize(application: Application, config: TelemetryConfig)`
- **Old signature:** Deprecated but still functional with limited parameters

### Network Interceptor
- **New:** Must use `getInterceptor()` instead of `createNetworkInterceptor()`
- **Behavior:** Throws exception if network tracking disabled

## Migration Guide

### From Legacy Config

**Before:**
```kotlin
val config = TelemetryConfig.builder(application, "edge_key")
    .debugMode(true)
    .batchSize(30)
    .enableCrashReporting(true)
    .build()

TelemetryManager.initialize(config)
```

**After:**
```kotlin
val config = TelemetryConfig(
    apiKey = "edge_key",
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
    batchSize = 50,
    enableCrashReporting = true
)

TelemetryManager.initialize(application, config)
```

### Network Interceptor

**Before:**
```kotlin
val interceptor = TelemetryManager.createNetworkInterceptor()
```

**After:**
```kotlin
val interceptor = TelemetryManager.getInstance().getInterceptor()
```

## Implementation Notes

1. **Pre-init queue** ensures no events are lost if SDK methods are called before initialization completes
2. **Flush timer** runs independently of batch size, ensuring events are sent even with low traffic
3. **Session timeout** logic properly handles app backgrounding and foregrounding
4. **Crash handler** maintains original behavior while adding telemetry
5. **All instrumentation** is opt-out by default (enabled unless explicitly disabled)

## Next Steps

1. Update integration tests to cover all 12 verification scenarios
2. Update SDK documentation with new initialization approach
3. Create migration guide for existing integrations
4. Add sample app demonstrating all automatic instrumentation features
5. Performance testing for pre-init queue under high load

## Status

✅ **Stage 9 Implementation Complete**

All required changes have been implemented according to the specification. The SDK now supports:
- Simplified TelemetryConfig
- Pre-init call queue with FIFO eviction
- Automatic screen tracking
- Automatic crash reporting
- Automatic app lifecycle tracking
- Network request tracking via interceptor
- Configurable flush timer
- Enforced 14-step initialization sequence
