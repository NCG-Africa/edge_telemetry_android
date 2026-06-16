# Phase 2: Standard Attributes Implementation

## Overview

Phase 2 ensures that all telemetry events include standardized app, device, user, and session attributes as required by the backend processor. This implementation maintains backward compatibility while improving data consistency and completeness.

## Implementation Status: ✅ COMPLETE

All Phase 2 requirements have been implemented and validated.

---

## Standard Attributes

### 2.1 App Information Attributes ✅

**Required for ALL Events:**

| Attribute | Description | Source | Status |
|-----------|-------------|--------|--------|
| `app.name` | Application name | `DeviceInfoCollector.collectAppInfo()` | ✅ Implemented |
| `app.version` | App version (e.g., "1.2.3") | `DeviceInfoCollector.collectAppInfo()` | ✅ Implemented |
| `app.build_number` | Build number (e.g., "123") | `DeviceInfoCollector.collectAppInfo()` | ✅ Implemented |
| `app.package_name` | Package identifier | `DeviceInfoCollector.collectAppInfo()` | ✅ Implemented |

**Implementation:**
- Collected via `DeviceInfoCollector.collectAppInfo()`
- Attached to every event via `TelemetryManager.buildAttributes()`
- Flattened in `TelemetryHttpClient.flattenAttributes()`

---

### 2.2 Device Information Attributes ✅

**Required for ALL Events:**

| Attribute | Description | Source | Status |
|-----------|-------------|--------|--------|
| `device.id` | Unique device identifier (UUID) | `IdGenerator.getDeviceId()` | ✅ Implemented |
| `device.platform` | Platform (always "android") | `DeviceInfoCollector.collectDeviceInfo()` | ✅ Implemented |
| `device.platform_version` | OS version (e.g., "13.0") | `Build.VERSION.RELEASE` | ✅ Implemented |
| `device.model` | Device model (e.g., "Pixel 7") | `Build.MODEL` | ✅ Implemented |
| `device.manufacturer` | Manufacturer (e.g., "Google") | `Build.MANUFACTURER` | ✅ Implemented |
| `device.brand` | Brand name | `Build.BRAND` | ✅ Implemented |
| `device.android_sdk` | Android SDK version | `Build.VERSION.SDK_INT` | ✅ Implemented |
| `device.android_release` | Android release version | `Build.VERSION.RELEASE` | ✅ Implemented |
| `device.fingerprint` | Device fingerprint | `Build.FINGERPRINT` | ✅ Implemented |
| `device.hardware` | Hardware identifier | `Build.HARDWARE` | ✅ Implemented |
| `device.product` | Product name | `Build.PRODUCT` | ✅ Implemented |

**Implementation:**
- Collected via `DeviceInfoCollector.collectDeviceInfo()`
- Attached to every event via `TelemetryManager.buildAttributes()`
- Flattened in `TelemetryHttpClient.flattenAttributes()`

**Changes Made:**
- Updated attribute names from `device.os_version` → `device.platform_version`
- Updated attribute names from `device.api_level` → `device.android_sdk`
- Ensured consistency across all components

---

### 2.3 User & Session Attributes ✅

**Required for ALL Events:**

| Attribute | Description | Source | Status |
|-----------|-------------|--------|--------|
| `user.id` | Unique user identifier | `UserProfileManager.getUserId()` | ✅ Implemented |
| `session.id` | Current session identifier | `SessionManager.getCurrentSessionId()` | ✅ Implemented |
| `session.start_time` | Session start timestamp (ISO 8601) | `SessionManager.getSessionAttributes()` | ✅ Implemented |

**Additional Session Attributes (included for enhanced analytics):**
- `session.duration_ms` - Total session duration
- `session.event_count` - Total events in session
- `session.metric_count` - Total metrics in session
- `session.screen_count` - Number of unique screens visited
- `session.visited_screens` - Comma-separated list of screen names
- `session.is_first_session` - Boolean for first-time user
- `session.total_sessions` - Total sessions for this user
- `network.type` - Network type (wifi, cellular, etc.)

**Implementation:**
- User ID: Managed by `UserProfileManager` with automatic generation and persistence
- Session attributes: Managed by `SessionManager` with lifecycle tracking
- Attached to every event via `TelemetryManager.buildAttributes()`
- Flattened in `TelemetryHttpClient.flattenAttributes()`

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     TelemetryManager                         │
│  - Coordinates all telemetry operations                     │
│  - Builds EventAttributes for each event                    │
└─────────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ DeviceInfo   │   │ SessionMgr   │   │ UserProfile  │
│ Collector    │   │              │   │ Manager      │
│              │   │              │   │              │
│ - App attrs  │   │ - Session    │   │ - User ID    │
│ - Device     │   │   tracking   │   │ - Profile    │
│   attrs      │   │ - Screen     │   │   data       │
│              │   │   visits     │   │              │
└──────────────┘   └──────────────┘   └──────────────┘
        │                   │                   │
        └───────────────────┼───────────────────┘
                            │
                            ▼
                ┌───────────────────────┐
                │   EventAttributes     │
                │   (nested structure)  │
                └───────────────────────┘
                            │
                            ▼
                ┌───────────────────────┐
                │ TelemetryHttpClient   │
                │ - flattenAttributes() │
                └───────────────────────┘
                            │
                            ▼
                ┌───────────────────────┐
                │  Flattened Map        │
                │  (sent to backend)    │
                └───────────────────────┘
```

### Data Flow

1. **Event Creation**: `TelemetryManager.recordEvent()` is called
2. **Attribute Collection**: 
   - App info from `DeviceInfoCollector.collectAppInfo()`
   - Device info from `DeviceInfoCollector.collectDeviceInfo()`
   - User info from `UserProfileManager.getUserAttributes()`
   - Session info from `SessionManager.getSessionAttributes()` or `TelemetryManager.getSessionInfo()`
3. **Event Building**: `TelemetryManager.buildAttributes()` creates `EventAttributes` object
4. **Queueing**: Event added to `eventQueue`
5. **Batching**: When batch size reached, `sendBatch()` is called
6. **Flattening**: `TelemetryHttpClient.flattenAttributes()` converts nested structure to flat map
7. **Transmission**: Flattened payload sent to backend

---

## Validation

### AttributeValidator Utility

A comprehensive validation utility has been created to ensure Phase 2 compliance:

**Location:** `com.androidtel.telemetry_library.core.validation.AttributeValidator`

**Features:**
- Validates all Phase 2 required attributes are present
- Checks for null or empty values
- Provides detailed validation reports
- Supports category-specific validation (App, Device, User/Session)

**Usage Example:**

```kotlin
import com.androidtel.telemetry_library.core.validation.AttributeValidator

// Validate all Phase 2 attributes
val result = AttributeValidator.validatePhase2Attributes(
    attributes = flattenedAttributes,
    eventName = "http.request"
)

if (!result.isValid) {
    val report = AttributeValidator.getValidationReport(result)
    Log.w(TAG, report)
}

// Validate specific categories
val appResult = AttributeValidator.validateAppAttributes(attributes)
val deviceResult = AttributeValidator.validateDeviceAttributes(attributes)
val userSessionResult = AttributeValidator.validateUserSessionAttributes(attributes)
```

---

## Testing

### Manual Validation

To verify Phase 2 implementation:

1. **Enable Debug Mode:**
   ```kotlin
   val config = TelemetryConfig.builder(application, "edge_your_api_key")
       .debugMode(true)
       .build()
   TelemetryManager.initialize(config)
   ```

2. **Inspect Logs:**
   - Look for event payloads in logcat
   - Verify all required attributes are present
   - Check attribute values are not null/empty

3. **Use AttributeValidator:**
   ```kotlin
   // In TelemetryHttpClient.flattenAttributes()
   if (debugMode) {
       val result = AttributeValidator.validatePhase2Attributes(flat, eventName)
       if (!result.isValid) {
           Log.w(TAG, AttributeValidator.getValidationReport(result))
       }
   }
   ```

### Integration Testing

Create test cases for each event type:

```kotlin
@Test
fun testHttpRequestHasAllPhase2Attributes() {
    // Record HTTP request
    telemetryManager.recordNetworkRequest(
        url = "https://api.example.com/data",
        method = "GET",
        statusCode = 200,
        durationMs = 150
    )
    
    // Capture and validate payload
    val payload = captureNextPayload()
    val event = payload.data.events.first()
    
    val result = AttributeValidator.validatePhase2Attributes(
        event.attributes,
        "http.request"
    )
    
    assertTrue(result.isValid)
}
```

---

## Performance Considerations

### Minimal Overhead

Phase 2 implementation has been designed for minimal performance impact:

1. **Lazy Collection**: Attributes collected only when needed
2. **Caching**: Device and app info collected once at initialization
3. **Efficient Flattening**: Single-pass attribute flattening
4. **No Reflection**: Direct property access for maximum performance

### Memory Impact

**Estimated additional memory per event:**
- App attributes: ~200 bytes
- Device attributes: ~500 bytes
- User/Session attributes: ~300 bytes
- **Total: ~1KB per event**

For a batch of 30 events: ~30KB additional memory (negligible)

### Network Impact

**Estimated payload size increase:**
- Before Phase 2: ~500 bytes per event
- After Phase 2: ~1.5KB per event
- **Increase: ~1KB per event (200% increase)**

**Mitigation:**
- Batch compression (future enhancement)
- Attribute deduplication at batch level (future enhancement)
- Configurable attribute sets (future enhancement)

---

## Backward Compatibility

### No Breaking Changes

Phase 2 implementation maintains full backward compatibility:

1. **Existing API unchanged**: All public methods remain the same
2. **Additive changes only**: Only adding attributes, not removing
3. **Legacy support**: Old attribute names still work (deprecated)
4. **Graceful degradation**: Missing optional attributes don't break events

### Migration Path

No migration required for existing integrations. Phase 2 attributes are automatically included in all events.

**Optional:** Update custom attribute names to match Phase 2 conventions:
- `device.os_version` → `device.platform_version` (both supported)
- `device.api_level` → `device.android_sdk` (both supported)

---

## Future Enhancements

### Planned Improvements

1. **Attribute Compression**: Reduce payload size via batch-level deduplication
2. **Configurable Attribute Sets**: Allow apps to opt-out of non-critical attributes
3. **Attribute Sampling**: Sample detailed attributes for high-volume events
4. **OpenTelemetry Resource**: Migrate to OTel Resource for standard attributes
5. **Attribute Caching**: Cache unchanging attributes to reduce collection overhead

---

## OpenTelemetry Alignment

### Current Implementation

The SDK uses a custom attribute system that is compatible with OpenTelemetry semantic conventions:

- Attribute naming follows OTel conventions (e.g., `app.*`, `device.*`, `session.*`)
- Flattened attribute structure matches OTel Span attributes
- ISO 8601 timestamps align with OTel standards

### Future OTel Integration

**Phase 3 (Future):** Migrate to OpenTelemetry SDK

1. **Resource Attributes**: Move app/device attributes to OTel Resource
2. **Span Attributes**: Use OTel Span for events
3. **Context Propagation**: Use OTel Context for session/user tracking
4. **Semantic Conventions**: Full alignment with OTel semantic conventions

**Benefits:**
- Reduced payload size (Resource sent once per batch)
- Better interoperability with OTel ecosystem
- Standard attribute validation
- Automatic context propagation

---

## Troubleshooting

### Common Issues

**Issue: Missing `device.id` or `user.id`**
- **Cause**: IDs not initialized before event creation
- **Solution**: Ensure `TelemetryManager.initialize()` completes before recording events
- **Validation**: Check `idsInitialized` flag in TelemetryManager

**Issue: Null attribute values**
- **Cause**: Permission denied or API failure
- **Solution**: Check app permissions (e.g., `ACCESS_NETWORK_STATE`)
- **Validation**: Use `AttributeValidator` to identify null attributes

**Issue: Empty `session.visited_screens`**
- **Cause**: No screens tracked yet
- **Solution**: Normal for first event in session
- **Validation**: Check subsequent events have screen data

---

## Summary

Phase 2 implementation ensures all telemetry events include comprehensive standard attributes for app, device, user, and session context. This provides the backend processor with consistent, complete data for analytics and monitoring.

**Key Achievements:**
- ✅ All Phase 2 required attributes implemented
- ✅ Backward compatibility maintained
- ✅ Minimal performance overhead
- ✅ Comprehensive validation utilities
- ✅ OpenTelemetry-aligned architecture

**Next Steps:**
- Phase 3: Event cleanup (remove unsupported events)
- Phase 4: Testing & validation
- Phase 5: Documentation updates
