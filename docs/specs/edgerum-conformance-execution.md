# EdgeRUM conformance + new datapoints — execution PRD

Rolls the 15 closed planning specs from wayfinder map [#29](https://github.com/NCG-Africa/edge_telemetry_android/issues/29) into one buildable handoff. The map was plan-only; this is the "now build it" spec. Each decision below links its source spec — read the linked `.md` for file:line detail and rationale; this PRD is the execution order, seams, and acceptance surface.

## Problem Statement

A consuming app integrates `edge_telemetry_android` expecting its telemetry to land, whole and correctly shaped, in the EdgeRUM backend. Today it doesn't:

- The SDK emits **two different envelope shapes** and two disjoint network-event schemas, so auto-captured HTTP is dark server-side and crash data races itself to disk and loses.
- Timestamps carry a literal `Z` on **device-local** time, so every non-UTC device is silently wrong by its offset — and `Instant.now()` crashes outright on Android 7.0/7.1 (the SDK's own min-SDK).
- Low-activity sessions **hold telemetry forever** (the flush timer Runnable is empty), unbounded buffers can grow without limit, and a per-frame `frame_drop` firehose emits ~36k events/session.
- ~890 LOC of unwired dead code presents as live attack/audit surface.
- Three datapoints a RUM product is expected to have — **user interactions, distributed traces, ANR/hang detection** — don't exist.

From the consuming app's perspective: "I integrated the SDK, but my dashboard is missing HTTP calls, my crash session context is empty, my timestamps are skewed, and there's no tap/trace/ANR data at all."

## Solution

Bring the SDK to EdgeRUM wire-format conformance and add the three missing datapoints, in one coordinated pass. Every event leaves on **one `telemetry_batch` envelope** with `sdk.*` common attrs; every timestamp is true-UTC ISO-8601; every network call is one `http.request`; crashes persist synchronously before re-throw with session/user frozen at crash time; buffers are bounded; the frame firehose becomes a windowed summary; dead code is gone; and taps, traces, and ANR/hang land as first-class events. Hub decisions (D1/D3/D6/D7/D8/D9/D11/D14) are fixed input, not re-litigated.

## User Stories

1. As a consuming app developer, I want all telemetry to leave on a single `telemetry_batch` envelope, so that the backend has one shape to parse and no traffic is silently dropped for being the "wrong" format.
2. As a consuming app developer, I want `sdk.version` and `sdk.platform` on every batch as common attributes, so that the backend can disambiguate SDK versions without relying on `X-SDK-*` headers.
3. As a backend operator, I want the hard cutover to the unified envelope (no dual-emit), so that I never have to reconcile two live formats from the same SDK version.
4. As an on-call engineer, I want a crash written synchronously to `filesDir` before the process re-throws, so that fatal crashes are never lost to the async race that drops them today.
5. As an on-call engineer, I want `session.*` and `user.*` frozen at crash time, so that crash reports carry the context that was live when the app died, not empty or post-death values.
6. As an on-call engineer, I want crashes to arrive as a standard `app.crash` event with `is_fatal`/`handled` flags, so that fatal and non-fatal errors are queryable the same way as every other event.
7. As a consuming app developer, I want persisted crashes replayed and deleted only after a 2xx, so that a crash survives an offline death and isn't dropped or duplicated.
8. As a data analyst, I want every timestamp in ms-precision ISO-8601 true UTC, so that events from devices in any timezone line up on one timeline.
9. As a consuming app developer on Android 7.0/7.1, I want the SDK to never call `Instant.now()`, so that the SDK doesn't crash on my min-SDK devices — including on the crash rail itself.
10. As a maintainer, I want one thread-safe UTC time helper instead of seven ad-hoc `SimpleDateFormat`s, so that timestamp formatting can't drift or race.
11. As a consuming app developer, I want the flush timer to force-send partial batches, so that a low-activity session's telemetry actually ships instead of being held indefinitely.
12. As a consuming app developer, I want an empty queue to skip the POST, so that idle apps don't generate heartbeat traffic.
13. As a backend operator, I want the offline store bounded to 200 envelopes drop-oldest, so that an app offline for a long time can't grow storage unboundedly.
14. As a backend operator, I want the event queue bounded to 500 events drop-oldest, so that a burst can't exhaust device memory.
15. As a consuming app developer, I want offline replay throttled to ≤10 oldest envelopes per flush tick with stop-on-failure, so that reconnection doesn't stampede the network or the backend.
16. As a backend operator, I want SDK-minted IDs in the `<kind>_<epochMs>_<16hex>_android` format (D7/D11), so that IDs are self-describing and platform-tagged.
17. As a backend operator, I want existing persisted device/user IDs preserved verbatim (not regenerated), so that an app update doesn't fork existing installs into phantom devices.
18. As a data analyst, I want one accurate session lifecycle (no cold-start double-mint, persisted `session_id`), so that session counts and durations are correct.
19. As a data analyst, I want a single screen-duration signal (`navigation` + `screen.duration`), so that the duplicate pause `screen_view` stops starving the real duration metric.
20. As a performance analyst, I want a windowed `frame.summary` (jank-rate counters, max timings) instead of per-frame `frame_drop`, so that I get usable jank signal without ~36k events per session.
21. As a performance analyst, I want fixed Vitals-aligned thresholds (slow >16ms, frozen >700ms) and the device `display.refresh_rate` recorded, so that jank is measured against a real, comparable bar.
22. As a security auditor, I want ~890 LOC of unwired dead code deleted, so that the audit surface reflects only live functionality.
23. As a consuming app developer, I want the dead `enableLocationTracking` flag and `testConnectivity()` removed (pre-1.0), so that the public API only exposes things that do something.
24. As a consuming app developer, I want all network calls unified onto one `http.request` event with D3's `http.*` keys, so that automatically-captured HTTP is no longer dark server-side.
25. As a consuming app developer, I want the OkHttp interceptor to set `http.success = code in 200..299` (D3a), so that success is a consistent server-readable boolean.
26. As a security-conscious app, I want query strings stripped from URLs by default (D8), so that tokens and PII in query params never leave the device.
27. As a consuming app developer, I want a transport failure to still emit `http.request` with `status_code=0`/`success=false`, so that failed calls are observable, not invisible.
28. As a product analyst, I want automatic user-interaction events (`ui.interaction` discriminated by tap/long_press/swipe), so that I can see what users tap without writing per-view instrumentation.
29. As a compliance officer at a regulated app, I want interaction capture suppressed on secure surfaces (password `inputType` / `FLAG_SECURE`) by hard default, so that raw taps over a PIN pad can't leak keystroke-inferable coordinates.
30. As an on-call engineer, I want the last interactions in the crash breadcrumb trail, so that I know what the user tapped before the crash.
31. As a performance analyst, I want distributed trace/span attributes (`trace.id`/`span.id`/`parent.span.id`) on existing events plus a `traceparent` header injected on outbound calls, so that a user action can be followed from tap through network into backend spans.
32. As a backend operator, I want the SDK to use W3C Trace Context ids and headers, so that traces stitch to backend spans through a standard the whole stack understands.
33. As an on-call engineer, I want an `app.anr` event when the main thread is blocked ≥5s, with an all-thread dump, so that I can find the lock-holder behind an ANR.
34. As an on-call engineer, I want an `app.hang` event with a main-thread stack for sub-5s stalls (≥2s), so that I get a stack trace for the "slow but not an ANR" band that frame counts and ANRs both miss.
35. As a backend operator, I want ANR and hang to ride the unified envelope with standard enrichment, so that ANR-free and hang-free rates are computable like any other event metric.
36. As a CI maintainer, I want CI retargeted to `master` running `testDebugUnitTest lintDebug assembleRelease` with the lint gate enforced, so that conformance regressions fail the build.
37. As a CI maintainer, I want source-grep guards (no timezone-less formatters, no `Instant.now()`, no deleted symbols), so that removed footguns can't silently return.
38. As a build maintainer, I want the launcher JDK pinned to 17 everywhere and the compile target at 11 via `jvmToolchain(11)`, so that JitPack and local builds are reproducible.

## Implementation Decisions

Grouped by execution wave. Waves reflect the one real dependency: the unified envelope (#30) unblocks the conformance events. Everything else is independent and can proceed in parallel.

### Wave 0 — foundations (unblock everything)

- **Unified wire envelope + `sdk.*`** ([#30](https://github.com/NCG-Africa/edge_telemetry_android/issues/30) · `unified-wire-envelope.md`). One `telemetry_batch` envelope, hard cutover (no dual-emit). `sdk.version`/`sdk.platform` become required common attrs; drop `X-SDK-*` headers. Full serializer + attribute unification. Drop top-level `device_id`/`location`/`tenant_id`. This is the single transport `sendBatch` that Seam 1 asserts against.
- **CI conformance** ([#31](https://github.com/NCG-Africa/edge_telemetry_android/issues/31) · `ci-conformance.md`). Retarget CI to `master`, drop stale `main`. One job: `testDebugUnitTest lintDebug assembleRelease`; lint gate needs `lint-baseline.xml` + `abortOnError=true`. Pin launcher JDK 17 (add master `jitpack.yml`=`openjdk17`), compile target 11 via `jvmToolchain(11)`. Delete the drifted `-Pversion` override in the java8 `jitpack.yml`. Hosts the Seam 3 source-grep guards.
- **Timestamp correctness** ([#41](https://github.com/NCG-Africa/edge_telemetry_android/issues/41) · `timestamp-correctness.md`). One representation: ms-precision ISO-8601 true UTC (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, D6). New `core/TelemetryTime.kt` = one `ThreadLocal<SimpleDateFormat>` + UTC (stdlib only, no `java.time`/desugaring). Delete all 7 formatters and all live `Instant.now()` (API-26 crash landmine). Attribute timestamps convert to the same ISO string; `*_ms` durations stay numeric.

### Wave 1 — conformance events (depend on #30)

- **Retire crash Path B** ([#39](https://github.com/NCG-Africa/edge_telemetry_android/issues/39) · `retire-crash-path-b.md`). Crash → standard `app.crash` on two rails: fatal uncaught = synchronous frozen write to `filesDir` **before** re-throw + replay/delete-after-2xx; `trackError`/`recordCrash` = normal batch. D1 unprefixed keys, `is_fatal` JSON bool, `handled` added. `session.*`/`user.*` frozen at crash time. Delete Path B envelope + `getCrashAttributes`.
- **Unify network schema + URL sanitization** ([#40](https://github.com/NCG-Africa/edge_telemetry_android/issues/40) · `network-schema-sanitization.md`). One event `http.request` with D3 `http.*` keys; interceptor re-keys, adds `http.success = code in 200..299` (D3a). Delete manual `recordNetworkRequest()` (D3). Strip-all query by default (D8). Delete unwired `EdgeTelemetryInterceptor` (owns the full-URL-leak fix #37 deferred here). No `http.error` (failure = `status_code=0`+`success=false`); omit size fields when `contentLength()<0`. Injects `traceparent` for #43.

### Wave 2 — independent hardening (no #30 dependency)

- **Broken timed flush** ([#32](https://github.com/NCG-Africa/edge_telemetry_android/issues/32) · `broken-timed-flush.md`). Inject an `onFlush` lambda into `BatchProcessingService.initialize`; `TelemetryManager` passes `{ scope.launch { sendBatch(forceSend = true) } }`. Timed path force-sends partial batches; empty queue no-ops; `fixedDelay` kept.
- **Bounded buffers & eviction** ([#33](https://github.com/NCG-Africa/edge_telemetry_android/issues/33) · `bounded-buffers-eviction.md`). Offline = 200-envelope drop-oldest (D9), SharedPreferences + `Mutex`. eventQueue = 500 drop-oldest, size via `AtomicInteger`. Pre-init = 50 FIFO (unchanged). Replay ≤10 oldest/tick, stop-on-failure; delete `restoreOfflineBatches`. Drop dead Room deps.
- **ID format migration** ([#34](https://github.com/NCG-Africa/edge_telemetry_android/issues/34) · `id-format-migration.md`). Target `<kind>_<epochMs>_<16hex>_android` (D7/D11). Preserve persisted device/user ids verbatim; new format only for newly-minted ids. One-file change (`IdGenerator.generateId(kind)`).
- **Session + screen correctness** ([#35](https://github.com/NCG-Africa/edge_telemetry_android/issues/35) · `session-screen-correctness.md`). Keep foreground-return rotation; fix cold-start double-mint and unpersisted `session_id`. Persist `session_id`/`session_start`/`last_active` on background; `initialize()` load-then-decide. Retire the pause `screen_view` and deprecated `recordScreenView`/`enableLegacyScreenEvents`; screen lifecycle = `navigation` + `screen.duration`.
- **Frame metrics windowing** ([#36](https://github.com/NCG-Africa/edge_telemetry_android/issues/36) · `frame-metrics-windowing.md`). Retire per-frame `frame_drop` for windowed `frame.summary`. Hybrid callback-driven window: flush on screen-change (`getCurrentScreen()`) or 10s cap. Jank-rate counters + max timings, no percentiles. Fixed thresholds slow >16ms / frozen >700ms; record `display.refresh_rate`; emit only when `slow_frames>0`; no sampling. Internal `onFrame(totalMs,buildMs,rasterMs)` seam.
- **Dead-code removal** ([#37](https://github.com/NCG-Africa/edge_telemetry_android/issues/37) · `dead-code-removal.md`). Delete location stack, `JsonEventTracker`, `LegacyPerformanceTracker`(+Wrapper), `TelemetryPayload` wrapper + dead import, `EventBatchPayload` (~890 LOC). Hard-remove public `enableLocationTracking` + `testConnectivity()` (pre-1.0). `EdgeTelemetryInterceptor` deletion folded into #40; Room deps owned by #33.

### Wave 3 — new datapoints (net-new; depend on #30, coordinate with waves above)

- **User-interaction events** ([#42](https://github.com/NCG-Africa/edge_telemetry_android/issues/42) · `user-interaction-events.md`). One `ui.interaction` event discriminated by `ui.type` (tap/long_press/swipe). Capture: wrap `Window.Callback` in the Activity observer (`:36`/`:61`) + `GestureDetector` + decorView hit-test — automatic, no permission. Compose v1 = coordinate-only (`target="compose_surface"`). Target = `getResourceEntryName`→class fallback; raw pixel x/y. **Privacy hard default:** suppress capture on secure surfaces (password-`inputType` `EditText` or `FLAG_SECURE`). Plus a compact crash breadcrumb per interaction.
- **Distributed trace/span contract** ([#43](https://github.com/NCG-Africa/edge_telemetry_android/issues/43) · `distributed-trace-span.md`). Android-first, flagged upstream. W3C traceparent ids (128-bit trace / 64-bit span, separate from #34's join keys). Root span opens on interaction, else screen; 100% sampling, configurable `traceSampleRate`. One process-global current root (`TraceManager` `AtomicReference`); spans are **attributes** on existing events (`trace.id`/`span.id`/`parent.span.id`) — no new event type. Interaction→new root; nav within 1000ms→child else new root; each network call→child injecting `traceparent: 00-<32hex>-<16hex>-01` (via #40 interceptor); background clears root. Flat two-level tree v1. **Hard hub dependency:** backend must read `traceparent` and continue `trace.id` or the injection is inert.
- **ANR detection** ([#38](https://github.com/NCG-Africa/edge_telemetry_android/issues/38) · `anr-detection.md`). Watchdog-only main-thread ping/pong daemon (ports Ionic/Capacitor `AnrWatchdog`), API 24+. Fixed 5000ms threshold (internal constant). All-thread dump (main flagged). New `app.anr` event, `is_fatal:false`/`handled:false`, rides unified envelope with standard enrichment. Delivery on #39's durable `filesDir` rail + replay/delete-after-2xx. Foreground-only via `ProcessLifecycleOwner`.
- **Hang detection** ([#44](https://github.com/NCG-Africa/edge_telemetry_android/issues/44) · `hang-detection.md`). The sub-5s complement to #38. Single 2000ms threshold (band `[2000,5000)`, internal constant). **Reuse the #38 `AnrWatchdog`** — one heartbeat (~1000ms), two thresholds; a stall crossing both emits both `app.hang`(2s) + `app.anr`(5s), dedup intra-threshold. Discrete `app.hang`, main-thread stack only (`hang.stack`), no sampling/aggregation. `is_fatal:false`; normal batch (not the durable rail).

### Cross-cutting contracts

- **Envelope is the contract.** Every event above serializes into #30's `telemetry_batch`. New event names (`app.anr`, `app.hang`, `ui.interaction`) and the `ui.interaction`/trace attr names are android-local, flagged non-blocking to the hub.
- **`AnrWatchdog` is shared** between #38 and #44 — one daemon, one heartbeat, two thresholds. Build #38 first; #44 adds the second threshold to it.
- **The #40 interceptor is the trace injection point** — #43 depends on it re-keying + injecting `traceparent` before `proceed()`.

## Testing Decisions

A good test asserts **external behavior only** — the bytes on the wire and observable side effects — never private field state or call order for its own sake. The whole effort tests at three seams the sub-specs already cut; add no new ones.

### Seam 1 — outgoing `telemetry_batch` JSON (primary, highest)

Stub the `sendBatch` HTTP POST (#30's single transport), drive the SDK through its public API + lifecycle callbacks, assert on the serialized batch. This is where the conformance contract lives and where most stories are verified: envelope shape, `sdk.*` common attrs, `app.crash` keys/flags, unified `http.request` (including `status_code=0`/`success=false` on transport failure), ISO-UTC timestamps, `<kind>_..._android` ids, `frame.summary` counters, `ui.interaction` discrimination + secure-surface suppression, trace attributes on events. Prior art: the existing payload/serializer unit tests under `src/test/` — extend the pattern of building a batch and asserting JSON keys.

### Seam 2 — injected collaborators for non-wire behavior

Each defect ticket cut its own seam; test through it, not around it:
- **Timed flush** (#32) — assert the injected `onFlush` fires on tick and force-sends a partial batch; empty queue no-ops.
- **Bounded buffers** (#33) — exercise the store APIs directly: seed past the cap, assert drop-oldest; seed 25 stored envelopes, one flush cycle → ≤10 sent, inject a failure at #3 → stop.
- **Session** (#35) — drive lifecycle callbacks, assert persisted `session_id` and single mint on cold start; resume-within-timeout is silent.
- **Crash durable rail** (#39) — assert the `filesDir` write happens before re-throw and the file is deleted only after a 2xx replay.
- **ANR/hang watchdog** (#38/#44) — block a fake main thread, advance a fake clock past 2s then 5s, assert `app.hang` then `app.anr` with the right stack scope and intra-threshold dedup.
- **Frame windowing** (#36) — feed the `onFrame(totalMs,buildMs,rasterMs)` seam, flush on screen-change, assert counters and the `slow_frames>0` emit floor.
- **Timestamp** (#41) — unit-test `TelemetryTime.now()` produces true-UTC ISO under a non-UTC default timezone.

### Seam 3 — source-grep guards in CI (#31)

Static conformance for the removals/rewrites that have no runtime assertion: a test that greps the module source and fails on any timezone-less `SimpleDateFormat`, any `Instant.now()`, and any deleted dead-code symbol (`IpLocationProvider`, `JsonEventTracker`, `enableLocationTracking`, etc.). Cheap fence so a footgun can't silently return.

## Out of Scope

- **All items in map #29's "Not yet specified" (fog)** — non-OkHttp manual HTTP capture, query-param allowlist, network breadcrumbs, URL path PII redaction, app-start timing, `ApplicationExitInfo` ANR enrichment, transport compression, collector old-format tolerance, Room-backed offline store, Compose `Modifier.trackTap`, content-desc target, normalized coords, scroll/drag gestures, custom keypad PII rule, cross-coroutine span parenting, deeper span nesting, manual span API, `tracestate`/baggage, orphan background-call traces. Graduate individually only when a real consumer or the hub forces it.
- **Backend / other-SDK adoption** of the android-decided trace contract, `traceparent` reading, `app.anr`/`app.hang`/`ui.interaction` names — flagged to the hub, tracked there, not here.
- **Re-litigating hub decisions** (D1/D3/D6/D7/D8/D9/D11/D14) — fixed input.

## Further Notes

- **Ground truth:** repo-root `sdk-audit.yaml` (466 lines, file:line evidence) and `CLAUDE.md`. Each sub-spec cites exact lines; this PRD stays path-light so it doesn't rot.
- **Hard hub dependency:** #43's trace injection is inert until the backend reads `traceparent` and continues `trace.id`. Ship the android side; the value lands when the hub conforms.
- **Pre-1.0 latitude:** public API removals (`enableLocationTracking`, `testConnectivity()`, `recordNetworkRequest()`, `recordScreenView`/`enableLegacyScreenEvents`) are hard-removed, no deprecation cycle.
- **Suggested execution order:** Wave 0 → Wave 1 + Wave 2 in parallel → Wave 3 (#38 before #44; #43 after #40's interceptor). One PR per ticket keeps review and the source-grep guards tractable.
