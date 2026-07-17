# Android spec: dead-code removal (audit #12, D14)

**Ticket:** [#37](https://github.com/NCG-Africa/edge_telemetry_android/issues/37) · **Map:** [#29](https://github.com/NCG-Africa/edge_telemetry_android/issues/29)
**Type:** research (AFK) · **Status:** spec, plan-only (no merged code)

## Problem

`sdk-audit.yaml:322-347,435-438` (D14) lists code that is compiled and shipped in the
AAR but never reaches the live wire. It is inert today, but an external reviewer or
security audit reads it as live surface — `IpLocationProvider` looks like the SDK phones
`ipinfo.io`; `EdgeTelemetryInterceptor` looks like it leaks full URLs. Deleting it shrinks
the audit surface to what actually runs.

This spec decides the **delete list**, the **public-API removal story** (`enableLocationTracking`,
`testConnectivity`), and the **sequencing** against other map tickets. Every claim below was
verified against source, not taken from the audit — two audit items needed correction (see
[Corrections](#corrections-to-the-audit)).

## Delete list (verified)

Each item: what to remove, the evidence it is dead, and its dependency.

### A. Location stack — **independent, delete now**

Provider code is never instantiated: `locationProvider` is declared `= null`
(`TelemetryManager.kt:111`) and never assigned, so the guarded reads at
`TelemetryManager.kt:639-640,651-652` always fall through to `currentLocation` (also never
set). `enableLocationTracking` is a no-op flag.

- **Delete** `core/location/IpLocationProvider.kt` (131 lines).
- **Delete** `core/location/LocationProvider.kt` (9-line interface) — only implementor is the file above; only-other-referent is the field below.
- **Delete** field `locationProvider` (`TelemetryManager.kt:111`) + imports (`:21-22`).
- **Delete** field `currentLocation` (`TelemetryManager.kt:112`) — never assigned.
- **Simplify** the two `location` blocks (`TelemetryManager.kt:639-652`): with the provider gone the value is always `null`. Coordinate with **#30**, which already removes the `location` field from the wire (`TelemetryDataOut`) — once that lands, the local `location` variable feeds nothing and the whole block is deleted, not just simplified.
- **Remove** config field `enableLocationTracking` (`TelemetryConfig.kt:20`) — see [Public API](#public-api-removal).
- **Delete tests** `IpLocationProviderTest.kt` and `LocationIntegrationTest.kt` (435 lines) — both exercise only this dead path.
- **Strip** the `enableLocationTracking = false,` line from 8 service/integration test configs (`UserProfileServiceTest.kt:36`, `EventTrackingServiceTest.kt:42`, `SessionServiceTest.kt:38`, `TelemetryManagerIntegrationTest.kt:42,481`, `EventIntegrationTest.kt:37`, `CrashReportingServiceTest.kt:52`, `BatchProcessingServiceTest.kt:39`) — mechanical, follows from removing the config field.

### B. `JsonEventTracker` — **independent, delete now**

Field `jsonEventTracker` is `= null` (`TelemetryManager.kt:108`), never assigned. Its only
call site, `jsonEventTracker?.testConnectivity()` (`TelemetryManager.kt:1026`), is a
permanent no-op that always logs "Event tracker not initialized". The class's send methods
are log-only stubs (audit #12).

- **Delete** `core/events/JsonEventTracker.kt` (327 lines).
- **Delete** field `jsonEventTracker` + import (`TelemetryManager.kt:108,19`).
- **Delete** public method `testConnectivity()` (`TelemetryManager.kt:1024-1028`) — see [Public API](#public-api-removal).
- The other referent, `EdgeTelemetryInterceptor` (constructor param, `:16`), dies with item D.

### C. `LegacyPerformanceTracker` — **independent, delete now**

`PerformanceTrackerFactory.createPerformanceTracker` unconditionally returns
`ModernPerformanceTracker` (`PerformanceTracker.kt:24-27`). `LegacyPerformanceTrackerWrapper`
(`:64-85`) is the only referent of `LegacyPerformanceTracker` and is itself never
instantiated. This is why frame sampling (the 10% `SAMPLING_RATE`) is effectively off — the
only sampler lives here (audit #12/#13).

- **Delete** `core/LegacyPerformanceTracker.kt` (291 lines).
- **Delete** class `LegacyPerformanceTrackerWrapper` (`PerformanceTracker.kt:61-85`).
- Cross-link **#36** (frame-metrics windowing) retired per-frame sampling anyway; this removal is the code-level tail of that decision and does not depend on it.

### D. `EdgeTelemetryInterceptor` — **blocked on #40, delete after**

Zero referents anywhere except its own file (grep confirmed). Public but unwired; sends the
**full URL incl. query** into `http.url` and breadcrumbs (`EdgeTelemetryInterceptor.kt:28,98`),
which is exactly the leak **#40** (unify network schema + URL sanitization, audit #2/#6) is
chartered to fix. #40 owns the interceptor consolidation and the sanitization contract.

- **Delete** `core/http/EdgeTelemetryInterceptor.kt` (130 lines) **as part of #40**, not here — removing it in isolation and then reworking network in #40 touches the same seam twice. Removing it also removes the last consumer of `JsonEventTracker` (item B), but B does not need to wait on D.

### E. `TelemetryPayload` wrapper — **independent, delete now**

The live serializer builds `TelemetryDataOut` and sends it directly — `"Send TelemetryDataOut
directly (no wrapper)"` (`TelemetryHttpClient.kt:165`). The outer `TelemetryPayload`
`{timestamp, device_id, data}` wrapper has **zero live constructors**; its import at
`TelemetryHttpClient.kt:10` is a dead import.

- **Delete** `data class TelemetryPayload` (`core/models/TelemetryBatch.kt:7-19`).
- **Delete** the dead import (`TelemetryHttpClient.kt:10`).
- **Keep** `TelemetryDataOut` + `TelemetryEventOut` (same file) — **live**, owned by **#30** (rename `type` → `telemetry_batch`, drop `device_id`/`location`). Do not touch them here.

### F. `EventBatchPayload` / `createEventBatchPayload` — **independent, delete now**

`FlutterPayloadFactory.createEventBatchPayload` (`FlutterCompatiblePayload.kt:152`) has **no
production callers** — the only callers are in the dead `LocationIntegrationTest.kt` (9
sites), which item A already deletes.

- **Delete** `data class EventBatchPayload` (`:36`) + `createEventBatchPayload` (`:152-…`).
- Coordinate with **#30**, which already edits this file (deletes `CrashBatchEnvelope` +
  `createCrashBatchEnvelope`). Same-file, independent edits; land together with #30's
  serializer work to avoid a second pass over `FlutterCompatiblePayload.kt`.

### G. Room dependency — **owned by #33, not this ticket**

Zero source usage of `androidx.room`, `@Entity`, `@Dao`, or `RoomDatabase`; declared as
`api(libs.androidx.room.runtime)` + `api(libs.androidx.room.ktx)` (`build.gradle.kts:123-124`),
runtime-only, no compiler/ksp. **Already decided in closed [#33](https://github.com/NCG-Africa/edge_telemetry_android/issues/33)** ("dead Room deps … dropped"). Listed here only for
completeness — do **not** re-own; it lands with #33's execution.

## Public API removal

Two removals touch the published surface. Both are safe to hard-remove — the SDK is
pre-1.0, `2.x`, distributed via JitPack; there is no compatibility promise, and both members
are functionally inert so no consumer relies on their behaviour.

| Member | Decision |
|---|---|
| `TelemetryConfig.enableLocationTracking` | **Remove** the field. It gates only dead code — no location is ever collected regardless of value. No deprecation cycle: keeping a deprecated no-op flag preserves the exact "SDK does geo" audit signal we are deleting. Consumers passing it get a compile error naming the removed param — the clearest possible signal, resolved by deleting one argument. |
| `TelemetryManager.testConnectivity()` | **Remove** the method. It is a debug helper that always logs "not initialized" and does nothing. Nothing wires it up. |

**No dual-write, no marker, no runtime flag.** These are compile-time source removals.

## Corrections to the audit

Verification changed two audit claims — recorded so downstream execution trusts this list,
not the raw audit:

1. **`TelemetryPayload` dead, but `TelemetryDataOut`/`TelemetryEventOut` are LIVE.** The
   audit lumps them; #30 correctly treats `TelemetryDataOut` as the live serializer. Only the
   unused `TelemetryPayload` wrapper + its dead import are removable here (item E).
2. **`enableLocationTracking` reads are guarded but the guard never fires** — the flag is not
   just unused, its `true` branch is unreachable because `locationProvider` is permanently
   null. Removal is behaviour-preserving.

## Sequencing summary

| Item | Depends on | When |
|---|---|---|
| A. Location stack | — (coordinate #30 wire-field) | now |
| B. `JsonEventTracker` | — | now |
| C. `LegacyPerformanceTracker` | — | now |
| E. `TelemetryPayload` wrapper | — | now |
| F. `EventBatchPayload` | — (land with #30's same-file edits) | now |
| D. `EdgeTelemetryInterceptor` | **#40** | after #40 |
| G. Room deps | **#33** (already decided) | with #33 |

Net removal: **~890 lines of production Kotlin** + 2 dead test files (~600 lines) + 1 config
flag + 1 public method, in one independent pass (items A/B/C/E/F) plus two follow-ons that
ride existing tickets (D→#40, G→#33).

## Verification for execution (non-negotiable)

After deletion, the build must prove nothing live depended on the removed code:

```bash
./gradlew :telemetry_library:testDebugUnitTest :telemetry_library:assembleRelease
```

Both must pass with **zero** references to the deleted symbols remaining (grep the tree for
each name). The compile step is the check — dead code that compiles-clean after removal was
genuinely dead.
