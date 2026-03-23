# Migration Guide: v1.x to v2.0.0

## Overview

Version 2.0.0 introduces **breaking changes** to the crash reporting payload structure to align with backend Kafka processor requirements. This guide will help you migrate from v1.x to v2.0.0.

## Breaking Changes Summary

### 1. Crash Payload Structure Redesigned

**What Changed:**
- Crash events now sent as `type: "event"` with `eventName: "app.crash"` (previously `type: "error"`)
- All crash events wrapped in batch envelope structure
- New required crash attributes added
- Field naming standardized (`stacktrace` vs `stackTrace`)
- Removed SDK-generated fields that backend auto-generates

**Impact:**
- v1.x crash payloads are **incompatible** with v2.0.0 backend processor
- Backend must be updated to handle new crash event structure
- Crash deduplication now uses backend-generated `crash_hash` instead of SDK fingerprint

---

## Migration Steps

### Step 1: Update Dependency

Update your `build.gradle` or `build.gradle.kts` to use v2.0.0:

**Gradle (Groovy):**
```gradle
dependencies {
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.0.0'
}
```

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("com.github.NCG-Africa:edge_telemetry_android:2.0.0")
}
```

### Step 2: Review Crash Tracking Code

**Good News:** The public API remains the same! Existing `trackError()` calls continue to work.

**Before (v1.x):**
```kotlin
TelemetryManager.getInstance().trackError(exception, mapOf(
    "custom" to "value"
))
```

**After (v2.0.0) - Basic (No Changes Required):**
```kotlin
// Same code works - crash structure updated automatically
TelemetryManager.getInstance().trackError(exception, mapOf(
    "custom" to "value"
))
```

**After (v2.0.0) - Enhanced (Recommended):**
```kotlin
// Add enhanced context for better crash analytics
TelemetryManager.getInstance().trackError(exception, mapOf(
    "error_code" to "AUTH_001",
    "product_id" to "authentication_module",
    "user_action" to "Clicked login button"
))
```

### Step 3: Leverage New Context APIs (Optional)

v2.0.0 introduces new APIs for tracking product context and user actions:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set product/module context
        TelemetryManager.getInstance().setProductContext("authentication_module")
        
        // Track user actions
        loginButton.setOnClickListener {
            TelemetryManager.getInstance().setLastUserAction("Clicked login button")
            performLogin()
        }
    }
}
```

**Benefits:**
- Crashes automatically include product context
- Last user action captured for debugging
- Better crash analytics and grouping

### Step 4: Coordinate with Backend Team

**Critical:** Backend Kafka processor must be updated to handle new crash structure.

**Backend Requirements:**
- Support `type: "event"` with `eventName: "app.crash"`
- Handle batch envelope structure
- Process new crash attributes (`message`, `stacktrace`, `exception_type`, etc.)
- Generate `crash_hash` for deduplication
- Auto-classify `severity_level` based on `exception_type`

**Testing Checklist:**
- [ ] Backend processes new crash event structure
- [ ] Crash deduplication working via `crash_hash`
- [ ] Severity classification accurate
- [ ] Crashes stored in database correctly
- [ ] No field truncation errors
- [ ] Analytics dashboards display crash data

### Step 5: Test in Staging

Before production deployment, thoroughly test crash reporting:

```kotlin
// Test crash reporting in staging
class TestActivity : AppCompatActivity() {
    fun testCrashReporting() {
        try {
            // Simulate different crash scenarios
            throw NullPointerException("Test crash for v2.0.0 validation")
        } catch (e: Exception) {
            TelemetryManager.getInstance().trackError(e, mapOf(
                "error_code" to "TEST_001",
                "product_id" to "test_module",
                "user_action" to "Testing v2.0.0 crash reporting"
            ))
        }
    }
}
```

**Validation Steps:**
1. Trigger test crashes in staging app
2. Verify crashes appear in backend with new structure
3. Check crash deduplication works correctly
4. Validate severity classification
5. Confirm analytics dashboards show crash data

---

## New Crash Payload Structure

### v1.x Crash Payload (Old)

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "device_id": "device_xxx",
  "data": {
    "type": "error",
    "error": "java.lang.RuntimeException: Something went wrong",
    "timestamp": "2025-01-15T10:30:45.123Z",
    "stackTrace": "Full stack trace...",
    "fingerprint": "RuntimeException_-1234567890_987654321",
    "attributes": {
      "crash.fingerprint": "...",
      "crash.breadcrumb_count": "5",
      "error.timestamp": "...",
      "error.has_stack_trace": "true",
      "breadcrumbs": "[...]"
    }
  }
}
```

### v2.0.0 Crash Payload (New)

```json
{
  "timestamp": "2025-03-18T13:30:00.000Z",
  "device_id": "device_xxx",
  "data": {
    "tenant_id": "uuid-here",
    "location": "Kenya",
    "timestamp": "2025-03-18T13:30:00.000Z",
    "batch_size": 1,
    "events": [
      {
        "type": "event",
        "eventName": "app.crash",
        "timestamp": "2025-03-18T13:30:00.000Z",
        "attributes": {
          "message": "NullPointerException: User object was null",
          "stacktrace": "at com.example.LoginActivity.onButtonClick...",
          "exception_type": "NullPointerException",
          "error_context": "LoginActivity.onButtonClick",
          "is_fatal": true,
          "cause": "User object was null",
          "product_id": "authentication_module",
          "user_action": "Clicked login button",
          "error_code": "AUTH_001"
        }
      }
    ]
  }
}
```

---

## New Crash Attributes

### Required Attributes (Auto-Generated)

| Attribute | Type | Max Length | Description |
|-----------|------|------------|-------------|
| `message` | String | 1000 chars | Exception class name and message |
| `stacktrace` | String | 2000 chars | Full stack trace |
| `exception_type` | String | 255 chars | Simple exception class name |
| `error_context` | String | 500 chars | Top stack frame location |
| `is_fatal` | Boolean | - | Auto-detected based on exception type |
| `cause` | String | 255 chars | Root cause exception message |

### Optional Attributes (Developer-Provided)

| Attribute | Type | Max Length | Description |
|-----------|------|------------|-------------|
| `product_id` | String | 255 chars | Module/feature identification |
| `user_action` | String | 500 chars | Last user interaction |
| `error_code` | String | 100 chars | App-specific error codes |

### Removed Attributes

| Attribute | Reason | Backend Replacement |
|-----------|--------|---------------------|
| `crash.fingerprint` | Backend generates | `crash_hash` (SHA-256) |
| `crash.breadcrumb_count` | Backend auto-counts | Counted from context |
| `stackTrace` | Renamed | `stacktrace` (lowercase) |

---

## New TelemetryManager APIs

### setProductContext()

Set the current product/module context for crash tracking:

```kotlin
fun setProductContext(productId: String)
```

**Example:**
```kotlin
class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TelemetryManager.getInstance().setProductContext("authentication_module")
    }
}
```

### setLastUserAction()

Track the last user interaction before a crash:

```kotlin
fun setLastUserAction(action: String)
```

**Example:**
```kotlin
loginButton.setOnClickListener {
    TelemetryManager.getInstance().setLastUserAction("Clicked login button")
    performLogin()
}
```

### trackError() (Enhanced)

Track errors with enhanced context:

```kotlin
fun trackError(error: Throwable, attributes: Map<String, String>? = null)
```

**Example:**
```kotlin
try {
    performRiskyOperation()
} catch (e: Exception) {
    TelemetryManager.getInstance().trackError(e, mapOf(
        "error_code" to "OP_001",
        "product_id" to "payment_module",
        "user_action" to "Initiated payment"
    ))
}
```

---

## Character Limit Enforcement

v2.0.0 automatically truncates crash attributes to backend limits:

| Field | Limit | Behavior |
|-------|-------|----------|
| `message` | 1000 chars | Truncated with "..." |
| `stacktrace` | 2000 chars | Truncated with "..." |
| `exception_type` | 255 chars | Truncated |
| `error_context` | 500 chars | Truncated |
| `product_id` | 255 chars | Truncated |
| `cause` | 255 chars | Truncated |
| `user_action` | 500 chars | Truncated |
| `error_code` | 100 chars | Truncated |

**Logging:** Truncation events are logged for debugging purposes.

---

## Fatal Exception Detection

v2.0.0 automatically determines if a crash is fatal based on exception type:

**Fatal Exception Types:**
- `OutOfMemoryError`
- `StackOverflowError`
- `FatalException`
- `RuntimeException`
- `IllegalStateException`

**Non-Fatal Exception Types:**
- `IOException`
- `NetworkException`
- `TimeoutException`
- `FileNotFoundException`
- All other exceptions

**Override:** You can manually set `is_fatal` via attributes if needed.

---

## Backend Compatibility

### Kafka Consumer Requirements

The backend Kafka consumer must support:

1. **Batch Envelope Structure:**
   - `tenant_id` (UUID)
   - `location` (String)
   - `timestamp` (ISO8601)
   - `batch_size` (Integer)
   - `events` (Array)

2. **Crash Event Structure:**
   - `type: "event"`
   - `eventName: "app.crash"`
   - `timestamp` (ISO8601)
   - `attributes` (Object with crash fields)

3. **Auto-Generated Fields:**
   - `crash_hash`: SHA-256 hash (first 16 chars) for deduplication
   - `severity_level`: "high", "medium", "low" based on `exception_type`
   - `breadcrumbs`: Built from `error_context`, `product_id`, `cause`
   - `breadcrumb_count`: Integer count of breadcrumb parts

### Severity Classification

Backend auto-classifies severity:

- **High:** OutOfMemoryError, StackOverflowError, SecurityException, NullPointerException, IllegalStateException
- **Low:** IOException, NetworkException, TimeoutException, FileNotFoundException, ConnectException
- **Medium:** All other exceptions

---

## Rollback Plan

If issues arise during migration:

### Option 1: Rollback SDK Version

```gradle
dependencies {
    implementation 'com.github.NCG-Africa:edge_telemetry_android:1.2.8'
}
```

### Option 2: Backend Dual Support

Configure backend to support both v1.x and v2.0.0 crash structures during transition period.

---

## FAQ

### Q: Do I need to change my crash tracking code?

**A:** No, the public API remains the same. Existing `trackError()` calls work without changes. However, we recommend adding enhanced context using new attributes.

### Q: Will v1.x crash payloads work with v2.0.0?

**A:** No, v1.x crash payloads are incompatible with v2.0.0 backend processor. Backend must be updated to handle new structure.

### Q: What happens if I exceed character limits?

**A:** Fields are automatically truncated to backend limits. Truncation events are logged for debugging.

### Q: How do I test crash reporting after migration?

**A:** Trigger test crashes in staging environment and verify they appear in backend with new structure. Check crash deduplication and severity classification.

### Q: Can I use v2.0.0 without updating backend?

**A:** No, backend Kafka processor must be updated to support new crash event structure. Coordinate with backend team before deploying v2.0.0.

### Q: What if my backend doesn't support new structure yet?

**A:** Stay on v1.2.8 until backend is ready. Coordinate migration timeline with backend team.

---

## Support

For migration assistance:

1. **Documentation:** See [CHANGELOG.md](../CHANGELOG.md) for detailed changes
2. **Sample Payloads:** See [sample_crash_payload.json](../sample_crash_payload.json) for examples
3. **Usage Examples:** See [USAGE_EXAMPLE.kt](../USAGE_EXAMPLE.kt) for code samples
4. **Backend Team:** Coordinate with backend team for Kafka processor updates

---

## Timeline Recommendation

1. **Week 1:** Update backend Kafka processor to support v2.0.0 structure
2. **Week 2:** Test v2.0.0 in staging environment
3. **Week 3:** Deploy v2.0.0 to production with monitoring
4. **Week 4:** Validate crash analytics and deduplication

---

**Last Updated:** March 18, 2025  
**SDK Version:** 2.0.0
