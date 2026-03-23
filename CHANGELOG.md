# Changelog

All notable changes to the Edge Telemetry Android SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.2] - 2026-03-23

### 🐛 Critical Bug Fix

#### Telemetry Batch Payload Structure Corrected
- **Fixed**: Removed incorrect "data" wrapper object from telemetry batch payloads
- **Issue**: API was rejecting payloads with "missing_events_field" error because events array was nested inside a "data" object
- **Solution**: Flattened payload structure to send all fields at root level as required by API

#### Payload Structure Changes
**Before (Incorrect):**
```json
{
  "data": {
    "batch_size": 1,
    "device_id": "...",
    "events": [...],
    "timestamp": "...",
    "type": "batch"
  },
  "device_id": "...",
  "timestamp": "..."
}
```

**After (Correct):**
```json
{
  "type": "batch",
  "events": [...],
  "batch_size": 1,
  "timestamp": "...",
  "device_id": "...",
  "location": "..."
}
```

### 🔧 Technical Changes

#### Files Modified
- `TelemetryBatch.kt`: Reordered `TelemetryDataOut` fields to match API requirements
- `TelemetryHttpClient.kt`: Updated `toJson()` method to serialize flat structure
- `sample_telemetry_payload.json`: Updated to reflect correct payload structure

#### Impact
- **API Compatibility**: Payloads now accepted by API validator
- **No Breaking Changes**: Internal refactoring only, no API changes for SDK users
- **Backward Compatible**: Existing SDK integrations continue to work without modification

### ⚠️ Important Notes
- This fix resolves API rejection errors for telemetry batch submissions
- No code changes required for existing SDK users
- Update to v2.0.2 to ensure telemetry data is successfully transmitted

---

## [2.1.0] - 2026-03-18

### 💥 BREAKING CHANGES

#### Navigation Event Structure Completely Redesigned
- **🔴 CRITICAL**: Navigation event structure updated to align with backend Kafka processor and `rum_navigation_events` database schema
- **Event Name Change**: Navigation events now use `"navigation"` (previously `"navigation.route_change"`)
- **Field Name Changes**: `navigation.to` → `navigation.to_screen`, `navigation.type` → `navigation.route_type`
- **Method Values Updated**: Now uses `push`/`pop`/`replace` (previously `resumed`/`paused`/`navigation`)
- **Backend Compatibility**: New structure required for backend processor to correctly store navigation events
- **Migration Required**: No code changes needed - SDK handles everything automatically

#### New Required Navigation Fields
- **`navigation.from_screen`** (String, nullable): Source screen name for tracking user journey (null on app launch)
- **`navigation.to_screen`** (String, required): Destination screen name (renamed from `navigation.to`)
- **`navigation.method`** (String, required): Navigation action - MUST be `push`, `pop`, or `replace`
- **`navigation.route_type`** (String, optional): Route classification (e.g., `main_flow`, `modal`, `deeplink`)
- **`navigation.has_arguments`** (Boolean, optional): Whether navigation includes data/arguments
- **`navigation.timestamp`** (String, required): ISO 8601 formatted datetime

#### Navigation Method Values (Database Constraint)
- **✅ VALID**: `"push"` - Forward navigation to new screen
- **✅ VALID**: `"pop"` - Back navigation to previous screen
- **✅ VALID**: `"replace"` - Screen replacement
- **❌ REMOVED**: `"resumed"`, `"paused"`, `"navigation"`, `"closed"`, `"destroyed"` (lifecycle states)

**Impact**: Backend database has CHECK constraint requiring method to be push/pop/replace. Old values cause constraint violations.

#### Timestamp Format Change
- **Old Format**: `System.currentTimeMillis().toString()` → `"1710770400000"`
- **New Format**: `Instant.now().toString()` → `"2024-03-18T14:50:23.456Z"`
- **Impact**: Backend expects ISO 8601 format. Old format causes parsing errors.

### ✨ New Features

#### Navigation Stack Management
- **NavigationStackTracker**: Thread-safe navigation history tracking with ArrayDeque
- **Automatic Method Detection**: SDK automatically determines if navigation is push/pop/replace
- **From Screen Tracking**: Tracks previous screen for complete user journey analysis
- **Concurrent Access Safe**: ReentrantReadWriteLock ensures thread safety

#### Enhanced Route Classification
- **Auto-Detection**: Activities automatically classified as `main_flow` or `deeplink`
- **Deep Link Detection**: Automatically detects deep link navigation from Intent data
- **Fragment Classification**: All fragments classified as `fragment_flow`
- **Custom Route Types**: Compose navigation supports custom route types via `additionalData`

#### Argument Tracking
- **Intent Extras**: Automatically detects when Activities have intent extras
- **Fragment Arguments**: Tracks when Fragments have arguments bundle
- **Compose Arguments**: Detects navigation arguments in Compose routes
- **Deep Link Parameters**: Tracks deep link parameter presence

### 🔧 Technical Improvements

#### New Navigation Event Structure
```json
{
  "type": "event",
  "eventName": "navigation",
  "timestamp": "2024-03-18T14:50:23.456Z",
  "attributes": {
    "navigation.from_screen": "HomeScreen",
    "navigation.to_screen": "ProfileScreen",
    "navigation.method": "push",
    "navigation.route_type": "main_flow",
    "navigation.has_arguments": true,
    "navigation.timestamp": "2024-03-18T14:50:23.456Z"
  }
}
```

#### Core Components Added
- **NavigationStackTracker.kt**: Thread-safe navigation stack management
- **NavigationEvent.kt**: Data class for navigation event structure
- **NavigationMethod.kt**: Enum for push/pop/replace methods
- **Helper Methods**: `detectRouteType()`, `hasIntentExtras()` for Activity observers

#### Updated Components
- **TelemetryActivityLifecycleObserver.kt**: Updated with navigation stack tracking and proper field names
- **TelemetryFragmentLifecycleObserver.kt**: Updated with navigation tracking and argument detection
- **EdgeTelemetryCompose.kt**: Updated Compose tracking with proper navigation structure

### 📝 Migration Guide

#### For SDK Users (App Developers)

**Good News**: No code changes required! Just update the SDK version.

**Step 1: Update Dependency**
```kotlin
dependencies {
    // Old version
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.0.0'
    
    // New version
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.1.0'
}
```

**Step 2: Verify (Optional)**
Enable debug mode to see new navigation event structure:
```kotlin
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,
    debugMode = true  // See navigation events in logs
)
```

#### For Backend Teams

**Step 1: Update Kafka Processor**
Ensure processor handles new field names:
- `navigation.to_screen` (not `navigation.to`)
- `navigation.from_screen` (new field, can be null)
- `navigation.method` with values: `push`, `pop`, `replace`
- `navigation.route_type` (renamed from `navigation.type`)
- `navigation.has_arguments` (new field)

**Step 2: Verify Database Schema**
```sql
CREATE TABLE rum_navigation_events (
    from_screen VARCHAR(255),  -- Nullable
    to_screen VARCHAR(255) NOT NULL,
    navigation_method VARCHAR(20) NOT NULL 
        CHECK (navigation_method IN ('push', 'pop', 'replace')),
    route_type VARCHAR(100),
    has_arguments BOOLEAN DEFAULT FALSE,
    timestamp TIMESTAMP NOT NULL
);
```

### 🧪 Testing & Validation

#### Comprehensive Test Coverage (37+ Tests)
- **NavigationStackTrackerTest.kt**: 10 unit tests for stack management
- **ActivityNavigationIntegrationTest.kt**: 14 integration tests for Activity navigation
- **FragmentNavigationIntegrationTest.kt**: 14 integration tests for Fragment navigation
- **ComposeNavigationIntegrationTest.kt**: Tests for Compose navigation (Java 11+ only)

#### Test Scenarios Covered
- ✅ Navigation stack push/pop/replace operations
- ✅ From screen tracking (null on first launch)
- ✅ Navigation method detection (push/pop/replace)
- ✅ Route type classification (main_flow, deeplink, fragment_flow)
- ✅ Argument detection (intent extras, fragment arguments)
- ✅ ISO 8601 timestamp format validation
- ✅ Thread safety under concurrent access
- ✅ Kafka schema compliance validation

### 📊 Field Mapping Reference

| Old Field (v2.0.x) | New Field (v2.1.0) | Status |
|--------------------|--------------------|--------|
| `navigation.to` | `navigation.to_screen` | ✅ Renamed |
| N/A | `navigation.from_screen` | ✅ New |
| `navigation.method: "resumed"` | `navigation.method: "push"` | ✅ Updated |
| `navigation.type` | `navigation.route_type` | ✅ Renamed |
| N/A | `navigation.has_arguments` | ✅ New |
| `currentTimeMillis()` | ISO 8601 timestamp | ✅ Updated |
| `"navigation.route_change"` | `"navigation"` | ✅ Simplified |

### ⚠️ Important Notes

#### Backend Compatibility
- **Required**: Backend Kafka processor must support new navigation event structure
- **Database**: `rum_navigation_events` table must have correct schema with CHECK constraint
- **Testing**: Test integration in staging before production deployment
- **Monitoring**: Monitor navigation event processing logs during rollout

#### Breaking Change Impact
- **SDK Users**: No code changes needed - update dependency only
- **Backend Teams**: Must update Kafka processor and verify database schema
- **Old Events**: v2.0.x navigation events will be rejected by backend

#### Navigation Stack Behavior
- **First Launch**: `from_screen` is `null` on first app launch (expected)
- **Back Navigation**: SDK automatically detects back button and sets method to `pop`
- **Deep Links**: Automatically detected and classified as `deeplink` route type

### 🎯 Success Criteria

#### Phase 5 (Documentation) - ✅ COMPLETE
- [x] README.md updated with navigation tracking section
- [x] NAVIGATION_MIGRATION_GUIDE.md created
- [x] navigation_event_examples.md created with comprehensive examples
- [x] CHANGELOG.md updated with v2.1.0 breaking changes

#### Next Phase: Backend Integration Testing
- [ ] Test navigation events reach Kafka successfully
- [ ] Verify events stored in `rum_navigation_events` table
- [ ] Validate field mapping in database
- [ ] Test with real backend processor
- [ ] Performance testing (overhead measurement)

### 📊 Files Modified/Created

#### Core Implementation (Phases 1-3)
- `NavigationStackTracker.kt` - New navigation stack management
- `NavigationEvent.kt` - Navigation event data class
- `NavigationMethod.kt` - Navigation method enum
- `TelemetryActivityLifecycleObserver.kt` - Updated with navigation tracking
- `TelemetryFragmentLifecycleObserver.kt` - Updated with navigation tracking
- `EdgeTelemetryCompose.kt` - Updated Compose navigation tracking

#### Test Files (Phase 4)
- `NavigationStackTrackerTest.kt` - 10 unit tests
- `ActivityNavigationIntegrationTest.kt` - 14 integration tests
- `FragmentNavigationIntegrationTest.kt` - 14 integration tests

#### Documentation (Phase 5)
- `README.md` - Updated with comprehensive navigation tracking section
- `docs/NAVIGATION_MIGRATION_GUIDE.md` - Complete migration guide
- `docs/navigation_event_examples.md` - 13 detailed examples
- `CHANGELOG.md` - This changelog entry

### 🔗 Related Documentation

- [Navigation Migration Guide](docs/NAVIGATION_MIGRATION_GUIDE.md) - Detailed migration instructions
- [Navigation Event Examples](docs/navigation_event_examples.md) - Comprehensive examples
- [README.md](README.md#5-navigation-tracking) - Navigation tracking documentation

---

## [2.0.0] - 2025-03-18

### 💥 BREAKING CHANGES

#### Crash Payload Structure Completely Redesigned
- **🔴 CRITICAL**: Crash payload structure updated to match backend Kafka processor requirements
- **Event Type Change**: Crashes now sent as `type: "event"` with `eventName: "app.crash"` (previously `type: "error"`)
- **Batch Envelope**: All crash events wrapped in batch envelope structure with `tenant_id`, `location`, `batch_size`, and `events` array
- **Backend Compatibility**: New structure required for backend processor to correctly handle crash events
- **Migration Required**: Existing crash payloads from v1.x are incompatible with v2.0.0

#### New Required Crash Attributes
- **`message`** (String, max 1000 chars): Exception class name and message
- **`stacktrace`** (String, max 2000 chars): Full stack trace (renamed from `stackTrace`)
- **`exception_type`** (String, max 255 chars): Simple exception class name
- **`error_context`** (String, max 500 chars): Top stack frame location (e.g., "LoginActivity.onButtonClick")
- **`is_fatal`** (Boolean): Auto-detected based on exception type
- **`cause`** (String, max 255 chars): Root cause exception message

#### Optional Enhanced Context Attributes
- **`product_id`** (String, max 255 chars): Module/feature identification
- **`user_action`** (String, max 500 chars): Last user interaction before crash
- **`error_code`** (String, max 100 chars): App-specific error codes

#### Removed Fields (Backend Auto-Generates)
- **🗑️ REMOVED**: `crash.fingerprint` → Backend generates `crash_hash` via SHA-256
- **🗑️ REMOVED**: `crash.breadcrumb_count` → Backend auto-counts from context
- **✅ RETAINED**: `breadcrumbs` JSON array for richer debugging context

### ✨ New Features

#### Automatic Crash Classification
- **Fatal Detection**: Automatically determines if crash is fatal based on exception type
- **Exception Type Extraction**: Extracts simple class name from throwable
- **Error Context Extraction**: Parses top stack frame for method/class context
- **Root Cause Analysis**: Captures root cause exception message

#### Character Limit Enforcement
- **Automatic Truncation**: All crash attributes truncated to backend limits
- **No Data Loss**: Truncation logged for debugging purposes
- **Backend Compatibility**: Prevents field truncation errors in backend processor

#### Enhanced Context Tracking
- **Product Context API**: `setProductContext(productId)` for module tracking
- **User Action API**: `setLastUserAction(action)` for interaction tracking
- **Error Code Support**: Custom error codes in crash attributes
- **Context Persistence**: Product and user action context persisted across crashes

### 🔧 Technical Improvements

#### New Crash Event Structure
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

#### Field Mapping Logic
- **message**: `${throwable.javaClass.name}: ${throwable.message}`.take(1000)
- **stacktrace**: Full stack trace truncated to 2000 chars
- **exception_type**: `throwable.javaClass.simpleName`.take(255)
- **error_context**: Extracted from top stack frame, max 500 chars
- **is_fatal**: Based on exception type (OutOfMemoryError, StackOverflowError, etc.)
- **cause**: Root cause message truncated to 255 chars

#### Helper Functions Added
- `extractErrorContext(stackTrace)`: Extracts "ClassName.methodName" from stack
- `isFatalException(throwable)`: Determines if exception indicates app crash
- Character limit enforcement for all crash attributes

### 📝 Migration Guide

#### Update Crash Tracking Code

**Before (v1.x):**
```kotlin
// Crash automatically tracked with old structure
TelemetryManager.getInstance().trackError(exception, mapOf(
    "custom" to "value"
))
```

**After (v2.0.0):**
```kotlin
// Same API, enhanced with new optional context
TelemetryManager.getInstance().trackError(exception, mapOf(
    "error_code" to "AUTH_001",
    "product_id" to "authentication_module",
    "user_action" to "Clicked login button"
))

// Or use new context APIs
TelemetryManager.getInstance().setProductContext("authentication_module")
TelemetryManager.getInstance().setLastUserAction("Clicked login button")
TelemetryManager.getInstance().trackError(exception)
```

#### New TelemetryManager APIs
```kotlin
// Set product/module context for crashes
fun setProductContext(productId: String)

// Track last user action for crash context
fun setLastUserAction(action: String)

// Enhanced error tracking with context
fun trackError(
    error: Throwable,
    attributes: Map<String, String>? = null
)
```

#### Backend Requirements
- **Kafka Consumer**: Must support new crash event structure
- **Database Schema**: Must handle new crash attributes
- **Deduplication**: Uses backend-generated `crash_hash` instead of SDK fingerprint
- **Severity Classification**: Backend auto-classifies severity based on `exception_type`

### 🧪 Testing & Validation

#### Comprehensive Test Coverage (60+ Tests)
- **PayloadStructureValidationTest.kt**: 12 tests validating backend compatibility
- **CrashScenarioIntegrationTest.kt**: 17 scenario tests for various crash types
- **BackendCompatibilityTest.kt**: 11 tests ensuring Kafka processor compatibility
- **CrashBatchEnvelopeTest.kt**: 20 tests for batch structure validation

#### Test Scenarios Covered
- ✅ Crash event structure (`type: "event"`, `eventName: "app.crash"`)
- ✅ Batch envelope wrapping with all required fields
- ✅ Character limit enforcement and truncation
- ✅ Field naming alignment (`stacktrace` vs `stackTrace`)
- ✅ Fatal exception detection accuracy
- ✅ Error context extraction from stack traces
- ✅ Product context and user action tracking
- ✅ Error code support and validation

### ⚠️ Important Notes

#### Backend Compatibility
- **Required**: Backend Kafka processor must support new crash event structure
- **Validation**: Test with backend team before production deployment
- **Monitoring**: Monitor crash processing logs during rollout

#### Breaking Change Impact
- **Existing Deployments**: v1.x crash payloads will not be processed correctly
- **Version Bump**: Major version bump (v2.0.0) indicates breaking change
- **Migration Timeline**: Plan migration window with backend team

#### Character Truncation
- **Automatic**: All fields truncated to backend limits
- **Logging**: Truncation events logged for debugging
- **No Data Loss**: Most important crash info preserved within limits

### 🎯 Success Criteria

#### Phase 2 (Implementation) - ✅ COMPLETE
- [x] New crash event structure implemented
- [x] Batch envelope wrapping implemented
- [x] All required fields present and validated
- [x] Character limits enforced
- [x] Field naming aligned with backend
- [x] Helper functions implemented
- [x] Product context tracking
- [x] User action tracking
- [x] Error code support

#### Phase 3 (Testing) - ✅ COMPLETE
- [x] 60+ unit tests passing
- [x] 17 integration scenario tests passing
- [x] 11 backend compatibility tests passing
- [x] No field truncation errors
- [x] Payload structure validation complete

#### Phase 4 (Migration) - 🚧 IN PROGRESS
- [x] v2.0.0 version bump
- [x] Comprehensive changelog created
- [ ] Migration guide published
- [ ] Sample payloads updated
- [ ] README documentation updated
- [ ] Backend compatibility confirmed
- [ ] Production deployment

### 📊 Files Modified

#### Core Implementation
- `FlutterCompatiblePayload.kt` - New crash event models
- `CrashReporter.kt` - Updated payload creation logic
- `TelemetryManager.kt` - New context tracking APIs
- `FlutterPayloadFactory.kt` - Crash event factory methods

#### Test Files Created/Updated
- `PayloadStructureValidationTest.kt` - Backend schema validation
- `CrashScenarioIntegrationTest.kt` - Crash scenario testing
- `BackendCompatibilityTest.kt` - Kafka processor compatibility
- `CrashBatchEnvelopeTest.kt` - Batch structure validation

### 🔄 Upgrade Path

#### Step 1: Update Dependency
```gradle
dependencies {
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.0.0'
}
```

#### Step 2: Review Crash Tracking
- Existing `trackError()` calls continue to work
- Consider adding `product_id`, `user_action`, `error_code` for enhanced context

#### Step 3: Backend Coordination
- Coordinate with backend team for Kafka processor update
- Test crash event processing in staging environment
- Monitor crash deduplication and severity classification

#### Step 4: Validate
- Test crash reporting in staging
- Verify backend processes new crash structure
- Monitor crash analytics dashboards

### 🚀 What's Next

#### Phase 5: Documentation Updates
- Update README with new crash payload examples
- Update sample crash payload JSON
- Update usage examples with new APIs
- Publish comprehensive migration guide

---

## [1.2.8] - 2025-01-XX

### 🔒 Critical Security & Reliability Fixes

#### API Key Authentication Enhancements
- **CrashRetryManager Fix**: Fixed critical bug where crash retry requests bypassed API key authentication
- **Comprehensive Coverage**: API key now included in all network requests (regular batches, crash reports, offline retries, WorkManager jobs)
- **Validation Enhancement**: API key validation now enforces "edge_" prefix requirement
- **Error Messages**: Improved error messages guide developers to obtain API key from backend

#### Crash Reporting Reliability
- **Offline Retry Fixed**: Crash reports now properly authenticated during offline retry attempts
- **WorkManager Integration**: Background retry jobs now include API key in all requests
- **Endpoint Configuration**: Removed hardcoded endpoints from CrashRetryManager
- **Consistent Headers**: All crash-related requests include both `X-API-Key` and `User-Agent` headers

### ✨ New Features

#### TelemetryConfig Builder Pattern
- **Cleaner Initialization**: New builder pattern for type-safe, immutable configuration
- **Validation at Build Time**: Configuration validated when built, not at runtime
- **Fluent API**: Chainable methods for intuitive configuration
- **Backward Compatible**: Existing parameter-based initialization still fully supported

```kotlin
// New TelemetryConfig builder (recommended)
val config = TelemetryConfig.builder(application, apiKey)
    .batchSize(30)
    .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
    .debugMode(true)
    .enableCrashReporting(true)
    .enableUserProfiles(true)
    .enableSessionTracking(true)
    .globalAttributes(mapOf("key" to "value"))
    .build()

TelemetryManager.initialize(config)
```

### 🔧 Improvements

#### Security Enhancements
- **API Key Redaction**: API keys automatically redacted in debug logs (e.g., `edge_****_xyz1`)
- **ProGuard/R8 Rules**: Consumer rules added to protect API keys in release builds
- **Security Documentation**: Comprehensive security best practices added to README
- **BuildConfig Guidance**: Clear examples for secure API key storage

#### Testing & Quality
- **Unit Test Coverage**: Comprehensive tests for API key validation and authentication
- **Integration Tests**: Tests verify API key inclusion in all request types
- **EdgeTelemetryTester**: Updated testing utilities validate API key configuration
- **Mock Tests**: HTTP client tests verify header presence and correctness

#### Documentation Updates
- **README.md**: Updated with TelemetryConfig examples and enhanced troubleshooting
- **README_EDGE_TELEMETRY.md**: Added API key integration and security guidance
- **USAGE_EXAMPLE.kt**: Enhanced security comments and API_KEY_GUIDE.md reference
- **INTEGRATION_SUMMARY.md**: Added comprehensive migration guide for v1.2.6+ and v1.2.8+
- **API_KEY_GUIDE.md**: New comprehensive guide for API key management (see Task 5.3)

### 🐛 Bug Fixes

#### Critical Fixes
- **CrashRetryManager API Key**: Fixed missing API key in crash retry requests
- **Hardcoded Endpoints**: Removed hardcoded telemetry endpoint from CrashRetryManager
- **WorkManager Retry**: Fixed API key not being passed to WorkManager retry jobs
- **Offline Batch Sync**: Fixed API key missing from offline batch synchronization

#### Component Updates
- **CrashRetryManager**: Now accepts `apiKey` and `telemetryEndpoint` parameters
- **CrashReporter**: Updated to pass API key and endpoint to CrashRetryManager
- **TelemetryManager**: Enhanced Flutter components initialization with API key
- **TelemetryHttpClient**: Consistent API key header across all request types

### 📝 Migration Guide

#### From v1.2.7 to v1.2.8

**No Breaking Changes** - This is a patch release with bug fixes and enhancements.

**Recommended Actions:**
1. **Adopt TelemetryConfig** for cleaner initialization code (optional)
2. **Review Security Practices** using updated documentation
3. **Test Crash Reporting** to verify offline retry functionality
4. **Update Documentation** references if using custom integration guides

**Example Migration to TelemetryConfig:**
```kotlin
// Before (still works)
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,
    batchSize = 30,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
    debugMode = BuildConfig.DEBUG
)

// After (recommended)
val config = TelemetryConfig.builder(this, BuildConfig.TELEMETRY_API_KEY)
    .batchSize(30)
    .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
    .debugMode(BuildConfig.DEBUG)
    .build()

TelemetryManager.initialize(config)
```

### ⚠️ Important Notes

- **Backend Compatibility**: Ensure your backend validates `X-API-Key` header
- **Crash Retry**: Offline crash reports now properly authenticated
- **API Key Format**: Must start with "edge_" prefix
- **Security**: Never hardcode API keys - use BuildConfig or environment variables

### 📊 Technical Details

#### Files Modified
- `CrashRetryManager.kt` - Added API key and endpoint parameters
- `CrashReporter.kt` - Updated to pass configuration to CrashRetryManager
- `TelemetryManager.kt` - Enhanced initialization with API key validation
- `TelemetryConfig.kt` - New configuration builder class
- `TelemetryManagerTest.kt` - Added comprehensive API key tests

#### Test Coverage
- API key validation tests (blank, invalid format, valid)
- HTTP request header verification tests
- Crash retry API key inclusion tests
- WorkManager retry authentication tests
- TelemetryConfig builder validation tests

### 🎯 Success Metrics

- ✅ All crash reports include API key in `X-API-Key` header
- ✅ Offline crash retries properly authenticated
- ✅ WorkManager retry jobs include API key
- ✅ No hardcoded endpoints in codebase
- ✅ API key validation prevents blank/invalid keys
- ✅ Unit test coverage >80% for API key logic
- ✅ Documentation comprehensive and clear

---

## [1.2.7] - 2025-01-27

### 🔒 ID Generation Enhancements

#### Guaranteed Non-Null IDs
- **User ID Auto-Generation**: `getUserId()` now returns non-nullable `String` with automatic generation if not set
- **Device ID Validation**: All device ID generation includes validation to ensure non-empty values
- **Session ID Validation**: Session ID generation validates output is never blank
- **Type Safety**: Changed all ID getter methods from nullable to non-nullable return types

#### ID Persistence
- **Device ID**: Persisted permanently in SharedPreferences (`edge_telemetry_ids`)
- **User ID**: Dual persistence in both `IdGenerator` and `TelemetryManager` SharedPreferences
- **Session ID**: Ephemeral by design - new ID generated per session
- **Auto-Recovery**: IDs automatically generated and persisted on first access if missing

### 📊 Payload Structure Improvements

#### Top-Level Device ID
- **Enhanced Payload**: `device_id` now included at the **root level** of all payloads
- **Dual Presence**: Device ID present in both root and nested `data.device_id` for backward compatibility
- **Backend Benefits**: Easier filtering and routing without parsing nested event structures
- **Crash Payloads**: Crash reports now include top-level `device_id`
- **Event Batches**: All event batches include top-level `device_id`

#### Payload Structure
```json
{
  "timestamp": "2025-01-27T...",
  "device_id": "device_123...",  // NEW: Top-level
  "data": {
    "type": "batch",
    "device_id": "device_123...",  // Existing
    "events": [...]
  }
}
```

### 🛡️ Reliability Improvements

#### Validation at Generation
- **IllegalStateException**: ID generators throw exceptions if blank IDs are produced
- **Early Detection**: Problems caught at generation time, not at usage time
- **Clear Error Messages**: Descriptive error messages for debugging

#### Fallback Mechanisms
- **TelemetryManager Fallback**: Generates timestamped fallback if userId is blank
- **HTTP Client Fallback**: Uses `unknown_device` if device_id is null/blank in batch
- **Attribute Fallback**: Uses `unknown_user` if user_id is blank in attributes
- **Graceful Degradation**: SDK never crashes due to missing IDs

### 🔧 Technical Changes

#### API Changes
- `IdGenerator.getUserId()`: `String?` → `String` (non-nullable)
- `UserProfileManager.getUserId()`: `String?` → `String` (non-nullable)
- `TelemetryManager.getUserId()`: `String?` → `String` (non-nullable)
- `FlutterPayloadFactory.createCrashPayload()`: Added `deviceId: String` parameter
- `FlutterPayloadFactory.createEventBatchPayload()`: Added `deviceId: String` parameter

#### Data Models
- `TelemetryPayload`: Added `device_id: String` field
- `CrashPayload`: Added `device_id: String` field
- `EventBatchPayload`: Added `device_id: String` field

### ⚠️ Migration Notes

#### Breaking Changes
- **None**: All changes are backward compatible
- Existing code continues to work without modifications
- New top-level `device_id` is additive, not replacing existing structure

#### Recommendations
- Update backend to leverage top-level `device_id` for faster filtering
- Monitor logs for "CRITICAL ERROR" messages indicating ID generation issues
- Consider using new non-nullable ID getters for cleaner code

## [1.2.6] - 2025-01-27

### 💥 Breaking Changes

#### API Key Authentication Required
- **API Key Parameter**: `apiKey` is now a **required parameter** in `TelemetryManager.initialize()`
- **Security Enhancement**: All telemetry requests now include `X-API-Key` header for backend authentication
- **Migration Required**: Existing integrations must add `apiKey` parameter to initialization

### 🔒 Security Enhancements

#### API Key Authentication
- **Required Parameter**: `apiKey` must be provided during SDK initialization
- **Header-Based Auth**: API key sent via `X-API-Key` HTTP header on all requests
- **Secure Transmission**: API key never exposed in URLs or request bodies
- **Consistent Auth**: API key included in all retry attempts and offline batch sends

### 🛡️ Reliability Improvements

#### Critical ID Validation with Safe Error Handling
- **Device ID Protection**: Multiple validation layers ensure `device_id` is never null or empty
- **User ID Protection**: Comprehensive validation ensures `user_id` is always present
- **Graceful Degradation**: SDK uses fallback IDs instead of crashing the instrumented app
- **Clear Error Logging**: All validation failures logged with "CRITICAL ERROR" prefix for easy debugging

#### Fallback ID System
- **Emergency Fallbacks**: Automatic fallback IDs generated if primary generation fails
- **Identifiable Patterns**: Fallback IDs clearly marked (e.g., `device_fallback_`, `user_emergency_`)
- **Telemetry Continuity**: Data collection continues even with fallback IDs
- **Zero App Crashes**: SDK errors never crash the host application

### 📊 Payload Structure Updates

#### Enhanced JSON Response
- **Top-Level Device ID**: `device_id` now included at the root level of the `data` object
- **Consistent Structure**: Device ID present in both payload root and event attributes
- **Backend Optimization**: Easier device identification without parsing individual events

### 🔧 Technical Improvements

#### Safe Error Handling
- **No Exception Throwing**: Replaced `require()` and `check()` calls with safe logging
- **Fallback Values**: Uses `unknown_device`, `unknown_user`, or timestamped fallbacks
- **Production Ready**: SDK never crashes the instrumented application
- **Debug Friendly**: Clear error logs help identify and fix integration issues

#### Validation Layers
1. **Initialization Layer**: IDs validated when created/loaded from SharedPreferences
2. **Data Model Layer**: `userId` is non-nullable in `UserInfo` data class
3. **Payload Builder Layer**: Validation before creating `TelemetryDataOut`
4. **Attribute Flattening Layer**: Final validation when converting to JSON

### 📝 Migration Guide

#### Update Initialization Code

**Before (v1.2.1 and earlier):**
```kotlin
TelemetryManager.initialize(
    application = this,
    batchSize = 30,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
)
```

**After (v1.2.6+):**
```kotlin
TelemetryManager.initialize(
    application = this,
    apiKey = "your-api-key-here",  // ⚠️ REQUIRED
    batchSize = 30,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
)
```

### 🔍 What Changed

#### New Required Parameter
- `apiKey: String` - Required parameter for backend authentication

#### HTTP Request Headers
All telemetry requests now include:
```http
X-API-Key: your-api-key-here
Content-Type: application/json
User-Agent: EdgeTelemetryAndroid/1.0.0
```

#### JSON Payload Structure
```json
{
  "timestamp": "2025-01-27T13:42:10.123Z",
  "data": {
    "type": "batch",
    "device_id": "device_1727012247_ghi789_android",
    "events": [...],
    "batch_size": 7,
    "timestamp": "2025-01-27T13:42:10.123Z"
  }
}
```

### ⚠️ Important Notes

- **Backend Integration**: Your backend must validate the `X-API-Key` header
- **API Key Storage**: Store API keys securely (e.g., in BuildConfig or secure storage)
- **No Default Value**: `apiKey` has no default - must be explicitly provided
- **Backward Incompatible**: Apps using v1.2.1 or earlier must update initialization code

## [1.2.1] - 2025-01-20

### 🔧 Improved
- **Unified API**: Merged all Flutter-compatible functionality into single TelemetryManager
- **Single Initialization**: Eliminated dual initialization confusion - only `TelemetryManager.initialize()` needed
- **Enhanced Backward Compatibility**: All existing code works unchanged with new features available
- **Simplified Configuration**: Streamlined initialization parameters with sensible defaults
- **Better Documentation**: Updated README and usage examples for unified API

### 🧹 Removed
- **EdgeTelemetry Class**: Functionality merged into TelemetryManager to eliminate API duplication

### 📚 Updated
- **Documentation**: Complete rewrite of README with unified API examples
- **Usage Examples**: Updated all examples to use single TelemetryManager API
- **Integration Guide**: Added comprehensive integration summary

## [1.2.0] - 2025-01-16

### 💥 Breaking Changes

#### Minimum SDK Requirement Updated
- **Minimum SDK**: Raised from Android 5.0 (API 21) to Android 7.0 (API 24)
- **Target Audience**: Now supports Android 7.0+ through Android 14+ (API 24-35)
- **Compatibility Impact**: Drops support for Android 5.0-6.x devices (API 21-23)

### 🚀 Enhanced Features

#### Frame Metrics Collection Now Standard
- **Universal Frame Metrics**: All users now have access to precise FrameMetrics API-based performance tracking
- **Simplified Architecture**: Removed legacy Choreographer-based fallback implementation
- **Enhanced Performance Insights**: Consistent, detailed frame drop detection across all supported devices
- **Notification Features**: Notification importance levels now available for all users

### 🔧 Technical Improvements

#### Simplified Codebase
- **Reduced Complexity**: Eliminated conditional API 21-23 compatibility code paths
- **Performance Optimization**: Removed overhead from legacy device support
- **Enhanced Reliability**: Standardized on modern Android APIs for better stability

### 📊 Impact Summary

#### Positive Changes
- **Better Performance Data**: All users get detailed frame metrics instead of estimated values
- **Simplified Maintenance**: Reduced conditional code complexity
- **Enhanced User Experience**: More accurate performance insights for app optimization

#### Migration Notes
- **App Integration**: Apps using this SDK must also update their minSdk to 24 or higher
- **Device Coverage**: Verify your user base compatibility before upgrading
- **Feature Parity**: All existing telemetry features remain unchanged, just with enhanced capabilities

## [1.1.15] - 2025-01-11

### 🚀 Major Features

#### Enhanced Memory Tracking with API-Level Compatibility
- **Progressive Memory Monitoring**: Comprehensive memory tracking that adapts to device capabilities across Android API levels 21-35
- **Tiered Memory Collection**: 
  - **Tier 1 (API 21+)**: Basic heap memory tracking via Runtime
  - **Tier 2 (API 23+)**: System memory info via ActivityManager  
  - **Tier 3 (API 26+)**: Detailed memory breakdown via Debug.MemoryInfo
  - **Tier 4 (API 28+)**: Advanced memory classification and profiling
- **Enhanced Memory Pressure Detection**: Intelligent pressure calculation using system memory state, heap ratios, and memory class
- **Storage Usage Tracking**: Comprehensive storage monitoring with internal/external breakdown
- **Memory Event Standardization**: Consistent telemetry structure across all API levels with progressive enhancement

#### Unified Memory Tracking Architecture
- **MemoryTracker Interface**: Factory pattern for automatic implementation selection based on device capabilities
- **EnhancedMemoryTracker**: Uses MemoryCapabilityTracker for detailed insights on capable devices
- **BasicMemoryTracker**: Runtime-based fallback ensuring compatibility on all API levels
- **Graceful Degradation**: No crashes or missing functionality regardless of Android version

### 🔧 Enhanced Components

#### TelemetryMemoryUsage Class Overhaul
- **API-Aware Implementation**: Automatically leverages enhanced tracking when available
- **Consistent Event Structure**: Same core attributes across all implementations with API-specific enhancements
- **Enhanced Pressure Calculation**: Multi-factor analysis including heap usage ratio and system pressure
- **Storage Integration**: Added comprehensive storage usage monitoring
- **Error Resilience**: Robust fallback mechanisms prevent memory tracking failures

#### MemoryCapabilityTracker Integration
- **Comprehensive Memory Analysis**: Detailed memory breakdown with native heap, PSS, and system totals
- **Storage Information**: Internal/external storage tracking with capability awareness
- **Memory Pressure Detection**: Advanced pressure detection using system thresholds
- **Safe API Access**: Reflection-based access to deprecated fields with proper error handling

### 📊 New Telemetry Events

#### Enhanced Memory Events
```kotlin
eventName = "memory_pressure"
attributes = {
    "memory.pressure_level": String,        // "low", "moderate", "high"
    "memory.heap_used_mb": Long,           // Always present
    "memory.heap_max_mb": Long,            // Always present  
    "memory.system_available_mb": Long?,   // API 16+ when available
    "memory.native_heap_kb": Int?,         // API 26+ detailed breakdown
    "memory.total_pss_kb": Int?,           // Process memory usage
    "memory.tracking_method": String,      // "enhanced" or "basic"
    "memory.api_level": Int,               // Device API level
    "memory.under_system_pressure": Boolean // System-level pressure detection
}
```

#### Storage Usage Events
```kotlin
eventName = "storage_usage"
attributes = {
    "storage.internal_total_mb": Long,
    "storage.internal_free_mb": Long,
    "storage.internal_usable_mb": Long,
    "storage.external_total_mb": Long?,    // When available
    "storage.api_level": Int
}
```

### 🏗️ Architecture Improvements

#### Factory Pattern Implementation
- **MemoryTrackerFactory**: Automatic selection of appropriate memory tracker implementation
- **Runtime Capability Detection**: Uses existing DeviceCapabilities system for intelligent selection
- **Consistent Interface**: Unified API regardless of underlying implementation complexity
- **Performance Optimized**: Lazy initialization and efficient capability detection

#### TelemetryManager Integration
- **getMemoryCapabilityTracker()**: Safe accessor method for enhanced memory tracking
- **Capability Awareness**: Leverages existing runtime feature detection system
- **Memory Safety**: Proper initialization checks and null handling throughout

### 🔄 Performance Enhancements

#### Memory Tracking Optimizations
- **Sampling Control**: Configurable sampling rates to minimize overhead on legacy devices
- **Efficient Collection**: Optimized memory info gathering with minimal performance impact
- **Lazy Initialization**: Memory trackers created only when needed
- **Error Handling**: Comprehensive error handling prevents tracking failures

#### Enhanced Pressure Detection
- **Multi-Factor Analysis**: Considers heap usage ratio, system memory state, and memory class
- **System Integration**: Uses ActivityManager.MemoryInfo for accurate system-level pressure detection
- **Threshold Intelligence**: Dynamic thresholds based on device memory class and available memory

### 🛡️ Reliability & Compatibility

#### Graceful Degradation
- **No Breaking Changes**: Existing memory events maintain same structure with progressive enhancement
- **API Compatibility**: Works reliably on all Android versions from 5.0 (API 21) to latest
- **Fallback Mechanisms**: Automatic fallback to basic tracking when enhanced features unavailable
- **Error Resilience**: Robust error handling ensures memory tracking never fails completely

#### Memory Safety
- **Leak Prevention**: Proper cleanup and lifecycle management for all memory tracking components
- **Thread Safety**: Synchronized access to prevent concurrent modification issues
- **Resource Management**: Efficient resource usage with proper cleanup on component destruction

### 📈 Expected Behavior by API Level

#### Modern Devices (API 26+)
- Full memory breakdown with native heap, PSS, and system totals
- Advanced pressure detection using system thresholds  
- Storage tracking with internal/external breakdown
- Enhanced memory classification and profiling data

#### Mid-Range Devices (API 21-25)
- System memory info via ActivityManager
- Enhanced pressure detection with memory class consideration
- Basic storage tracking with capability awareness
- Graceful handling of unavailable advanced features

#### Legacy Fallback (All APIs)
- Runtime heap memory tracking guaranteed on all devices
- Basic pressure calculation using heap usage ratios
- Consistent event structure with core memory metrics
- No feature loss, only progressive enhancement

### 🔧 Developer Experience

#### Automatic Implementation Selection
- **Zero Configuration**: Memory tracking automatically adapts to device capabilities
- **Transparent Operation**: Developers don't need to handle API level differences
- **Consistent Events**: Same event names and core structure across all implementations
- **Clear Attribution**: Tracking method clearly indicated in telemetry events

#### Enhanced Logging
- **Capability Detection**: Clear logging of memory tracking capabilities at initialization
- **Implementation Selection**: Logs which memory tracker implementation is selected
- **Performance Metrics**: Detailed logging of memory usage and pressure levels
- **Error Reporting**: Comprehensive error logging with graceful degradation messages

### 📋 Technical Details

#### Memory Tracking Methods
- **Enhanced Tracking**: Uses MemoryCapabilityTracker for comprehensive analysis
- **Basic Tracking**: Runtime-based fallback for maximum compatibility
- **Progressive Enhancement**: Additional data collected where APIs support it
- **Consistent Structure**: Same core event format regardless of collection method

#### Storage Monitoring
- **Internal Storage**: Total, free, and usable space tracking
- **External Storage**: Optional external storage monitoring when available
- **Capability Aware**: Respects device storage capabilities and permissions
- **Error Handling**: Graceful handling of storage access issues

## [1.1.0] - 2024-12-15

### 🚀 Major Features

#### Extended Android Compatibility
- **Minimum SDK Lowered**: Now supports Android 5.0 (API 21) through Android 14 (API 35)
- **Runtime Feature Detection**: Comprehensive capability detection system for graceful degradation
- **Progressive Enhancement**: Enhanced features on newer devices, reliable basics on older devices

#### Conditional Frame Drop Collection with API-Level Fallbacks
- **Modern Implementation (API 24+)**: Native FrameMetrics API for precise frame drop detection
- **Legacy Implementation (API 21-23)**: Choreographer-based performance tracking with estimated metrics
- **Unified Interface**: Same telemetry events regardless of tracking method
- **Performance Optimized**: Sampling-based collection on older devices to minimize overhead

#### Device Capabilities System
- **DeviceCapabilities**: Central runtime detection of API levels, hardware features, and permissions
- **NetworkCapabilityDetector**: API-aware network state detection with modern and legacy fallbacks
- **MemoryCapabilityTracker**: Capability-aware memory tracking using appropriate APIs per Android version

### 🔧 Enhanced Components

#### Performance Tracking Architecture
- **PerformanceTracker Interface**: Unified performance tracking across all API levels
- **PerformanceTrackerFactory**: Automatic selection of appropriate implementation
- **ModernPerformanceTracker**: Wraps TelemetryFrameDropCollector for API 24+
- **LegacyPerformanceTracker**: Choreographer-based tracking for API 21-23

#### Frame Drop Collection Improvements
- **Runtime Capability Checks**: No more compile-time API restrictions
- **Error Handling**: Comprehensive error handling and logging
- **Memory Leak Prevention**: Proper cleanup with WeakReferences and synchronized methods
- **Graceful Degradation**: No crashes on unsupported API levels

### 🔄 Breaking Changes Avoided
- **No API Changes**: All existing telemetry functionality preserved
- **Same Event Structure**: Consistent telemetry event format across implementations
- **Backward Compatible**: Existing integrations continue to work without modification

### 🛡️ Reliability Improvements
- **Java 8 Time API Removal**: Replaced with System.currentTimeMillis() for API 21+ compatibility
- **Annotation Cleanup**: Removed @RequiresApi annotations that blocked older Android versions
- **Build Compatibility**: Library builds successfully with minSdk 21

### 📈 Performance Enhancements
- **Sampling Control**: 10% sampling rate on legacy devices to reduce overhead
- **Efficient Choreographer Usage**: Proper cleanup and memory management
- **Configurable Thresholds**: Performance classification with adjustable frame timing thresholds

## [1.0.15] - 2024-11-20

### 🐛 Bug Fixes
- **HTTP Retry Logic**: Fixed infinite loop in server error (5xx) retry handling
- **Crash Handler Performance**: Eliminated ANR-causing blocking operations during crash handling
- **Memory Leak Prevention**: Fixed frame metrics listener accumulation in TelemetryFrameDropCollector
- **ID Format Consistency**: Standardized device and session ID formats

### 🔧 Improvements
- **Network Resilience**: Proper exponential backoff for server error retries
- **Performance Optimization**: Crash handler execution time reduced to <100ms
- **Memory Management**: Proper cleanup of listeners and observers
- **User ID Management**: Automatic user ID generation with persistent storage

### 📊 Enhanced Telemetry
- **Consistent Event Structure**: Standardized telemetry event formats
- **Improved Error Handling**: Better error reporting and graceful degradation
- **Session Management**: Enhanced session tracking with proper ID generation

---

## Version History Summary

- **1.2.0**: Raised minimum SDK requirement from 21 to 24, simplified architecture, and enhanced performance insights
- **1.1.15**: Enhanced memory tracking with API-level compatibility and progressive enhancement
- **1.1.0**: Extended Android compatibility (API 21+) with runtime feature detection
- **1.0.15**: Bug fixes for HTTP retry logic, crash handler performance, and memory leaks

---

**For detailed technical documentation, see [README.md](README.md)**
