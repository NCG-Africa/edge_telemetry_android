# Spec: app cold-start timing

Spec for map [#85 — Native headroom](https://github.com/NCG-Africa/edge_telemetry_android/issues/85),
ticket [#87](https://github.com/NCG-Africa/edge_telemetry_android/issues/87). Ranked #3 in the execution
backlog by [#86](https://github.com/NCG-Africa/edge_telemetry_android/issues/86)
(`docs/specs/device-signals-delivery-model.md`) — "highest standalone user-facing metric value." Carries its
own delivery rail (independent of the static/dynamic device-context rails).

**Plan-only.** No merged code. Downstream execution takes this as the fixed spec.

Prior context: this was fog in the now-closed conformance map
[#29](https://github.com/NCG-Africa/edge_telemetry_android/issues/29) ("app-start / cold-warm-start timing —
design once envelope + interaction shapes settle"). Envelope + interaction shapes have settled (#30/#42
closed), so it graduates here.

## Decision: cold-start only

A telemetry **library** initializes *inside* the consumer's process — it is not the `Application` and gets no
guaranteed first-in-process hook. From that vantage point:

- **Cold start** (process forked from scratch → first UI) is cleanly measurable and is the industry headline
  metric (Google Play Vitals reports it as *the* startup number). **In scope.**
- **Warm / hot** classification (process alive + activity recreated / activity resident + re-foregrounded)
  requires the library to track process-and-activity residency across lifecycle. Fragile from a library
  vantage point and low marginal value. **Not attempted** — deferred, graduates only if a named consumer
  needs it. The wire schema reserves a `type` key (below) so warm/hot can be added later with no schema break.

`reportFullyDrawn()` (TTFD) was rejected as the end marker: it requires every consumer to manually call
`Activity.reportFullyDrawn()`, so data would be silently missing wherever they don't.

## Measurement

| Bound | Source | API | Notes |
|---|---|---|---|
| **start** | `Process.getStartUptimeMillis()` | 24 | True process fork time, on the `SystemClock.uptimeMillis()` clock. Stable regardless of when the SDK's `initialize()` runs — late init does not move the anchor. Permission-free. |
| **end** | first Activity `onResume` | — | Caught by the **already-wired** `ActivityLifecycleCallbacks` (`TelemetryActivityLifecycleObserver`, registered at init). Zero consumer effort. `duration_ms = SystemClock.uptimeMillis()_at_first_onResume − start`. |

"First `onResume`" = the first Activity resume this process observes. The SDK sets a one-shot latch; only the
first observed resume is timed, all later resumes are ignored.

## Background-start guard (required)

Android forks the process for a service / broadcast / content-provider with **no UI**; the first `onResume`
may then arrive minutes later, producing a bogus multi-minute "cold start." The sample is **dropped** (emit
nothing — no partial/garbage event) when **either** guard trips:

1. **Not foreground-intended at init.** Capture `RunningAppProcessInfo.importance` via
   `ActivityManager.getMyMemoryState(info)` (API 16, permission-free) **at SDK init**. Drop unless
   `importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE` (200) — i.e. keep only `FOREGROUND` (100) /
   `VISIBLE` (200); `SERVICE` (300) and worse mean the process was started for background work. (Lower
   numeric importance = more foreground.)
2. **Duration cap.** Drop if `duration_ms > 60_000` (60 s) — an absolute-value backstop for anything the
   importance check misses.

Belt-and-suspenders: (1) kills slow *and* fast background starts; (2) catches residual outliers.

## Event & wire keys

Standalone **`app.start`** event — same family as `app.crash` / `app.anr` / `app.hang` / `app.exit`. Emitted
**once per process**, at the first qualifying `onResume`, on the SDK's existing event-dispatch path (own rail;
**not** a rider on `session.started`).

| Key | Type | Value |
|---|---|---|
| `app.start.type` | String | `"cold"` (only value today; key reserved so warm/hot graduate without a schema break) |
| `app.start.duration_ms` | Int | `first_onResume_uptime − Process.getStartUptimeMillis()`, milliseconds |

Native `Int` (not stringified) — the wire bag is `Map<String, Any?>` (`core/models/TelemetryBatch.kt:16-23`),
consistent with the static device-context spec (#90). All events auto-enriched with app/device/session/user
context as usual, so no start-specific `device.*` keys are needed here.

## Constraints

- **minSDK 24**, permission-free. `Process.getStartUptimeMillis()` = 24; `getMyMemoryState` = 16;
  `SystemClock.uptimeMillis` = 1. No gate needed at the library's min-SDK 24.
- **Late-init caveat (documented, best-effort):** if the consumer calls `initialize()` *after* the first
  Activity has already resumed, the SDK's `ActivityLifecycleCallbacks` were registered too late to observe it
  → **no `app.start` sample that process** (the latch never sees a first resume; nothing is emitted). The
  documented requirement is init in `Application.onCreate`, which registers the callbacks before any Activity
  resumes. No fabricated fallback from init-time.

## Explicitly excluded

- **Warm / hot start** — deferred (see Decision). Not measured; `type` key reserved for its later arrival.
- **TTFD / `reportFullyDrawn`** — requires per-consumer opt-in; rejected as unreliable end marker.
- **Per-Activity / per-screen start timing** — screen-duration tracking already exists
  (`ScreenTimingTracker`); this ticket is process-level cold start only.
