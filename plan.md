# API Key Implementation Plan

## Overview
This plan addresses the integration and enhancement of API key authentication across the Edge Telemetry Android SDK to ensure all network requests include proper authentication headers as required by the backend.

## Current Status
- ✅ API key parameter added to `TelemetryManager.initialize()` (v1.2.6)
- ✅ `TelemetryHttpClient` includes `X-API-Key` header in regular batch requests
- ❌ **CRITICAL:** `CrashRetryManager` bypasses `TelemetryHttpClient` and doesn't include API key
- ⚠️ No API key validation at initialization
- ⚠️ API key might be exposed in debug logs
- ⚠️ Hardcoded endpoint in `CrashRetryManager`

---

## Phase 1: Critical Bug Fixes 🔴

### Task 1.1: Fix CrashRetryManager API Key Issue
**Priority:** CRITICAL  
**Estimated Time:** 2-3 hours

#### Subtasks:
1. **Modify CrashRetryManager constructor**
   - Add `apiKey: String` parameter
   - Add `telemetryEndpoint: String` parameter
   - Remove hardcoded endpoint URL
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/retry/CrashRetryManager.kt`

2. **Update sendCrashData() method**
   - Add `X-API-Key` header to HTTP request builder (line 79-84)
   - Add `User-Agent` header for consistency
   - Use configurable endpoint instead of hardcoded URL
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/retry/CrashRetryManager.kt`

3. **Update CrashReporter initialization**
   - Pass `apiKey` from TelemetryManager to CrashReporter
   - Pass `telemetryEndpoint` from TelemetryManager to CrashReporter
   - Store these values in CrashReporter
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt`

4. **Update CrashReporter to pass values to CrashRetryManager**
   - Modify CrashRetryManager instantiation (line 37)
   - Pass apiKey and endpoint parameters
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt`

5. **Update TelemetryManager Flutter components initialization**
   - Pass apiKey to CrashReporter during initialization
   - Pass telemetryEndpoint to CrashReporter
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt` (line 264-273)

6. **Update CrashRetryWorker**
   - Ensure WorkManager worker can access API key
   - Consider storing API key in SharedPreferences for worker access
   - Or pass via WorkManager input data
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/retry/CrashRetryManager.kt` (line 232-255)

#### Acceptance Criteria:
- [x] All crash reports include `X-API-Key` header
- [x] Offline crash retries include `X-API-Key` header
- [x] WorkManager retry jobs include `X-API-Key` header
- [x] No hardcoded endpoints remain in CrashRetryManager
- [ ] Backend successfully receives authenticated crash reports (pending manual verification)

---

### Task 1.2: Add API Key Validation
**Priority:** HIGH  
**Estimated Time:** 1 hour

#### Subtasks:
1. **Add validation in TelemetryManager.initialize()**
   - Check if apiKey is not blank
   - Throw IllegalArgumentException with helpful message
   - Add logging for validation failure
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt` (line 121-157)

2. **Add API key format validation (optional)**
   - Check if API key matches expected format (e.g., starts with "edge_")
   - Add warning log if format seems incorrect
   - Don't block initialization, just warn
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt`

3. **Update error messages**
   - Provide clear guidance on where to get API key
   - Include link to documentation
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt`

#### Acceptance Criteria:
- [x] Blank API key throws IllegalArgumentException
- [x] Error message guides developers to get API key from backend
- [x] Validation happens before any network requests
- [x] Existing valid initializations continue to work

---

## Phase 2: Security Enhancements 🟡

### Task 2.1: Prevent API Key Exposure in Logs
**Priority:** MEDIUM  
**Estimated Time:** 2 hours

#### Subtasks:
1. **Create API key redaction interceptor**
   - Create new class `ApiKeyRedactionInterceptor`
   - Redact `X-API-Key` header value in logs
   - Show only first/last 4 characters (e.g., "edge_****_5R...")
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/interceptors/ApiKeyRedactionInterceptor.kt` (new file)

2. **Update TelemetryHttpClient logging**
   - Add redaction interceptor before logging interceptor
   - Ensure API key never appears in full in logs
   - Test with debugMode = true
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryHttpClient.kt` (line 34-42)

3. **Update CrashRetryManager logging**
   - Add similar redaction for crash retry requests
   - Ensure consistency across all HTTP clients
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/retry/CrashRetryManager.kt`

4. **Add security documentation**
   - Document API key security best practices
   - Warn against hardcoding API keys
   - Recommend using BuildConfig or secure storage
   - **File:** `README.md` and `README_EDGE_TELEMETRY.md`

#### Acceptance Criteria:
- [ ] API key never appears in full in logs
- [ ] Debug mode logs show redacted API key
- [ ] Security documentation added to README
- [ ] No API key leakage in crash reports or error logs

---

### Task 2.2: Add API Key Storage Recommendations
**Priority:** LOW  
**Estimated Time:** 1 hour

#### Subtasks:
1. **Create security guide section in README**
   - Add "Security Best Practices" section
   - Document BuildConfig approach
   - Document Android Keystore approach
   - Add .gitignore recommendations
   - **File:** `README.md`

2. **Add example code for secure storage**
   - Show BuildConfig example
   - Show local.properties example
   - Show environment variable example
   - **File:** `USAGE_EXAMPLE.kt` or new `SECURITY_GUIDE.md`

3. **Add ProGuard/R8 rules for API key obfuscation**
   - Ensure API key strings are obfuscated in release builds
   - Add to consumer-rules.pro
   - **File:** `telemetry_library/consumer-rules.pro`

#### Acceptance Criteria:
- [ ] Security guide added to documentation
- [ ] Example code shows secure API key storage
- [ ] ProGuard rules protect API key in release builds
- [ ] Developers warned against committing API keys

---

## Phase 3: Configuration Improvements 🟢

### Task 3.1: Centralize Configuration
**Priority:** MEDIUM  
**Estimated Time:** 2 hours

#### Subtasks:
1. **Create TelemetryConfig data class**
   - Centralize all configuration parameters
   - Include apiKey, endpoint, debugMode, etc.
   - Make it immutable
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryConfig.kt` (new file)

2. **Refactor TelemetryManager.initialize()**
   - Accept TelemetryConfig object (optional, maintain backward compatibility)
   - Keep existing parameter-based initialization
   - Add overload for config-based initialization
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt`

3. **Pass config to all components**
   - Update TelemetryHttpClient to accept config
   - Update CrashRetryManager to accept config
   - Update CrashReporter to accept config
   - Ensure single source of truth for configuration

#### Acceptance Criteria:
- [ ] TelemetryConfig class created
- [ ] Both initialization methods work (params and config)
- [ ] All components use centralized configuration
- [ ] Backward compatibility maintained

---

### Task 3.2: Add API Key Rotation Support (Future)
**Priority:** LOW  
**Estimated Time:** 3 hours

#### Subtasks:
1. **Design API key rotation mechanism**
   - Allow updating API key without reinitializing SDK
   - Ensure thread-safety during rotation
   - Handle in-flight requests gracefully

2. **Add updateApiKey() method**
   - Create public method in TelemetryManager
   - Update all HTTP clients with new key
   - Invalidate old key safely

3. **Add rotation event tracking**
   - Track when API key is rotated
   - Send event to backend for audit trail

#### Acceptance Criteria:
- [ ] API key can be updated at runtime
- [ ] No requests fail during rotation
- [ ] Rotation is tracked for security audit

---

## Phase 4: Testing & Validation 🔵

### Task 4.1: Add Unit Tests
**Priority:** HIGH  
**Estimated Time:** 3 hours

#### Subtasks:
1. **Test API key validation**
   - Test blank API key throws exception
   - Test valid API key passes
   - Test error messages are helpful
   - **File:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/TelemetryManagerTest.kt`

2. **Test API key in HTTP requests**
   - Mock HTTP client
   - Verify X-API-Key header is present
   - Verify header value is correct
   - **File:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/TelemetryHttpClientTest.kt`

3. **Test crash retry includes API key**
   - Mock crash retry requests
   - Verify X-API-Key header in retry requests
   - Test offline retry includes API key
   - **File:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/CrashRetryManagerTest.kt`

4. **Test API key redaction in logs**
   - Enable debug mode
   - Verify API key is redacted in logs
   - Verify only partial key is shown
   - **File:** `telemetry_library/src/test/java/com/androidtel/telemetry_library/ApiKeyRedactionTest.kt`

#### Acceptance Criteria:
- [ ] All tests pass
- [ ] Code coverage > 80% for API key logic
- [ ] Edge cases covered (null, empty, invalid format)
- [ ] Mock tests verify header presence

---

### Task 4.2: Integration Testing
**Priority:** MEDIUM  
**Estimated Time:** 2 hours

#### Subtasks:
1. **Test against real backend**
   - Send test events with valid API key
   - Verify backend accepts requests
   - Test with invalid API key (should fail)
   - Test crash reports are authenticated

2. **Test offline retry mechanism**
   - Disable network
   - Generate crash reports
   - Enable network
   - Verify retries include API key
   - Verify backend receives retried crashes

3. **Test WorkManager retry**
   - Force WorkManager retry scenario
   - Verify API key is included
   - Verify backend receives delayed crashes

#### Acceptance Criteria:
- [ ] Backend successfully receives all authenticated requests
- [ ] Invalid API key requests are rejected by backend
- [ ] Offline retries work with authentication
- [ ] WorkManager retries include API key

---

### Task 4.3: Update EdgeTelemetryTester
**Priority:** LOW  
**Estimated Time:** 1 hour

#### Subtasks:
1. **Add API key validation test**
   - Add test method to EdgeTelemetryTester
   - Verify API key is configured
   - Test connectivity with authentication
   - **File:** `telemetry_library/src/main/java/com/androidtel/telemetry_library/testing/EdgeTelemetryTester.kt`

2. **Update comprehensive test suite**
   - Include API key in test payloads
   - Verify authenticated requests
   - Add to runComprehensiveTest()

#### Acceptance Criteria:
- [ ] EdgeTelemetryTester validates API key
- [ ] Test suite covers authentication scenarios
- [ ] Developers can easily test API key setup

---

## Phase 5: Documentation Updates 📚

### Task 5.1: Update README Files
**Priority:** HIGH  
**Estimated Time:** 2 hours

#### Subtasks:
1. **Update README.md**
   - Emphasize API key requirement
   - Update initialization examples
   - Add troubleshooting section for API key issues
   - **File:** `README.md`

2. **Update README_EDGE_TELEMETRY.md**
   - Update Flutter compatibility section
   - Ensure API key examples are consistent
   - **File:** `README_EDGE_TELEMETRY.md`

3. **Update USAGE_EXAMPLE.kt**
   - Add API key to all examples
   - Show secure storage example
   - Add comments about API key security
   - **File:** `USAGE_EXAMPLE.kt`

4. **Update INTEGRATION_SUMMARY.md**
   - Document API key requirement
   - Add migration notes
   - **File:** `INTEGRATION_SUMMARY.md`

#### Acceptance Criteria:
- [ ] All documentation mentions API key requirement
- [ ] Examples show proper API key usage
- [ ] Security warnings are prominent
- [ ] Migration guide is clear

---

### Task 5.2: Update CHANGELOG
**Priority:** MEDIUM  
**Estimated Time:** 30 minutes

#### Subtasks:
1. **Add new version entry**
   - Create v1.2.8 or v1.3.0 section
   - Document API key fixes
   - Document security enhancements
   - List breaking changes if any
   - **File:** `CHANGELOG.md`

2. **Document migration path**
   - Explain changes from v1.2.7
   - Provide upgrade instructions
   - Highlight critical fixes

#### Acceptance Criteria:
- [ ] CHANGELOG updated with new version
- [ ] All changes documented
- [ ] Migration instructions clear
- [ ] Breaking changes highlighted

---

### Task 5.3: Create API Key Management Guide
**Priority:** LOW  
**Estimated Time:** 1 hour

#### Subtasks:
1. **Create API_KEY_GUIDE.md**
   - How to obtain API key from backend
   - How to securely store API key
   - How to rotate API key
   - Troubleshooting common issues
   - **File:** `API_KEY_GUIDE.md` (new file)

2. **Add to documentation index**
   - Link from README
   - Add to GitHub wiki if applicable

#### Acceptance Criteria:
- [ ] Comprehensive API key guide created
- [ ] Covers all common scenarios
- [ ] Linked from main documentation
- [ ] Includes troubleshooting section

---

## Phase 6: Release Preparation 🚀

### Task 6.1: Version Bump
**Priority:** HIGH  
**Estimated Time:** 30 minutes

#### Subtasks:
1. **Update version in build.gradle.kts**
   - Bump to v1.2.8 or v1.3.0
   - Update version in publishing block
   - **File:** `telemetry_library/build.gradle.kts` (line 122)

2. **Update version references**
   - Update README badges
   - Update installation instructions
   - Update CHANGELOG

#### Acceptance Criteria:
- [ ] Version bumped consistently across all files
- [ ] Semantic versioning followed
- [ ] Installation instructions updated

---

### Task 6.2: Pre-release Testing
**Priority:** CRITICAL  
**Estimated Time:** 2 hours

#### Subtasks:
1. **Run all unit tests**
   - Execute `./gradlew test`
   - Ensure 100% pass rate
   - Fix any failures

2. **Run integration tests**
   - Execute `./gradlew connectedAndroidTest`
   - Test on real devices
   - Test on different Android versions

3. **Manual testing checklist**
   - [ ] Initialize SDK with valid API key
   - [ ] Initialize SDK with invalid API key (should fail gracefully)
   - [ ] Send regular telemetry events
   - [ ] Trigger crash report
   - [ ] Test offline crash retry
   - [ ] Verify all requests include X-API-Key header
   - [ ] Check logs for API key redaction

#### Acceptance Criteria:
- [ ] All automated tests pass
- [ ] Manual testing checklist completed
- [ ] No regressions found
- [ ] API key authentication works end-to-end

---

### Task 6.3: Publish to JitPack
**Priority:** HIGH  
**Estimated Time:** 1 hour

#### Subtasks:
1. **Create Git tag**
   - Tag commit with version number
   - Push tag to GitHub
   - `git tag v1.2.8 && git push origin v1.2.8`

2. **Trigger JitPack build**
   - Visit JitPack.io
   - Verify build succeeds
   - Test installation from JitPack

3. **Update installation instructions**
   - Update version in README
   - Test installation in sample app

#### Acceptance Criteria:
- [ ] Git tag created and pushed
- [ ] JitPack build successful
- [ ] Library installable via Gradle
- [ ] Sample app works with new version

---

## Summary

### Critical Path (Must Complete First):
1. Task 1.1: Fix CrashRetryManager API Key Issue ⚠️
2. Task 1.2: Add API Key Validation
3. Task 4.1: Add Unit Tests
4. Task 5.1: Update README Files
5. Task 6.1: Version Bump
6. Task 6.2: Pre-release Testing
7. Task 6.3: Publish to JitPack

### Total Estimated Time: 24-28 hours

### Priority Order:
1. **Phase 1** (Critical Bug Fixes) - 3-4 hours
2. **Phase 4** (Testing) - 5-6 hours
3. **Phase 5** (Documentation) - 3-4 hours
4. **Phase 2** (Security) - 3 hours
5. **Phase 3** (Configuration) - 2-5 hours
6. **Phase 6** (Release) - 3-4 hours

### Risk Areas:
- CrashRetryWorker API key access (may need SharedPreferences)
- Backward compatibility with existing integrations
- JitPack build configuration changes

### Success Metrics:
- [ ] All crash reports include API key
- [ ] Backend accepts 100% of authenticated requests
- [ ] No API key exposure in logs
- [ ] Zero breaking changes for existing users
- [ ] Documentation comprehensive and clear
