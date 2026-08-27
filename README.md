# Edge Telemetry Android SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-11-ED8B00.svg?style=flat&logo=java)](https://www.oracle.com/java/)
[![JitPack](https://jitpack.io/v/NCG-Africa/edge_telemetry_android.svg)](https://jitpack.io/#NCG-Africa/edge_telemetry_android)

Android RUM SDK: crashes, ANRs, app-exit reasons, cold-start timing, screens, sessions, HTTP calls,
user interactions, and distributed traces — collected automatically after a two-line init and shipped
to the EdgeTelemetry collector in batches.

**Current release: `2.2.0`**

---

## What you get automatically

After `initialize()`, with no further code:

| Signal | Event | Notes |
|---|---|---|
| Cold/warm start timing | `app.start` | anchored at process fork; background starts suppressed |
| Fatal crashes | `app.crash` | frozen to disk, replayed next launch; breadcrumbs attached |
| ANRs & hangs | `app.anr`, `app.hang` | in-process watchdog, all-thread dump on ANR |
| Abnormal process deaths | `app.exit` | harvested from `ApplicationExitInfo` (API 30+) |
| Screen transitions | `navigation`, `screen.duration` | Activity/Fragment auto; Compose opt-in helper |
| Taps, long-presses, flings | `ui.interaction` | roots a distributed trace |
| Sessions | `session.started`, `session.finalized` | 30-min timeout, auto-rotation |
| HTTP calls | `http.request` | requires the OkHttp interceptor (below) |
| Frames / memory / storage | `frame.summary`, `memory_pressure`, `storage_usage` | on by default |
| Device context | `device.*` attrs on every event | static bundle at init + live state (battery, thermal, orientation) |

Every event is enriched with app info, device info, session info, and user info.

---

## Requirements

| | |
|---|---|
| Min SDK | 24 (Android 7.0) |
| Compile SDK | 35 · Target SDK 34 |
| Java | 11 (a Java 8 bytecode build is published as `-java8`) |
| Kotlin | 2.1.0+ · AGP 8.7.1+ · Gradle 8.4+ |

Permission `ACCESS_NETWORK_STATE` is declared by the library manifest — nothing to add.

---

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```groovy
// app/build.gradle
implementation 'com.github.NCG-Africa:edge_telemetry_android:2.2.0'
```

**Java 8 bytecode** (apps whose `compileOptions` are pinned to 1.8) — built from the
`master-java8-compatible` branch with core-library desugaring for `java.time.*`:

```groovy
implementation 'com.github.NCG-Africa:edge_telemetry_android:2.1.13-java8'
```

> The `-java8` line lags the main line: `2.1.13-java8` carries the 2.1.13 feature set, so it has
> **no distributed tracing** (2.2.0). Truly legacy toolchains (AGP 7.x, Kotlin 1.8, no Compose)
> should use `1.2.5-java8`.

---

## Quick start

### 1. Initialize in `Application.onCreate`

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = TelemetryConfig(
            apiKey = BuildConfig.TELEMETRY_API_KEY,   // must start with "edge_"
            endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
            traceHostAllowlist = listOf("api.example.com")   // see Distributed Tracing
        )
        TelemetryManager.initialize(this, config)
    }
}
```

Java:

```java
TelemetryConfig config = new TelemetryConfig(
    BuildConfig.TELEMETRY_API_KEY,
    "https://edgetelemetry.ncgafrica.com/collector/telemetry"
);
TelemetryManager.initialize(this, config);
```

> Never hardcode the API key — see [API key handling](#api-key-handling).

### 2. Register the Application class

```xml
<application android:name=".MyApplication" ... />
```

### 3. Add the OkHttp interceptor (for `http.request` + `traceparent`)

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(TelemetryManager.createNetworkInterceptor())  // application interceptor
    .build()
```

The SDK skips its own collector requests, so there is no feedback loop.

---

## Configuration

`TelemetryConfig` is a plain data class — construct it with named arguments. There is no builder.

| Field | Type | Default | Description |
|---|---|---|---|
| `apiKey` | `String` | *required* | Must be non-blank and start with `edge_` |
| `endpoint` | `String` | *required* | Collector URL |
| `batchSize` | `Int` | `50` | Events buffered before a send |
| `flushIntervalMs` | `Long` | `30_000` | Timed flush of partial batches |
| `sessionTimeoutMs` | `Long` | `1_800_000` | Idle time before a session rotates (30 min) |
| `enableScreenTracking` | `Boolean` | `true` | Activity/Fragment lifecycle tracking |
| `enableCrashReporting` | `Boolean` | `true` | Uncaught-exception handler, ANR watchdog |
| `enableNetworkTracking` | `Boolean` | `true` | Instantiates the interceptor |
| `enableLifecycleTracking` | `Boolean` | `true` | Process foreground/background |
| `enableMemoryTracking` | `Boolean` | `true` | `memory_pressure` |
| `enableStorageTracking` | `Boolean` | `true` | `storage_usage` |
| `enableFrameTracking` | `Boolean` | `true` | `frame.summary` |
| `enableUserInteractionEvents` | `Boolean` | `true` | `ui.interaction` |
| `enableCapabilityEvents` | `Boolean` | `true` | `telemetry.capabilities_initialized` |
| `enableSessionTracking` | `Boolean` | `true` | Session lifecycle events |
| `traceSampleRate` | `Double` | `1.0` | Head-based sampling; fixed at `1.0` in v2 |
| `traceHostAllowlist` | `List<String>` | `emptyList()` | Bare hosts that receive `traceparent` |

Invalid values fail fast at construction (`IllegalArgumentException`).

Outbound batches carry `X-API-Key`, `X-SDK-Version`, `X-SDK-Platform: android`, and a versioned
`User-Agent`.

---

## Distributed tracing (v2)

The SDK stitches an app action → the network calls it triggers → backend spans into one W3C trace by
injecting a `traceparent` header on outbound requests.

### `traceHostAllowlist` — ⚠️ dark on upgrade

```kotlin
val config = TelemetryConfig(
    apiKey = "edge_...",
    endpoint = "https://collector.example.com/...",
    traceHostAllowlist = listOf("api.example.com", "checkout.example.com")
)
```

`List<String>`, default `emptyList()`. Bare hosts only — **no scheme, port, or path** (a mis-formatted
entry fails fast at `initialize()`). Match is exact host, case-insensitive; `api.example.com` matches
that host **only** — not `example.com`, not `api.example.com.evil.com`.

> **⚠️ Upgrading to v2:** distributed tracing goes **dark** on upgrade — **no** `traceparent` is
> injected on any request — until you enumerate your backend hosts in `traceHostAllowlist`. There is
> intentionally **no** "inject everywhere" option; the header must never reach a host you didn't name.
> Off-allowlist calls are still recorded locally (you keep `trace.id`/`span.id` on your own
> `http.request` events); only the outbound header is withheld.

Sampling is fixed — `traceSampleRate` stays `1.0`; it is **not** a v2 knob.

### `traceparent.outcome`

Every traced `http.request` event carries a `traceparent.outcome` string so you can tell per request how
the header was handled. Untraced requests (the SDK's own telemetry POSTs) carry no such attribute —
absence means "not traced."

| Value | Meaning |
|---|---|
| `injected_attributed` | header injected; call belongs to a known RUM action |
| `injected_unattributed` | header injected; no active action (parentless trace) |
| `adopted` | inbound `traceparent` mirrored; header + trace left as the caller set them |
| `skipped_off_allowlist` | host not in `traceHostAllowlist`; recorded locally, no header sent |
| *(absent)* | request not traced |

### Attributing app-owned coroutine calls

The SDK's tap/nav listeners run outside your coroutines, so a call fired from `viewModelScope.launch { … }`
on `Dispatchers.IO` is unattributed by default. Opt in by propagating the trace element captured on the
main thread:

```kotlin
withContext(TelemetryManager.traceElement()) { api.get() }
```

---

## API reference

All instance methods are on `TelemetryManager.getInstance()` (throws if called before `initialize`).
Calls made before the SDK is ready are buffered in a 50-item pre-init queue and replayed.

### Events and metrics

```kotlin
val tm = TelemetryManager.getInstance()

tm.recordEvent("checkout_started", mapOf("cart_size" to 3))
tm.recordMetric("image_decode_ms", 42.0, mapOf("screen" to "feed"))
```

### Errors

```kotlin
tm.trackError(throwable)                                    // simple
tm.trackError(                                              // with context
    error = throwable,
    errorCode = "PAY_001",
    productId = "checkout",
    userAction = "submit_payment"
)
tm.trackError("Payment declined", stackTrace = null)        // message-only
tm.recordCrash(throwable)                                   // report as a crash
```

Ambient context attached to every subsequent error:

```kotlin
tm.setProductContext("checkout_flow")
tm.setLastUserAction("tap_pay")
```

### Breadcrumbs

Circular buffer of the last 50, attached to crashes and errors.

```kotlin
tm.addBreadcrumb("Opened settings", category = "user", level = "info")
```

### Screens

Activities and Fragments are tracked automatically. For Compose, either register the nav controller
once:

```kotlin
TelemetryManager.getInstance().trackComposeScreens(navController)
```

…or track a single screen:

```kotlin
@Composable
fun ProfileScreen(navController: NavController) {
    TrackComposeScreen(navController, screenName = "ProfileScreen")
    // …
}
```

Manual duration reporting, when you own the timing:

```kotlin
tm.recordScreenDuration("Checkout", durationMs = 4200, exitMethod = "back")
```

### User profile

```kotlin
tm.setUserProfile(name = "Jane Doe", email = "jane@example.com", phone = "+2547…")
tm.clearUserProfile()
```

### Sessions and IDs

```kotlin
tm.startNewSession()
tm.endCurrentSession()
tm.getSessionId(); tm.getUserId(); tm.getDeviceId()
```

---

## Privacy

- **IDs**: device ID and user ID are SDK-generated and persisted locally; session ID rotates.
- **No PII by default** — name/email/phone are only sent if you call `setUserProfile`.
- Transport is HTTPS-only. API keys are redacted from logs.
- `traceparent` is sent **only** to hosts you enumerate in `traceHostAllowlist`.

### API key handling

Keep the key out of source control. Put it in `local.properties`:

```properties
TELEMETRY_API_KEY=edge_your_key_here
```

Expose it through `BuildConfig`:

```kotlin
// app/build.gradle.kts
val props = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    defaultConfig {
        buildConfigField("String", "TELEMETRY_API_KEY", "\"${props["TELEMETRY_API_KEY"]}\"")
    }
    buildFeatures { buildConfig = true }
}
```

Then `apiKey = BuildConfig.TELEMETRY_API_KEY`. Full details: [API Key Guide](docs/API_KEY_GUIDE.md).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `IllegalArgumentException: apiKey must start with 'edge_'` | Wrong key format — check the value reaching `TelemetryConfig` |
| `IllegalStateException: TelemetryManager not initialized` | `getInstance()` called before `initialize()` |
| No `http.request` events | Interceptor not added, or added as a *network* interceptor instead of an application interceptor |
| No `traceparent` on requests | Host missing from `traceHostAllowlist` (bare host, no scheme/port/path) |
| No `app.start` event | SDK initialized after the first `onResume`, or the process started in the background |
| No `app.exit` events | Device is below API 30, or there were no abnormal deaths to harvest |
| 401 from the collector | Key inactive/expired on the backend |

Verify the crash path end-to-end in a debug build:

```kotlin
TelemetryManager.getInstance().testCrashReporting("smoke test")
```

---

## Documentation

- [Event Schema Reference](docs/EVENT_SCHEMA_REFERENCE.md) — wire format per event type
- [Specs](docs/specs) — per-feature design docs (tracing, ANR, app-start, exit harvest, device signals)
- [Changelog](CHANGELOG.md)
- [v2 Migration Guide](docs/MIGRATION_GUIDE_V2.md)
- [API Key Guide](docs/API_KEY_GUIDE.md)

---

## Contributing

```bash
./gradlew :telemetry_library:testDebugUnitTest      # unit tests
./gradlew :telemetry_library:lintDebug              # lint
./gradlew :telemetry_library:assembleRelease        # build the AAR
./gradlew :telemetry_library:publishToMavenLocal    # local publish
```

Issues and PRs: [github.com/NCG-Africa/edge_telemetry_android](https://github.com/NCG-Africa/edge_telemetry_android)

## License

Apache 2.0.
