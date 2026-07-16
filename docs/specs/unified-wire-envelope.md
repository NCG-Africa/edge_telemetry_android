# Android spec — unified wire envelope + `sdk.*` attributes

Resolves wayfinder ticket **#30** (audit #3 / #11). Hub decisions **D2** (envelope), **D11** (`sdk.*`), **D1** (crash) are fixed input — see `NCG-Africa/edge_rum_spec` `SEMANTIC_CONVENTIONS.md` / `SPEC_V1_DECISIONS.md`. Evidence for current state: `sdk-audit.yaml` (repo root) + file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Problem

HEAD (`2.1.13`) ships **two** non-conformant envelopes:

- **Path A** (all events + manual `recordCrash`) — `TelemetryHttpClient.kt:147-166`:
  `{type:"batch", events[], batch_size, timestamp, device_id, location}`, sent unwrapped.
- **Path B** (uncaught crashes + all `trackError`) — `FlutterCompatiblePayload.kt`, sent by `CrashRetryManager.sendCrashData` (`:131-145`):
  `{timestamp, device_id, data:{tenant_id, location, timestamp, batch_size, events[]}}`, its own Gson + headers, crash attrs from `DeviceInfoCollector.getCrashAttributes()` (device+app+network only — **no `session.*`, no `user.*`**).

The hub wants **one** `telemetry_batch` envelope everyone else already sends.

## Decisions (from grilling #30)

1. **One envelope, hard cutover.** SDK emits only `telemetry_batch`. No SDK-side dual-emit — old-format tolerance, if any, is the collector's concern (an app on an old SDK version keeps sending the old shape until it upgrades; the SDK can't control that, and `sdk.version` lets the backend tell conformant from legacy traffic).
2. **`sdk.*` move to the body.** `sdk.version` + `sdk.platform` become **required common attributes** on every event. Drop the `X-SDK-Version` / `X-SDK-Platform` headers (`TelemetryHttpClient.kt:120-125`, `CrashRetryManager.kt:139-140`). `X-API-Key` stays.
3. **Full serializer + envelope + attribute unification.** One envelope builder, one attribute-enrichment pipeline, one POST helper for both rails. Crash keeps only its distinct **flush/retry timing** (immediate flush, eviction-exempt storage, its own retry schedule).
4. **Drop `tenant_id` from the body.** Tenant is identified server-side by `X-API-Key`.

## Target envelope

```json
{
  "type": "telemetry_batch",
  "timestamp": "2026-07-16T09:41:00.123Z",
  "batch_size": 5,
  "events": [ { "type": "event", "eventName": "...", "timestamp": "...", "attributes": { ... } } ]
}
```

- Top-level: `type` (literal `telemetry_batch`), `timestamp` (ISO 8601 **true UTC**, D6), `batch_size` (int), `events[]`. Nothing else.
- **Removed top-level:** `device_id` (now `device.id` per-event attr), `location` (D2 — geo is collector-derived), `data{}` wrapper (Path B), `tenant_id`.
- Per-event element unchanged in shape: `{type:"event"|"metric", eventName?, metricName?, value?, timestamp, attributes{}}`. `attributes` values are **natively typed** — no stringified bools/numbers (fixes Path B `is_fatal` string).

## Common-attribute block (every event's `attributes`)

Existing Path-A block stays (`TelemetryHttpClient.kt:170-234`): `app.*`, `device.*`, `user.*`, `session.*`, `network.type`. **Add:**

| Attribute | Value | Source |
|---|---|---|
| `sdk.version` | `BuildConfig.SDK_VERSION` | required |
| `sdk.platform` | `"android"` (D11 enum) | required |

`device.platform` stays lowercase `"android"`; `network.type` stays in the D20 enum (`wifi`/`cellular`/`ethernet`/`offline`/`unknown`).

## Crash as a standard event

Crash stops being a separate envelope. It becomes an `app.crash` event built through the **same** `EventAttributes` enrichment as every other event, so it automatically carries `session.*`, `user.*`, `sdk.*` (kills the Path B gap — D1). Crash-specific keys are **unprefixed** per D1: `message`, `stacktrace`, `exception_type`, `cause`, `is_fatal` (real JSON bool), `handled`, `error_context`, `error_code`, `product_id`, `user_action`, `crash.breadcrumbs`. SDK never sends `crash_hash`/`severity` (server-computed).

## Code changes

| Area | Change |
|---|---|
| `core/models/` (`TelemetryDataOut`) | Rename wire `type` → `telemetry_batch`; delete `device_id` + `location` fields. |
| `core/TelemetryHttpClient.kt:147-166` | Emit unified envelope; drop top-level `device_id`/`location`; add `sdk.version`/`sdk.platform` into `flattenAttributes` (`:170-234`). |
| `core/TelemetryHttpClient.kt:120-125` | Remove `X-SDK-*` headers. |
| `core/payload/FlutterCompatiblePayload.kt` | **Delete** `CrashBatchEnvelope` + `createCrashBatchEnvelope` overloads. |
| `core/retry/CrashRetryManager.kt:131-145` | Drop bespoke Gson + `X-SDK-*` headers; build crash as an `app.crash` event via the shared enrichment; serialize + POST via the shared helper. Keep immediate-flush + eviction-exempt + retry schedule. |
| `core/device/DeviceInfoCollector.kt:95` (`getCrashAttributes`) | Retire — crash uses full common enrichment, not this reduced set. |

Single source of truth for the envelope + attribute enrichment; two flush *triggers* only.

## Migration

Hard cutover in the next SDK version. No dual-emit. Backend must accept `telemetry_batch` before/as clients upgrade; legacy `type:"batch"` traffic from un-upgraded apps is disambiguated by `sdk.version` (D17 drop-counters make legacy vs conformant observable).

## Test plan

Golden-JSON assertions (`src/test/`, Robolectric + Gson):

1. **Envelope shape** — serialize a batch of one `event`, one `metric`; assert exact top-level keys `{type,timestamp,batch_size,events}`, `type == "telemetry_batch"`, and **absence** of `device_id`/`location`/`data`/`tenant_id`.
2. **Common attrs** — assert every event's `attributes` contains `sdk.version` + `sdk.platform=="android"`, plus `device.id`, `session.id`, `user.id`.
3. **Native types** — assert `is_fatal` serializes as JSON boolean, session counters as JSON ints (no quotes).
4. **Crash unification** — force a crash; assert it rides `telemetry_batch` with the D1 unprefixed keys **and** carries `session.*` + `user.*` (the Path B regression guard).
5. **Headers** — assert request has `X-API-Key`, and **no** `X-SDK-Version`/`X-SDK-Platform`.

## Downstream

These tickets spec their slice against this envelope: retire-Path-B (**#39**), network-unify (**#40**), timestamp (**#41**), interaction events (**#42**), trace/span (**#43**).
