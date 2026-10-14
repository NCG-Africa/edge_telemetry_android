# Edge Telemetry Android SDK

## Overview

Android telemetry library (AAR) published via JitPack as `com.github.NCG-Africa:edge_telemetry_android`. Provides automatic instrumentation for crash reporting, screen tracking, network monitoring, session management, and more — with minimal setup from consuming apps.

## Build & Test

```bash
# Build the library
./gradlew :telemetry_library:assembleRelease

# Run unit tests
./gradlew :telemetry_library:testDebugUnitTest

# Run instrumented tests (requires emulator/device)
./gradlew :telemetry_library:connectedDebugAndroidTest

# Lint check
./gradlew :telemetry_library:lintDebug

# Publish locally (for testing)
./gradlew :telemetry_library:publishToMavenLocal
```

## SDK Versions & Toolchain

- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 | **Compile SDK:** 35
- **Kotlin:** 2.1.0 | **AGP:** 8.7.1
- **Java:** 11 (source + target)
- **Gradle:** 8.4+
- **Current version:** 4.0.1

## Project Structure

```
edge_telemetry_android/
├── telemetry_library/                    # Single library module
│   ├── build.gradle.kts                  # Library build config + JitPack publishing
│   └── src/main/java/com/androidtel/telemetry_library/
│       ├── EdgeTelemetry.kt              # Deprecated typealias → TelemetryManager
│       ├── compose/
│       │   └── EdgeTelemetryCompose.kt   # Compose-specific utilities
│       ├── testing/
│       │   └── EdgeTelemetryTester.kt    # Testing utilities for consumers
│       └── core/
│           ├── TelemetryManager.kt       # Main singleton entry point
│           ├── TelemetryConfig.kt        # Configuration data class
│           ├── TelemetryHttpClient.kt    # HTTP transport layer
│           ├── TelemetryInterceptor.kt   # OkHttp interceptor for network tracking
│           ├── TelemetryTime.kt          # Single source of truth for wire timestamps (ISO-8601 UTC)
│           ├── OfflineBatchStorage.kt    # SharedPreferences offline persistence (200-envelope cap)
│           ├── CountedEventQueue.kt      # Bounded event buffer with eviction counters
│           ├── ScreenTimingTracker.kt    # Screen duration measurement
│           ├── PerformanceTracker.kt     # Frame/performance tracking facade
│           ├── MemoryTracker.kt          # Memory pressure monitoring
│           ├── DeviceCapabilities.kt     # Runtime capability detection
│           ├── services/                 # Service-based architecture (Phase 2)
│           │   ├── EventTrackingService.kt
│           │   ├── SessionService.kt
│           │   ├── UserProfileService.kt
│           │   ├── CrashReportingService.kt
│           │   └── BatchProcessingService.kt
│           ├── models/                   # Data models (TelemetryBatch, TelemetryEvent, etc.)
│           ├── trace/                    # TraceManager (W3C traceparent, spans on events)
│           ├── crash/                    # CrashFingerprinter, FatalCrashStore
│           ├── anr/                      # AnrWatchdog (ANR + sub-5s hang detection)
│           ├── exit/                     # ExitInfoReader, ApplicationExitHarvester (app.exit)
│           ├── startup/                  # AppStartTracker (cold-start timing)
│           ├── interaction/              # UserInteractionTracker (tap/swipe → ui.interaction)
│           ├── breadcrumbs/              # BreadcrumbManager (circular buffer, max 50)
│           ├── session/                  # SessionManager
│           ├── user/                     # UserProfileManager
│           ├── ids/                      # IdGenerator (device, session, user, W3C trace/span IDs)
│           ├── navigation/               # NavigationStackTracker
│           ├── device/                   # DeviceInfoCollector, DeviceStateSnapshot
│           ├── validation/               # EventPayloadValidator, AttributeValidator, RuntimeEventValidator
│           ├── interceptors/             # ApiKeyRedactionInterceptor
│           └── retry/                    # CrashRetryManager
├── gradle/libs.versions.toml            # Version catalog
├── settings.gradle.kts                  # Single module: :telemetry_library
├── CONTEXT.md                           # Domain glossary (ubiquitous language)
└── docs/
    ├── EVENT_SCHEMA_REFERENCE.md        # Wire format per event type
    ├── API_KEY_GUIDE.md                 # API key setup for consumers
    ├── MIGRATION_GUIDE_V2.md            # v1 → v2 consumer migration
    ├── specs/                           # Per-feature design specs (tracked via GitHub issues, see wayfinder #29)
    └── agents/issue-tracker.md          # Issue-tracker conventions for agent skills
```

## Architecture

Singleton `TelemetryManager` delegates to five services:

- **EventTrackingService** — custom events, metrics, network requests
- **SessionService** — session lifecycle, timeout (default 30min), statistics
- **UserProfileService** — user profile storage (SharedPreferences)
- **CrashReportingService** — uncaught exception handler, manual error tracking, breadcrumbs
- **BatchProcessingService** — event queue, batching (default 50), flush timer (default 30s), offline retry

Thread safety: `AtomicBoolean` for ready state, `ConcurrentLinkedQueue` for event buffer, `ReentrantReadWriteLock` in breadcrumb/navigation managers, `CoroutineScope(Dispatchers.IO)` for async work.

## Automatic Instrumentation (enabled by default)

These features work out of the box after `TelemetryManager.initialize()`:

| Feature | How it works |
|---|---|
| **Crash reporting** | Installs `UncaughtExceptionHandler`, captures breadcrumbs + stack trace |
| **Screen tracking** | `ActivityLifecycleCallbacks` auto-tracks Activity transitions |
| **Lifecycle tracking** | `ProcessLifecycleOwner` observer for app foreground/background |
| **Session management** | Auto-creates sessions, handles timeout + rotation |
| **Memory monitoring** | Tracks memory pressure levels and usage |
| **Storage monitoring** | Monitors device storage state |
| **Frame tracking** | Frame drop detection for performance |
| **Network tracking** | OkHttp interceptor (requires adding to client — see below) |

## Opt-in Features (disabled by default)

| Feature | Config flag |
|---|---|
| `traceparent` propagation | `traceHostAllowlist = listOf("api.example.com", ".example.com")` — empty (the default) injects on **no** host; a leading dot is a suffix match |

## Tracing (how it works)

Spans are **attributes on existing events** — no span-start/end emission, no new event type. Span-carrying events get `trace.id`, `span.id`, `parent.span.id`, `rum.action.id` (root's span), `trace.root_type`, `span.start_time`.

Three concepts: **root** (a user action — `launch` | `interaction` | `navigation` | `request`, minted at the causing moment), **carrier** (`ThreadLocal<TraceContext>`, projected into coroutines via `asContextElement`), **outcome enum** on every request so gaps are measurable, never silent.

Four load-bearing decisions:

1. **Capture where the caller owns the thread.** OkHttp runs interceptors on its dispatcher pool → a thread-local read there is `null` for every async call. `TracingCallFactory` stamps a `TraceTag` at `newCall()` (caller's thread); the interceptor reads the tag, not the carrier.
2. **Roots expire** — 2 s idle / 10 s cap on an injected monotonic clock, extended on child-span start and on request completion. Expiry is lazy (discovered on read, clears the slot).
3. **Launch is a root** — opened in `initialize()` from the process fork instant, idle-exempt until first Activity resume. That resume ends the *exemption*, not the root.
4. **`lastRoot` annotates, never parents** — read by the crash and hang emitters only; a guard test enforces it. A crash gets join keys, not a span.

Per-request ladder (order matters):

```
host off allowlist      → local ids, header suppressed   skipped_off_allowlist
valid inbound header    → mirror foreign ids, untouched  adopted
no tag                  → instrument() never wired       injected_unwired
tag holds null          → no action open                 injected_unattributed
tag holds expired root  → action aged out                injected_expired
otherwise               → child of the live root         injected_attributed
```

Gotchas: mint interaction roots on touch-up (`onSingleTapUp`), not tap-confirm — 300 ms later misses the request; `clock`/`epochClock` are injectable closures (expiry is untestable otherwise); `lastActivity` is an `AtomicLong` (written from the OkHttp pool, read from main); emit-time attrs must not mint; roots carry `span.start_time` only, no `duration_ms` (envelope derived server-side).

Spec: `docs/specs/distributed-trace-span.md`. Wire contract: `docs/specs/backend-wire-contract.md` — port verbatim to other SDKs so they land in the same tables.

## Key APIs for Developers

### Initialization (required, typically in Application.onCreate)

```kotlin
val config = TelemetryConfig(
    apiKey = "edge_your_api_key",       // Must start with "edge_"
    endpoint = "https://edgetelemetry.ncgafrica.com/telemetry"
)
TelemetryManager.initialize(application, config)
```

### Network Tracking (instrument the OkHttpClient)

```kotlin
val client = OkHttpClient.Builder().build()

Retrofit.Builder()
    .callFactory(TelemetryManager.instrument(client))   // interceptor + newCall() trace capture
    .build()
```

`createNetworkInterceptor()` is deprecated (v3 Δ6): OkHttp runs interceptors on its dispatcher pool, so
a bare interceptor cannot attribute async calls to the action that caused them.

### Custom Events & Metrics

```kotlin
val tm = TelemetryManager.getInstance()
tm.recordEvent("button_clicked", mapOf("button_id" to "checkout"))
tm.recordMetric("load_time_ms", 342.0, mapOf("screen" to "home"))
```

### Error Tracking (manual)

```kotlin
tm.trackError(exception, errorCode = "PAY_001", productId = "checkout", userAction = "submit_payment")
tm.recordCrash(throwable)
```

### Compose Navigation Tracking

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    TelemetryManager.getInstance().trackComposeScreens(navController)
    // ... NavHost setup
}
```

### User Profile

```kotlin
tm.setUserProfile(name = "John", email = "john@example.com", phone = "+254...")
tm.clearUserProfile()
```

### Breadcrumbs

```kotlin
tm.addBreadcrumb("User tapped settings", category = "user", level = "info")
```

### Session Control

```kotlin
tm.startNewSession()
val sessionId = tm.getSessionId()
```

### Context for Errors

```kotlin
tm.setProductContext("checkout_flow")
tm.setLastUserAction("submit_payment")
```

## Key Dependencies

- **OkHttp3** 4.12.0 — HTTP transport + network interceptor
- **Gson** 2.10.1 — JSON serialization
- **AndroidX Work** 2.9.0 — background retry scheduling
- **AndroidX Lifecycle** 2.9.2 — process/activity lifecycle observation
- **AndroidX Navigation** 2.9.3 — Compose navigation tracking
- **AndroidX Compose BOM** 2024.04.01 — Compose UI support

## Testing

- **Unit tests:** JUnit 4 + Mockk + Robolectric (`src/test/`)
- **Instrumented tests:** Espresso + Compose test (`src/androidTest/`)
- Test helper: `EdgeTelemetryTester` for consumer test setup
- `TelemetryManager.resetForTesting()` — internal method to reset singleton in tests

## Conventions

- API key validation: must start with `"edge_"` and be non-blank
- Endpoint validation: must be non-blank
- All events auto-enriched with: app info, device info, session info, user info
- Pre-init queue (max 50 items) buffers calls made before SDK is ready
- `EdgeTelemetry` is a deprecated typealias for `TelemetryManager`
- Payload format is Flask-compatible JSON matching the backend collector
- Permission required: `ACCESS_NETWORK_STATE` (declared in library manifest)
- Publishing: JitPack via `maven-publish` plugin, release variant with sources + javadoc
