# Mobile SDK Event Alignment Plan

## Overview
This plan ensures the Android Telemetry SDK aligns with backend processor service requirements for event names, attributes, and data structures.

---

## Phase 1: Event Name Alignment

### 1.1 HTTP Request Events
**Status:** Pending  
**Current:** `network.request`  
**Required:** `http.request`

**Tasks:**
- [ ] Locate HTTP interceptor implementation
- [ ] Update event name from `network.request` to `http.request`
- [ ] Verify required attributes:
  - `http.url` - Full request URL
  - `http.method` - HTTP method (GET, POST, PUT, DELETE, etc.)
  - `http.status_code` - Response status code
  - `http.duration_ms` - Request duration in milliseconds
  - `http.timestamp` - Request timestamp (ISO 8601 format)
  - `http.success` - Boolean (status < 400)

**Files to Update:**
- HTTP interceptor/network tracking module
- Event constants/definitions

---

### 1.2 Session End Events
**Status:** Pending  
**Current:** `session_end`  
**Required:** `session.finalized`

**Tasks:**
- [ ] Locate session lifecycle manager
- [ ] Rename `session_end` to `session.finalized`
- [ ] Add/verify required session attributes:
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

**Files to Update:**
- Session manager/lifecycle observer
- Session state tracking

---

### 1.3 Navigation Events
**Status:** Pending  
**Current:** `navigation` (correct) and `navigation.route_change` (legacy)  
**Required:** Standardize on `navigation`

**Tasks:**
- [ ] Audit all navigation event emissions
- [ ] Standardize on `navigation` event name
- [ ] Deprecate/remove `navigation.route_change`
- [ ] Verify required attributes:
  - `navigation.from_screen` - Source screen name (nullable for app launch)
  - `navigation.to_screen` - Destination screen name
  - `navigation.method` - Navigation method (push, pop, replace, etc.)
  - `navigation.route_type` - Route type (named, generated, etc.)
  - `navigation.has_arguments` - Boolean indicating if route has arguments
  - `navigation.timestamp` - Navigation timestamp (ISO 8601)

**Files to Update:**
- Navigation observer/tracker
- Route change listeners

---

### 1.4 Screen Duration Events
**Status:** Pending  
**Current:** `performance.screen_duration` (correct)  
**Required:** Keep as `performance.screen_duration`

**Tasks:**
- [ ] Verify event name is correct
- [ ] Ensure required attributes are present:
  - `screen.name` - Screen/route name
  - `screen.duration_ms` - Time spent on screen
  - `screen.exit_method` - How user left screen (navigation, back, etc.)
  - `screen.timestamp` - Screen exit timestamp (ISO 8601)

**Files to Update:**
- Screen duration tracker
- Performance monitoring module

---

### 1.5 Crash Events
**Status:** Pending  
**Current:** `app.crash` (correct)  
**Required:** Keep as `app.crash`

**Tasks:**
- [ ] Verify event name is correct
- [ ] Implement comprehensive crash attributes with field length limits:
  - `error.message` - Error message (max 1000 chars)
  - `error.stack_trace` - Full stack trace (max 2000 chars)
  - `error.exception_type` - Exception class name (max 255 chars)
  - `error.context` - Error context/description (max 500 chars)
  - `error.product_id` - Product/module identifier (max 255 chars)
  - `error.cause` - Root cause description (max 255 chars)
  - `error.severity_level` - Severity (critical, error, warning, info)
  - `error.code` - Error code if available (max 100 chars)
  - `error.user_action` - What user was doing (max 500 chars)
  - `error.breadcrumbs` - JSON array of breadcrumbs (max 800 chars)
  - `error.breadcrumb_count` - Number of breadcrumbs
  - `error.is_fatal` - Boolean indicating if crash is fatal

**Files to Update:**
- Crash handler/exception tracker
- Error reporting module

---

## Phase 2: Standard Attributes

### 2.1 App Information Attributes
**Status:** Pending

**Required for ALL Events:**
- `app.name` - Application name
- `app.version` - App version (e.g., "1.2.3")
- `app.build_number` - Build number (e.g., "123")
- `app.package_name` - Package identifier (e.g., "com.example.myapp")

**Tasks:**
- [ ] Locate attribute collection module
- [ ] Ensure app info is collected at SDK initialization
- [ ] Verify app attributes are attached to every event

---

### 2.2 Device Information Attributes
**Status:** Pending

**Required for ALL Events:**
- `device.id` - Unique device identifier (UUID recommended)
- `device.platform` - Platform (android)
- `device.platform_version` - OS version (e.g., "13.0")
- `device.model` - Device model (e.g., "Pixel 7")
- `device.manufacturer` - Manufacturer (e.g., "Google")
- `device.brand` - Brand name
- `device.android_sdk` - Android SDK version
- `device.android_release` - Android release version
- `device.fingerprint` - Device fingerprint
- `device.hardware` - Hardware identifier
- `device.product` - Product name

**Tasks:**
- [ ] Locate device info collection module
- [ ] Ensure all device attributes are collected
- [ ] Verify device attributes are attached to every event

---

### 2.3 User & Session Attributes
**Status:** Pending

**Required for ALL Events:**
- `user.id` - Unique user identifier
- `session.id` - Current session identifier
- `session.start_time` - Session start timestamp (ISO 8601)

**Tasks:**
- [ ] Verify user ID is set and persisted
- [ ] Ensure session ID is generated and tracked
- [ ] Verify user/session attributes are attached to every event

---

## Phase 3: Event Cleanup

### 3.1 Remove Unsupported Events
**Status:** Pending

**Events NOT Processed by Backend (remove or disable):**
- `telemetry.capabilities_initialized`
- `app.error` (only `app.crash` is processed)
- `memory_pressure`
- `storage_usage`
- `frame_drop`
- `performance.frame_summary`
- `navigation.screen_resume`
- `navigation.screen_pause`
- `screen.entry`
- `screen.exit`
- `screen.resume`
- `screen.pause`
- `user.interaction`
- `performance.compose`
- `screen_view` (legacy)

**Tasks:**
- [ ] Audit codebase for unsupported event emissions
- [ ] Remove or add feature flags to disable unsupported events
- [ ] Document removed events in CHANGELOG
- [ ] Update migration guide

---

## Phase 4: Testing & Validation

### 4.1 Event Payload Validation
**Status:** Pending

**Tasks:**
- [ ] Create test suite for each event type
- [ ] Validate event names match backend expectations
- [ ] Validate all required attributes are present
- [ ] Validate attribute data types and formats
- [ ] Validate field length limits (especially for crash events)
- [ ] Test ISO 8601 timestamp formatting
- [ ] Test boolean value formatting

---

### 4.2 Integration Testing
**Status:** Pending

**Tasks:**
- [ ] Test HTTP request tracking end-to-end
- [ ] Test session lifecycle and finalization
- [ ] Test navigation tracking across different scenarios
- [ ] Test screen duration tracking
- [ ] Test crash reporting with various exception types
- [ ] Verify standard attributes on all events

---

## Phase 5: Documentation

### 5.1 Update SDK Documentation
**Status:** Pending

**Tasks:**
- [ ] Update API documentation with new event names
- [ ] Document all required attributes for each event type
- [ ] Update code examples with correct event structures
- [ ] Create migration guide for existing integrations
- [ ] Update CHANGELOG with breaking changes
- [ ] Update README with alignment notes

---

### 5.2 Create Event Schema Reference
**Status:** Pending

**Tasks:**
- [ ] Document complete event schema for each event type
- [ ] Include example JSON payloads
- [ ] Document field length limits
- [ ] Document required vs optional attributes
- [ ] Create quick reference guide

---

## Implementation Order

1. **Phase 1: Event Name Alignment** (Critical)
   - Start with HTTP requests (most common)
   - Then session finalization
   - Then navigation standardization
   - Verify screen duration and crash events

2. **Phase 2: Standard Attributes** (Critical)
   - Ensure all events have app, device, user, session info
   - This is foundational for all events

3. **Phase 3: Event Cleanup** (Important)
   - Remove noise from unsupported events
   - Reduces bandwidth and processing overhead

4. **Phase 4: Testing & Validation** (Critical)
   - Validate changes before release
   - Prevent regressions

5. **Phase 5: Documentation** (Important)
   - Help users migrate
   - Prevent integration issues

---

## Success Criteria

- [ ] All event names match backend processor expectations
- [ ] All required attributes present on each event type
- [ ] Standard attributes (app, device, user, session) on ALL events
- [ ] Field length limits enforced for crash events
- [ ] Unsupported events removed or disabled
- [ ] Test coverage for all event types
- [ ] Documentation updated and migration guide created
- [ ] No breaking changes for users (or clearly documented)

---

## Risk Mitigation

**Breaking Changes:**
- Event name changes are breaking for existing integrations
- Mitigation: Version bump, clear migration guide, deprecation warnings

**Data Loss:**
- Removing events could lose valuable data
- Mitigation: Feature flags to enable/disable, document what's removed

**Performance Impact:**
- Additional attributes increase payload size
- Mitigation: Monitor payload sizes, optimize where possible

**Testing Coverage:**
- Complex event scenarios may be missed
- Mitigation: Comprehensive test suite, beta testing period

---

## Timeline Estimate

- **Phase 1:** 3-5 days (event name alignment)
- **Phase 2:** 2-3 days (standard attributes)
- **Phase 3:** 1-2 days (event cleanup)
- **Phase 4:** 3-4 days (testing & validation)
- **Phase 5:** 2-3 days (documentation)

**Total:** 11-17 days (2-3 weeks)

---

## Next Steps

1. Begin Phase 1.1: Audit and update HTTP request events
2. Set up feature flags for gradual rollout
3. Create test harness for event validation
4. Schedule review with backend team to confirm alignment
