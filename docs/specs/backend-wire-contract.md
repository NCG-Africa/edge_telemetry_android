# Android → Backend Wire Contract

**Status:** proposal, for the team authoring `EDGETELEMETRYPROCESSORGO`'s schema
**Audience:** backend / schema authors. SDK consumers want `docs/EVENT_SCHEMA_REFERENCE.md` (stale — see banner there).
**SDK:** `edge_telemetry_android` 2.2.2, plus the v3 trace additions specified in [`distributed-trace-span.md`](./distributed-trace-span.md).
**Target:** `EDGETELEMETRYPROCESSORGO` migration lineage. Enterprise `edge_db` is prior art, not a target.
**Decided in:** [#129 — Backend handover contract](https://github.com/NCG-Africa/edge_telemetry_android/issues/129). Authored under [#132](https://github.com/NCG-Africa/edge_telemetry_android/issues/132). Part of map [#120](https://github.com/NCG-Africa/edge_telemetry_android/issues/120).

**Verified against:** `EDGETELEMETRYPROCESSORGO@b9bf1b5` (`internal/telemetry/extract.go`, `internal/telemetry/service.go`, `internal/db/migrations/0001_init.up.sql`) and this repo at `55c603b`. Every claim below carries a `file:line`.

---

## How to read this

| You want to… | Go to |
|---|---|
| **Know what Android sends, key by key** — the inventory to build tables from | **§4** (§4.16 for totals) |
| Know what to migrate | **§5** |
| Understand why a JSONB bag rather than a longer allowlist | §1, §2 |
| Decide about a key not listed here | **§3** — the rule, applicable without asking us |
| Wire up distributed tracing | §6 |
| Understand why Android's `frame.*` keys look nothing like the columns you have | §7 |
| Check any number in this document yourself | Appendix B |

**The one-line version for a backend engineer:** treat §4 as the source of truth for Android, store every key in a JSONB bag, and promote the 84 marked `COL` to typed columns. Do not rename anything that already exists.

---

## 0. The ask, in one page

The Android SDK emits **146 attribute keys** across **17 event names** and 2 metric names. The processor's allowlist names **38 keys** (`extract.go`) feeding **7 tables** (`0001_init.up.sql`) — but only **30** of those 38 are keys Android actually sends; the other 8 are Flutter's vocabulary (§7). So **116 of Android's 146 keys are parsed into memory and thrown away**, because there is no JSONB bag in any table to catch them.

This document asks for a **model change, not a field list**:

| # | Ask | Why |
|---|---|---|
| 1 | An `attributes JSONB` column on `rum_telemetry_events`, written unconditionally | Makes column promotion **retroactive by backfill**; stops the allowlist failing by omission |
| 2 | **84 keys** additionally promoted to typed columns, per a stated **rule** (§3) — 33 stay bag-only | So the backend can decide about keys we have not written yet, without asking us |
| 3 | Five new child tables: HTTP, errors, navigation, screens, interactions (§5) | 108 dropped keys have nowhere to live; HTTP is a **port** of `edge_db`'s existing `rum_http_requests`, not an invention |
| 4 | `rum_action_id` + `span_start_time` + `span_duration_ms` on every span-carrying row, plus one **query-time view** (§6) | Makes a user action traceable launch → request. One column, one index, one view — no processor state |
| 5 | **Never a rename.** Flutter's seven `frame.*` columns and every query reading them stay exactly as they are | The backend serves four SDKs; this contract must extend without breaking any of them |

Two things this document does **not** ask for: converging the four SDKs onto one frame/memory vocabulary (§7 ships the evidence, someone else owns the fix), and any change to the collector.

---

## 1. Why the model changes, not the field list

### 1.1 The allowlist fails by omission

`extract.go` names exactly 38 dotted keys and lifts each into a typed struct field:

| Extractor | Keys | `extract.go` |
|---|---|---|
| `extractAppInfo` | 4 | `:136-143` |
| `extractDeviceInfo` | 10 | `:145-158` |
| `extractUserInfo` | 1 | `:160-162` |
| `extractSessionInfo` | 10 | `:164-181` |
| `extractPerformanceMetric` | 7 (`metric.unit`, 4 `frame.*`, 2 `memory.*`) | `:183-203` |
| `extractPerformanceEvent` | 8 (5 `frame.*`, 3 `memory.*`) | `:205-221` |

Nothing else is read. There is no default branch, no `for k, v := range attrs`, no bag.

**The failure mode is silence.** A key the SDK adds is not rejected, not logged, not counted — it is simply never looked up. `map[string]any` returns the zero value and the row is written without it. So every SDK release widens the gap invisibly, and the gap is only ever discovered by someone going to look for one specific attribute — which is exactly how [the evidence run (#128)](https://github.com/NCG-Africa/edge_telemetry_android/issues/128) found it while trying to measure the `injected_unattributed` ratio.

A list of columns buys one migration and rebuilds the same trap. That is why §2 and §3 propose a shape and a rule instead.

### 1.2 The bag is already in memory, and free

The full attribute map survives all the way to the write:

```go
// internal/domain/telemetry.go:5-12
type TelemetryEvent struct {
    Type       string         `json:"type"`
    MetricName *string        `json:"metricName"`
    EventName  *string        `json:"eventName"`
    Value      *float64       `json:"value"`
    Timestamp  time.Time      `json:"timestamp"`
    Attributes map[string]any `json:"attributes"`
}
```

`service.go:107-142` passes `event.Attributes` to six extractors and then lets it fall out of scope. Nothing needs re-parsing, no new decode step, no collector change: persisting the bag is **one extra column on the existing `CreateTelemetryEvent` call** (`service.go:131`).

### 1.3 Two live defects the current shape produces

**(a) Every non-metric event writes a junk `rum_performance_events` row.** The dispatch is on `event.Type`, not on event name:

```go
// internal/telemetry/service.go:136-147
switch event.Type {
case "metric":  ... CreatePerformanceMetric ...
case "event":   ... CreatePerformanceEvent ...
default:        return fmt.Errorf("unsupported event type %q", event.Type)
}
```

Every Android event carries `type: "event"` (`TelemetryBatch.kt:36-43`; e.g. `CrashReportingService.kt:110`). So `app.crash`, `navigation`, `ui.interaction`, `http.request`, `session.started`, `storage_usage` each write a `rum_performance_events` row whose eight frame/memory columns are all NULL (`extract.go:205-221` finds none of its keys on those events). The table is not merely missing data — it is **majority padding**, and any `AVG`/`COUNT` over it is wrong today.

**(b) `rum_devices` has no device identity.** `0001_init.up.sql:11-24` stores platform, model, manufacturer, brand, SDK, release, fingerprint, hardware, product — and a `SERIAL` surrogate key. There is no `device_id` column, and `extractDeviceInfo` (`extract.go:145-157`) never reads `device.id`.

Meanwhile the SDK treats `device.id` as load-bearing enough to **throw** rather than send a blank one:

```kotlin
// TelemetryHttpClient.kt:170-175
if (attrs.device.deviceId.isBlank()) {
    throw IllegalStateException(
        "CRITICAL ERROR: device.id is blank in event attributes. " +
        "This indicates IDs were not properly validated before sending."
    )
}
```

So "how many distinct devices" is currently unanswerable, and two identical handsets collapse into one row. `device.id` is a join key under §3 clause 1 and needs a column with a unique index.

---

## 2. Storage model

### 2.1 Bag-first

```sql
ALTER TABLE rum_telemetry_events ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX ix_rum_telemetry_events_attributes ON rum_telemetry_events USING GIN (attributes jsonb_path_ops);
```

Every attribute the SDK sends lands here **unconditionally**, including keys already promoted to columns. The bag is the record; columns are an access-path optimisation over it.

### 2.2 Additive columns only, never a rename

New keys get **new** columns. No existing column is renamed, retyped, dropped or repurposed — in particular the seven `frame.*` columns on `rum_performance_metrics` / `rum_performance_events` (`0001_init.up.sql:67-70, 84-88`) stay bound to Flutter's per-frame vocabulary, which is what they already hold (§7). Android's windowed-aggregate frame figures get their own columns under their own names.

This is the concrete form of the standing constraint that the backend serves other SDKs: every existing endpoint and analytics query keeps working, byte for byte, across this migration.

### 2.3 The property that makes bag-first the right answer: promotion becomes retroactive

Under an allowlist, a column captures data **from the day it ships**. Under bag-first, the raw key was always stored, so promoting it later is a `UPDATE ... SET col = attributes->>'key'` backfill — **the history is already there**.

This is not theoretical. `traceparent.outcome` has been on every `http.request` since 2.2.0 (`TraceManager.kt:102, 111, 122, 128`) and no store has ever held one, which is precisely why the `injected_unattributed` ratio — the number that would tell us whether v2 tracing worked at all — is unanswerable today ([#128](https://github.com/NCG-Africa/edge_telemetry_android/issues/128)). Had a bag existed in 2.2.0, that question would be a backfill.

It also decouples the release trains: the SDK can ship a new attribute and it is queryable (via `attributes->>'…'`) with **no backend release**, and promoted to a column later if it earns one.

**Accepted cost, stated plainly.** JSONB on every row of the hottest table is real storage and real write amplification. The tighter variant — bag only the *unpromoted* keys — was considered and rejected in [#129](https://github.com/NCG-Africa/edge_telemetry_android/issues/129): it forfeits retroactive backfill for exactly the keys most likely to be promoted, which is the whole value.

---

## 3. The promotion rule

Every key is in the bag. A key **additionally** earns a typed column if and only if it is one of:

1. **A join key** — used to relate rows across tables.
2. **A bounded-cardinality dimension** — something you `GROUP BY` or `WHERE` on, with a small, enumerable value set.
3. **An aggregated numeric measure** — something you `AVG`, `MAX`, or percentile.

Everything else stays bag-only. Roughly **40 of 146** promote.

Two non-clauses, stated so they don't get argued each time:

- **High-cardinality free text is bag-only** even when it is queried — `error.stack_trace`, `exit.trace`, `anr.threads`, `crash.breadcrumbs`. They are read after you have already filtered by something else.
- **PII is bag-only** — `user.name`, `user.email`, `user.phone` (`TelemetryHttpClient.kt:204-206`). Bag placement keeps redaction and deletion a single-column operation rather than a schema change. `user.id` is the join key and is already a column.

The rule exists so the backend can classify keys the SDK has **not written yet** without a conversation. Apply it on arrival; the bag means a wrong call is reversible by backfill.

---

## 4. The inventory

**146 live wire attribute keys** — 130 dotted, 16 undotted — across 13 event names, plus 4 keys the v3 trace spec adds. Appendix A reconciles this against the "169 keys" figure quoted in [#129](https://github.com/NCG-Africa/edge_telemetry_android/issues/129) and explains what the other 39 literals are.

Legend: **Call** is `COL` (promote to a typed column) or `bag` (JSONB only). **Now** is what `extract.go@b9bf1b5` does today: ✅ read, ❌ dropped.

### 4.1 Common envelope — on **every** event

Written by `TelemetryHttpClient.flattenAttributes()` (`TelemetryHttpClient.kt:155-226`). 40 keys.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `sdk.version` | String | never | ~10s | COL (dim) | ❌ | `TelemetryHttpClient.kt:159` |
| `sdk.platform` | String | never | 1 (`android`) | COL (dim) | ❌ | `:160` |
| `app.name` | String | never | ~10s | COL (exists) | ✅ | `:163` |
| `app.version` | String | never | ~100s | COL (exists) | ✅ | `:164` |
| `app.build_number` | String | never | ~1000s | COL (exists) | ✅ | `:165` |
| `app.package_name` | String | never | ~10s | COL (exists) | ✅ | `:166` |
| `device.id` | String | never (throws) | high (per install) | **COL (join key)** | ❌ | `:176`, guard `:170-175` |
| `device.platform` | String | never | 1 | COL (exists) | ✅ | `:177` |
| `device.platform_version` | String | never | ~20 | COL (exists) | ✅ | `:178` |
| `device.model` | String | never | ~1000s | COL (exists) | ✅ | `:179` |
| `device.manufacturer` | String | never | ~100s | COL (exists) | ✅ | `:180` |
| `device.brand` | String | never | ~100s | COL (exists) | ✅ | `:181` |
| `device.android_sdk` | String | never | ~15 | COL (exists) | ✅ | `:182` |
| `device.android_release` | String | never | ~15 | COL (exists) | ✅ | `:183` |
| `device.fingerprint` | String | never | high | COL (exists) | ✅ | `:184` |
| `device.hardware` | String | never | ~100s | COL (exists) | ✅ | `:185` |
| `device.product` | String | never | ~1000s | COL (exists) | ✅ | `:186` |
| `device.cpu_abi` | String | never | ~5 | COL (dim) | ❌ | `DeviceInfoCollector.kt:53` |
| `device.cpu_cores` | Int | never | ~10 | COL (dim) | ❌ | `:54` |
| `device.low_ram` | Bool | never | 2 | COL (dim) | ❌ | `:55` |
| `device.screen_density` | Int | never | ~10 | COL (dim) | ❌ | `:56` |
| `device.screen_width_px` | Int | never | ~100s | bag | ❌ | `:57` |
| `device.screen_height_px` | Int | never | ~100s | bag | ❌ | `:58` |
| `device.dark_mode` | Bool | never | 2 | COL (dim) | ❌ | `:59` |
| `device.locale` | String | never | ~100s | COL (dim) | ❌ | `:60` |
| `device.timezone` | String | never | ~400 | COL (dim) | ❌ | `:61` |
| `user.id` | String | never (throws) | high | COL (exists) | ✅ | `:199`, guard `:193-198` |
| `user.name` | String | optional | high | bag (PII) | ❌ | `:204` |
| `user.email` | String | optional | high | bag (PII) | ❌ | `:205` |
| `user.phone` | String | optional | high | bag (PII) | ❌ | `:206` |
| `session.id` | String | never | high | COL (exists) | ✅ | `:209` |
| `session.start_time` | String ISO-8601 | nullable | — | COL (exists) | ✅ | `:210` |
| `session.duration_ms` | Long | nullable | measure | COL (exists) | ✅ | `:211` |
| `session.event_count` | Int | nullable | measure | COL (exists) | ✅ | `:212` |
| `session.metric_count` | Int | nullable | measure | COL (exists) | ✅ | `:213` |
| `session.screen_count` | Int | nullable | measure | COL (exists) | ✅ | `:214` |
| `session.visited_screens` | String (CSV) | nullable | high | COL (exists) | ✅ | `:215` |
| `session.is_first_session` | Bool | nullable | 2 | COL (exists) | ✅ | `:216` |
| `session.total_sessions` | Int | nullable | measure | COL (exists) | ✅ | `:217` |
| `network.type` | String | nullable | ~6 | COL (exists) | ✅ | `:218` |

The nine `device.*` keys at `DeviceInfoCollector.kt:53-61` are a launch-time bundle merged in at `TelemetryHttpClient.kt:189`; they carry **native types** (Int/Bool), not strings.

**Envelope score: 25 of 40 read, 15 dropped** — `sdk.version`, `sdk.platform`, `device.id`, the nine static `device.*` keys, and the three `user.*` PII keys.

### 4.2 Dynamic device state — on `app.crash`, `app.anr`, `app.hang` only

`DeviceStateSnapshot.read()` (`DeviceStateSnapshot.kt:36-59`), merged at `CrashReportingService.kt:106, 152, 186`. Every key is **omitted, not nulled**, when the platform declines to answer.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `device.battery_level` | Int 0–100 | omitted if unsupported | measure | COL | ❌ | `DeviceStateSnapshot.kt:36` |
| `device.battery_charging` | Bool | omitted | 2 | COL (dim) | ❌ | `:40` |
| `device.power_save` | Bool | omitted | 2 | COL (dim) | ❌ | `:44` |
| `device.thermal_status` | Int 0–6 | **absent below API 29** | 7 | COL (dim) | ❌ | `:47-50` |
| `device.orientation` | String | omitted when `UNDEFINED` | 2 | COL (dim) | ❌ | `:52-59` |

### 4.3 `http.request`

`TelemetryInterceptor.kt:46-59`, plus the trace attrs merged at `:56`.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `http.url` | String | never | high | COL (join/dim) | ❌ | `TelemetryInterceptor.kt:47` — **query string stripped** at `substringBefore('?')` |
| `http.method` | String | never | ~8 | COL (dim) | ❌ | `:48` |
| `http.status_code` | Int | never (`0` on transport failure) | ~60 | COL (dim) | ❌ | `:49`, `0` at `:44` |
| `http.duration_ms` | Long | never | measure | COL (measure) | ❌ | `:50` |
| `http.success` | Bool | never | 2 | COL (dim) | ❌ | `:51` |
| `http.request_size` | Long | **omitted** when chunked/streamed | measure | COL (measure) | ❌ | `:54` |
| `http.response_size` | Long | **omitted** when chunked/streamed | measure | COL (measure) | ❌ | `:55` |

> `http.status_code = 0` means the call never got a response (DNS, TLS, timeout, cancellation) — `response?.code ?: 0` at `:44`. It is not an HTTP status. Dashboards must treat `0` as a distinct failure bucket, not fold it into 5xx.

> `http.request_size` / `http.response_size` are a true optional pair: `contentLength()` returns `-1` for chunked bodies and the SDK omits the key rather than sending a false `0` (`:52-55`). The column stays NULL.

### 4.4 Trace attributes — on span-carrying events

Shipping today (`TraceManager.kt`); see §6 for the v3 additions and the full outcome ladder.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `trace.id` | String (32 hex, W3C) | present on every sampled span | high | **COL (join key)** | ❌ | `TraceManager.kt:80, 109, 141, 149` |
| `span.id` | String (16 hex, W3C) | present on every sampled span | high | **COL (join key)** | ❌ | `:80, 110, 142, 150` |
| `parent.span.id` | String (16 hex) | **children only** — absent on roots and parentless spans | high | **COL (join key)** | ❌ | `:143` |
| `rum.action.id` | String (16 hex) | absent on parentless spans (`injected_unattributed`) | high | **COL (join key, indexed)** | ❌ | `:80, 113, 144` |
| `traceparent.outcome` | String enum | `http.request` only | **6** (v3) | COL (dim) | ❌ | `:102, 111, 122, 128` |

On a **root**, `rum.action.id == span.id` (`:80`). On a **child**, `rum.action.id == parent.span.id == ` the root's span (`:140-145`). That identity is what makes the envelope view in §6.4 a single `GROUP BY`.

### 4.5 `navigation`

Four emitters, identical key set: `TelemetryActivityLifecycleObserver.kt:60-65`, `TelemetryFragmentLifecycleObserver.kt:34-39`, `TrackComposeScreen.kt:47-52`, `TelemetryManager.kt:638-643`.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `navigation.from_screen` | String | never (`""` on first screen) | ~100s | COL (dim) | ❌ | `TelemetryManager.kt:638` |
| `navigation.to_screen` | String | never | ~100s | COL (dim) | ❌ | `:639` |
| `navigation.method` | String | never | ~4 (`push`/`pop`/…) | COL (dim) | ❌ | `:640` |
| `navigation.route_type` | String | never | ~4 (`compose_route`, `fragment_flow`, …) | COL (dim) | ❌ | `:641` |
| `navigation.has_arguments` | Bool | never | 2 | COL (dim) | ❌ | `:642` |
| `navigation.timestamp` | String ISO-8601 | never | — | COL | ❌ | `:643` |

> `navigation.from_screen` is `""`, not NULL, when there is no previous screen. Treat empty string as "session entry".

### 4.6 `screen.duration`

`TelemetryManager.kt:666-669`.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `screen.name` | String | never | ~100s | COL (dim) | ❌ | `TelemetryManager.kt:666` |
| `screen.duration_ms` | Long | never | measure | COL (measure) | ❌ | `:667` |
| `screen.exit_method` | String | never | ~4 (`disposed`, …) | COL (dim) | ❌ | `:668` |
| `screen.timestamp` | String ISO-8601 | never | — | COL | ❌ | `:669` |

`screen.name` is also emitted on `frame.summary` (`TelemetryFrameDropCollector.kt:155`), `app.anr` (`CrashReportingService.kt:150`) and `app.hang` (`:184`) — `""` when unknown.

### 4.7 `frame.summary` — Android's windowed-aggregate frame model

`TelemetryFrameDropCollector.kt:146-155`. Emitted **only when `slowFrames > 0`** (`:142`), so absence of the event is not absence of frames.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `frame.total_frames` | Int | never | measure | COL (measure) | ❌ | `TelemetryFrameDropCollector.kt:146` |
| `frame.slow_frames` | Int | never | measure | COL (measure) | ❌ | `:147` |
| `frame.frozen_frames` | Int | never | measure | COL (measure) | ❌ | `:148` |
| `frame.slow_frame_rate` | Double 0–1 | never | measure | COL (measure) | ❌ | `:149` |
| `frame.max_total_duration_ms` | Double | never | measure | COL (measure) | ❌ | `:150` |
| `frame.max_build_duration_ms` | Double | never | measure | COL (measure) | ❌ | `:151` |
| `frame.max_raster_duration_ms` | Double | never | measure | COL (measure) | ❌ | `:152` |
| `frame.window_duration_ms` | Double | never | measure | COL (measure) | ❌ | `:153` |
| `display.refresh_rate` | Float Hz | never | ~6 | COL (dim) | ❌ | `:154` |

**None of these nine is `extract.go`'s `frame.*` vocabulary.** The overlap is zero. See §7.

### 4.8 `memory_pressure`

Two emission paths with different key sets — the capability-aware path (`TelemetryMemoryUsage.kt:59-96`) and the basic fallback (`:156-165`), plus a third at `MemoryTracker.kt:307-335`. Everything below `memory.tracking_method` is **conditionally present**: each is a `?.let` on a map lookup that may be absent by API level or device.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `memory.timestamp` | String ISO-8601 | never | — | COL | ✅ | `TelemetryMemoryUsage.kt:60, 161` |
| `memory.pressure_level` | String | never | 4 (`low`/`moderate`/`high`/…) | COL (dim) | ✅ | `:61, 160`; thresholds `:145-148` |
| `memory.under_system_pressure` | Bool | enhanced path only | 2 | COL (dim) | ❌ | `:62` |
| `memory.api_level` | Int | never | ~15 | COL (dim) | ❌ | `:63, 162` |
| `memory.tracking_method` | String | never | 3 (`enhanced_capability_aware`/`basic_runtime`/`enhanced`) | COL (dim) | ❌ | `:64, 163` |
| `memory.heap_used_mb` | Long/Double | conditional | measure | COL (measure) | ❌ | `:68, 157` |
| `memory.heap_max_mb` | Long/Double | conditional | measure | COL (measure) | ❌ | `:69, 158` |
| `memory.heap_free_mb` | Long/Double | conditional | measure | COL (measure) | ❌ | `:70, 159` |
| `memory.app_memory_class_mb` | Long | conditional | measure | bag | ❌ | `:71` |
| `memory.system_available_mb` | Long | conditional | measure | COL (measure) | ❌ | `:74` |
| `memory.system_low_memory` | Bool | conditional | 2 | COL (dim) | ❌ | `:75` |
| `memory.large_memory_class_mb` | Long | conditional | measure | bag | ❌ | `:76` |
| `memory.system_total_mb` | Long | conditional | measure | bag | ❌ | `:80` |
| `memory.system_threshold_mb` | Long | conditional | measure | bag | ❌ | `:83` |
| `memory.native_heap_kb` | Long | API 26+ | measure | bag | ❌ | `:87` |
| `memory.total_pss_kb` | Long | API 26+ | measure | COL (measure) | ❌ | `:88` |
| `memory.process_pss_kb` | Long | API 26+ | measure | bag | ❌ | `:89` |

> **`memory.usage_mb` is not emitted by Android.** `extract.go:212` reads it into `rum_performance_events.memory_usage_mb`; the only occurrence in this SDK is a **comment** on a dead data class (`TelemetryBatch.kt:95-99` — `MemoryEventInfo` is never constructed anywhere; verified by grep). That column is therefore permanently NULL for Android. Android's equivalent is `memory.heap_used_mb`.

### 4.9 Memory metrics — `type: "metric"`

Metric names `memory_usage` (`TelemetryMemoryUsage.kt:102, 170`) and `system_memory_available` (`:115`). These ride the `metricName` / `value` envelope fields (`TelemetryBatch.kt:38-40`), not an attribute.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `metric.unit` | String | never | ~5 (`MB`, …) | COL (exists) | ✅ | `TelemetryMemoryUsage.kt:105, 118, 173` |
| `memory.type` | String | never | 2 (`heap`/`system`) | COL (exists) | ✅ | `:106, 119, 174` |
| `memory.source` | String | never | 4 (`capability_aware`/`activity_manager`/`runtime`/`enhanced_tracker`) | COL (exists) | ✅ | `:107, 120, 175`; `MemoryTracker.kt:110` |

### 4.10 `storage_usage`

`TelemetryMemoryUsage.kt:225-253`; `MemoryTracker.kt:132-144`. The five size keys are conditional.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `storage.timestamp` | String ISO-8601 | never | — | COL | ❌ | `TelemetryMemoryUsage.kt:226` |
| `storage.api_level` | Int | never | ~15 | bag | ❌ | `:227` |
| `storage.internal_total_mb` | Long | conditional | measure | COL (measure) | ❌ | `:232` |
| `storage.internal_free_mb` | Long | conditional | measure | COL (measure) | ❌ | `:235` |
| `storage.internal_usable_mb` | Long | conditional | measure | COL (measure) | ❌ | `:238` |
| `storage.external_total_mb` | Long | conditional | measure | bag | ❌ | `:243` |
| `storage.external_free_mb` | Long | conditional | measure | bag | ❌ | `:246` |
| `storage.tracking_method` | String | `MemoryTracker` path only | 1 (`enhanced`) | bag | ❌ | `MemoryTracker.kt:133` |

### 4.11 `ui.interaction`

`UserInteractionTracker.kt:68-79`. Suppressed entirely on secure windows and password fields (`:60, :65`) — absence is a privacy guarantee, not a gap.

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `ui.type` | String | never | ~4 (`tap`/`swipe`/…) | COL (dim) | ❌ | `UserInteractionTracker.kt:69` |
| `ui.target` | String | never (`unknown` on miss) | ~100s, **capped 64 chars** in v3 | COL (dim) | ❌ | `:70`, fallback `:67` |
| `ui.x` | Int (window-relative px) | never | high | bag | ❌ | `:71` |
| `ui.y` | Int (window-relative px) | never | high | bag | ❌ | `:72` |
| `ui.direction` | String | **swipes only** | ~4 | COL (dim) | ❌ | `:74` |
| `ui.screen` | String | omitted when no current screen | ~100s | COL (dim) | ❌ | `:75` |
| `ui.name_source` | String | **v3, not yet emitted** | 7 | COL (dim) | — | [spec Δ12](./distributed-trace-span.md) |

**`ui.target` changes meaning in v3** ([#131](https://github.com/NCG-Africa/edge_telemetry_android/issues/131)). Today every Compose tap resolves to the single constant `compose_surface`. In v3:

| Value | Meaning |
|---|---|
| a resolved name (`send_reset_link`, …) | normalized `snake_case`, capped 64 chars |
| `unnamed` | hit a semantics node, no name survived the Role gate — **actionable**, wants a `Modifier.trackTap` |
| `compose_surface` | hit no semantics node at all — **noise**, nothing to fix |

Any dashboard reading `compose_surface` as "a Compose tap happened" will, after v3, mean "a Compose tap hit nothing".

**`ui.name_source`** ∈ `track_tap | test_tag | content_description | text | resource_id | class_name | none` — always present, so Compose-vs-View naming coverage slices without a second field. It exists to make naming coverage **measurable rather than write-only**, which is the lesson `traceparent.outcome` taught the hard way (§2.3).

### 4.12 `app.start`

`TelemetryManager.kt:368-372`. Cold start only; fires at most once per process, and not at all if init missed the first Activity resume (`:360-361`).

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `app.start.type` | String | never | ~2 (`cold`/…) | COL (dim) | ❌ | `TelemetryManager.kt:370` |
| `app.start.duration_ms` | Long | never | measure | COL (measure) | ❌ | `:370` |

> **Do not conflate `app.start.duration_ms` with `span.duration_ms`.** The first is the fork → first-resume cold-start measure. In v3 `app.start` also becomes span-carrying as the launch root — and roots carry **no** `span.duration_ms` (§6.2). They are different numbers on the same event.

### 4.13 `app.exit`

`ApplicationExitHarvester.kt:81-91`, harvested from `ApplicationExitInfo` at init (`TelemetryManager.kt:422`). No-op below API 30. Records are **historical** — the timestamps are from previous processes, and only reasons in `REASON_ENUM` survive the filter (`:73`).

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `exit.reason` | String enum | never | ~11 | COL (dim) | ❌ | `ApplicationExitHarvester.kt:82` |
| `exit.importance` | String enum | never (`unknown_<n>` fallback) | ~10 | COL (dim) | ❌ | `:83` |
| `exit.description` | String | **omitted** when absent | high | bag | ❌ | `:84` |
| `exit.status` | Int | never | ~50 | COL (dim) | ❌ | `:85` |
| `exit.pss_kb` | Int (KB) | never | measure | COL (measure) | ❌ | `:87` |
| `exit.rss_kb` | Int (KB) | never | measure | COL (measure) | ❌ | `:88` |
| `exit.timestamp` | **Long epoch ms** | never | — | COL | ❌ | `:89` |
| `exit.trace` | String | omitted when absent | high, **capped** | bag | ❌ | `:90`, cap `:93-95` |

> **`exit.timestamp` is the one timestamp on the wire that is not an ISO-8601 string.** It is the raw `ApplicationExitInfo.getTimestamp()` epoch-millisecond `Long` (`:89`). Every other `*.timestamp` goes through `TelemetryTime` (`TelemetryTime.kt:26-29`). `parseTime` in `extract.go:117-134` accepts only `time.Time` and RFC3339 strings, so it would return `nil` here — this key needs an epoch-ms path, not `parseTime`.

### 4.14 `app.crash`, `app.anr`, `app.hang`

`CrashReportingService.kt`. **This group is the largest naming surprise in the contract: the error payload is mostly undotted.**

`app.crash` — `buildCrashAttributes()` (`:246-260`) + fatal extras (`:103-106`) + `DeviceStateSnapshot`:

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `message` | String, **≤1000** | never | high | COL (dim, truncated) | ❌ | `CrashReportingService.kt:248` |
| `stacktrace` | String, **≤2000** | never | high | bag | ❌ | `:249` |
| `exception_type` | String, **≤255** | never | ~100s | **COL (dim)** | ❌ | `:250` — `javaClass.simpleName` |
| `cause` | String, **≤255** | never (`unknown`) | high | bag | ❌ | `:251` |
| `error_context` | String, **≤500** | never | high | bag | ❌ | `:252` |
| `is_fatal` | Bool | never | 2 | COL (dim) | ❌ | `:253` |
| `handled` | Bool | never | 2 | COL (dim) | ❌ | `:254` |
| `crash.breadcrumbs` | String (JSON array), **≤50 entries** (`BreadcrumbManager.kt:19`) | never (`[]`) | high | bag | ❌ | `:255` |
| `user_action` | String, **≤500** | **omitted** when unset | ~100s | COL (dim) | ❌ | `:257` |
| `error_code` | String, **≤100** | **omitted** when unset | ~100s | COL (dim) | ❌ | `:258` |
| `crash.thread` | String | fatal only | ~10s | bag | ❌ | `:104` |
| `crash.is_main_thread` | Bool | fatal only | 2 | COL (dim) | ❌ | `:105` |

`app.anr` (`:146-152`) — `is_fatal:false`, `handled:false`, plus:

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `anr.duration_ms` | Long | never | measure | COL (measure) | ❌ | `CrashReportingService.kt:149` |
| `anr.threads` | **Array of objects** | never | high | bag (nested) | ❌ | `:151` |
| `screen.name` | String | never (`""`) | ~100s | COL (dim) | ❌ | `:150` |

`app.hang` (`:180-186`) — `is_fatal:false`, `handled:false`, plus:

| Key | Type | Null? | Cardinality | Call | Now | Source |
|---|---|---|---|---|---|---|
| `hang.duration_ms` | Long | never | measure | COL (measure) | ❌ | `CrashReportingService.kt:183` |
| `hang.stack` | String | never | high | bag | ❌ | `:185` |
| `screen.name` | String | never (`""`) | ~100s | COL (dim) | ❌ | `:184` |

> **⚠ The 13 `error.*` keys in this repo are not on the wire.** `EventPayloadValidator.validateCrashEvent` (`EventPayloadValidator.kt:242-308`) requires `error.message`, `error.stack_trace`, `error.exception_type`, `error.context`, `error.cause`, `error.severity_level`, `error.is_fatal`, `error.breadcrumbs`, `error.breadcrumb_count`, `error.code`, `error.product_id`, `error.user_action`. The emitter at `CrashReportingService.kt:246-260` writes the **undotted** names in the table above. The two never meet, because the only caller of that validator — `RuntimeEventValidator.validateEvent` (`RuntimeEventValidator.kt:26-62`) — **is never invoked from anywhere in the SDK** (verified by grep across `src/main`). The whole `validation/` package is unreachable code.
>
> **Build against the emitted names, not the validated ones.** Reconciling the SDK's two vocabularies is Android's problem, not the schema's, and is out of scope for this contract (§8).

### 4.15 Session lifecycle, profile, capabilities, and the Compose helper

| Key | Event | Type | Null? | Call | Now | Source |
|---|---|---|---|---|---|---|
| `session.reason` | `session.finalized` | String, 2 (`timeout`/`manual`) | never | COL (dim) | ❌ | `TelemetryManager.kt:445, 507, 1050` |
| `user.profile_updated_at` | `user.profile.update` | String ISO-8601 | never | bag | ❌ | `:959` |
| `device_capabilities` | `telemetry.capabilities_initialized` | **nested object** | never | bag | ❌ | `:815` |
| `network_capabilities` | ″ | **nested object** | never | bag | ❌ | `:816` |
| `memory_status` | ″ | **nested object** | never | bag | ❌ | `:817` |
| `initialization_timestamp` | ″ | String ISO-8601 | never | bag | ❌ | `:818` |
| `action` | `user.interaction` | String | never | COL (dim) | ❌ | `EdgeTelemetryCompose.kt:181` |
| `target` | `user.interaction` | String | never | COL (dim) | ❌ | `:182` |
| `timestamp` | `user.interaction` | String ISO-8601 | never | bag | ❌ | `:183` |
| `test.crash` | `app.crash` (debug) | String | debug helper only | bag | ❌ | `CrashReportingService.kt:283` |

> **Three attribute values are nested objects, not scalars** — `device_capabilities`, `network_capabilities`, `memory_status` (`TelemetryManager.kt:815-817`), plus `anr.threads` which is an array of objects (`CrashReportingService.kt:151`). `extract.go`'s `stringAttr` would render these through `fmt.Sprint` (`extract.go:23, 244-246`), producing Go map syntax rather than JSON. **They belong in the bag**, where JSONB stores them natively. This is a second, independent argument for the bag: parts of the payload are not flat and never were.

> `user.interaction` (`EdgeTelemetryCompose.kt:197`) is the **manual** Compose helper and is a rival to the automatic `ui.interaction`. [Δ12 of the v3 spec](./distributed-trace-span.md) retires the rival event — the helper will rename the open trace root instead. Treat `user.interaction` as deprecated; do not build a table for it.

### 4.16 Inventory totals

| | Count |
|---|---|
| Live wire attribute keys today | **146** (130 dotted + 16 undotted) |
| …of which `extract.go@b9bf1b5` reads | **30** |
| …of which are **silently dropped** | **116** |
| Keys the v3 trace spec adds | **4** (`span.start_time`, `span.duration_ms`, `trace.root_type`, `ui.name_source`) |
| Promoted to a typed column under §3 | **114** — 30 existing, **84 new** |
| Bag-only | **33** |

Two counts worth separating, because they are easy to conflate:

- **`extract.go` names 38 keys; Android sends only 30 of them.** The remaining eight — `frame.build_duration_ms`, `frame.raster_duration_ms`, `frame.type`, `frame.dropped`, `frame.total_duration_ms`, `frame.severity`, `frame.target_fps`, `memory.usage_mb` — are Flutter's vocabulary (§7) and are permanently NULL on every Android row.
- **84 new columns is more than the "~40" estimated in [#129](https://github.com/NCG-Africa/edge_telemetry_android/issues/129).** That estimate was made before the five child tables were scoped, and it is a consequence of the same rule, not a change to it: on a *narrow child table that only one event writes to*, a bounded dimension costs one column on a small table, so clause 2 admits far more keys than it does on the shared parent. The bag still holds all 146 regardless, so a wrong promotion call costs a column, never data.

---

## 5. New tables

Five child tables, each following the existing `rum_performance_events` pattern (`0001_init.up.sql:78-92`): a `SERIAL` PK, the event's own columns, `created_at`, and `telemetry_event_id INTEGER NOT NULL REFERENCES rum_telemetry_events(id)`.

| Table | Fed by | Notes |
|---|---|---|
| `rum_http_requests` | `http.request` | **Port and widen** — this table already exists in enterprise `edge_db` (`edge_apm_sql_enterprise_v012.sql:5501`) with `url, method, status_code, duration_ms, success, request_timestamp`, and has simply never been ported to Go. Widen with `request_size`, `response_size`, and the four trace join keys + `traceparent_outcome`. |
| `rum_errors` | `app.crash`, `app.anr`, `app.hang` | One table, discriminated by `is_fatal` / `handled` / the event name already on `rum_telemetry_events.event_name`. Columns per §4.14 — remember the names are **undotted**. |
| `rum_navigations` | `navigation` | §4.5. |
| `rum_screen_durations` | `screen.duration` | §4.6. Also prior art in `edge_db` (`edge_apm_sql_enterprise_v012.sql:5615`) — port it. |
| `rum_ui_interactions` | `ui.interaction` | §4.11. Do **not** build one for `user.interaction`, which v3 retires. |

Additionally, on **existing** tables:

- `rum_telemetry_events` — `attributes JSONB` (§2.1), plus the trace columns in §6.3 (`trace_id`, `span_id`, `parent_span_id`, `rum_action_id`, `trace_root_type`, `span_start_time`, `span_duration_ms`).
- `rum_devices` — `device_id VARCHAR(255)` with a unique index, and the nine static-context columns from §4.1 (§1.3b).
- `rum_apps` — `sdk_version`, `sdk_platform`.
- New `rum_frame_summaries` and `rum_storage_snapshots`, or additive columns on `rum_performance_events` — **either is fine, provided nothing existing is renamed.** Android's nine `frame.*` keys and `memory.*` keys have no overlap with the columns already there (§7), so there is no collision either way.

**Fix `service.go:136-147` alongside.** Dispatch on `event_name`, not `event.Type`, so a `navigation` or `app.crash` event stops writing an all-NULL `rum_performance_events` row (§1.3a). Without this, the new tables are additive but the pollution continues.

---

## 6. Trace: the v3 additions

Full rationale and the SDK-side design live in [`distributed-trace-span.md`](./distributed-trace-span.md). This section is only the wire and storage surface.

### 6.1 `traceparent.outcome` — six values

`injected_unwired` and `injected_expired` join the four v2 values.

| # | Value | Meaning | New in v3 |
|---|---|---|---|
| 1 | `skipped_off_allowlist` | host not allowlisted; ids recorded locally, **no header sent** | |
| 2 | `adopted` | valid inbound `traceparent` mirrored; header and action root untouched | |
| 3 | `injected_attributed` | header injected; call belongs to a known, live action | |
| 4 | `injected_unwired` | header injected; no request tag — `instrument()` never wired | ✅ |
| 5 | `injected_expired` | header injected; an action existed but aged out (2 s idle / 10 s cap) | ✅ |
| 6 | `injected_unattributed` | header injected; tag present and genuinely no action | |

Precedence, when more than one could apply: `skipped_off_allowlist` > `adopted` > `injected_attributed` > `injected_unwired` > `injected_expired` > `injected_unattributed`.

Store as a `VARCHAR` dimension, not an enum type — value 7 should not need a migration.

### 6.2 `span.start_time` and `span.duration_ms`

| Key | Type | Present on |
|---|---|---|
| `span.start_time` | String, ISO-8601 **millisecond true-UTC** (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`) | every span-carrying event |
| `span.duration_ms` | Number (ms) | **children only — absent on roots** |

The format is not a fresh choice: `TelemetryTime.kt:20-29` fixes it and `EventPayloadValidator.kt:17` already encodes the matching regex. It is the same format as every other `*.timestamp` on the wire — with the single exception of `exit.timestamp` (§4.13).

**Why it matters for storage:** today `http.request`'s event timestamp is the span **end**, not its start (the interceptor records in a `finally` block, `TelemetryInterceptor.kt:41-59`). Any backend inferring "start = event timestamp" is drawing every HTTP span one duration too late. An absolute `span.start_time` removes the inference.

Roots carry no `span.duration_ms` because a root's extent is derived, not measured — see §6.4.

### 6.3 `trace.root_type` — denormalized across all spans

Values: `launch` | `interaction` | `navigation` | `request`, one per root-minting path.

- **Added**, because the backend cannot currently tell a cold-start trace from a tap trace, and "are startup requests slower than in-session requests" is the first question anyone asks of launch-to-request tracing. It appears nowhere in the SDK today, so there is nothing to migrate.
- **Denormalized onto every child span**, not root-only. Root-only forces every interesting query to join children back to their root on `rum_action_id` purely to filter by root type — and that is the hot query, the one §6.4's view already groups by. One short low-cardinality string per child makes `p95(http.request) WHERE trace_root_type = 'launch'` a single-table scan.
- `request` is mildly redundant with `traceparent.outcome = injected_unattributed`. Kept for uniformity, so the column is never NULL and needs no special case. **`traceparent.outcome` is authoritative if the two ever disagree.**

### 6.4 Root envelopes: a query-time view, never processor state

The columns:

```sql
ALTER TABLE rum_telemetry_events
  ADD COLUMN trace_id         VARCHAR(32),
  ADD COLUMN span_id          VARCHAR(16),
  ADD COLUMN parent_span_id   VARCHAR(16),
  ADD COLUMN rum_action_id    VARCHAR(16),
  ADD COLUMN trace_root_type  VARCHAR(16),
  ADD COLUMN span_start_time  TIMESTAMPTZ,
  ADD COLUMN span_duration_ms DOUBLE PRECISION;

CREATE INDEX ix_rum_telemetry_events_rum_action_id ON rum_telemetry_events (rum_action_id);
```

The view:

```sql
CREATE VIEW rum_action_envelopes AS
SELECT
    rum_action_id,
    MIN(trace_id)            AS trace_id,
    MIN(trace_root_type)     AS root_type,
    MIN(span_start_time)     AS root_start,
    MAX(span_start_time + (span_duration_ms || ' milliseconds')::interval)
      - MIN(span_start_time) AS envelope
FROM rum_telemetry_events
WHERE rum_action_id IS NOT NULL
GROUP BY rum_action_id;
```

**Why query-time and not write-time.** The SDK persists offline batches (`OfflineBatchStorage.kt:30`, `MAX_ENVELOPES = 200`, drop-oldest), so a root's children can arrive hours or days late, out of order, in a replayed batch. A processor holding roots open in memory and closing them as children land is therefore **wrong by construction**: the replayed batch arrives long after any in-memory root was evicted, and the envelope silently under-reports. A query-time aggregate is immune — it is recomputed over whatever rows exist.

That is also why [#125](https://github.com/NCG-Africa/edge_telemetry_android/issues/125)'s rejected alternative — an explicit root-close event — **stays rejected**, saving one event per user action.

**Two properties to state up front rather than let anyone discover:**

1. **A childless root has a NULL envelope.** `MAX(...)` over a root with `span_duration_ms IS NULL` and no children yields NULL. Accepted in [#125](https://github.com/NCG-Africa/edge_telemetry_android/issues/125): a tap that triggered nothing has no meaningful extent.
2. **An envelope is never final.** It widens whenever a late batch lands. Any dashboard caching it needs a recompute window; treating first-seen as complete will silently under-report exactly the slow, flaky sessions you most want to see.

### 6.5 The forward-looking requirement

"Do you see stripped or rewritten `traceparent` headers in practice?" is unanswerable as posed — nothing has ever recorded the enum that would show it (§2.3). Once `traceparent.outcome` is persisted, header stripping by a proxy or gateway becomes visible as an anomaly in the `adopted` / `injected_*` distribution. That is the acceptance test for this section: **after the migration, `SELECT traceparent_outcome, count(*) ... GROUP BY 1` returns rows.**

---

## 7. Flagged finding: four SDKs, four frame vocabularies — and the schema implements one

**This is a finding, not a proposal. This document deliberately does not propose a canonical vocabulary.**

### 7.1 The diff

| SDK | Frame model | Keys | Verified in |
|---|---|---|---|
| **Flutter** | per-frame | `frame.build_duration_ms`, `frame.raster_duration_ms`, `frame.type`, `frame.dropped`, `frame.severity`, `frame.target_fps`, `frame.total_duration_ms` | `edge_telemetry_flutter/lib` |
| **Android** | windowed aggregate | `frame.total_frames`, `frame.slow_frames`, `frame.frozen_frames`, `frame.slow_frame_rate`, `frame.max_{total,build,raster}_duration_ms`, `frame.window_duration_ms` | `TelemetryFrameDropCollector.kt:146-153` |
| **iOS** | sampled percentile | `frame.dropped_count`, `frame.max_ms`, `frame.p95_ms`, `frame.sample_count`, `frame.source`, `frame.target_hz` | `edge_telemetry_ios_sdk` — `Sources/EdgeRumCapture/FrameSampler.swift`, asserted in `Tests/EdgeRumContractTests/PerformanceMetricsWireConformanceTests.swift:50-54` |
| **React Native** | sampled percentile | `frame.dropped_count`, `frame.max_ms`, `frame.p95_ms`, `frame.source`, `frame.target_hz` (no `sample_count`) | `edge_telemetry_react_native/src/adapters/frameAggregate.ts:9-29` |

Each row was checked directly against its SDK's source for this document, not carried over from a prior summary.

### 7.2 The evidence

`extract.go`'s seven frame columns are **Flutter's set, exactly**:

```go
// extract.go:196-199 (metric path)
FrameBuildDurationMs:  safeFloat(valueFor(event.Attributes, "frame.build_duration_ms")),
FrameRasterDurationMs: safeFloat(valueFor(event.Attributes, "frame.raster_duration_ms")),
FrameType:             stringAttr(event.Attributes, "frame.type"),
FrameDropped:          safeBool(valueFor(event.Attributes, "frame.dropped")),

// extract.go:215-219 (event path)
FrameBuildDurationMs:  safeFloat(valueFor(event.Attributes, "frame.build_duration_ms")),
FrameRasterDurationMs: safeFloat(valueFor(event.Attributes, "frame.raster_duration_ms")),
FrameTotalDurationMs:  safeFloat(valueFor(event.Attributes, "frame.total_duration_ms")),
FrameSeverity:         stringAttr(event.Attributes, "frame.severity"),
FrameTargetFPS:        safeInt(valueFor(event.Attributes, "frame.target_fps")),
```

The overlap with Android's nine keys (§4.7) is **zero**. iOS and React Native have independently converged on the same sampled-percentile shape; **Flutter and Android are the two outliers, and the schema implements Flutter's.** Three of the four SDKs' frame data is discarded.

The same pattern holds in memory, and it is worse — there is no pair of SDKs that agree:

| SDK | Memory keys |
|---|---|
| **React Native** | `memory.usage_mb`, `memory.type`, `memory.source`, `memory.pressure_level`, `memory.unit` |
| **Android** | `memory.heap_used_mb`, `memory.total_pss_kb`, `memory.pressure_level`, … (17 keys, §4.8) |
| **iOS** | `memory.footprint_kb`, `memory.resident_kb`, `memory.virtual_kb`, `memory.pressure` |
| **Flutter** | `memory.current_mb` |

`extract.go:212` reads `memory.usage_mb` — **React Native's key**, which no other SDK emits. Note iOS uses `memory.pressure` where Android and React Native use `memory.pressure_level`: a one-word difference that drops the column silently.

### 7.3 What this means for the reading of "the backend serves other SDKs"

That constraint is real and this contract respects it absolutely (§2.2, never a rename). But it cuts the opposite way to how it sounds. **The allowlist is a union of whatever shipped first, not a negotiated contract** — so it is not a working schema to be trodden around carefully. It is working for Flutter, and silently empty for everyone else.

### 7.4 Why it is not fixed here

Converging four SDKs onto one frame/memory model spans four repos and four release cycles, needs agreement from four owners, and has a different audience from this document. **Attempting it inside an Android trace ticket is how a fifth vocabulary gets created.** Recorded as out of scope on map [#120](https://github.com/NCG-Africa/edge_telemetry_android/issues/120); it wants its own effort and its own owner.

Nothing in this contract blocks that work, and bag-first actively helps it: once all four SDKs' raw keys are in JSONB, a future canonical vocabulary can be **backfilled** across historical data rather than starting from its ship date (§2.3).

---

## 8. What the SDK is not changing

So the schema is not designed around a hoped-for cleanup:

- **The undotted error vocabulary stays undotted** for now (§4.14). Reconciling `EventPayloadValidator`'s unreachable `error.*` names with the emitter's `message` / `stacktrace` / `exception_type` is an SDK-internal cleanup with its own migration cost. Build against what is emitted.
- **`exit.timestamp` stays an epoch-ms `Long`** (§4.13) unless someone asks otherwise.
- **The four nested-object attributes stay nested** (§4.15). They are JSON and belong in JSONB.
- **`http.url` stays query-stripped** (`TelemetryInterceptor.kt:47`). This is deliberate PII protection, not an omission.
- **Sampling stays fixed at 1.0** in v3, so every action produces a trace. Any sampling design is a later effort.
- **`app.exit` carries no `rum.action.id`.** Attributing a harvested exit means persisting the active root across process death; deliberately excluded in [#126](https://github.com/NCG-Africa/edge_telemetry_android/issues/126).

---

## Appendix A — reconciling "169 keys"

[#129](https://github.com/NCG-Africa/edge_telemetry_android/issues/129) quotes "the SDK emits 169 keys". That figure is the count of **distinct dotted string literals** in `telemetry_library/src/main`:

```bash
grep -rhoE '"[a-z_]+(\.[a-z_0-9]+)+"' --include='*.kt' telemetry_library/src/main/java \
  | tr -d '"' | sort -u | wc -l    # → 169
```

It is the right number for "how wide is the gap", and it is the wrong number for an inventory, because 39 of those literals are not attribute keys:

| Class | Count | Examples |
|---|---|---|
| Event / metric **names** | 14 | `app.crash`, `http.request`, `screen.duration`, `ui.interaction` |
| Test-only event names (`testing/` package) | 5 | `test.button_click`, `api_key.validation_test` |
| Stack-frame filter substrings + filenames | 5 | `java.lang`, `dalvik.system` (`CrashFingerprinter.kt:39-41`); `pending_anr.json` (`FatalCrashStore.kt:24`) |
| **Validator-only / dead** — named in `validation/` but never emitted | 15 | the 12 `error.*` keys, `error.breadcrumb_count`, `event.timestamp`, `http.timestamp`, `memory.usage_mb` |

`169 − 39 = 130` live **dotted** attribute keys. Adding the **16 undotted** wire keys the crash / capabilities / Compose-helper paths emit — `message`, `stacktrace`, `exception_type`, `cause`, `error_context`, `is_fatal`, `handled`, `user_action`, `error_code`, `device_capabilities`, `network_capabilities`, `memory_status`, `initialization_timestamp`, `action`, `target`, `timestamp` — gives the **146** used throughout this document.

The undotted 16 are the reason a purely dotted-key grep is not a sufficient inventory, and why §4.14 carries the loudest warning in the document.

---

## Appendix B — reproducing every number here

```bash
# 1. The 169 dotted literals, and the 130 live keys after classification
grep -rhoE '"[a-z_]+(\.[a-z_0-9]+)+"' --include='*.kt' telemetry_library/src/main/java \
  | tr -d '"' | sort -u

# 2. The 38-key allowlist
grep -oE '"[a-z_.]+"' EDGETELEMETRYPROCESSORGO/internal/telemetry/extract.go | sort -u

# 3. The 7 tables and their columns
cat EDGETELEMETRYPROCESSORGO/internal/db/migrations/0001_init.up.sql

# 4. Proof that validation/ is unreachable (returns nothing outside the package)
grep -rn --include='*.kt' 'RuntimeEventValidator' telemetry_library/src/main/java \
  | grep -v 'validation/RuntimeEventValidator.kt'

# 5. Proof that memory.usage_mb is comment-only on Android
grep -rn --include='*.kt' 'usage_mb\|MemoryEventInfo' telemetry_library/src/main/java
```

Verified at `edge_telemetry_android@55c603b` and `EDGETELEMETRYPROCESSORGO@b9bf1b5`.
