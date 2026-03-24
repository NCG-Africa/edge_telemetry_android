# Critical Bug Report: CrashRetryManager Offline Storage Corruption

**Date:** 2026-03-24 15:24:25  
**Severity:** HIGH  
**Status:** ACTIVE

## Problem Summary

The `CrashRetryManager` is experiencing a cascading failure loop caused by JSON corruption in the offline crash storage file. This creates an infinite error cycle where:

1. Crashes are reported
2. System attempts to store them offline
3. Loading existing crashes fails due to malformed JSON
4. The failure itself triggers more crash reports
5. Circuit breaker activates but errors continue
6. System becomes unstable with hundreds of concurrent error threads

## Error Patterns Observed

### Primary Errors

1. **JsonSyntaxException - Malformed JSON**
   ```
   com.google.gson.JsonSyntaxException: com.google.gson.stream.MalformedJsonException: 
   Use JsonReader.setLenient(true) to accept malformed JSON at line 1 column 3261 path $
   ```
   - Occurs at: `CrashRetryManager.kt:168` (loadOfflineCrashes)
   - Root cause: JSON file contains malformed data at various column positions (3261, 6519, 16293, etc.)

2. **NullPointerException**
   ```
   java.lang.NullPointerException: fromJson(...) must not be null
   at CrashRetryManager.loadOfflineCrashes(CrashRetryManager.kt:168)
   ```
   - Occurs when `gson.fromJson()` returns null
   - Current code assumes non-null result despite nullable return type

### Secondary Effects

- **Circuit breaker activation**: Multiple threads hit rate limits (58531ms, 58628ms, etc.)
- **Thread explosion**: 50+ concurrent threads attempting crash reporting
- **Storage corruption**: Offline crash count becomes unreliable (reports 1, 2, 3, 4, 7 crashes inconsistently)
- **Log spam**: Hundreds of duplicate crash reports in milliseconds

## Root Cause Analysis

### Issue 1: Concurrent Write Race Condition
**Location:** `CrashRetryManager.kt:147-162` (storeCrashOffline)

Multiple threads simultaneously:
1. Read the offline crashes file
2. Append new crash data
3. Write back to file

This creates a race condition where JSON becomes malformed due to interleaved writes.

**Evidence:**
- Multiple threads (6015-6065, 6015-6083, 6015-6100, etc.) all storing crashes simultaneously
- Inconsistent crash counts reported
- JSON corruption at varying positions

### Issue 2: Insufficient Error Handling
**Location:** `CrashRetryManager.kt:179`

```kotlin
val result = gson.fromJson<Array<Map<String, Any>>>(json, type)
result?.toList() ?: emptyList()
```

**Problems:**
- Assumes `fromJson` returns null on error, but it throws exceptions
- Exception handling deletes the file but doesn't prevent the crash loop
- No prevention of recursive crash reporting when crash storage itself fails

### Issue 3: File Lock Insufficient
**Location:** `CrashRetryManager.kt:167` (synchronized block)

The `synchronized(fileLock)` only synchronizes within the same process instance, not across:
- Multiple coroutine dispatchers
- Potential multi-process scenarios
- File system level operations

## Impact Assessment

### Immediate Impact
- ✗ Crash reporting system non-functional
- ✗ Application logs flooded with errors
- ✗ Performance degradation from thread explosion
- ✗ Circuit breaker constantly triggered
- ✗ Offline crash data lost/corrupted

### User Impact
- Crash data not being reliably stored or transmitted
- Potential app slowdown or ANR (Application Not Responding)
- Loss of critical debugging information

## Recommended Fixes

### Priority 1: Prevent Recursive Crash Reporting
```kotlin
private var isStoringCrash = AtomicBoolean(false)

private fun storeCrashOffline(crashData: Map<String, Any>) {
    if (isStoringCrash.getAndSet(true)) {
        Log.w(TAG, "Already storing crash, skipping to prevent recursion")
        return
    }
    try {
        // existing logic
    } finally {
        isStoringCrash.set(false)
    }
}
```

### Priority 2: Use File-Level Locking
Replace in-memory synchronization with proper file locking:
```kotlin
private fun storeCrashOffline(crashData: Map<String, Any>) {
    val lockFile = File(context.filesDir, "crash_storage.lock")
    RandomAccessFile(lockFile, "rw").use { raf ->
        raf.channel.lock().use { lock ->
            // perform file operations
        }
    }
}
```

### Priority 3: Robust JSON Parsing
```kotlin
private fun loadOfflineCrashes(): List<Map<String, Any>> {
    synchronized(fileLock) {
        return try {
            if (!offlineStorageFile.exists() || offlineStorageFile.length() == 0L) {
                return emptyList()
            }
            
            val json = offlineStorageFile.readText()
            if (json.isBlank()) {
                return emptyList()
            }
            
            // Add lenient parsing
            val gsonLenient = GsonBuilder().setLenient().create()
            val type = object : TypeToken<Array<Map<String, Any>>>() {}.type
            val result: Array<Map<String, Any>>? = gsonLenient.fromJson(json, type)
            
            result?.toList() ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON corrupted, attempting recovery", e)
            attemptJsonRecovery() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load offline crashes", e)
            safeDeleteCorruptedFile()
            emptyList()
        }
    }
}
```

### Priority 4: Add Crash Storage Circuit Breaker
Prevent crash storage from triggering when already in error state:
```kotlin
private val storageFailureCount = AtomicInteger(0)
private val MAX_STORAGE_FAILURES = 3

private fun storeCrashOffline(crashData: Map<String, Any>) {
    if (storageFailureCount.get() >= MAX_STORAGE_FAILURES) {
        Log.e(TAG, "Storage circuit breaker open, dropping crash data")
        return
    }
    // existing logic with failure tracking
}
```

## Testing Requirements

1. **Concurrency Test**: Simulate 50+ simultaneous crash reports
2. **Corruption Recovery Test**: Manually corrupt JSON file and verify recovery
3. **File Lock Test**: Verify exclusive access during write operations
4. **Circuit Breaker Test**: Verify system stops attempting storage after threshold
5. **Data Integrity Test**: Verify no crashes lost during normal operation

## Prevention Measures

1. Add unit tests for concurrent crash storage
2. Implement crash storage health monitoring
3. Add metrics for storage success/failure rates
4. Consider using SQLite instead of JSON file for better ACID guarantees
5. Implement exponential backoff for storage retries

## Next Steps

- [ ] Implement Priority 1 fix (prevent recursion)
- [ ] Implement Priority 2 fix (file-level locking)
- [ ] Implement Priority 3 fix (robust JSON parsing)
- [ ] Implement Priority 4 fix (storage circuit breaker)
- [ ] Add comprehensive tests
- [ ] Consider migration to SQLite for crash storage
- [ ] Add monitoring and alerting for storage failures

## Related Files

- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/retry/CrashRetryManager.kt:166-192`
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt:238`
