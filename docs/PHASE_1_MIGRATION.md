# Phase 1 Migration Guide: Event Name Alignment

## Overview

Phase 1 of the EdgeRum SDK alignment updates event names and attributes to match backend processor requirements. This ensures proper event processing and analytics.

**Version:** 2.0.0+  
**Status:** ✅ Completed  
**Breaking Changes:** Yes - Event names and attribute structures have changed

---

## Summary of Changes

### 1. HTTP Request Events
**Event Name Changed:** `network.request` → `http.request`

#### Old Structure
```kotlin
// Event: network.request
attributes = {
    "url": "https://api.example.com/data",
    "method": "GET",
    "status_code": 200,
    "duration_ms": 1234
}
```

#### New Structure
```kotlin
// Event: http.request
attributes = {
    "http.url": "https://api.example.com/data",
    "http.method": "GET",
    "http.status_code": 200,
    "http.duration_ms": 1234,
    "http.timestamp": "2024-03-23T12:00:00Z",
    "http.success": true,  // Automatically calculated (status < 400)
    "http.request_body_size": 0,
    "http.response_body_size": 512,
    "http.error": "none"
}
```

**Required Attributes:**
- `http.url` - Full request URL
- `http.method` - HTTP method (GET, POST, PUT, DELETE, etc.)
- `http.status_code` - Response status code
- `http.duration_ms` - Request duration in milliseconds
- `http.timestamp` - Request timestamp (ISO 8601 format)
- `http.success` - Boolean (status < 400)

**Migration Impact:** None - SDK handles this automatically via `TelemetryInterceptor`

---

### 2. Session End Events
**Event Name Changed:** `session_end` → `session.finalized`

#### Old Structure
```kotlin
// Event: session_end
attributes = {
    "session_duration_ms": 123456
}
```

#### New Structure
```kotlin
// Event: session.finalized
attributes = {
    "session.id": "sess_abc123",
    "session.start_time": "2024-03-23T12:00:00Z",
    "session.duration_ms": 123456,
    "session.event_count": 45,
    "session.metric_count": 12,
    "session.screen_count": 5,
    "session.visited_screens": "HomeScreen,ProfileScreen,SettingsScreen",
    "session.is_first_session": false,
    "session.total_sessions": 10,
    "network.type": "wifi"
}
```

**Required Attributes:**
- `session.id` - Unique session identifier
- `session.start_time` - Session start timestamp (ISO 8601)
- `session.duration_ms` - Total session duration
- `session.event_count` - Total events in session
- `session.metric_count` - Total metrics in session
- `session.screen_count` - Number of unique screens visited
- `session.visited_screens` - Comma-separated list of screen names
- `session.is_first_session` - Boolean for first-time user
- `session.total_sessions` - Total sessions for this user
- `network.type` - Network type (wifi, cellular, etc.)

**Migration Impact:** None - SDK handles this automatically

---

### 3. Navigation Events
**Event Name Standardized:** `navigation.route_change` → `navigation`

#### Old Structure
```kotlin
// Event: navigation.route_change
telemetryManager.recordEvent("navigation.route_change", mapOf(
    "navigation.to" to screenRoute,
    "navigation.method" to "entered",
    "navigation.type" to "compose_route"
))
```

#### New Structure
```kotlin
// Event: navigation
telemetryManager.recordEvent("navigation", mapOf(
    "navigation.from_screen" to "HomeScreen",
    "navigation.to_screen" to "ProfileScreen",
    "navigation.method" to "push",
    "navigation.route_type" to "compose_route",
    "navigation.has_arguments" to false,
    "navigation.timestamp" to "2024-03-23T12:00:00Z"
))
```

**Required Attributes:**
- `navigation.from_screen` - Source screen name (nullable for app launch)
- `navigation.to_screen` - Destination screen name
- `navigation.method` - Navigation method (push, pop, replace, etc.)
- `navigation.route_type` - Route type (named, generated, compose_route, etc.)
- `navigation.has_arguments` - Boolean indicating if route has arguments
- `navigation.timestamp` - Navigation timestamp (ISO 8601)

**Migration Impact:** 
- Activities and Fragments: Automatic via lifecycle observers
- Compose: Automatic via `TrackComposeScreen` composable
- Manual tracking: Update event name from `navigation.route_change` to `navigation`

---

### 4. Screen Duration Events
**Event Name:** `performance.screen_duration` (unchanged)

#### Updated Structure
```kotlin
// Metric: performance.screen_duration
telemetryManager.recordMetric(
    metricName = "performance.screen_duration",
    value = 5432.0,
    attributes = mapOf(
        "screen.name" to "HomeScreen",
        "screen.duration_ms" to 5432,
        "screen.exit_method" to "navigation",
        "screen.timestamp" to "2024-03-23T12:00:00Z",
        "metric.unit" to "milliseconds"
    )
)
```

**Required Attributes:**
- `screen.name` - Screen/route name
- `screen.duration_ms` - Time spent on screen
- `screen.exit_method` - How user left screen (navigation, back, paused, closed, destroyed)
- `screen.timestamp` - Screen exit timestamp (ISO 8601)

**Migration Impact:** None - SDK handles this automatically

---

### 5. Crash Events
**Event Name:** `app.crash` (unchanged)

#### Enhanced Structure with Field Limits
```kotlin
// Event: app.crash
attributes = {
    "error.message": "java.lang.NullPointerException: ...",  // max 1000 chars
    "error.stack_trace": "at com.example...",                // max 2000 chars
    "error.exception_type": "NullPointerException",          // max 255 chars
    "error.context": "MainActivity.onCreate",                // max 500 chars
    "error.cause": "Variable was null",                      // max 255 chars
    "error.severity_level": "error",                         // critical, error, warning, info
    "error.is_fatal": true,
    "error.breadcrumbs": "[{...}]",                         // max 800 chars
    "error.breadcrumb_count": 5,
    "error.code": "ERR_001",                                // max 100 chars (optional)
    "error.product_id": "checkout_module",                  // max 255 chars (optional)
    "error.user_action": "Clicked checkout button"         // max 500 chars (optional)
}
```

**Required Attributes:**
- `error.message` - Error message (max 1000 chars)
- `error.stack_trace` - Full stack trace (max 2000 chars)
- `error.exception_type` - Exception class name (max 255 chars)
- `error.context` - Error context/description (max 500 chars)
- `error.cause` - Root cause description (max 255 chars)
- `error.severity_level` - Severity (critical, error, warning, info)
- `error.is_fatal` - Boolean indicating if crash is fatal
- `error.breadcrumbs` - JSON array of breadcrumbs (max 800 chars)
- `error.breadcrumb_count` - Number of breadcrumbs

**Optional Attributes:**
- `error.code` - Error code if available (max 100 chars)
- `error.product_id` - Product/module identifier (max 255 chars)
- `error.user_action` - What user was doing (max 500 chars)

**Migration Impact:** None - SDK handles this automatically

---

## Removed/Unsupported Events

The following events are **no longer tracked** as they are not processed by the backend:

### Lifecycle Events (Removed)
- ❌ `navigation.screen_resume`
- ❌ `navigation.screen_pause`
- ❌ `screen.entry`
- ❌ `screen.exit`
- ❌ `screen.resume`
- ❌ `screen.pause`

### Performance Events (Removed)
- ❌ `frame_drop`
- ❌ `performance.frame_summary`
- ❌ `performance.compose`

### System Events (Removed)
- ❌ `memory_pressure`
- ❌ `storage_usage`
- ❌ `telemetry.capabilities_initialized`

### Legacy Events (Removed)
- ❌ `screen_view` (use `navigation` instead)
- ❌ `app.error` (use `app.crash` instead)
- ❌ `user.interaction`

**Migration Impact:** These events are no longer emitted. If you were manually tracking these, they will be ignored by the backend.

---

## Migration Checklist

### For Existing Integrations

- [x] **HTTP Tracking:** No action needed - `TelemetryInterceptor` handles automatically
- [x] **Session Tracking:** No action needed - SDK handles automatically
- [x] **Navigation Tracking:** No action needed - Lifecycle observers handle automatically
- [x] **Screen Duration:** No action needed - SDK handles automatically
- [x] **Crash Reporting:** No action needed - SDK handles automatically

### For Custom Event Tracking

If you manually track events, update your code:

```kotlin
// OLD - Don't use
telemetryManager.recordEvent("navigation.route_change", attributes)
telemetryManager.recordEvent("session_end", attributes)
telemetryManager.recordEvent("network.request", attributes)

// NEW - Use instead
telemetryManager.recordEvent("navigation", attributes)
telemetryManager.recordEvent("session.finalized", attributes)
telemetryManager.recordEvent("http.request", attributes)
```

### For Error Tracking

```kotlin
// OLD - Don't use
telemetryManager.recordEvent("app.error", attributes)

// NEW - Use instead
telemetryManager.recordCrash(throwable)
// or
telemetryManager.recordError(throwable, attributes)
```

---

## Testing Your Migration

### 1. Verify HTTP Requests
```kotlin
// Make a network request and check logs
// Should see: "Event tracked: http.request"
```

### 2. Verify Session Finalization
```kotlin
// Put app in background and check logs
// Should see: "Event tracked: session.finalized"
```

### 3. Verify Navigation
```kotlin
// Navigate between screens and check logs
// Should see: "Event tracked: navigation"
```

### 4. Verify Crash Reporting
```kotlin
// Test crash reporting
telemetryManager.testCrashReporting("Test crash")
// Should see: "Event tracked: app.crash"
```

---

## Performance Impact

**No additional overhead** - All changes maintain or improve performance:

- ✅ Event batching unchanged
- ✅ Offline storage unchanged
- ✅ Network efficiency improved (better attribute structure)
- ✅ Field length limits prevent excessive payload sizes
- ✅ Removed unsupported events reduce unnecessary processing

---

## Backward Compatibility

**Breaking Changes:**
- Event names have changed (backend will not process old event names)
- Attribute structures have changed
- Some events are no longer tracked

**Non-Breaking:**
- SDK API remains the same
- No code changes required for standard usage
- Automatic migration for all built-in tracking

---

## Support

For issues or questions:
1. Check the [CHANGELOG.md](../CHANGELOG.md) for version-specific changes
2. Review [INTEGRATION_SUMMARY.md](./INTEGRATION_SUMMARY.md) for integration details
3. See [API_KEY_GUIDE.md](./API_KEY_GUIDE.md) for setup instructions

---

**Last Updated:** March 23, 2024  
**SDK Version:** 2.0.0+
