# Edge Telemetry Android SDK — Pre-3.0 Refactor Plan

> Refactor the existing `telemetry_library` (v2.1.12) into a clean, testable, modular-ready monolith **before** beginning the 3.0 OpenTelemetry / ByteBuddy rewrite (`plan.md`). The goal is a codebase where Phase 0 of the 3.0 plan becomes a mechanical re-wiring exercise rather than an archaeological dig.

---

## 1. Guiding Principles

1. **No behavior changes.** This refactor must be invisible to consumers. `TelemetryManager`'s public API and on-the-wire payloads stay identical. Version stays `2.x` throughout — refactor ships as `2.2.0`.
2. **Safety net before surgery.** 80%+ unit-test coverage on the critical paths lands *before* any structural change.
3. **Clean Architecture layering** — `domain` (pure Kotlin, no Android), `data` (platform + IO adapters), `presentation` (public API facade). Enforced by package structure + ArchUnit/Konsist tests.
4. **Monolith with module-ready seams.** One Gradle module, but packages are organized so each future submodule (`telemetry-core`, `telemetry-okhttp`, `telemetry-compose`, etc.) can be extracted by moving a package with zero cross-cuts.
5. **Manual constructor injection.** No DI framework. A single `TelemetryGraph` object composes the graph at `initialize()`.
6. **Coroutines + Flow everywhere internally.** Public API preserves existing signatures; new suspend variants added alongside.
7. **Kill hidden singletons.** `object` holders and static mutable state become constructor-injected collaborators behind interfaces.

---

## 2. Target Package Structure (Clean Architecture)

```
com.ncgafrica.edge.telemetry/
│
├── api/                              ← PUBLIC — stable facade (2.x compatible)
│   ├── TelemetryManager.kt
│   ├── EdgeTelemetry.kt             (typealias / deprecated shim)
│   ├── config/
│   │   ├── TelemetryConfig.kt
│   │   └── ConfigBuilder.kt
│   ├── model/                        (public DTOs: UserProfile, Breadcrumb, …)
│   └── network/
│       └── TelemetryInterceptor.kt
│
├── domain/                           ← PURE KOTLIN — no android.*, no okhttp, no room
│   ├── model/                        (Event, Metric, CrashReport, Session, …)
│   ├── usecase/
│   │   ├── RecordEventUseCase.kt
│   │   ├── RecordMetricUseCase.kt
│   │   ├── TrackErrorUseCase.kt
│   │   ├── StartSessionUseCase.kt
│   │   ├── EndSessionUseCase.kt
│   │   ├── FlushQueueUseCase.kt
│   │   └── …
│   ├── port/                         ← interfaces the domain depends on
│   │   ├── EventSink.kt
│   │   ├── CrashSink.kt
│   │   ├── SessionStore.kt
│   │   ├── UserProfileStore.kt
│   │   ├── DeviceInfoProvider.kt
│   │   ├── NetworkStateProvider.kt
│   │   ├── Clock.kt
│   │   ├── IdGenerator.kt
│   │   ├── Logger.kt
│   │   └── TelemetryExporter.kt
│   └── policy/
│       ├── SamplingPolicy.kt
│       ├── RetryPolicy.kt
│       └── BatchingPolicy.kt
│
├── data/                             ← IMPL LAYER — adapters for ports
│   ├── exporter/
│   │   ├── FlaskHttpExporter.kt
│   │   └── PayloadSerializer.kt
│   ├── storage/
│   │   ├── room/                     (Room DB, DAOs)
│   │   ├── OfflineBatchStore.kt
│   │   └── SessionStoreImpl.kt
│   ├── platform/                     ← android.* wrappers
│   │   ├── AndroidDeviceInfoProvider.kt
│   │   ├── AndroidNetworkStateProvider.kt
│   │   ├── AndroidClock.kt
│   │   ├── AndroidLogger.kt
│   │   └── lifecycle/
│   │       ├── ActivityLifecycleTracker.kt
│   │       └── ProcessLifecycleObserver.kt
│   ├── crash/
│   │   ├── UncaughtHandlerInstaller.kt
│   │   ├── CrashFingerprinter.kt
│   │   └── CrashSinkImpl.kt
│   ├── network/
│   │   ├── OkHttpTelemetryInterceptor.kt
│   │   └── ConnectivityMonitor.kt
│   └── scheduler/
│       ├── WorkManagerScheduler.kt
│       └── CoroutineDispatchers.kt
│
├── internal/                         ← EVERYTHING NON-PUBLIC
│   ├── TelemetryGraph.kt            (manual DI composition root)
│   ├── TelemetryScope.kt            (SDK-owned CoroutineScope)
│   └── Errors.kt                    (sealed error hierarchy)
│
└── compose/                          ← stays, but depends only on api/
    └── EdgeTelemetryCompose.kt
```

**Package rules (enforced by Konsist tests):**
- `domain/**` must not import `android.*`, `androidx.*`, `okhttp3.*`, `retrofit2.*`, `io.opentelemetry.*`, `androidx.room.*`.
- `data/**` may import platform packages but must only depend on `domain` interfaces, never on other `data` packages directly unless explicitly wired.
- `api/**` is the only package consumers can import. Everything under `internal/` + `data/` is hidden via Kotlin `internal` visibility where possible.

---

## 3. Pain Points → Fixes

| Pain point (you flagged) | Refactor response |
|---|---|
| **God class: `TelemetryManager`** | Becomes a thin facade that delegates to use cases. Each current method → one use case class. Manager holds no business logic, only routes calls. |
| **Tight Android coupling** | All `android.*` access moves behind `domain/port/*` interfaces with `Android*` implementations in `data/platform/`. Domain becomes JVM-testable with no Robolectric. |
| **Inconsistent error handling / swallowing** | Introduce `sealed class TelemetryError` + `Result<T, TelemetryError>` return type for internal operations. Single `ErrorReporter` port handles all non-fatal SDK errors uniformly (logged, never thrown). A lint rule (detekt) bans `catch (e: Exception) { }`. |
| **Unclear public vs internal API** | Hard split: `api/` package is public, everything else is `internal` modifier or in `internal/` package. Metalava baseline locks the public surface. |
| **Mutable global state / singletons** | `TelemetryGraph` is the *only* singleton; everything else is an injected collaborator. All `object` classes audited — converted to classes or kept only if genuinely stateless utilities. |

---

## 4. Async / Threading Model

- Introduce a single `TelemetryScope: CoroutineScope` owned by `TelemetryGraph`, built from `SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler`.
- A `CoroutineDispatchers` holder (`main`, `io`, `default`, `unconfined`) injected everywhere — tests replace it with `UnconfinedTestDispatcher`.
- **Public API compatibility rule:** every existing non-suspend method keeps its signature. Internally, it launches into `TelemetryScope` and returns immediately (fire-and-forget semantics preserved). A new `suspend fun recordEventAwait(...)` variant is added where awaiting completion is useful.
- Background work that used `Thread` / `Executors` / `HandlerThread` migrates to structured coroutines. `WorkManager` stays for cross-process persistence.
- Flows replace the existing callback/listener patterns for session state, connectivity, and queue status. Old listeners kept as deprecated adapters over `Flow.collect`.

---

## 5. Dependency Injection — Manual Graph

Single composition root in `internal/TelemetryGraph.kt`:

```kotlin
internal class TelemetryGraph private constructor(
    val config: TelemetryConfig,
    val dispatchers: CoroutineDispatchers,
    val scope: CoroutineScope,
    val clock: Clock,
    val idGen: IdGenerator,
    val logger: Logger,
    val deviceInfo: DeviceInfoProvider,
    val networkState: NetworkStateProvider,
    val sessionStore: SessionStore,
    val userStore: UserProfileStore,
    val eventSink: EventSink,
    val crashSink: CrashSink,
    val exporter: TelemetryExporter,
    val samplingPolicy: SamplingPolicy,
    val retryPolicy: RetryPolicy,
    val batchingPolicy: BatchingPolicy,
    val recordEvent: RecordEventUseCase,
    val recordMetric: RecordMetricUseCase,
    val trackError: TrackErrorUseCase,
    val startSession: StartSessionUseCase,
    val endSession: EndSessionUseCase,
    val flushQueue: FlushQueueUseCase,
    // …
) {
    companion object {
        fun build(app: Application, config: TelemetryConfig): TelemetryGraph { /* wiring */ }
    }
}
```

- `TelemetryManager` holds a `lateinit var graph: TelemetryGraph`, set in `initialize()`.
- Every collaborator is constructed explicitly in `build()` — no reflection, no annotations, no generated code.
- Tests build a `TestTelemetryGraph` with fakes / in-memory stores.

---

## 6. Testing Strategy — Safety Net First

### 6.1 Characterization tests (land **before** any refactor change)
Goal: 80%+ line coverage on the high-risk paths. These tests pin *current* behavior, even if the current behavior is awkward — they'll be updated intentionally as refactors change internals.

- `TelemetryManager` — every public method, happy path + error path.
- Crash reporting flow — uncaught exception → payload emitted → retry on failure.
- Session lifecycle — start, timeout, end, resume.
- Offline queue — enqueue, persist, flush on connectivity.
- Network interceptor — request/response capture, header propagation.
- Payload serialization — golden JSON snapshots of every event type (locks wire format).

### 6.2 Tools
- **JUnit 4**, **Mockk**, **Truth** or **Kotest assertions**.
- **Turbine** for `Flow` testing.
- **Robolectric** only where Android SDK touch is unavoidable (lifecycle, Context). Domain tests run on plain JVM.
- **Kover** for coverage reporting; CI fails below 80% on `domain/**` and 70% overall.
- **Golden payload tests** — JSON snapshots in `src/test/resources/golden/` diffed on every build.

### 6.3 Architecture tests
- **Konsist** rules enforcing package boundaries (see §2).
- **Detekt** with custom rules: no empty catches, no `!!`, no `GlobalScope`, no `runBlocking` outside tests.
- **Metalava** (or `binary-compatibility-validator`) baselining public API — CI fails on accidental breaks.

---

## 7. Error Handling Overhaul

```kotlin
sealed class TelemetryError {
    data class Serialization(val cause: Throwable) : TelemetryError()
    data class Transport(val cause: Throwable, val retryable: Boolean) : TelemetryError()
    data class Storage(val cause: Throwable) : TelemetryError()
    data class Config(val message: String) : TelemetryError()
    data class Unknown(val cause: Throwable) : TelemetryError()
}

internal sealed class TelemetryResult<out T> {
    data class Ok<T>(val value: T) : TelemetryResult<T>()
    data class Err(val error: TelemetryError) : TelemetryResult<Nothing>()
}
```

- All use cases return `TelemetryResult`.
- A single `ErrorReporter` (port) funnels errors to logs + internal metrics. Never propagates exceptions to the host app.
- Top-level `CoroutineExceptionHandler` on `TelemetryScope` catches anything that slips through and routes to `ErrorReporter`.
- Detekt rule bans `catch (e: Exception) { /* nothing */ }` — must call `ErrorReporter.report(...)`.

---

## 8. Public API Audit

Deliverable: `docs/public-api-surface.md` enumerating every currently-exposed symbol.

- Everything intended to be public → stays in `api/` package, marked with no visibility modifier (i.e., `public`).
- Everything else gets `internal` modifier. Consumers who imported non-API symbols will break — this is acceptable within 2.x minor bumps only if documented, otherwise kept `public` but annotated `@InternalTelemetryApi` (opt-in with `@RequiresOptIn`).
- Metalava baseline committed; CI enforces no drift without a changelog entry.

---

## 9. Phased Execution

Each phase is independently shippable. **No phase starts until the previous phase's tests are green and coverage gates pass.**

### Phase R0 — Audit & Baseline (2–3 days)
- Generate current dependency graph (`./gradlew :telemetry_library:dependencies > deps.txt`).
- Run detekt + Android Lint; baseline existing violations.
- Generate Metalava baseline of public API.
- Coverage report — document starting coverage %.
- Write `docs/code-audit.md`: list every `object`, every `!!`, every empty catch, every file >400 lines, every class with >10 public methods.

**Exit:** audit document + baselines in repo.

### Phase R1 — Safety Net (1 week)
- Add characterization tests until coverage ≥ 80% on critical paths.
- Add golden-payload snapshots for every event type.
- Wire Kover + CI gates (tests must pass, coverage thresholds enforced).
- Add Konsist + ArchUnit test scaffolding (rules initially permissive, tightened phase by phase).

**Exit:** `./gradlew check` covers unit tests + coverage + arch tests. Red build blocks merges.

### Phase R2 — Error Handling & Logging (3–4 days)
- Introduce `TelemetryError`, `TelemetryResult`, `ErrorReporter`.
- Sweep: every `try/catch` audited — rethrow, convert to Result, or report-and-swallow with justification comment.
- Detekt custom rules added for empty catches, `!!`, `GlobalScope`.

**Exit:** zero empty catches; every swallowed exception flows through `ErrorReporter`.

### Phase R3 — Coroutines & Dispatchers (1 week)
- Introduce `TelemetryScope` + `CoroutineDispatchers`.
- Migrate internal `Thread`/`Executor` usage to coroutines, one subsystem at a time: serialization → exporter → storage → session → crash.
- Public API retains current signatures; internally launches into `TelemetryScope`.
- Replace listener-based APIs' internals with `SharedFlow` / `StateFlow`; keep old listener surfaces as adapters.

**Exit:** no raw `Thread.start()` or `Executors.new*` in the codebase (enforced by detekt).

### Phase R4 — Port Extraction (Android Decoupling) (1 week)
- Define `domain/port/*` interfaces: `Clock`, `IdGenerator`, `Logger`, `DeviceInfoProvider`, `NetworkStateProvider`, `SessionStore`, `UserProfileStore`, `EventSink`, `CrashSink`, `TelemetryExporter`, `ErrorReporter`.
- Move existing implementations into `data/platform/*`, `data/storage/*`, `data/exporter/*`, `data/crash/*`, `data/network/*`.
- Update callers to depend on the interface, not the impl.

**Exit:** Konsist test passes — no `android.*` / `okhttp3.*` / `androidx.room.*` imports under `domain/**`.

### Phase R5 — Use Cases & God-Class Breakup (1.5 weeks)
- Extract one use case at a time from `TelemetryManager` — `RecordEventUseCase`, `RecordMetricUseCase`, `TrackErrorUseCase`, `StartSessionUseCase`, `EndSessionUseCase`, `FlushQueueUseCase`, `SetUserProfileUseCase`, `AddBreadcrumbUseCase`, `SetProductContextUseCase`, `RecordCrashUseCase`.
- `TelemetryManager` shrinks to a routing facade; each method is ≤ 3 lines (delegate to graph.useCase).
- Target: no class in the SDK >300 lines, no method >40 lines.

**Exit:** `TelemetryManager.kt` under 200 lines. Every use case has ≥ 90% unit test coverage in isolation.

### Phase R6 — Composition Root (3–4 days)
- Build `TelemetryGraph.build(app, config)`.
- `TelemetryManager.initialize()` delegates entirely to graph construction.
- Kill remaining `object` singletons — each becomes a class instantiated by the graph. Justified exceptions (genuinely stateless helpers) documented in `docs/singletons.md`.

**Exit:** `TelemetryGraph` is the only SDK-level singleton. Test graph can be constructed with fakes in under 20 lines.

### Phase R7 — Public API Lockdown (3–4 days)
- Audit every non-`api/` symbol exposed as `public` → mark `internal` or annotate `@InternalTelemetryApi` with `@RequiresOptIn`.
- Metalava baseline refreshed and locked.
- `docs/public-api-surface.md` finalized.
- Deprecate `EdgeTelemetry` typealias with a clear migration message.

**Exit:** Metalava CI gate passes; public API documented and minimal.

### Phase R8 — Module-Ready Seams (3–4 days)
- Internal package layout audited against 3.0 target module split (see `plan.md` §3).
- Move code so that each future module = one package with no outbound dependencies except `domain` + `api`.
- Verify with a Konsist test that asserts each `data.<area>` package only imports from its own subtree + `domain` + `api`.
- Do NOT split into Gradle modules yet — that happens in 3.0 Phase 0.

**Exit:** extracting `data.network.*` into its own module would require zero source changes other than a new `build.gradle.kts` and `package` imports. Verified on a throwaway branch.

### Phase R9 — Release 2.2.0 (2–3 days)
- Full regression pass against a sample app.
- Update `CHANGELOG.md` — refactor is invisible to consumers, but note internal restructuring.
- Bump version to `2.2.0`.
- Publish to JitPack.

**Exit:** 2.2.0 tagged, consumers upgrading report no behavioral change. 3.0 work begins from this baseline.

---

## 10. Total Timeline

| Phase | Duration |
|---|---|
| R0 Audit & Baseline | 2–3 days |
| R1 Safety Net | 1 week |
| R2 Error Handling | 3–4 days |
| R3 Coroutines | 1 week |
| R4 Port Extraction | 1 week |
| R5 Use Cases | 1.5 weeks |
| R6 Composition Root | 3–4 days |
| R7 API Lockdown | 3–4 days |
| R8 Module-Ready Seams | 3–4 days |
| R9 Release 2.2.0 | 2–3 days |
| **Total** | **~6–7 weeks** |

After 2.2.0 ships, the 3.0 plan in `plan.md` begins with Phase 0 — which becomes a largely mechanical exercise because the seams already exist.

---

## 11. Ralphy PRD (`REFACTOR-PRD.md`)

Create a companion checklist in the repo root so Ralphy can drive this autonomously. Each item below maps to a focused commit.

```markdown
# Edge Telemetry Refactor — Task List

See refactor-plan.md for architectural context. Phases are strictly ordered.

## Phase R0 — Audit & Baseline
- [x] Generate dependency graph and commit as docs/deps.txt
- [ ] Run detekt with default rules, commit baseline as detekt-baseline.xml
- [ ] Add Metalava plugin, generate and commit api/current.api baseline
- [ ] Add Kover, generate initial coverage report, commit to docs/coverage-baseline.md
- [ ] Write docs/code-audit.md listing files >400 lines, classes with >10 public methods, all `object` declarations, all `!!` usages, all empty catch blocks

## Phase R1 — Safety Net
- [ ] Add JUnit + Mockk + Turbine + Kotest-assertions to test dependencies
- [ ] Write characterization tests for TelemetryManager.recordEvent covering happy path and 3 error cases
- [ ] Write characterization tests for TelemetryManager.recordMetric
- [ ] Write characterization tests for TelemetryManager.trackError
- [ ] Write characterization tests for TelemetryManager.recordCrash including uncaught handler flow
- [ ] Write characterization tests for session start/timeout/end/resume
- [ ] Write characterization tests for offline queue enqueue/persist/flush
- [ ] Write characterization tests for OkHttp interceptor request/response capture
- [ ] Add golden JSON snapshot tests for every payload type under src/test/resources/golden/
- [ ] Configure Kover thresholds: 80% on domain paths, 70% overall
- [ ] Add Konsist test module with initial permissive rules
- [ ] Wire coverage + arch tests into ./gradlew check

## Phase R2 — Error Handling
- [ ] Create sealed TelemetryError hierarchy in internal/Errors.kt
- [ ] Create TelemetryResult<T> sealed type
- [ ] Define ErrorReporter port interface in domain/port/
- [ ] Implement AndroidLoggerErrorReporter in data/platform/
- [ ] Add detekt rule banning empty catch blocks
- [ ] Add detekt rule banning !! operator in src/main (allow in tests)
- [ ] Add detekt rule banning GlobalScope usage
- [ ] Sweep codebase: replace every empty catch with ErrorReporter.report call
- [ ] Convert internal APIs with throwing error paths to return TelemetryResult

## Phase R3 — Coroutines & Dispatchers
- [ ] Add CoroutineDispatchers data class with main/io/default/unconfined
- [ ] Add TelemetryScope factory using SupervisorJob + CoroutineExceptionHandler
- [ ] Migrate PayloadSerializer threading to coroutines on dispatchers.default
- [ ] Migrate FlaskHttpExporter to coroutines on dispatchers.io
- [ ] Migrate OfflineBatchStore operations to coroutines on dispatchers.io
- [ ] Migrate SessionService timers to coroutines
- [ ] Migrate CrashReporting background work to coroutines
- [ ] Replace listener-based session state API internals with StateFlow, keep old listener as adapter
- [ ] Replace connectivity listener internals with StateFlow
- [ ] Add detekt rule banning Thread() and Executors.new* in main source
- [ ] Add suspend fun variants of recordEvent, recordMetric, trackError alongside existing methods

## Phase R4 — Port Extraction
- [ ] Define Clock port and AndroidClock impl, inject into SessionService
- [ ] Define IdGenerator port and UuidIdGenerator impl
- [ ] Define Logger port and AndroidLogger impl, replace all android.util.Log calls
- [ ] Define DeviceInfoProvider port, move DeviceInfo collection into AndroidDeviceInfoProvider
- [ ] Define NetworkStateProvider port, move ConnectivityManager usage into AndroidNetworkStateProvider
- [ ] Define SessionStore port, split SessionService into domain logic + SessionStoreImpl
- [ ] Define UserProfileStore port, split UserProfile handling similarly
- [ ] Define EventSink port, move enqueue logic behind it
- [ ] Define CrashSink port, move crash payload assembly behind it
- [ ] Define TelemetryExporter port, move Flask HTTP client behind it
- [ ] Add Konsist test: no android.*, okhttp3.*, androidx.room.* imports allowed under domain/**
- [ ] Move existing classes into data/platform, data/storage, data/exporter, data/crash, data/network packages

## Phase R5 — Use Cases & God-Class Breakup
- [ ] Extract RecordEventUseCase from TelemetryManager.recordEvent, add isolated unit tests
- [ ] Extract RecordMetricUseCase, add tests
- [ ] Extract TrackErrorUseCase, add tests
- [ ] Extract StartSessionUseCase + EndSessionUseCase, add tests
- [ ] Extract FlushQueueUseCase, add tests
- [ ] Extract SetUserProfileUseCase + ClearUserProfileUseCase, add tests
- [ ] Extract AddBreadcrumbUseCase, add tests
- [ ] Extract SetProductContextUseCase + SetLastUserActionUseCase, add tests
- [ ] Extract RecordCrashUseCase, add tests
- [ ] Reduce TelemetryManager to pure delegation — each method ≤3 lines
- [ ] Verify no class >300 lines and no method >40 lines via detekt

## Phase R6 — Composition Root
- [ ] Create internal/TelemetryGraph.kt with build(app, config) factory
- [ ] Wire every collaborator explicitly in TelemetryGraph.build
- [ ] Refactor TelemetryManager.initialize to delegate entirely to TelemetryGraph.build
- [ ] Convert remaining `object` singletons to classes owned by TelemetryGraph, or document as stateless in docs/singletons.md
- [ ] Add test TestTelemetryGraph builder with fakes for all ports

## Phase R7 — Public API Lockdown
- [ ] Audit every public symbol outside api/ package, mark internal or annotate @InternalTelemetryApi
- [ ] Define @RequiresOptIn annotation InternalTelemetryApi
- [ ] Generate docs/public-api-surface.md from Metalava output
- [ ] Add @Deprecated to EdgeTelemetry typealias with replacement guidance
- [ ] Lock Metalava baseline, require CHANGELOG entry for any API drift

## Phase R8 — Module-Ready Seams
- [ ] Audit package layout against plan.md §3 target module split
- [ ] Rearrange data/ subpackages so each future module = one subtree
- [ ] Add Konsist test asserting data.network depends only on domain + api
- [ ] Add Konsist test asserting data.exporter depends only on domain + api
- [ ] Add Konsist test asserting data.storage depends only on domain + api
- [ ] Add Konsist test asserting data.crash depends only on domain + api
- [ ] On a throwaway branch, extract data.network into a separate Gradle module as a dry run; document findings in docs/modularization-dry-run.md; revert branch

## Phase R9 — Release 2.2.0
- [ ] Full manual regression against sample app — verify payloads unchanged via golden snapshots
- [ ] Update CHANGELOG.md with internal restructuring notes
- [ ] Bump version to 2.2.0 in build.gradle.kts
- [ ] Tag v2.2.0 and publish to JitPack
- [ ] Update README with architecture section linking to refactor-plan.md
```

---

## 12. Running with Ralphy

From the project root (on the `refactor/audit` branch):

```bash
# Sequential — strongly recommended for R0→R3 (cross-cutting foundational changes)
ralphy --prd REFACTOR-PRD.md --max-iterations 5

# Parallel only becomes safe inside R4 and R5 where use-case extractions are independent
ralphy --prd REFACTOR-PRD.md --parallel --max-parallel 3
```

**Rules to add to `.ralphy/config.yaml` specifically for the refactor:**

```yaml
rules:
  - "This is a pure refactor — no behavior changes. Existing wire payloads must be byte-identical."
  - "All public API methods on TelemetryManager must retain their existing signatures."
  - "Run ./gradlew check before marking any task complete — tests, coverage, detekt, konsist, metalava all must pass."
  - "Coverage must never decrease. If a refactor drops coverage below threshold, add tests before finalizing."
  - "No new runtime dependencies may be added without explicit human approval."
  - "Golden payload snapshots in src/test/resources/golden/ must not change without a documented reason."
  - "Every commit message must reference the phase (e.g., 'R4: extract Clock port')."
```

---

## 13. Open Questions

1. **Sample app access** — do we have a reference consumer app to run regression against, or should Phase R0 include scaffolding a minimal test harness?
2. **Wire format stability** — are there any 2.1.12 payload fields currently undocumented that the backend relies on? Golden snapshots only protect what we capture.
3. **Consumer count** — how many apps currently integrate 2.1.12? Influences how strict the `@InternalTelemetryApi` opt-in gating should be vs. hard `internal` visibility.
4. **CI environment** — is there an existing CI (GitHub Actions?) we can extend, or does R1 include standing one up?