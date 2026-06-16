# API Migration Guide - Test Updates

## Phase 1: Current API Investigation Results

### 1. IdGenerator API (Current)

**Location:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/ids/IdGenerator.kt`

#### Available Methods:
```kotlin
class IdGenerator {
    fun initialize(context: Context)
    fun getOrGenerateDeviceId(): String
    fun getDeviceId(): String
    fun generateSessionId(): String
    fun getUserId(): String  // Auto-generates and persists if not exists
}
```

#### ❌ REMOVED Methods (causing test failures):
- `generateUserId()` - **REMOVED**
- `setUserId(userId: String)` - **REMOVED**
- `clearUserId()` - **REMOVED**

#### ✅ Migration Path:
```kotlin
// OLD (broken):
val userId = idGenerator.generateUserId()
idGenerator.setUserId("custom_id")
idGenerator.clearUserId()

// NEW (working):
val userId = idGenerator.getUserId()  // Auto-generates and persists
// Note: No manual set/clear - user ID is auto-managed
```

---

### 2. TelemetryManager Initialization API (Current)

**Location:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt`

#### Current Initialization (Config-based):
```kotlin
fun initialize(application: Application, config: TelemetryConfig): TelemetryManager
```

#### Deprecated Initialization (Still available but deprecated):
```kotlin
@Deprecated("Use initialize(application, TelemetryConfig) instead")
fun initialize(
    application: Application,
    apiKey: String,
    batchSize: Int = 50,
    endpoint: String = "https://telemetry.ncgafrica.com/telemetry"
): TelemetryManager
```

#### ❌ REMOVED Pattern (causing test failures):
- **Builder pattern** - No longer exists
- No `TelemetryManager.builder()` method

#### ✅ Migration Path:
```kotlin
// OLD (broken):
val manager = TelemetryManager.builder()
    .setApiKey("edge_test_key")
    .setBatchSize(50)
    .build()
TelemetryManager.initialize(application)

// NEW (working):
val config = TelemetryConfig(
    apiKey = "edge_test_key",
    endpoint = "https://telemetry.ncgafrica.com/telemetry",
    batchSize = 50
)
val manager = TelemetryManager.initialize(application, config)
```

---

### 3. TelemetryConfig (Required for initialization)

**Location:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryConfig.kt`

#### Structure:
```kotlin
data class TelemetryConfig(
    val apiKey: String,                           // Required, must start with "edge_"
    val endpoint: String,                         // Required
    val batchSize: Int = 50,                      // Optional, default: 50
    val flushIntervalMs: Long = 30_000L,          // Optional, default: 30s
    val sessionTimeoutMs: Long = 30 * 60 * 1000L, // Optional, default: 30min
    val enableScreenTracking: Boolean = true,
    val enableCrashReporting: Boolean = true,
    val enableNetworkTracking: Boolean = true,
    val enableLifecycleTracking: Boolean = true,
    val enableMemoryTracking: Boolean = true,
    val enableStorageTracking: Boolean = true,
    val enableFrameTracking: Boolean = true,
    val enableLegacyScreenEvents: Boolean = false,
    val enableUserInteractionEvents: Boolean = true,
    val enableCapabilityEvents: Boolean = true
)
```

#### Validation Rules:
- `apiKey` must not be blank and must start with `"edge_"`
- `endpoint` must not be blank
- `batchSize`, `flushIntervalMs`, `sessionTimeoutMs` must be > 0

---

### 4. Event Recording API (Current)

**Location:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt`

#### Current Method:
```kotlin
fun recordEvent(
    eventName: String,
    attributes: Map<String, Any> = emptyMap()
)
```

#### Status:
✅ **Still available** - No changes needed for tests using `recordEvent()`

---

## Test Migration Summary

### Files Requiring Updates:

1. **IdGeneratorTest.kt** (12 errors)
   - Replace `generateUserId()` → `getUserId()`
   - Remove `setUserId()` calls (not supported)
   - Remove `clearUserId()` calls (not supported)

2. **IdConsistencyTest.kt** (1 error)
   - Replace `generateUserId()` → `getUserId()`

3. **TelemetryManagerTest.kt** (root) (9 errors)
   - Remove builder pattern usage
   - Use `TelemetryConfig` for initialization

4. **TelemetryManagerTest.kt** (core) (18 errors)
   - Remove builder pattern usage
   - Use `TelemetryConfig` for initialization

5. **TelemetryIdValidationTest.kt** (7 errors)
   - Update `initialize()` calls to use `TelemetryConfig`
   - Verify `recordEvent()` usage (should be fine)

6. **EnhancedCrashContextTest.kt** (3 errors)
   - Fix import issues
   - Update `initialize()` call to use `TelemetryConfig`

---

## Next Steps

Phase 1 ✅ **COMPLETE** - API investigation done

Proceed to Phase 2: Fix IdGenerator tests
