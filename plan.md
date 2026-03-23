# Test Failure Remediation Plan

## Problem Statement
Multiple test files have compilation errors due to API changes in the codebase. Tests are calling deprecated or removed methods, using old initialization patterns, and missing required parameters.

## Identified Failing Test Files

### 1. **IdGeneratorTest.kt** (12 errors)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/ids/IdGeneratorTest.kt`

**Issues:**
- `generateUserId()` - Method removed/renamed (9 occurrences)
- `setUserId()` - Method removed/renamed (2 occurrences)
- `clearUserId()` - Method removed/renamed (2 occurrences)
- Type inference failures (2 occurrences)

**Root Cause:** User ID generation methods were refactored or removed from IdGenerator API

---

### 2. **TelemetryManagerTest.kt** (Root) (9 errors)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/TelemetryManagerTest.kt`

**Issues:**
- `builder` - Unresolved reference (5 occurrences)
- Missing `config` parameter in `initialize()` calls (4 occurrences)

**Root Cause:** TelemetryManager initialization changed from builder pattern to config-based initialization

---

### 3. **TelemetryManagerTest.kt** (Core) (18 errors)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/TelemetryManagerTest.kt`

**Issues:**
- `builder` - Unresolved reference (13 occurrences)
- Missing `config` parameter in `initialize()` calls (13 occurrences)
- API mismatch (1 occurrence)

**Root Cause:** Same as root TelemetryManagerTest - builder pattern removed

---

### 4. **TelemetryIdValidationTest.kt** (7 errors)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/TelemetryIdValidationTest.kt`

**Issues:**
- `initialize()` API mismatch (4 occurrences)
- `recordEvent()` - Unresolved reference (1 occurrence)
- Type inference failure (1 occurrence)
- Receiver type mismatch (1 occurrence)

**Root Cause:** Event recording API changed, initialization API changed

---

### 5. **EnhancedCrashContextTest.kt** (3 errors)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/crash/EnhancedCrashContextTest.kt`

**Issues:**
- `ApplicationProvider` - Unresolved reference (1 occurrence)
- Import error for `core` (1 occurrence)
- `initialize()` API mismatch (1 occurrence)

**Root Cause:** Missing test dependency or wrong import path

---

### 6. **IdConsistencyTest.kt** (1 error)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/ids/IdConsistencyTest.kt`

**Issues:**
- `generateUserId()` - Unresolved reference (1 occurrence)

**Root Cause:** Same as IdGeneratorTest - user ID API changed

---

### 7. **LocationIntegrationTest.kt** (Unknown count)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/LocationIntegrationTest.kt`

**Issues:** TBD - needs investigation

---

### 8. **TelemetryHttpClientTest.kt** (Unknown count)
**Location:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/TelemetryHttpClientTest.kt`

**Issues:** TBD - needs investigation

---

## Summary Statistics

| Test File | Error Count | Primary Issue |
|-----------|-------------|---------------|
| IdGeneratorTest.kt | 12 | User ID methods removed |
| TelemetryManagerTest.kt (core) | 18 | Builder pattern removed |
| TelemetryManagerTest.kt (root) | 9 | Builder pattern removed |
| TelemetryIdValidationTest.kt | 7 | API changes |
| EnhancedCrashContextTest.kt | 3 | Import/dependency issues |
| IdConsistencyTest.kt | 1 | User ID API changed |
| LocationIntegrationTest.kt | ? | TBD |
| TelemetryHttpClientTest.kt | ? | TBD |

**Total Known Errors:** 50+

---

## Remediation Strategy

### Phase 1: Investigate Current API (Priority: HIGH)
**Estimated Time:** 30 minutes

- [ ] Check `IdGenerator.kt` for current user ID methods
- [ ] Check `TelemetryManager.kt` for current initialization API
- [ ] Check event recording API (recordEvent vs trackEvent)
- [ ] Document current API signatures

### Phase 2: Fix IdGenerator Tests (Priority: HIGH)
**Estimated Time:** 45 minutes

- [ ] Fix `IdGeneratorTest.kt` (12 errors)
  - Replace `generateUserId()` calls with current API
  - Replace `setUserId()` calls with current API
  - Replace `clearUserId()` calls with current API
  - Fix type inference issues
- [ ] Fix `IdConsistencyTest.kt` (1 error)
  - Replace `generateUserId()` call

### Phase 3: Fix TelemetryManager Tests (Priority: HIGH)
**Estimated Time:** 1 hour

- [ ] Fix `TelemetryManagerTest.kt` (root) (9 errors)
  - Replace builder pattern with config-based initialization
  - Add missing `config` parameters
- [ ] Fix `TelemetryManagerTest.kt` (core) (18 errors)
  - Replace builder pattern with config-based initialization
  - Add missing `config` parameters

### Phase 4: Fix Validation Tests (Priority: MEDIUM)
**Estimated Time:** 30 minutes

- [ ] Fix `TelemetryIdValidationTest.kt` (7 errors)
  - Update `initialize()` calls
  - Replace `recordEvent()` with current API
  - Fix type inference issues

### Phase 5: Fix Crash Context Tests (Priority: MEDIUM)
**Estimated Time:** 20 minutes

- [ ] Fix `EnhancedCrashContextTest.kt` (3 errors)
  - Fix import for `ApplicationProvider`
  - Fix `core` import
  - Update `initialize()` call

### Phase 6: Investigate Remaining Tests (Priority: LOW)
**Estimated Time:** 30 minutes

- [ ] Investigate `LocationIntegrationTest.kt`
- [ ] Investigate `TelemetryHttpClientTest.kt`
- [ ] Fix any issues found

### Phase 7: Verification (Priority: HIGH)
**Estimated Time:** 20 minutes

- [ ] Run all tests: `./gradlew test`
- [ ] Verify all tests compile
- [ ] Verify all tests pass
- [ ] Document any remaining issues

---

## Implementation Checklist

### API Investigation
- [ ] Document current IdGenerator API
- [ ] Document current TelemetryManager initialization API
- [ ] Document current event recording API
- [ ] Create API migration guide

### Test Fixes
- [ ] IdGeneratorTest.kt - Fixed
- [ ] IdConsistencyTest.kt - Fixed
- [ ] TelemetryManagerTest.kt (root) - Fixed
- [ ] TelemetryManagerTest.kt (core) - Fixed
- [ ] TelemetryIdValidationTest.kt - Fixed
- [ ] EnhancedCrashContextTest.kt - Fixed
- [ ] LocationIntegrationTest.kt - Investigated & Fixed
- [ ] TelemetryHttpClientTest.kt - Investigated & Fixed

### Verification
- [ ] All tests compile successfully
- [ ] All tests pass
- [ ] No compilation errors
- [ ] Documentation updated

---

## Timeline

**Total Estimated Time:** 4-5 hours

1. **API Investigation:** 30 minutes
2. **IdGenerator Tests:** 45 minutes
3. **TelemetryManager Tests:** 1 hour
4. **Validation Tests:** 30 minutes
5. **Crash Context Tests:** 20 minutes
6. **Remaining Tests:** 30 minutes
7. **Verification:** 20 minutes
8. **Buffer:** 30 minutes

---

## Success Criteria

- [ ] Zero compilation errors in test files
- [ ] All tests compile successfully
- [ ] All tests pass (or have documented failures with reasons)
- [ ] API migration documented
- [ ] Test coverage maintained or improved

---

## Next Steps

1. Start with Phase 1: Investigate current API
2. Document findings
3. Execute fixes in priority order
4. Verify after each phase
5. Update this plan with progress
