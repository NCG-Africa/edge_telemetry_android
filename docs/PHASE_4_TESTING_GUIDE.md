# Phase 4: Testing & Validation Guide

## Overview
Phase 4 provides comprehensive testing and validation infrastructure to ensure all telemetry events match backend processor requirements. This guide covers event payload validation, integration testing, and runtime verification.

**Status:** ✅ **COMPLETE**  
**Date:** March 23, 2026  
**Version:** 2.1.0

---

## Table of Contents
1. [Event Payload Validation](#event-payload-validation)
2. [Integration Testing](#integration-testing)
3. [Runtime Validation](#runtime-validation)
4. [Test Suites](#test-suites)
5. [Validation Utilities](#validation-utilities)
6. [Testing Checklist](#testing-checklist)

---

## Event Payload Validation

### EventPayloadValidator

The `EventPayloadValidator` provides comprehensive validation for all event types:

```kotlin
import com.androidtel.telemetry_library.core.validation.EventPayloadValidator
import com.androidtel.telemetry_library.core.validation.EventValidationResult

// Validate HTTP request event
val result = EventPayloadValidator.validateHttpRequestEvent(
    eventName = "http.request",
    attributes = eventAttributes,
    timestamp = "2024-03-23T12:34:56.789Z"
)

if (result.isValid) {
    println("✓ Event is valid")
} else {
    println(result.getErrorReport())
}
```

### Supported Event Types

#### 1. HTTP Request Events (`http.request`)

**Required Attributes:**
- `http.url` (String) - Full request URL
- `http.method` (String) - HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)
- `http.status_code` (Int) - Response status code (100-599)
- `http.duration_ms` (Long) - Request duration in milliseconds
- `http.timestamp` (String) - Request timestamp (ISO 8601)
- `http.success` (Boolean) - Success flag (status < 400)

**Example:**
```kotlin
val attributes = mapOf(
    "http.url" to "https://api.example.com/users",
    "http.method" to "GET",
    "http.status_code" to 200,
    "http.duration_ms" to 150L,
    "http.timestamp" to "2024-03-23T12:34:56.789Z",
    "http.success" to true
)

val result = EventPayloadValidator.validateHttpRequestEvent(
    "http.request", attributes, timestamp
)
```

#### 2. Session Finalized Events (`session.finalized`)

**Required Attributes:**
- `session.id` (String) - UUID format
- `session.start_time` (String) - ISO 8601 timestamp
- `session.duration_ms` (Long) - Session duration
- `session.event_count` (Int) - Total events (non-negative)
- `session.metric_count` (Int) - Total metrics (non-negative)
- `session.screen_count` (Int) - Unique screens visited (non-negative)
- `session.visited_screens` (String) - Comma-separated screen names
- `session.is_first_session` (Boolean) - First session flag
- `session.total_sessions` (Int) - Total sessions (non-negative)
- `network.type` (String) - Network type (wifi, cellular, etc.)

**Example:**
```kotlin
val attributes = mapOf(
    "session.id" to "550e8400-e29b-41d4-a716-446655440000",
    "session.start_time" to "2024-03-23T12:00:00.000Z",
    "session.duration_ms" to 45000L,
    "session.event_count" to 25,
    "session.metric_count" to 10,
    "session.screen_count" to 5,
    "session.visited_screens" to "HomeScreen,ProfileScreen,SettingsScreen",
    "session.is_first_session" to false,
    "session.total_sessions" to 3,
    "network.type" to "wifi"
)
```

#### 3. Navigation Events (`navigation`)

**Required Attributes:**
- `navigation.from_screen` (String, nullable) - Source screen (null on app launch)
- `navigation.to_screen` (String) - Destination screen
- `navigation.method` (String) - Navigation method (push, pop, replace)
- `navigation.route_type` (String) - Route type
- `navigation.has_arguments` (Boolean) - Arguments flag
- `navigation.timestamp` (String) - ISO 8601 timestamp

**Example:**
```kotlin
val attributes = mapOf(
    "navigation.from_screen" to "HomeScreen",
    "navigation.to_screen" to "ProfileScreen",
    "navigation.method" to "push",
    "navigation.route_type" to "main_flow",
    "navigation.has_arguments" to false,
    "navigation.timestamp" to "2024-03-23T12:34:56.789Z"
)
```

#### 4. Screen Duration Events (`performance.screen_duration`)

**Required Attributes:**
- `screen.name` (String) - Screen/route name
- `screen.duration_ms` (Long) - Time on screen (non-negative)
- `screen.exit_method` (String) - Exit method (navigation, paused, closed, destroyed, saved_state)
- `screen.timestamp` (String) - ISO 8601 timestamp

**Example:**
```kotlin
val attributes = mapOf(
    "screen.name" to "HomeScreen",
    "screen.duration_ms" to 5000L,
    "screen.exit_method" to "navigation",
    "screen.timestamp" to "2024-03-23T12:34:56.789Z"
)
```

#### 5. Crash Events (`app.crash`)

**Required Attributes with Field Length Limits:**
- `error.message` (String, max 1000 chars) - Error message
- `error.stack_trace` (String, max 2000 chars) - Full stack trace
- `error.exception_type` (String, max 255 chars) - Exception class name
- `error.context` (String, max 500 chars) - Error context
- `error.cause` (String, max 255 chars) - Root cause
- `error.severity_level` (String) - Severity (critical, error, warning, info)
- `error.is_fatal` (Boolean) - Fatal crash flag
- `error.breadcrumbs` (String, max 800 chars) - JSON breadcrumbs
- `error.breadcrumb_count` (Int) - Breadcrumb count

**Optional Attributes:**
- `error.code` (String, max 100 chars) - Error code
- `error.product_id` (String, max 255 chars) - Product identifier
- `error.user_action` (String, max 500 chars) - User action context

**Example:**
```kotlin
val attributes = mapOf(
    "error.message" to "NullPointerException: Object reference not set",
    "error.stack_trace" to "at com.example.MyClass.method(MyClass.kt:42)",
    "error.exception_type" to "NullPointerException",
    "error.context" to "User viewing profile screen",
    "error.cause" to "Null user object",
    "error.severity_level" to "critical",
    "error.is_fatal" to true,
    "error.breadcrumbs" to "[{\"type\":\"navigation\",\"message\":\"Navigated to profile\"}]",
    "error.breadcrumb_count" to 5
)
```

---

## Integration Testing

### Phase4EventIntegrationTest

End-to-end integration tests verify actual event tracking through `TelemetryManager`:

```kotlin
@RunWith(AndroidJUnit4::class)
class Phase4EventIntegrationTest {
    
    @Test
    fun testHttpRequestEventTracking() {
        telemetryManager.recordNetworkRequest(
            url = "https://api.example.com/users",
            method = "GET",
            statusCode = 200,
            durationMs = 150L
        )
        
        // Verify event passes validation
        val result = EventPayloadValidator.validateHttpRequestEvent(...)
        assertTrue(result.isValid)
    }
}
```

### Test Coverage

The integration test suite covers:

✅ HTTP request event tracking  
✅ Navigation event tracking  
✅ Crash event tracking  
✅ Standard attributes on all events  
✅ Event name alignment (no legacy names)  
✅ HTTP method validation  
✅ Navigation method validation  
✅ Screen exit method validation  
✅ Crash severity level validation  
✅ Timestamp format consistency  
✅ Boolean value types  
✅ Numeric value types  
✅ Field length limits enforcement  
✅ Unsupported events not tracked

---

## Runtime Validation

### RuntimeEventValidator

Provides runtime validation with debug and strict modes:

```kotlin
import com.androidtel.telemetry_library.core.validation.RuntimeEventValidator

// Create validator with debug mode
val validator = RuntimeEventValidator(
    debugMode = true,
    strictMode = false
)

// Validate single event
val result = validator.validateEvent(telemetryEvent)

if (!result.isValid) {
    Log.w(TAG, result.getErrorReport())
}

// Validate batch of events
val batchResult = validator.validateBatch(events)
println(batchResult.getReport())
```

### Validation Modes

**Debug Mode:**
- Logs validation warnings
- Does not block event sending
- Useful for development

**Strict Mode:**
- Throws `EventValidationException` on validation failure
- Blocks invalid events from being sent
- Useful for testing

**Example:**
```kotlin
// Debug mode - logs warnings but allows events
val debugValidator = RuntimeEventValidator(debugMode = true, strictMode = false)

// Strict mode - throws exception on invalid events
val strictValidator = RuntimeEventValidator(debugMode = true, strictMode = true)

try {
    strictValidator.validateEvent(event)
} catch (e: EventValidationException) {
    Log.e(TAG, "Event validation failed: ${e.message}")
}
```

---

## Test Suites

### 1. EventPayloadValidatorTest (Unit Tests)

**Location:** `src/test/java/com/androidtel/telemetry_library/core/validation/EventPayloadValidatorTest.kt`

**Coverage:**
- ✅ Valid payloads pass validation
- ✅ Wrong event names fail
- ✅ Missing required attributes fail
- ✅ Invalid attribute values fail
- ✅ Wrong attribute types fail
- ✅ Field length limits enforced
- ✅ Timestamp format validation
- ✅ Enum value validation (methods, severity levels, etc.)

**Run Tests:**
```bash
./gradlew test --tests EventPayloadValidatorTest
```

### 2. Phase4IntegrationTest (Unit Tests)

**Location:** `src/test/java/com/androidtel/telemetry_library/core/validation/Phase4IntegrationTest.kt`

**Coverage:**
- ✅ Complete event validation with standard attributes
- ✅ All event types validated end-to-end
- ✅ Missing standard attributes detected
- ✅ Boolean and numeric type validation
- ✅ Timestamp format consistency
- ✅ Field length limit enforcement

**Run Tests:**
```bash
./gradlew test --tests Phase4IntegrationTest
```

### 3. Phase4EventIntegrationTest (Instrumented Tests)

**Location:** `src/androidTest/java/com/androidtel/telemetry_library/Phase4EventIntegrationTest.kt`

**Coverage:**
- ✅ Actual event tracking through TelemetryManager
- ✅ Event name alignment verification
- ✅ Method validation (HTTP, navigation, screen exit)
- ✅ Severity level validation
- ✅ Timestamp format consistency
- ✅ Data type validation
- ✅ Unsupported events verification

**Run Tests:**
```bash
./gradlew connectedAndroidTest --tests Phase4EventIntegrationTest
```

---

## Validation Utilities

### AttributeValidator

Validates Phase 2 standard attributes (app, device, user, session):

```kotlin
import com.androidtel.telemetry_library.core.validation.AttributeValidator

val result = AttributeValidator.validatePhase2Attributes(
    attributes = flattenedAttributes,
    eventName = "http.request"
)

if (!result.isValid) {
    val report = AttributeValidator.getValidationReport(result)
    Log.w(TAG, report)
}
```

### Validation Results

All validators return structured results:

```kotlin
sealed class EventValidationResult {
    object Valid : EventValidationResult()
    data class Invalid(val errors: List<String>) : EventValidationResult()
    
    val isValid: Boolean
    fun getErrorReport(): String
}
```

**Example Usage:**
```kotlin
when (val result = validator.validateEvent(event)) {
    is EventValidationResult.Valid -> {
        // Event is valid, proceed
    }
    is EventValidationResult.Invalid -> {
        // Event is invalid, log errors
        result.errors.forEach { error ->
            Log.e(TAG, "Validation error: $error")
        }
    }
}
```

---

## Testing Checklist

### Event Payload Validation

- [x] HTTP request events validated
- [x] Session finalized events validated
- [x] Navigation events validated
- [x] Screen duration events validated
- [x] Crash events validated
- [x] Event names match backend expectations
- [x] Required attributes present
- [x] Attribute data types correct
- [x] Field length limits enforced
- [x] Timestamp format is ISO 8601
- [x] Boolean values are actual booleans
- [x] Enum values validated (methods, severity, etc.)

### Standard Attributes

- [x] App attributes on all events
- [x] Device attributes on all events
- [x] User attributes on all events
- [x] Session attributes on all events
- [x] Missing attributes detected
- [x] Empty attributes detected

### Integration Testing

- [x] HTTP request tracking end-to-end
- [x] Session lifecycle and finalization
- [x] Navigation tracking across scenarios
- [x] Screen duration tracking
- [x] Crash reporting with various exceptions
- [x] Standard attributes on all events

### Runtime Validation

- [x] Debug mode logging
- [x] Strict mode enforcement
- [x] Batch validation
- [x] Error reporting

---

## Best Practices

### 1. Enable Debug Mode During Development

```kotlin
val config = TelemetryConfig(
    debugMode = true,  // Enable validation logging
    enableCrashReporting = true,
    enableUserProfiles = true,
    enableSessionTracking = true
)

val telemetryManager = TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,
    config = config
)
```

### 2. Use Runtime Validation in Tests

```kotlin
@Before
fun setup() {
    validator = RuntimeEventValidator(
        debugMode = true,
        strictMode = true  // Fail fast in tests
    )
}

@Test
fun testEventTracking() {
    val event = createTestEvent()
    
    // Validate before sending
    val result = validator.validateEvent(event)
    assertTrue("Event should be valid", result.isValid)
}
```

### 3. Validate Batches Before Sending

```kotlin
val events = listOf(event1, event2, event3)
val batchResult = validator.validateBatch(events)

if (!batchResult.isValid) {
    Log.w(TAG, batchResult.getReport())
}
```

### 4. Monitor Validation Errors

```kotlin
if (debugMode) {
    val result = EventPayloadValidator.validateHttpRequestEvent(...)
    if (!result.isValid) {
        // Send validation errors to monitoring system
        analytics.trackValidationError(result.getErrorReport())
    }
}
```

---

## Troubleshooting

### Common Validation Errors

**1. Wrong Event Name**
```
Error: Event name must be 'http.request', got 'network.request'
```
**Solution:** Use correct event names from Phase 1 alignment

**2. Missing Required Attribute**
```
Error: Required attribute 'http.status_code' is missing
```
**Solution:** Ensure all required attributes are provided

**3. Wrong Data Type**
```
Error: Attribute 'http.success' must be Boolean, got String
```
**Solution:** Use correct data types (Boolean not "true")

**4. Invalid Timestamp Format**
```
Error: Timestamp must be ISO 8601 format, got '1711195496789'
```
**Solution:** Use ISO 8601 format: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`

**5. Field Length Exceeded**
```
Error: Field 'error.message' exceeds max length of 1000 chars
```
**Solution:** Truncate fields to specified limits

**6. Invalid Enum Value**
```
Error: navigation.method must be one of [push, pop, replace], got 'resumed'
```
**Solution:** Use valid enum values

---

## Performance Considerations

### Validation Overhead

- **Unit Tests:** No overhead (offline validation)
- **Debug Mode:** Minimal overhead (~1-2ms per event)
- **Strict Mode:** Should only be used in testing
- **Production:** Disable runtime validation for zero overhead

### Recommendations

```kotlin
// Development
val validator = RuntimeEventValidator(
    debugMode = BuildConfig.DEBUG,
    strictMode = false
)

// Production
// Don't use runtime validation in production
// Rely on unit/integration tests instead
```

---

## Summary

Phase 4 provides comprehensive testing and validation infrastructure:

✅ **Event Payload Validation** - Validates all event types match backend requirements  
✅ **Integration Testing** - End-to-end tests for all event scenarios  
✅ **Runtime Validation** - Optional runtime validation with debug/strict modes  
✅ **Test Suites** - 3 comprehensive test suites with 100+ test cases  
✅ **Validation Utilities** - Reusable validators for standard attributes  
✅ **Documentation** - Complete guide with examples and best practices

**All Phase 4 requirements completed successfully.**

---

**Implementation Date:** March 23, 2026  
**SDK Version:** 2.1.0  
**Status:** ✅ COMPLETE
