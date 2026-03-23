# Event Schema Reference

## Overview

This document provides a comprehensive reference for all telemetry events supported by the EdgeRum Android SDK. Each event type includes:

- Event name and purpose
- Required and optional attributes
- Data types and validation rules
- Field length limits (where applicable)
- JSON payload examples
- Backend compatibility notes

**SDK Version:** 2.1.0+  
**Last Updated:** March 23, 2026  
**Backend Alignment:** ✅ Complete

---

## Standard Attributes

All events automatically include these standard attributes:

### App Information (4 attributes)
| Attribute | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| `app.name` | String | Yes | Application name | `"MyApp"` |
| `app.version` | String | Yes | App version | `"1.2.3"` |
| `app.build_number` | String | Yes | Build number | `"123"` |
| `app.package_name` | String | Yes | Package identifier | `"com.example.myapp"` |

### Device Information (11 attributes)
| Attribute | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| `device.id` | String | Yes | Unique device identifier | `"device_1234567890_abc123_android"` |
| `device.platform` | String | Yes | Platform | `"android"` |
| `device.platform_version` | String | Yes | OS version | `"13.0"` |
| `device.model` | String | Yes | Device model | `"Pixel 7"` |
| `device.manufacturer` | String | Yes | Manufacturer | `"Google"` |
| `device.brand` | String | Yes | Brand name | `"google"` |
| `device.android_sdk` | String | Yes | Android SDK version | `"33"` |
| `device.android_release` | String | Yes | Android release | `"13"` |
| `device.fingerprint` | String | Yes | Device fingerprint | `"google/..."` |
| `device.hardware` | String | Yes | Hardware identifier | `"panther"` |
| `device.product` | String | Yes | Product name | `"panther"` |

### User & Session Information (3 attributes)
| Attribute | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| `user.id` | String | Yes | Unique user identifier | `"user_1234567890_xyz789"` |
| `session.id` | String | Yes | Current session ID | `"session_1234567890_abc123_android"` |
| `session.start_time` | String | Yes | Session start (ISO 8601) | `"2024-03-23T12:00:00.000Z"` |

---

## Event Types

### 1. HTTP Request Events

**Event Name:** `http.request`  
**Purpose:** Track HTTP/HTTPS network requests  
**Auto-Collected:** Yes (via `TelemetryInterceptor`)

#### Required Attributes

| Attribute | Type | Required | Validation | Description |
|-----------|------|----------|------------|-------------|
| `http.url` | String | Yes | Non-empty | Full request URL |
| `http.method` | String | Yes | GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS | HTTP method |
| `http.status_code` | Int | Yes | 100-599 | Response status code |
| `http.duration_ms` | Long | Yes | ≥ 0 | Request duration in milliseconds |
| `http.timestamp` | String | Yes | ISO 8601 | Request timestamp |
| `http.success` | Boolean | Yes | true/false | Success flag (status < 400) |

#### Optional Attributes

| Attribute | Type | Description |
|-----------|------|-------------|
| `http.request_body_size` | Long | Request body size in bytes |
| `http.response_body_size` | Long | Response body size in bytes |
| `http.error` | String | Error message if request failed |

#### Example Payload

```json
{
  "type": "event",
  "eventName": "http.request",
  "timestamp": "2024-03-23T14:30:45.123Z",
  "attributes": {
    "http.url": "https://api.example.com/users",
    "http.method": "GET",
    "http.status_code": 200,
    "http.duration_ms": 245,
    "http.timestamp": "2024-03-23T14:30:45.123Z",
    "http.success": true,
    "http.request_body_size": 0,
    "http.response_body_size": 1024,
    "app.name": "MyApp",
    "app.version": "1.2.3",
    "app.build_number": "123",
    "app.package_name": "com.example.myapp",
    "device.id": "device_1234567890_abc123_android",
    "device.platform": "android",
    "device.platform_version": "13.0",
    "device.model": "Pixel 7",
    "device.manufacturer": "Google",
    "device.brand": "google",
    "device.android_sdk": "33",
    "device.android_release": "13",
    "device.fingerprint": "google/panther/panther:13/...",
    "device.hardware": "panther",
    "device.product": "panther",
    "user.id": "user_1234567890_xyz789",
    "session.id": "session_1234567890_abc123_android",
    "session.start_time": "2024-03-23T12:00:00.000Z"
  }
}
```

#### Usage Example

```kotlin
// Automatic tracking via OkHttp interceptor
val client = OkHttpClient.Builder()
    .addInterceptor(TelemetryManager.createNetworkInterceptor())
    .build()

// Manual tracking
TelemetryManager.getInstance()?.recordNetworkRequest(
    url = "https://api.example.com/users",
    method = "GET",
    statusCode = 200,
    durationMs = 245L,
    requestBodySize = 0L,
    responseBodySize = 1024L
)
```

---

### 2. Session Finalized Events

**Event Name:** `session.finalized`  
**Purpose:** Track session end with comprehensive analytics  
**Auto-Collected:** Yes (on app background)

#### Required Attributes

| Attribute | Type | Required | Validation | Description |
|-----------|------|----------|------------|-------------|
| `session.id` | String | Yes | UUID format | Unique session identifier |
| `session.start_time` | String | Yes | ISO 8601 | Session start timestamp |
| `session.duration_ms` | Long | Yes | ≥ 0 | Total session duration |
| `session.event_count` | Int | Yes | ≥ 0 | Total events in session |
| `session.metric_count` | Int | Yes | ≥ 0 | Total metrics in session |
| `session.screen_count` | Int | Yes | ≥ 0 | Number of unique screens |
| `session.visited_screens` | String | Yes | Comma-separated | List of screen names |
| `session.is_first_session` | Boolean | Yes | true/false | First-time user flag |
| `session.total_sessions` | Int | Yes | ≥ 0 | Total sessions for user |
| `network.type` | String | Yes | Non-empty | Network type (wifi, cellular, etc.) |

#### Example Payload

```json
{
  "type": "event",
  "eventName": "session.finalized",
  "timestamp": "2024-03-23T14:45:30.456Z",
  "attributes": {
    "session.id": "session_1234567890_abc123_android",
    "session.start_time": "2024-03-23T12:00:00.000Z",
    "session.duration_ms": 9930456,
    "session.event_count": 45,
    "session.metric_count": 12,
    "session.screen_count": 5,
    "session.visited_screens": "HomeScreen,ProfileScreen,SettingsScreen,DetailScreen,CheckoutScreen",
    "session.is_first_session": false,
    "session.total_sessions": 23,
    "network.type": "wifi",
    "app.name": "MyApp",
    "app.version": "1.2.3",
    "user.id": "user_1234567890_xyz789",
    "device.id": "device_1234567890_abc123_android",
    "device.platform": "android"
  }
}
```

#### Usage

Session finalization is automatic - no code required. The SDK tracks:
- Session start when app enters foreground
- Session end when app enters background
- All session statistics automatically

---

### 3. Navigation Events

**Event Name:** `navigation`  
**Purpose:** Track screen navigation and user journey  
**Auto-Collected:** Yes (Activities, Fragments, Compose)

#### Required Attributes

| Attribute | Type | Required | Validation | Description |
|-----------|------|----------|------------|-------------|
| `navigation.from_screen` | String | No | Nullable on app launch | Source screen name |
| `navigation.to_screen` | String | Yes | Non-empty | Destination screen name |
| `navigation.method` | String | Yes | push, pop, replace | Navigation action |
| `navigation.route_type` | String | Yes | Non-empty | Route classification |
| `navigation.has_arguments` | Boolean | Yes | true/false | Arguments present flag |
| `navigation.timestamp` | String | Yes | ISO 8601 | Navigation timestamp |

#### Navigation Methods

| Method | Description | Example Scenario |
|--------|-------------|------------------|
| `push` | Forward navigation to new screen | User taps button to open profile |
| `pop` | Back navigation to previous screen | User presses back button |
| `replace` | Screen replacement | Login screen replaced by home screen |

#### Route Types

| Route Type | Description | Auto-Detected For |
|------------|-------------|-------------------|
| `main_flow` | Primary app navigation | Activities (default) |
| `fragment_flow` | Fragment-based navigation | Fragments |
| `compose_route` | Compose navigation | Compose screens |
| `deeplink` | Deep link navigation | Intent with data URI |
| `modal` | Modal/dialog screens | Custom (via additionalData) |
| `onboarding` | Onboarding flow | Custom (via additionalData) |
| `settings` | Settings screens | Custom (via additionalData) |

#### Example Payload

```json
{
  "type": "event",
  "eventName": "navigation",
  "timestamp": "2024-03-23T14:32:15.789Z",
  "attributes": {
    "navigation.from_screen": "HomeScreen",
    "navigation.to_screen": "ProfileScreen",
    "navigation.method": "push",
    "navigation.route_type": "main_flow",
    "navigation.has_arguments": true,
    "navigation.timestamp": "2024-03-23T14:32:15.789Z",
    "app.name": "MyApp",
    "user.id": "user_1234567890_xyz789",
    "session.id": "session_1234567890_abc123_android"
  }
}
```

#### Usage Examples

**Activities (Automatic):**
```kotlin
class ProfileActivity : AppCompatActivity() {
    // Navigation automatically tracked
}
```

**Fragments (Automatic):**
```kotlin
class ProfileFragment : Fragment() {
    // Navigation automatically tracked
}
```

**Compose (Manual Tracking):**
```kotlin
@Composable
fun ProfileScreen(navController: NavController) {
    TrackComposeScreen(
        navController = navController,
        screenName = "ProfileScreen"
    )
}
```

---

### 4. Screen Duration Events

**Event Name:** `performance.screen_duration`  
**Purpose:** Track time spent on each screen  
**Auto-Collected:** Yes (Activities, Fragments, Compose)

#### Required Attributes

| Attribute | Type | Required | Validation | Description |
|-----------|------|----------|------------|-------------|
| `screen.name` | String | Yes | Non-empty | Screen/route name |
| `screen.duration_ms` | Long | Yes | ≥ 0 | Time spent on screen |
| `screen.exit_method` | String | Yes | Enum (see below) | How user left screen |
| `screen.timestamp` | String | Yes | ISO 8601 | Screen exit timestamp |

#### Exit Methods

| Exit Method | Description | Example Scenario |
|-------------|-------------|------------------|
| `navigation` | User navigated to another screen | Tapped button to go to profile |
| `paused` | Screen paused but not destroyed | App went to background |
| `closed` | Screen closed/finished | User pressed back on Activity |
| `destroyed` | Screen destroyed by system | Low memory, configuration change |
| `saved_state` | State saved before destruction | Configuration change |

#### Example Payload

```json
{
  "type": "event",
  "eventName": "performance.screen_duration",
  "timestamp": "2024-03-23T14:35:20.123Z",
  "attributes": {
    "screen.name": "HomeScreen",
    "screen.duration_ms": 45678,
    "screen.exit_method": "navigation",
    "screen.timestamp": "2024-03-23T14:35:20.123Z",
    "metric.unit": "milliseconds",
    "app.name": "MyApp",
    "user.id": "user_1234567890_xyz789",
    "session.id": "session_1234567890_abc123_android"
  }
}
```

#### Usage

Screen duration tracking is automatic - no code required. The SDK tracks:
- Entry time when screen becomes visible
- Exit time when screen is left
- Duration calculation automatically
- Exit method detection automatically

---

### 5. Crash Events

**Event Name:** `app.crash`  
**Purpose:** Track application crashes with comprehensive context  
**Auto-Collected:** Yes (uncaught exceptions)

#### Required Attributes

| Attribute | Type | Required | Max Length | Validation | Description |
|-----------|------|----------|------------|------------|-------------|
| `error.message` | String | Yes | 1000 | Non-empty | Error message |
| `error.stack_trace` | String | Yes | 2000 | Non-empty | Full stack trace |
| `error.exception_type` | String | Yes | 255 | Non-empty | Exception class name |
| `error.context` | String | Yes | 500 | Non-empty | Error context/description |
| `error.cause` | String | Yes | 255 | Non-empty | Root cause description |
| `error.severity_level` | String | Yes | - | critical, error, warning, info | Severity level |
| `error.is_fatal` | Boolean | Yes | - | true/false | Fatal crash flag |
| `error.breadcrumbs` | String | Yes | 800 | JSON array | Breadcrumb trail |
| `error.breadcrumb_count` | Int | Yes | - | ≥ 0 | Number of breadcrumbs |

#### Optional Attributes

| Attribute | Type | Max Length | Description |
|-----------|------|------------|-------------|
| `error.code` | String | 100 | Error code if available |
| `error.product_id` | String | 255 | Product/module identifier |
| `error.user_action` | String | 500 | What user was doing |

#### Severity Levels

| Level | Description | Auto-Detected For |
|-------|-------------|-------------------|
| `critical` | System-critical errors | OutOfMemoryError, StackOverflowError |
| `error` | Standard exceptions | RuntimeException, IOException |
| `warning` | Non-critical issues | IllegalArgumentException |
| `info` | Informational | Custom exceptions |

#### Example Payload

```json
{
  "type": "event",
  "eventName": "app.crash",
  "timestamp": "2024-03-23T14:40:55.999Z",
  "attributes": {
    "error.message": "Unable to load user profile",
    "error.stack_trace": "java.lang.NullPointerException: Attempt to invoke virtual method...\n    at com.example.ProfileActivity.onCreate(ProfileActivity.kt:45)\n    at android.app.Activity.performCreate...",
    "error.exception_type": "java.lang.NullPointerException",
    "error.context": "ProfileActivity.onCreate - loading user data",
    "error.cause": "User object was null when accessing profile data",
    "error.severity_level": "error",
    "error.is_fatal": true,
    "error.breadcrumbs": "[{\"timestamp\":\"2024-03-23T14:40:50.000Z\",\"event\":\"navigation\",\"screen\":\"HomeScreen\"},{\"timestamp\":\"2024-03-23T14:40:53.000Z\",\"event\":\"button_click\",\"action\":\"view_profile\"}]",
    "error.breadcrumb_count": 2,
    "error.code": "PROFILE_LOAD_ERROR",
    "error.product_id": "user_profile_module",
    "error.user_action": "Viewing user profile",
    "app.name": "MyApp",
    "app.version": "1.2.3",
    "user.id": "user_1234567890_xyz789",
    "session.id": "session_1234567890_abc123_android",
    "device.model": "Pixel 7",
    "device.platform_version": "13.0"
  }
}
```

#### Usage Examples

**Automatic (Uncaught Exceptions):**
```kotlin
// SDK automatically catches and reports uncaught exceptions
// No code required
```

**Manual Crash Reporting:**
```kotlin
try {
    loadUserProfile()
} catch (e: Exception) {
    TelemetryManager.getInstance()?.recordCrash(
        throwable = e,
        isFatal = false,
        additionalContext = mapOf(
            "error.code" to "PROFILE_LOAD_ERROR",
            "error.product_id" to "user_profile_module",
            "error.user_action" to "Viewing user profile"
        )
    )
}
```

**Enhanced Context APIs:**
```kotlin
// Set product context for better crash categorization
TelemetryManager.getInstance()?.setProductContext("checkout_module")

// Set last user action for crash context
TelemetryManager.getInstance()?.setLastUserAction("Completing payment")

// Crashes will automatically include this context
```

---

## Unsupported Events

The following events are **NOT** processed by the backend and are disabled by default:

### Performance Events (Disabled)
- `frame_drop` - Frame rendering performance
- `performance.frame_summary` - Aggregated frame metrics
- `performance.compose` - Jetpack Compose performance

### System Resource Events (Disabled)
- `memory_pressure` - Memory usage tracking
- `storage_usage` - Device storage metrics

### Legacy Screen Events (Disabled)
- `navigation.screen_resume` - Screen lifecycle resume
- `navigation.screen_pause` - Screen lifecycle pause
- `screen.entry` - Screen entry tracking
- `screen.exit` - Screen exit tracking
- `screen.resume` - Screen resume lifecycle
- `screen.pause` - Screen pause lifecycle
- `screen_view` - Legacy screen view event

### User Interaction Events (Disabled)
- `user.interaction` - User interaction tracking

### Capability Events (Disabled)
- `telemetry.capabilities_initialized` - Device capability initialization

### Deprecated Events
- `app.error` - Use `app.crash` instead

### Enabling Unsupported Events

If you need specific unsupported events, enable them via configuration:

```kotlin
val config = TelemetryConfig.builder(application, apiKey)
    .enableMemoryTracking(true)      // Enable memory events
    .enableFrameTracking(true)       // Enable frame events
    .enableLegacyScreenEvents(true)  // Enable screen lifecycle events
    .enableUserInteractionEvents(true) // Enable user interactions
    .enableCapabilityEvents(true)    // Enable capability events
    .build()

TelemetryManager.initialize(config)
```

---

## Validation Rules

### Timestamp Format

All timestamps must use ISO 8601 format:

**Valid:**
- `2024-03-23T14:30:45.123Z`
- `2024-03-23T14:30:45Z`
- `2024-03-23T14:30:45.123456Z`

**Invalid:**
- `1711202445123` (Unix timestamp)
- `2024-03-23 14:30:45` (Missing T separator)
- `2024-03-23` (Date only)

### Field Length Limits

Crash event attributes have strict length limits:

| Attribute | Max Length | Truncation Behavior |
|-----------|------------|---------------------|
| `error.message` | 1000 chars | Auto-truncated with "..." |
| `error.stack_trace` | 2000 chars | Auto-truncated, preserves top frames |
| `error.exception_type` | 255 chars | Auto-truncated |
| `error.context` | 500 chars | Auto-truncated |
| `error.cause` | 255 chars | Auto-truncated |
| `error.breadcrumbs` | 800 chars | Auto-truncated JSON array |
| `error.code` | 100 chars | Auto-truncated |
| `error.product_id` | 255 chars | Auto-truncated |
| `error.user_action` | 500 chars | Auto-truncated |

### Enum Validations

**HTTP Methods:**
- GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS

**Navigation Methods:**
- push, pop, replace

**Screen Exit Methods:**
- navigation, paused, closed, destroyed, saved_state

**Severity Levels:**
- critical, error, warning, info

---

## Backend Compatibility

### Event Processing

All events in this schema are processed by the backend Kafka processor and stored in the appropriate database tables:

| Event Type | Kafka Topic | Database Table |
|------------|-------------|----------------|
| `http.request` | `rum_http_requests` | `rum_http_requests` |
| `session.finalized` | `rum_sessions` | `rum_sessions` |
| `navigation` | `rum_navigation_events` | `rum_navigation_events` |
| `performance.screen_duration` | `rum_screen_durations` | `rum_screen_durations` |
| `app.crash` | `rum_crashes` | `rum_crashes` |

### Schema Validation

The backend validates:
- Event names match exactly
- All required attributes present
- Data types correct
- Enum values valid
- Field lengths within limits
- Timestamp format correct

### Error Handling

If validation fails, the backend:
- Logs validation error
- Rejects the event
- Returns 400 Bad Request (for sync requests)
- Moves to dead letter queue (for async processing)

---

## Testing & Validation

### Runtime Validation

Enable runtime validation in debug builds:

```kotlin
import com.androidtel.telemetry_library.core.validation.RuntimeEventValidator

val validator = RuntimeEventValidator(
    debugMode = true,  // Log validation results
    strictMode = false // Don't throw exceptions
)

// Validate single event
val result = validator.validateEvent(telemetryEvent)
if (!result.isValid) {
    Log.w(TAG, result.getErrorReport())
}

// Validate batch
val batchResult = validator.validateBatch(events)
println(batchResult.getReport())
```

### Event Payload Validation

Validate event payloads programmatically:

```kotlin
import com.androidtel.telemetry_library.core.validation.EventPayloadValidator

// Validate HTTP request
val result = EventPayloadValidator.validateHttpRequestEvent(
    eventName = "http.request",
    attributes = attributes,
    timestamp = timestamp
)

// Validate navigation
val navResult = EventPayloadValidator.validateNavigationEvent(
    eventName = "navigation",
    attributes = attributes,
    timestamp = timestamp
)

// Check results
if (!result.isValid) {
    println(result.getErrorReport())
}
```

---

## Migration Notes

### From v2.0.x to v2.1.0

**Event Name Changes:**
- `network.request` → `http.request`
- `session_end` → `session.finalized`
- `navigation.route_change` → `navigation`

**Attribute Changes:**
- `device.os_version` → `device.platform_version`
- `device.api_level` → `device.android_sdk`
- `navigation.to` → `navigation.to_screen`
- `navigation.type` → `navigation.route_type`

**Migration:** Automatic - SDK handles all changes internally.

---

## Additional Resources

- [Phase 1 Migration Guide](PHASE_1_MIGRATION.md) - Event name alignment
- [Phase 2 Implementation](PHASE_2_IMPLEMENTATION.md) - Standard attributes
- [Phase 3 Summary](PHASE_3_SUMMARY.md) - Event cleanup
- [Phase 4 Testing Guide](PHASE_4_TESTING_GUIDE.md) - Validation and testing
- [API Key Guide](API_KEY_GUIDE.md) - Security best practices
- [Integration Summary](INTEGRATION_SUMMARY.md) - Integration details

---

**Document Version:** 1.0  
**SDK Version:** 2.1.0+  
**Last Updated:** March 23, 2026  
**Status:** Production Ready
