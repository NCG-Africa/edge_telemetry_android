# Spec: ApplicationExitInfo — system exit-reason harvest

Spec for map [#85 — Native headroom](https://github.com/NCG-Africa/edge_telemetry_android/issues/85),
ticket [#88](https://github.com/NCG-Africa/edge_telemetry_android/issues/88). Widens the closed-map
[#29](https://github.com/NCG-Africa/edge_telemetry_android/issues/29) fog line ("ANR `ApplicationExitInfo`
enrichment — deferred in #38") from ANR-only to **all abnormal exit reasons**.

**Plan-only.** No merged code. Downstream execution takes this as the fixed key set.

## Why this closes the biggest coverage gap

`getHistoricalProcessExitReasons()` is the OS's authoritative record of *why the process last died* —
including **system ANR/OOM/kill reasons the in-process watchdog can never observe** (the process was already
dead) and a **native trace** (ANR all-thread dump / native tombstone) no in-process handler can capture.
Read-once at next launch; permission-free; API 30+.

## Delivery rail — harvest-at-next-launch

Its own rail, distinct from #90 (static `device.*`) and #91 (dynamic freeze-on-fault):

- **Timing:** synchronously during `initialize()`, after services are ready, on the existing
  `Dispatchers.IO` scope. Reading the buffer + `getTraceInputStream()` is blocking I/O — **never on the
  main thread**.
- **Emission:** each surviving record → one `app.exit` event queued into `BatchProcessingService`, riding
  existing batching + offline retry. No envelope change.
- **Gate:** `Build.VERSION.SDK_INT >= 30`. No-op below (minSDK 24). Absent, not stubbed.
- **Timestamp semantics:** envelope `event.timestamp` = harvest time (now); `exit.timestamp` = actual death
  time. Backend uses `exit.timestamp` for when-it-died.

Standalone by necessity, not just choice: the dead process's `app.anr`/`app.crash` were already batched-and-
sent (or lost) in the process that died — they can't be retroactively enriched in-SDK. Backend may correlate
`app.exit` ↔ prior `app.anr`/`app.crash` on `device.id` + timestamp if it wants.

## Reasons emitted — abnormal only

Emit these `getReason()` values (stability-triage signal):

`REASON_ANR`, `REASON_CRASH`, `REASON_CRASH_NATIVE`, `REASON_LOW_MEMORY`, `REASON_SIGNALED`,
`REASON_DEPENDENCY_DIED`, `REASON_EXCESSIVE_RESOURCE_USAGE`, `REASON_PERMISSION_CHANGE`.

**Dropped** (benign / retention-not-stability, would dilute the signal): `REASON_EXIT_SELF`,
`REASON_USER_REQUESTED`, `REASON_USER_STOPPED`, `REASON_OTHER`, `REASON_INITIALIZATION_FAILURE`,
`REASON_FREEZER`, `REASON_PACKAGE_*`.

`REASON_LOW_MEMORY` is the single highest-value record — the OS killing us for RAM, invisible to the
in-process watchdog today.

## Dedup across launches — timestamp watermark

The buffer is rolling (~16 records); the same records reappear every launch. Cursor:

- Persist `getTimestamp()` of the newest emitted exit in SharedPreferences (`telemetry_last_exit_ts`).
- Each init: emit only records with `timestamp > watermark` (strict `>`, so the watermark record is never
  re-emitted), then advance the watermark to the newest emitted.
- `getTimestamp()` is ms-precision and monotonic per process death — same-ms collisions across distinct
  deaths are effectively impossible; no `(timestamp, pid)` tuple needed.
- **First-ever launch** (no watermark): emit the whole existing buffer once, then set the watermark — free
  history, including exits from before the SDK update. Backfill happens exactly once.

## Keys — `app.exit` event

All permission-free, gated `SDK_INT >= 30`.

| Key | Type | Source |
|---|---|---|
| `exit.reason` | String | `getReason()` → enum name: `"anr"`, `"crash"`, `"crash_native"`, `"low_memory"`, `"signaled"`, `"dependency_died"`, `"excessive_resource_usage"`, `"permission_change"` |
| `exit.description` | String | `getDescription()` — OS free-text |
| `exit.importance` | String | `getImportance()` → `"foreground"`/`"visible"`/`"background"`/`"cached"`/… — was the app visible when killed? |
| `exit.status` | Int | `getStatus()` — exit code / signal number |
| `exit.pss_kb` | Int | `getPss()` — proportional set size at death (KB) |
| `exit.rss_kb` | Int | `getRss()` — resident set size at death (KB) |
| `exit.timestamp` | Long | `getTimestamp()` — when the death happened (**not** harvest time) |
| `exit.trace` | String | `getTraceInputStream()` — **ANR + native-crash only**, capped ~4000 chars (double the SDK's normal 2000 stack cap; head + truncation marker when over). Absent for reasons with no trace. |

`exit.reason` and `exit.importance` are **mapped string enums**, not raw ints — self-describing on the wire,
consistent with this SDK's string-key convention.

## Trace cap rationale

The native trace is the headline value, but multi-thread ANR dumps run tens of KB. Cap at ~4000: below =
full; over = head (where the culprit sits) + truncation marker. Keeps a single `app.exit` from ballooning
the 200-envelope offline cap or blowing batch size.

## Out of this ticket

- Backend correlation / indexing of `app.exit` — hub/backend concern (`edge_rum_spec`), not an android
  ticket.
- Any pre-API-30 fallback (in-process ANR ground-truth) — that's the live watchdog's job (#61 hang
  detection), not this harvest.
