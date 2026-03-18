# Android SDK Crash Data Structure Review & Implementation Plan

## Executive Summary

This plan reviews the current Android SDK crash reporting implementation against the backend processor requirements (Kafka consumer) to identify gaps and create a phased implementation strategy.

---

## Phase 1: Gap Analysis & Validation

### 1.1 Current Implementation Analysis

**Current Crash Payload Structure:**
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
      "breadcrumbs": "[...]",
      // device, app, session, user attributes
    }
  }
}
```

**Backend Expected Structure (from your requirements):**
```json
{
  "data": {
    "tenant_id": "uuid-here",
    "location": "Kenya",
    "timestamp": "2024-03-18T13:30:00.000Z",
    "batch_size": 1,
    "events": [
      {
        "type": "event",
        "eventName": "app.crash",
        "timestamp": "2024-03-18T13:30:00.000Z",
        "attributes": {
          "message": "NullPointerException: ...",
          "stacktrace": "at com.example...",
          "exception_type": "NullPointerException",
          "error_context": "LoginActivity.onButtonClick",
          "product_id": "authentication_module",
          "cause": "User object was null",
          "is_fatal": true,
          "user_action": "Clicked login button",
          "error_code": "AUTH_001"
        }
      }
    ]
  }
}
```

### 1.2 Critical Gaps Identified

#### **Gap 1: Payload Structure Mismatch** 🔴 CRITICAL
- **Current**: Crash sent as standalone `type: "error"` payload
- **Required**: Crash sent as event with `type: "event"`, `eventName: "app.crash"` inside batch structure
- **Impact**: Backend processor expects batch envelope with events array
- **Status**: ❌ NOT COMPATIBLE

#### **Gap 2: Missing Required Fields** 🔴 CRITICAL
- **Current attributes**: `error`, `stackTrace`, `fingerprint`
- **Required attributes**: 
  - `message` (max 1000 chars) - ❌ MISSING
  - `stacktrace` (max 2000 chars) - ⚠️ NAMED `stackTrace` instead
  - `exception_type` (max 255 chars) - ❌ MISSING
  - `error_context` (max 500 chars) - ❌ MISSING
  - `product_id` (max 255 chars) - ❌ MISSING
  - `cause` (max 255 chars) - ❌ MISSING
  - `is_fatal` (boolean) - ❌ MISSING
  - `user_action` (max 500 chars) - ❌ MISSING
  - `error_code` (max 100 chars) - ❌ MISSING
- **Status**: ❌ INCOMPATIBLE

#### **Gap 3: Batch Envelope Missing** 🔴 CRITICAL
- **Current**: No `tenant_id`, `location`, `batch_size`, `events` array
- **Required**: Crash must be wrapped in batch envelope
- **Status**: ❌ MISSING

#### **Gap 4: Field Naming Inconsistency** 🟡 MEDIUM
- **Current**: `stackTrace` (camelCase)
- **Required**: `stacktrace` (lowercase)
- **Status**: ⚠️ NEEDS ALIGNMENT

#### **Gap 5: Auto-Generated Backend Fields** ✅ INFO
Backend processor auto-generates (SDK doesn't need to send):
- `crash_hash` - SHA-256 hash for deduplication
- `severity_level` - "high", "medium", "low" based on exception_type
- `breadcrumbs` - Built from context, product_id, cause
- `breadcrumb_count` - Number of breadcrumb parts

**Current SDK sends**: `crash.fingerprint`, `crash.breadcrumb_count`, `breadcrumbs` (JSON array)
**Status**: ⚠️ SDK duplicates backend logic - can be removed

#### **Gap 6: Character Limits Not Enforced** 🟡 MEDIUM
Backend enforces strict limits:
- `message`: 1000 chars (truncated)
- `stacktrace`: 2000 chars (truncated)
- `exception_type`: 255 chars
- `error_context`: 500 chars
- `product_id`: 255 chars
- `cause`: 255 chars
- `error_code`: 100 chars
- `user_action`: 500 chars

**Current SDK**: No truncation logic
**Status**: ⚠️ NEEDS IMPLEMENTATION

---

## Phase 2: Implementation Strategy

### 2.1 Backward Compatibility Considerations

**Decision Point**: Should we maintain backward compatibility with existing crash payloads?

**Options:**
1. **Breaking Change (Recommended)**: Migrate fully to new structure
   - ✅ Clean implementation
   - ✅ Matches backend exactly
   - ❌ Requires version bump (v2.0.0)
   - ❌ Existing deployments need update

2. **Dual Mode**: Support both old and new formats
   - ✅ Backward compatible
   - ❌ Complex maintenance
   - ❌ Technical debt

**Recommendation**: Breaking change with major version bump (v2.0.0)

### 2.2 Implementation Phases

#### **Phase 2A: Core Structure Changes** (Priority: CRITICAL)

**Tasks:**
1. Create new crash event structure matching backend requirements
2. Wrap crash events in batch envelope
3. Update `CrashReporter.kt` to use new structure
4. Update `FlutterCompatiblePayload.kt` with new models

**Files to Modify:**
- `FlutterCompatiblePayload.kt` - Add new crash event models
- `CrashReporter.kt` - Update payload creation logic
- `TelemetryManager.kt` - Ensure crash events use batch structure

**New Models Needed:**
```kotlin
data class CrashEventData(
    val type: String = "event",
    val eventName: String = "app.crash",
    val timestamp: String,
    val attributes: CrashAttributes
)

data class CrashAttributes(
    // Required fields
    val message: String,              // max 1000 chars
    val stacktrace: String,           // max 2000 chars
    val exception_type: String,       // max 255 chars
    
    // Optional but important
    val error_context: String? = null,    // max 500 chars
    val product_id: String? = null,       // max 255 chars
    val cause: String? = null,            // max 255 chars
    val is_fatal: Boolean = false,
    val user_action: String? = null,      // max 500 chars
    val error_code: String? = null,       // max 100 chars
    
    // Device/session/user context (existing)
    // ... all existing device attributes
)
```

#### **Phase 2B: Field Mapping & Truncation** (Priority: HIGH)

**Tasks:**
1. Implement character limit enforcement
2. Map existing fields to new structure
3. Extract `exception_type` from throwable
4. Generate `error_context` from stack trace
5. Determine `is_fatal` based on exception type

**Field Mapping Logic:**
```kotlin
// message: First 1000 chars of error message
message = "${throwable.javaClass.name}: ${throwable.message}".take(1000)

// stacktrace: First 2000 chars of stack trace
stacktrace = generateStackTrace(throwable).take(2000)

// exception_type: Simple class name
exception_type = throwable.javaClass.simpleName.take(255)

// error_context: Extract from top stack frame
error_context = extractErrorContext(stackTrace).take(500)

// is_fatal: Based on exception type
is_fatal = isFatalException(throwable)

// cause: Root cause message
cause = (throwable.cause?.message ?: "unknown").take(255)
```

**Helper Functions Needed:**
```kotlin
private fun extractErrorContext(stackTrace: String): String {
    // Extract "ClassName.methodName" from top stack frame
    // e.g., "LoginActivity.onButtonClick"
}

private fun isFatalException(throwable: Throwable): Boolean {
    // Check if exception type indicates app crash
    val fatalTypes = listOf(
        "OutOfMemoryError",
        "StackOverflowError",
        "FatalException",
        "RuntimeException",
        "IllegalStateException"
    )
    return fatalTypes.any { throwable.javaClass.simpleName.contains(it) }
}
```

#### **Phase 2C: Enhanced Context Collection** (Priority: MEDIUM)

**Tasks:**
1. Add `product_id` tracking (module/feature identification)
2. Add `user_action` tracking (last user interaction)
3. Add `error_code` support (app-specific error codes)

**Implementation:**
```kotlin
class CrashReporter {
    private var currentProductId: String? = null
    private var lastUserAction: String? = null
    
    fun setProductContext(productId: String) {
        this.currentProductId = productId
    }
    
    fun setLastUserAction(action: String) {
        this.lastUserAction = action.take(500)
    }
}
```

**Integration Points:**
- Activity lifecycle callbacks → set product_id
- Button clicks → set user_action
- Navigation events → set product_id and user_action

#### **Phase 2D: Remove Redundant Fields** (Priority: LOW)

**Tasks:**
1. Remove `crash.fingerprint` (backend generates `crash_hash`)
2. Remove `crash.breadcrumb_count` (backend generates)
3. Keep `breadcrumbs` JSON array (useful for backend)

**Rationale:**
- Backend auto-generates hash for deduplication
- Backend counts breadcrumbs from context/product_id/cause
- SDK breadcrumbs provide richer debugging context

---

## Phase 3: Testing & Validation

### 3.1 Unit Tests

**Test Cases:**
1. ✅ Crash event has correct structure (`type: "event"`, `eventName: "app.crash"`)
2. ✅ Crash wrapped in batch envelope
3. ✅ All required fields present
4. ✅ Character limits enforced
5. ✅ Field naming matches backend (`stacktrace` not `stackTrace`)
6. ✅ `is_fatal` correctly determined
7. ✅ `exception_type` extracted correctly
8. ✅ `error_context` extracted from stack trace

**Files to Create/Update:**
- `CrashReporterTest.kt` - Test new crash structure
- `PayloadStructureTest.kt` - Validate against backend schema

### 3.2 Integration Tests

**Test Scenarios:**
1. Trigger uncaught exception → verify payload structure
2. Manual error tracking → verify payload structure
3. Fatal vs non-fatal crashes → verify `is_fatal` flag
4. Long error messages → verify truncation
5. Deep stack traces → verify truncation

### 3.3 Backend Validation

**Validation Checklist:**
- [ ] Crash events processed by Kafka consumer
- [ ] `crash_hash` generated correctly
- [ ] `severity_level` classified correctly
- [ ] Crashes stored in database with correct schema
- [ ] No field truncation errors
- [ ] Deduplication working via `crash_hash`

---

## Phase 4: Migration & Rollout

### 4.1 Version Strategy

**Proposed Version**: `v2.0.0` (Breaking Change)

**Changelog:**
```markdown
### Version 2.0.0 (BREAKING CHANGE)
- 🔴 **BREAKING**: Crash payload structure updated to match backend processor
- ✨ **NEW**: Crash events now sent as `type: "event"`, `eventName: "app.crash"`
- ✨ **NEW**: Crash events wrapped in batch envelope structure
- ✨ **NEW**: Added required fields: `message`, `exception_type`, `error_context`, `is_fatal`, `cause`
- ✨ **NEW**: Character limit enforcement (message: 1000, stacktrace: 2000, etc.)
- ✨ **NEW**: Automatic `is_fatal` detection based on exception type
- ✨ **NEW**: Product context tracking (`product_id`)
- ✨ **NEW**: User action tracking (`user_action`)
- ✨ **NEW**: Error code support (`error_code`)
- 🔧 **IMPROVED**: Field naming aligned with backend (`stacktrace` vs `stackTrace`)
- 🗑️ **REMOVED**: `crash.fingerprint` (backend generates `crash_hash`)
- 🗑️ **REMOVED**: `crash.breadcrumb_count` (backend auto-counts)
- ⚠️ **MIGRATION**: Existing crash payloads incompatible - update to v2.0.0
```

### 4.2 Migration Guide

**For Existing Users:**
```kotlin
// OLD (v1.x)
TelemetryManager.getInstance().trackError(exception, mapOf(
    "custom" to "value"
))

// NEW (v2.0.0) - Same API, different payload structure
TelemetryManager.getInstance().trackError(exception, mapOf(
    "error_code" to "AUTH_001",
    "product_id" to "authentication_module",
    "user_action" to "Clicked login button"
))

// NEW - Set product context
TelemetryManager.getInstance().setProductContext("authentication_module")

// NEW - Track user actions
TelemetryManager.getInstance().setLastUserAction("Clicked login button")
```

### 4.3 Rollout Plan

**Phase 4A: Internal Testing** (Week 1)
- Deploy to test environment
- Validate backend processing
- Monitor Kafka consumer logs
- Verify database schema compatibility

**Phase 4B: Beta Release** (Week 2)
- Release v2.0.0-beta.1
- Deploy to staging environment
- Gather feedback from early adopters
- Fix any compatibility issues

**Phase 4C: Production Release** (Week 3)
- Release v2.0.0
- Update documentation
- Notify all users of breaking changes
- Provide migration guide

---

## Phase 5: Documentation Updates

### 5.1 README Updates

**Sections to Update:**
1. Crash Reporting section - show new payload structure
2. API Reference - document new methods
3. Migration guide - v1.x to v2.0.0
4. Backend compatibility - note Kafka processor requirements

### 5.2 Sample Payloads

**Update Files:**
- `sample_crash_payload.json` - Update to new structure
- `USAGE_EXAMPLE.kt` - Show new API usage
- `README_EDGE_TELEMETRY.md` - Update crash payload examples

### 5.3 API Documentation

**New Methods to Document:**
```kotlin
// Set product/module context for crashes
fun setProductContext(productId: String)

// Track last user action for crash context
fun setLastUserAction(action: String)

// Track error with enhanced context
fun trackError(
    error: Throwable,
    errorCode: String? = null,
    productId: String? = null,
    userAction: String? = null,
    attributes: Map<String, String>? = null
)
```

---

## Risk Assessment

### High Risk Items
1. **Breaking Change Impact**: Existing deployments will break
   - **Mitigation**: Clear migration guide, version bump to v2.0.0
   
2. **Backend Compatibility**: New structure must match processor exactly
   - **Mitigation**: Thorough testing with backend team

### Medium Risk Items
1. **Character Truncation**: May lose important debug info
   - **Mitigation**: Log warnings when truncation occurs
   
2. **Field Mapping**: Incorrect mapping breaks analytics
   - **Mitigation**: Comprehensive unit tests

### Low Risk Items
1. **Performance**: Additional field extraction overhead
   - **Mitigation**: Minimal - only on crash events

---

## Success Criteria

### Phase 1 (Gap Analysis)
- [x] All gaps documented
- [x] Impact assessment complete
- [x] Implementation strategy defined

### Phase 2 (Implementation)
- [x] Phase 2A: New crash event structure implemented
- [x] Phase 2A: Batch envelope wrapping implemented
- [x] Phase 2A: All required fields present
- [x] Phase 2B: Character limits enforced
- [x] Phase 2B: Field naming aligned with backend
- [x] Phase 2B: Helper functions implemented (extractErrorContext, isFatalException)
- [x] Phase 2B: Field mapping logic complete
- [x] Phase 2B: Exception type extraction
- [x] Phase 2B: Error context extraction
- [x] Phase 2B: Fatal exception determination
- [x] Phase 2C: user_action tracking implemented (max 500 chars)
- [x] Phase 2C: error_code support implemented (max 100 chars)
- [x] Phase 2C: product_id tracking implemented (max 255 chars)
- [x] Phase 2C: Context persistence and override logic
- [x] Phase 2C: TelemetryManager API exposure
- [x] Phase 2C: Comprehensive test coverage
- [x] Phase 2D: crash.fingerprint removed (backend generates crash_hash)
- [x] Phase 2D: crash.breadcrumb_count removed (backend auto-counts)
- [x] Phase 2D: breadcrumbs JSON array retained for debugging context

### Phase 3 (Testing)
- [ ] All unit tests passing
- [ ] Integration tests passing
- [ ] Backend validation complete
- [ ] No field truncation errors

### Phase 4 (Migration)
- [ ] v2.0.0 released
- [ ] Migration guide published
- [ ] Backend compatibility confirmed
- [ ] Zero production issues

### Phase 5 (Documentation)
- [ ] README updated
- [ ] Sample payloads updated
- [ ] API documentation complete
- [ ] Migration guide published

---

## Next Steps

1. **Review this plan** - Confirm approach and priorities
2. **Backend alignment** - Verify backend processor requirements
3. **Start Phase 2A** - Implement core structure changes
4. **Create feature branch** - `feature/crash-payload-v2`
5. **Incremental commits** - Small, testable changes

---

## Questions for Stakeholders

1. **Breaking Change Approval**: Confirm v2.0.0 breaking change is acceptable?
2. **Backward Compatibility**: Need to support v1.x payloads during transition?
3. **Timeline**: What's the target release date for v2.0.0?
4. **Backend Readiness**: Is Kafka processor ready to handle new structure?
5. **Tenant ID**: How should `tenant_id` be determined/configured?
6. **Location Field**: Should we include location in crash batch envelope?

---

## Appendix: Backend Processor Requirements Summary

### Required Crash Event Structure
```json
{
  "data": {
    "tenant_id": "uuid",
    "location": "Kenya",
    "timestamp": "ISO8601",
    "batch_size": 1,
    "events": [
      {
        "type": "event",
        "eventName": "app.crash",
        "timestamp": "ISO8601",
        "attributes": {
          "message": "string (max 1000)",
          "stacktrace": "string (max 2000)",
          "exception_type": "string (max 255)",
          "error_context": "string (max 500)",
          "product_id": "string (max 255)",
          "cause": "string (max 255)",
          "is_fatal": "boolean",
          "user_action": "string (max 500)",
          "error_code": "string (max 100)"
        }
      }
    ]
  }
}
```

### Backend Auto-Generated Fields
- `crash_hash` - SHA-256 (first 16 chars)
- `severity_level` - "high", "medium", "low"
- `breadcrumbs` - Built from context/product_id/cause
- `breadcrumb_count` - Integer count

### Severity Classification
- **High**: OutOfMemoryError, StackOverflowError, SecurityException, NullPointerException, IllegalStateException
- **Low**: IOException, NetworkException, TimeoutException, FileNotFoundException, ConnectException
- **Medium**: Everything else

### Fatal Classification
- Contains: OutOfMemoryError, StackOverflowError, FatalException, RuntimeException, IllegalStateException
- OR `is_fatal: true`
