# Edge Telemetry Android SDK 3.0 — Implementation Plan

> Evolving `edge_telemetry_android` from a custom telemetry SDK (v2.1.12) into a competitive, OpenTelemetry-native observability platform that matches Sentry, NewRelic, and Dynatrace capabilities — while preserving developer ergonomics and soft-migrating existing consumers.

---

## 1. Goals & Non-Goals

### Goals
- Become **OpenTelemetry-native**: spans, metrics, and logs are the internal data model; the existing Flask-compatible payload becomes a pluggable exporter/adapter.
- **Zero-config auto-instrumentation** via a Gradle plugin performing build-time bytecode weaving (ASM, with ByteBuddy for agent-style transformations where ASM is awkward).
- Match competitor feature parity across four pillars:
    - **Sentry parity** — crash grouping/fingerprinting, release health, source map (R8 mapping) upload, performance traces.
    - **NewRelic parity** — distributed tracing (W3C `traceparent`), APM-style transactions, HTTP/DB spans, slow transaction detection.
    - **Dynatrace parity** — RUM, rich user sessions, *event-based* session replay.
- **Soft backward compatibility** with 2.1.12: every public API on `TelemetryManager` / `EdgeTelemetry` continues to function, annotated `@Deprecated` where superseded, with documented replacements. No forced code changes at upgrade.
- Support exporting to **third-party OTel backends** over **OTLP/HTTP** (Jaeger, Tempo, Honeycomb, Grafana Cloud, etc.), alongside the NCG Africa collector.

### Non-goals (for 3.0)
- OTLP/gRPC exporter — dropped for APK size; can be added in 3.x.
- Pixel-level session replay (screenshots / view hierarchy rendering) — we ship **event replay** only.
- Runtime ByteBuddy agent on device (APK size + stability risk).
- iOS / KMP — Android-only for this milestone.

---

## 2. High-Level Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│                   Consumer App (Application.onCreate)             │
│                  EdgeTelemetry.initialize(config)                 │
└──────────────────────────────┬────────────────────────────────────┘
                               │
┌──────────────────────────────▼────────────────────────────────────┐
│                      TelemetryManager (facade)                    │
│  - Public API (2.x compatible + 3.x additions)                    │
│  - Delegates to OTel SDK + service layer                          │
└──────────────────────────────┬────────────────────────────────────┘
                               │
       ┌───────────────────────┼───────────────────────┐
       │                       │                       │
┌──────▼────────┐   ┌──────────▼──────────┐   ┌────────▼──────────┐
│  OTel Core    │   │  Service Layer      │   │  Auto-Instr.      │
│  (SDK)        │   │  (existing, v2)     │   │  Runtime Hooks    │
│               │   │                     │   │                   │
│ - TracerProv. │   │ - CrashReporting    │   │ - ActivityLC cb   │
│ - MeterProv.  │   │ - SessionService    │   │ - ProcessLC obs   │
│ - LoggerProv. │   │ - UserProfile       │   │ - ANR watchdog    │
│ - Resource    │   │ - BatchProcessing   │   │ - Frame/Choreo.   │
│ - Propagators │   │ - EventTracking     │   │ - Compose hooks   │
└──────┬────────┘   └──────────┬──────────┘   └────────┬──────────┘
       │                       │                       │
       └───────────────────────┼───────────────────────┘
                               │
┌──────────────────────────────▼────────────────────────────────────┐
│                     Exporter Pipeline                             │
│   ┌──────────────┐  ┌─────────────┐  ┌──────────────────────┐     │
│   │  OTLP/HTTP   │  │ Edge (Flask │  │  Debug/Logcat        │     │
│   │  Exporter    │  │  payload    │  │  Exporter            │     │
│   │              │  │  adapter)   │  │                      │     │
│   └──────────────┘  └─────────────┘  └──────────────────────┘     │
│            │                │                                     │
│            ▼                ▼                                     │
│   BatchSpanProcessor + OfflineBatchStorage (Room) + WorkManager   │
└───────────────────────────────────────────────────────────────────┘
```

Parallel to the library runtime, a **Gradle plugin** performs build-time weaving:

```
┌─────────────────────────────────────────────────────────────────┐
│  edge-telemetry-gradle-plugin (applied in app's build.gradle)   │
│                                                                 │
│  - AGP AsmClassVisitorFactory pipeline (primary)                │
│  - ByteBuddy plugin tasks for complex transforms                │
│  - R8 mapping upload task (post-assemble)                       │
│                                                                 │
│  Instruments:                                                   │
│  - OkHttp clients, Retrofit builders, HttpURLConnection, Ktor   │
│  - Room @Dao, SupportSQLiteDatabase, SQLDelight, ContentProvider│
│  - Activity / Fragment / Compose navigation                     │
│  - View click listeners (tap instrumentation)                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Module Layout (3.0)

The repo becomes a **multi-module** Gradle project:

```
edge_telemetry_android/
├── telemetry-core/                 # OTel-backed runtime (replaces telemetry_library internals)
├── telemetry-api/                  # Public API surface — thin, stable, 2.x-compatible facade
├── telemetry-okhttp/               # OkHttp/Retrofit instrumentation helpers
├── telemetry-ktor/                 # Ktor client plugin
├── telemetry-compose/              # Jetpack Compose instrumentation (existing module promoted)
├── telemetry-room/                 # Room/SQLite instrumentation runtime
├── telemetry-replay/               # Event-based session replay recorder
├── telemetry-exporter-otlp/        # OTLP/HTTP exporter
├── telemetry-exporter-edge/        # Flask-compatible payload adapter (existing)
├── telemetry-gradle-plugin/        # Build-time ASM/ByteBuddy weaver + mapping upload
├── telemetry-testing/              # EdgeTelemetryTester (promoted)
└── telemetry-bom/                  # Bill-of-materials for version alignment
```

**Publishing:** each module to JitPack under `com.github.NCG-Africa:edge_telemetry_android:<module>:<version>`. The BOM lets consumers pin one version and get aligned modules.

**Backward compatibility:** `telemetry_library` (the old single module) remains published as a **meta artifact** that transitively depends on `telemetry-core + telemetry-api + telemetry-okhttp + telemetry-compose` so existing Gradle coordinates still work.

---

## 4. OpenTelemetry Integration

### 4.1 Dependencies
- `io.opentelemetry:opentelemetry-api:1.42.x`
- `io.opentelemetry:opentelemetry-sdk:1.42.x`
- `io.opentelemetry:opentelemetry-exporter-otlp-http` (HTTP only, no gRPC)
- `io.opentelemetry.semconv:opentelemetry-semconv:1.27.x`
- `io.opentelemetry.android:instrumentation-*` — selectively pull from upstream OTel Android where mature (lifecycle, crash, slow rendering); fork/wrap where we need richer behavior.

### 4.2 Data Model Mapping

| v2.x concept | v3.0 OTel mapping |
|---|---|
| `recordEvent(name, attrs)` | Span event on active span **or** standalone zero-duration span (`event.name` semantic) |
| `recordMetric(name, value, attrs)` | `DoubleHistogram` / `DoubleGauge` via `MeterProvider` |
| `trackError(e, ...)` | `span.recordException(e)` + `span.setStatus(ERROR)` + fingerprint attribute |
| `recordCrash(t)` | Crash span + `crash.*` attributes matching OTel incubating conventions |
| Breadcrumbs | Span events on the session-level span + OTel log records (`breadcrumb.*`) |
| Screen tracking | `ui.screen` span per Activity/Compose destination |
| Session | `session.id` resource attribute (OTel session semconv) |
| Network request | HTTP client span with W3C `traceparent` injection |

### 4.3 Resource Attributes (set once at init)
`service.name`, `service.version`, `telemetry.sdk.*`, `device.*`, `os.*`, `app.*`, `session.id`, `deployment.environment`, plus Edge-specific: `edge.api_key_hash`, `edge.sdk.channel`.

### 4.4 Propagators
- `W3CTraceContextPropagator` (default)
- `W3CBaggagePropagator`
- Optional `B3Propagator` for consumers with legacy backends.

---

## 5. Gradle Plugin Design

**Artifact:** `com.github.NCG-Africa.edge_telemetry_android:telemetry-gradle-plugin:3.0.0`

**Applied in consumer:**
```kotlin
plugins {
    id("com.android.application")
    id("com.ncgafrica.edge.telemetry") version "3.0.0"
}

edgeTelemetry {
    apiKey = providers.gradleProperty("edge.apiKey")
    autoInstrument {
        okhttp = true
        retrofit = true
        httpUrlConnection = true
        ktor = true
        room = true
        sqlite = true
        sqldelight = true
        contentProvider = true
        uiInteractions = true
    }
    mappingUpload {
        enabled = true
        variants = listOf("release")
    }
}
```

### 5.1 Weaving Strategy
- **Primary: AGP `AsmClassVisitorFactory`** — officially supported, fast, incremental. Used for ~90% of transforms.
- **ByteBuddy** — used where ASM visitor code becomes unmaintainable (e.g., rewriting method bodies to wrap in try/finally with span scope, or subclassing at build time). ByteBuddy is invoked by a custom Gradle task that runs **after** AGP's transform pipeline on the merged class output.
- Plugin registers visitors lazily per-class (filter by class name / interface) to keep build times low.

### 5.2 Auto-Instrumentation Targets

| Target | Technique | Bytecode trigger |
|---|---|---|
| `okhttp3.OkHttpClient$Builder.<init>` | ASM method exit hook | Auto-adds `EdgeOkHttpInterceptor` |
| `retrofit2.Retrofit$Builder.client(OkHttpClient)` | ASM wrap argument | Wraps client if no Edge interceptor present |
| `java.net.URL.openConnection()` | ByteBuddy delegation (since we can't modify JDK) — alternative: static helper + ASM rewrite of call sites | Wraps returned `HttpURLConnection` |
| `io.ktor.client.HttpClient.<init>` | ASM install plugin | Installs `EdgeKtorPlugin` |
| Room-generated `_Impl` classes (`@Dao`) | ASM method wrap | Spans around each DAO method, SQL attribute from annotations |
| `androidx.sqlite.db.SupportSQLiteDatabase.query/execSQL` | ASM call-site rewrite | Delegate to `EdgeSqliteTracer` |
| SQLDelight generated `QueriesImpl` | ASM method wrap | Spans around `executeQuery` / `execute` |
| `android.content.ContentResolver.query` | ASM call-site rewrite | Span with `content.uri` attribute |
| `android.view.View.setOnClickListener` | ASM call-site rewrite | Wraps listener to emit `ui.tap` span event |
| Compose: handled at runtime (see §7) | N/A | — |

### 5.3 R8 Mapping Upload Task
- Task `uploadEdgeMapping<Variant>` hooks into `assemble<Variant>` finalizer.
- Reads `mapping.txt` from AGP's `MAPPING_FILE` artifact.
- POSTs to `https://edgetelemetry.ncgafrica.com/collector/mappings` with `build_id` attribute matching what the runtime stamps into crash payloads.
- Supports upload skip + local archive for offline/CI-restricted builds.

---

## 6. Auto-Captured Data (Runtime)

### 6.1 UI Interactions
- **Views:** taps via bytecode-wrapped `setOnClickListener` → `ui.tap` span event with view id, class, visible text (redactable).
- **Gestures/scrolls:** `GestureDetector` hook on key container views instrumented by plugin.
- **Compose:** `Modifier.edgeTrack("cta_checkout")` public modifier + automatic via `CompositionLocal` wiring for `Button`, `Clickable`, `Text` (via Compose compiler plugin phase — deferred to 3.1 if scope slips; 3.0 ships explicit modifier + `semantics` sniffing).

### 6.2 HTTP/gRPC with W3C Context
- OkHttp interceptor (existing, upgraded): extracts / injects `traceparent`, `tracestate`.
- **Body/header capture:** full bodies with configurable redaction.
    - Default redaction patterns: email, phone (E.164), credit card (Luhn-verified), IBAN, JWTs, bearer tokens, AWS keys.
    - Header allowlist (default): `content-type`, `content-length`, `user-agent`, `accept`, `cache-control`. Everything else redacted unless explicitly allowed.
    - Body cap: 32 KB per direction; larger bodies get `edge.body.truncated=true`.
    - Per-request opt-out via `@EdgeNoCapture` annotation on Retrofit interfaces, or URL pattern blocklist.
- Supported libraries: OkHttp, Retrofit, HttpURLConnection, Ktor (auto-wired by plugin).

### 6.3 Database Spans
- Room `@Dao` methods → span `db.query` with `db.system=sqlite`, `db.statement` (redacted), `db.operation`, row count attribute on return.
- Raw `SupportSQLiteDatabase.query/execSQL` → same span shape.
- SQLDelight → span per generated query execution.
- `ContentResolver` → span with `content.uri`, operation.
- **Slow query detection:** spans exceeding configurable threshold (default 100ms) flagged with `edge.slow=true` and promoted to a dedicated "slow queries" metric.

### 6.4 Performance Signals
- **ANR:** watchdog thread pings main-thread `Handler`; 5s timeout → capture main-thread stack, emit `anr` event with thread dump.
- **Slow frames / jank:** `FrameMetricsAggregator` on API 24+, plus `Choreographer.FrameCallback` fallback. Frozen frames (>700ms) and slow frames (>16/33ms) counted as metrics.
- **App startup:** cold/warm/hot classification using `ProcessLifecycleOwner` + `Application.ATTACH_BASE_CONTEXT` timestamp → `app.startup` span with phases (process fork → first frame).
- **Memory pressure / storage:** keep existing `MemoryTracker` and storage monitor; emit as OTel gauges.

### 6.5 Event-Based Session Replay
- **Recorder** (`telemetry-replay`): ring buffer of structured events (taps, scrolls, text changes, screen transitions, network events, logs). No pixel capture.
- **Schema:** each event has `ts`, `type`, `target` (stable selector), `data` (redacted).
- **Sampling:** default 100% on crash, 10% on errored sessions, 0% otherwise. Configurable.
- **Upload:** flushed as OTel log records with `edge.replay.chunk.id` correlating to the session.
- **Retention:** last 5 minutes of events before a crash/error, capped at 500 events.

### 6.6 Logcat Capture
- Opt-in `captureLogcat = true`.
- Uses `Runtime.exec("logcat -v threadtime")` with ring buffer; tagged & filtered.
- Emitted as OTel log records with `logcat.priority`, `logcat.tag`.

### 6.7 Coroutine / Thread Tracing
- `CoroutineContext` element `EdgeSpanElement` that propagates the active span across `withContext` / `launch`.
- `kotlinx-coroutines-debug` optional integration for thread naming.
- Plugin weaves `ThreadPoolExecutor.submit` / `execute` call sites to wrap `Runnable` with span-propagating wrapper (optional, size-gated).

---

## 7. Compose Integration

- Existing `EdgeTelemetryCompose` promoted to `telemetry-compose` module.
- `trackComposeScreens(navController)` remains (deprecated alias kept).
- New: `EdgeTelemetryNavObserver` as a `NavController.OnDestinationChangedListener` → emits `ui.screen` spans with route template and arguments (redacted).
- `Modifier.edgeTrack(name, attrs)` — explicit tap/interaction tagging.
- `EdgeReplayRoot { content() }` composable — opt-in root for replay recording at the Compose layer (`Modifier.onGloballyPositioned` for layout structural events).

---

## 8. Crash Reporting — Sentry-Grade

### 8.1 Grouping & Fingerprinting
- Existing `CrashFingerprinter` refined: normalized stack frames (strip line numbers for library frames, keep for app frames), exception chain, synthetic vs user-thrown detection.
- Fingerprint becomes OTel span attribute `edge.crash.fingerprint`.
- Release health metrics: `crash_free_sessions`, `crash_free_users`, computed server-side from session spans + crash spans.

### 8.2 Symbolication
- R8 `mapping.txt` uploaded at build time keyed by `edge.build.id` (deterministic hash of `mapping.txt`).
- Runtime stamps every crash with `edge.build.id` so the backend matches mapping → symbolicated stack.
- Offline crashes: stack stored raw in Room; re-sent after network recovery (existing `CrashRetryManager` + WorkManager, upgraded).
- NDK/JNI symbolication: **out of scope for 3.0**, tracked for 3.1 (needs `ndk-build` integration + Breakpad).

### 8.3 Release Health
- `app.release` resource attribute (version + build number).
- Session outcomes: `healthy`, `errored`, `crashed`, `abnormal`.
- Backend computes adoption curves per release.

---

## 9. Privacy, Redaction & Data Governance

- **Default-on redaction** for HTTP bodies, logs, and replay events.
- Configurable `RedactionPolicy`:
  ```kotlin
  redaction {
      patterns += Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", IGNORE_CASE) // email
      headerAllowlist = setOf("content-type", "content-length")
      bodyMaxBytes = 32 * 1024
      urlBlocklist = listOf("**/auth/**", "**/payment/**")
  }
  ```
- PII scrubbing runs **before** data leaves the device.
- `enableLocationTracking` stays opt-in.
- New: `enableLogcatCapture`, `enableReplay`, `enableHttpBodyCapture` all explicitly toggleable.
- GDPR helpers: `TelemetryManager.deleteUserData()` purges on-device Room queue + sends tombstone.

---

## 10. Exporter Pipeline

| Exporter | Module | Notes |
|---|---|---|
| OTLP/HTTP | `telemetry-exporter-otlp` | Protobuf over HTTP/1.1. No gRPC. Supports custom headers (auth). |
| Edge Flask payload | `telemetry-exporter-edge` | Adapter: converts OTel spans/metrics/logs → existing JSON schema. Keeps current backend working. |
| Debug/Logcat | built-in | Dev-only; off in release. |

- `BatchSpanProcessor` + `BatchLogRecordProcessor` + `PeriodicMetricReader`.
- **Offline persistence:** existing Room-based `OfflineBatchStorage` keeps working; adapter layer stores *serialized OTLP batches* instead of Flask JSON. WorkManager retry preserved.
- Consumers choose exporters in config:
  ```kotlin
  exporters {
      edge(apiKey = "edge_...") // defaults on
      otlp(endpoint = "https://api.honeycomb.io/v1/traces", headers = mapOf("x-honeycomb-team" to "..."))
      debug(enabled = BuildConfig.DEBUG)
  }
  ```

---

## 11. Backward Compatibility Strategy (2.1.12 → 3.0)

### 11.1 Source Compatibility
- `TelemetryManager.initialize(app, config)` signature unchanged; `TelemetryConfig` gains new optional fields (defaulted).
- All existing methods retained: `recordEvent`, `recordMetric`, `trackError`, `recordCrash`, `setUserProfile`, `clearUserProfile`, `addBreadcrumb`, `startNewSession`, `getSessionId`, `setProductContext`, `setLastUserAction`, `createNetworkInterceptor`, `trackComposeScreens`.
- `EdgeTelemetry` typealias preserved (already deprecated).
- New methods added without shadowing old ones.
- Methods that change semantics (e.g., `recordEvent` now emits OTel span events) are marked `@Deprecated(level = WARNING)` only if the contract differs observably; otherwise silently upgraded.

### 11.2 Binary Compatibility
- Validated via **Metalava** / **binary-compatibility-validator**; CI fails if old ABI breaks.

### 11.3 Migration Guide
- `docs/migration-2.x-to-3.0.md` covering:
    - Gradle plugin adoption (optional but recommended).
    - New `exporters { }` block (old `endpoint` still works).
    - New redaction defaults (behavioral change — callout).
    - R8 mapping upload setup.

### 11.4 Feature Flags
- All new auto-instrumentation is **on by default** except: replay, logcat capture, HTTP body capture (privacy-sensitive), coroutine thread-pool weaving (size).
- `TelemetryConfig.compatibilityMode = V2` can be set to fully suppress new automatic collection for teams that want a gradual rollout.

---

## 12. Performance & Size Budgets

| Budget | Target |
|---|---|
| APK size impact (core + okhttp + compose) | ≤ 450 KB (proguarded) |
| Cold start overhead | ≤ 25 ms P50 on mid-range device |
| Per-event CPU | ≤ 100 µs P50 |
| Memory overhead (idle) | ≤ 4 MB |
| Build time impact (plugin, 1k classes) | ≤ 1.5× baseline |

Enforced by:
- Microbenchmark module (`benchmark/`) using `androidx.benchmark`.
- APK size CI check via `apkscale`.
- Startup macrobenchmark on Pixel 4a reference device.

---

## 13. Testing Strategy

- **Unit tests:** JUnit 4 + Mockk + Robolectric (existing).
- **OTel conformance:** in-memory `InMemorySpanExporter` assertions.
- **Instrumented tests:** Espresso + Compose test (existing).
- **Gradle plugin tests:** `TestKit` with fixture Android projects; assert weaved bytecode via ASM `Textifier` diff.
- **Contract tests:** OTLP payload validated against OTel collector in Docker (CI).
- **Compatibility matrix:** AGP 8.5–8.7, Kotlin 1.9/2.0/2.1, Gradle 8.4–8.10.
- **Golden tests** for Flask payload adapter (snapshot per event type — guards backend compat).

---

## 14. Phased Rollout

### Phase 0 — Foundation (Weeks 1–2)
- Multi-module split; BOM; CI pipelines; binary-compatibility-validator.
- Pull in OTel SDK; build `telemetry-core` with TracerProvider/MeterProvider/LoggerProvider.
- Wire existing services (`SessionService`, `CrashReporting`, etc.) onto OTel primitives behind feature flag.
- Ship `telemetry-exporter-edge` adapter so existing backend stays green.

**Exit criteria:** existing consumer apps upgrade to 3.0-alpha with zero code changes; all 2.x events still land in backend.

### Phase 1 — Gradle Plugin + Core Auto-Instrumentation (Weeks 3–5)
- Scaffold `telemetry-gradle-plugin` with AGP `AsmClassVisitorFactory`.
- Implement OkHttp + Retrofit + HttpURLConnection + Ktor weaving.
- Implement Activity/Fragment screen tracking via lifecycle callbacks (no bytecode needed — already in v2).
- R8 mapping upload task.
- `exporters { otlp(...) }` DSL + OTLP/HTTP exporter module.

**Exit criteria:** adding the plugin to a sample app auto-captures HTTP + screen spans without any runtime code changes.

### Phase 2 — Database + UI + Performance (Weeks 6–8)
- Room, SupportSQLite, SQLDelight, ContentProvider weaving.
- `View.setOnClickListener` weaving + Compose `Modifier.edgeTrack`.
- ANR watchdog, frame metrics, startup spans.
- Slow query detection.

**Exit criteria:** DB spans + UI taps + jank metrics visible end-to-end.

### Phase 3 — Replay + Logs + Redaction (Weeks 9–10)
- `telemetry-replay` recorder.
- Logcat capture pipeline.
- `RedactionPolicy` DSL + default PII patterns.
- HTTP body/header capture with redaction.

**Exit criteria:** replay chunks flush on crash; redaction verified via fuzz tests.

### Phase 4 — Crash Parity + Release Health (Weeks 11–12)
- Upgraded fingerprinter.
- Build ID stamping + mapping lookup path end-to-end.
- Release health metrics emitted.
- Coroutine context propagation.

**Exit criteria:** symbolicated crash with replay + breadcrumbs in backend.

### Phase 5 — Hardening & GA (Weeks 13–14)
- Performance budgets enforced.
- Docs: migration guide, plugin reference, DSL reference, semconv mapping.
- Sample apps: Views-only, Compose-only, mixed, multi-module.
- JitPack release as `3.0.0`.

---

## 15. Open Questions for Follow-up

1. **Backend readiness:** does `edgetelemetry.ncgafrica.com` accept OTLP/HTTP directly, or do we ship only the Flask adapter first and add native OTLP ingest in parallel?
2. **Mapping upload endpoint:** confirm `/collector/mappings` contract (multipart? signed URL?).
3. **Replay schema ownership:** is there an existing schema in the backend to conform to, or do we define it fresh in `docs/replay-schema.md`?
4. **License audit:** OTel SDK is Apache-2.0 (fine); ByteBuddy is Apache-2.0; confirm no GPL contamination from any transitive we pull in.
5. **Source review:** once the current `telemetry_library/src/main/java/...` tree is shared, Phase 0 estimates will firm up — especially around how much of the existing service layer can be mapped 1:1 onto OTel vs needs refactoring.

---

## 16. Success Metrics

- **Developer ergonomics:** adding SDK + plugin → meaningful telemetry in ≤ 5 lines of code.
- **Feature parity checklist** (Sentry / NewRelic / Dynatrace) ≥ 85% green at GA.
- **APK size impact** within budget.
- **Zero breaking changes** for 2.1.12 consumers (verified by binary-compat CI).
- **OTLP interop** verified against ≥ 2 third-party backends (Honeycomb, Grafana Tempo).