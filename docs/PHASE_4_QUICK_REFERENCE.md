# Phase 4: Quick Reference Guide

## Validation Quick Start

### 1. Validate HTTP Request Event
```kotlin
val result = EventPayloadValidator.validateHttpRequestEvent(
    eventName = "http.request",
    attributes = mapOf(
        "http.url" to "https://api.example.com/users",
        "http.method" to "GET",
        "http.status_code" to 200,
        "http.duration_ms" to 150L,
        "http.timestamp" to "2024-03-23T12:34:56.789Z",
        "http.success" to true
    ),
    timestamp = "2024-03-23T12:34:56.789Z"
)
```

### 2. Validate Session Finalized Event
```kotlin
val result = EventPayloadValidator.validateSessionFinalizedEvent(
    eventName = "session.finalized",
    attributes = mapOf(
        "session.id" to "550e8400-e29b-41d4-a716-446655440000",
        "session.start_time" to "2024-03-23T12:00:00.000Z",
        "session.duration_ms" to 45000L,
        "session.event_count" to 25,
        "session.metric_count" to 10,
        "session.screen_count" to 5,
        "session.visited_screens" to "HomeScreen,ProfileScreen",
        "session.is_first_session" to false,
        "session.total_sessions" to 3,
        "network.type" to "wifi"
    ),
    timestamp = "2024-03-23T12:34:56.789Z"
)
```

### 3. Validate Navigation Event
```kotlin
val result = EventPayloadValidator.validateNavigationEvent(
    eventName = "navigation",
    attributes = mapOf(
        "navigation.from_screen" to "HomeScreen",
        "navigation.to_screen" to "ProfileScreen",
        "navigation.method" to "push",
        "navigation.route_type" to "main_flow",
        "navigation.has_arguments" to false,
        "navigation.timestamp" to "2024-03-23T12:34:56.789Z"
    ),
    timestamp = "2024-03-23T12:34:56.789Z"
)
```

### 4. Runtime Validation
```kotlin
val validator = RuntimeEventValidator(debugMode = true, strictMode = false)
val result = validator.validateEvent(telemetryEvent)

if (!result.isValid) {
    Log.w(TAG, result.getErrorReport())
}
```

## Event Validation Rules

### HTTP Request (`http.request`)
| Attribute | Type | Required | Values |
|-----------|------|----------|--------|
| http.url | String | ✅ | Non-empty |
| http.method | String | ✅ | GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS |
| http.status_code | Int | ✅ | 100-599 |
| http.duration_ms | Long | ✅ | ≥ 0 |
| http.timestamp | String | ✅ | ISO 8601 |
| http.success | Boolean | ✅ | true/false |

### Session Finalized (`session.finalized`)
| Attribute | Type | Required | Values |
|-----------|------|----------|--------|
| session.id | String | ✅ | UUID |
| session.start_time | String | ✅ | ISO 8601 |
| session.duration_ms | Long | ✅ | ≥ 0 |
| session.event_count | Int | ✅ | ≥ 0 |
| session.metric_count | Int | ✅ | ≥ 0 |
| session.screen_count | Int | ✅ | ≥ 0 |
| session.visited_screens | String | ✅ | Comma-separated |
| session.is_first_session | Boolean | ✅ | true/false |
| session.total_sessions | Int | ✅ | ≥ 0 |
| network.type | String | ✅ | Non-empty |

### Navigation (`navigation`)
| Attribute | Type | Required | Values |
|-----------|------|----------|--------|
| navigation.from_screen | String | ❌ | Nullable |
| navigation.to_screen | String | ✅ | Non-empty |
| navigation.method | String | ✅ | push, pop, replace |
| navigation.route_type | String | ✅ | Non-empty |
| navigation.has_arguments | Boolean | ✅ | true/false |
| navigation.timestamp | String | ✅ | ISO 8601 |

### Screen Duration (`performance.screen_duration`)
| Attribute | Type | Required | Values |
|-----------|------|----------|--------|
| screen.name | String | ✅ | Non-empty |
| screen.duration_ms | Long | ✅ | ≥ 0 |
| screen.exit_method | String | ✅ | navigation, paused, closed, destroyed, saved_state |
| screen.timestamp | String | ✅ | ISO 8601 |

### Crash (`app.crash`)
| Attribute | Type | Required | Max Length |
|-----------|------|----------|------------|
| error.message | String | ✅ | 1000 |
| error.stack_trace | String | ✅ | 2000 |
| error.exception_type | String | ✅ | 255 |
| error.context | String | ✅ | 500 |
| error.cause | String | ✅ | 255 |
| error.severity_level | String | ✅ | critical, error, warning, info |
| error.is_fatal | Boolean | ✅ | true/false |
| error.breadcrumbs | String | ✅ | 800 |
| error.breadcrumb_count | Int | ✅ | ≥ 0 |
| error.code | String | ❌ | 100 |
| error.product_id | String | ❌ | 255 |
| error.user_action | String | ❌ | 500 |

## Run Tests

```bash
# Unit tests
./gradlew test --tests EventPayloadValidatorTest
./gradlew test --tests Phase4IntegrationTest

# Integration tests
./gradlew connectedAndroidTest --tests Phase4EventIntegrationTest

# All Phase 4 tests
./gradlew test --tests "*Phase4*"
./gradlew test --tests "*EventPayloadValidator*"
```

## Common Validation Errors

| Error | Solution |
|-------|----------|
| Wrong event name | Use correct event names: `http.request`, `session.finalized`, `navigation`, `performance.screen_duration`, `app.crash` |
| Missing attribute | Ensure all required attributes are present |
| Wrong data type | Use correct types: Boolean not "true", Int not "200" |
| Invalid timestamp | Use ISO 8601: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` |
| Field too long | Truncate to max length (see table above) |
| Invalid enum value | Use valid values (see tables above) |

## Standard Attributes (Required on ALL events)

### App (4 attributes)
- app.name
- app.version
- app.build_number
- app.package_name

### Device (11 attributes)
- device.id
- device.platform
- device.platform_version
- device.model
- device.manufacturer
- device.brand
- device.android_sdk
- device.android_release
- device.fingerprint
- device.hardware
- device.product

### User & Session (3 attributes)
- user.id
- session.id
- session.start_time

**Total: 18 required standard attributes on every event**
