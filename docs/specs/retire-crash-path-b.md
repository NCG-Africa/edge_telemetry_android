# Android spec — retire crash Path B, attach session + user

Resolves wayfinder ticket **#39** (audit #1, hub decision **D1**). Builds on the unified
envelope spec (**#30**, `docs/specs/unified-wire-envelope.md`) — that envelope is fixed input.
Evidence for current state: `sdk-audit.yaml` (repo root) + file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Problem

HEAD (`2.1.13`) runs crashes down a **separate pipeline** from all other telemetry:

- `CrashReporter` (installed `UncaughtExceptionHandler` + **all** `trackError()` overloads)
  → `FlutterPayloadFactory.createCrashBatchEnvelope()` → `CrashBatchEnvelope`
  → `CrashRetryManager.sendCrashData` (`:131-145`).
- Its own envelope (`{timestamp, device_id, data:{tenant_id, location, ...}}`), its own Gson,
  its own `X-SDK-*` headers, crash attrs from `DeviceInfoCollector.getCrashAttributes()` —
  **device + app + network only, NO `session.*`, NO `user.*`** (`sdk-audit.yaml:193-209`).

Result (audit #1, the top PROD bug): the processor keys on `session.id`/`user.id`, so it **drops
every genuine crash**. Only manual `recordCrash()` (which rides the batch/event path, "Path A")
lands. And `is_fatal` is a stringified `"true"` on Path B vs a JSON bool on Path A — two
incompatible schemas under one `eventName: "app.crash"`.

**Two further latent bugs found while specing this:**

1. **Fatal crashes barely land at all today.** The uncaught handler (`CrashReporter.kt:151-162`)
   does `scope.launch { sendCrashData }` then **immediately** calls
   `originalHandler.uncaughtException(...)` in `finally` (`:76`) — which terminates the process.
   The async coroutine races process death and usually loses. Path B's "durability" is real only
   for non-fatal `trackError()` (process alive) and for retries — not for actual fatal crashes.
2. **Offline crash storage is on `cacheDir`** (`CrashRetryManager.kt:66`), which the OS **evicts
   under storage pressure**. So it is not the eviction-exempt store it claims to be.

## Decisions (from grilling #39)

### 1. Two rails, three entry points

Crash stops being a separate envelope. Every crash becomes an `app.crash` **event** built through
the **same** `EventAttributes` enrichment as every other event (#30), so it automatically carries
`session.*`, `user.*`, `sdk.*` — killing the Path B context gap (D1). There are exactly **two
rails**, keyed on whether the process is dying:

| Entry point | Rail | `is_fatal` | `handled` |
|---|---|---|---|
| Uncaught `UncaughtExceptionHandler` | **Durable-fatal** (synchronous write, §2) | `true` | `false` |
| `trackError(...)` | **Normal batch** | `false` | `true` |
| `recordCrash(throwable)` | **Normal batch** | `false` | `true` |

- **Normal batch** = enrich as an `app.crash` event, queue into the standard batch; it gets the
  same batching, flush, and offline-retry every event already gets. No dedicated immediate-flush,
  no dedicated crash file. `trackError()` and `recordCrash()` become indistinguishable on the wire
  (both "SDK-reported handled error") — accepted; `recordCrash` is now effectively a `trackError`
  alias. Drops `recordCrash`'s bespoke `telemetry_pending_crash.json`.
- **Durable-fatal** is the only special case — see §2.

Trade-off accepted: a `trackError()`/`recordCrash()` recorded microseconds before an unrelated
process death can be lost with its unflushed batch. It is non-fatal and breadcrumbs still capture
it.

### 2. Durable-fatal path (fixes the two latent bugs)

On an uncaught exception the handler MUST, **synchronously, on the crashing thread, before**
calling the original handler:

1. Build the fully-enriched `app.crash` event — `session.*`/`user.*`/`sdk.*`/`device.*`/`app.*`
   captured from the **crashing** session, plus the §3 crash keys.
2. **Freeze** it: serialize the complete event and write it to a single crash file with a blocking
   write + `fd.sync()`. Replay sends it **as-is** — never re-enrich at replay time (that would
   stamp the *new* session onto an old crash).
3. Then call `originalHandler.uncaughtException(...)`.

Storage:
- **`filesDir`, not `cacheDir`** — survives OS eviction; cleared only on uninstall / clear-data.
- **No `RandomAccessFile` + `FileLock`.** After decision 1 the durable file has exactly **one
  writer** (the uncaught handler, single dying thread). Plain `writeText` + fsync. Delete the lock
  machinery (`CrashRetryManager.kt:71,172-201`).

Replay on `TelemetryManager.initialize()`:
- Read the `filesDir` crash file → build a single-event `telemetry_batch` → send via the **shared
  POST helper** (#30, one transport for both rails) → delete the file **only on HTTP 2xx**
  (delete-after-**send**, not after-enqueue: the frozen crash survives across launches until
  actually delivered).
- On failure / offline: leave the file, let **WorkManager** retry (keeps the flush/retry timing
  #30 preserved). Crash delivery stays independent of normal batch-flush timing.

### 3. Canonical `app.crash` key set

D1 shape: **unprefixed, natively-typed** keys. Reconciliation of every field both current paths
emit onto the one canonical set:

| Path A (`recordCrash`) | Path B (uncaught/`trackError`) | → Canonical | Type |
|---|---|---|---|
| `error.message` | `message` | `message` | string ≤1000 |
| `error.stack_trace` | `stacktrace` | `stacktrace` | string ≤2000 |
| `error.exception_type` | `exception_type` | `exception_type` | string ≤255 |
| `error.cause` | `cause` | `cause` | string ≤255 |
| `error.context` | `error_context` | `error_context` | string ≤500 |
| `error.is_fatal` (bool) | `is_fatal` (`"true"` string) | `is_fatal` | **JSON bool** |
| — | `user_action` | `user_action` | string ≤500 |
| — | `error_code` | `error_code` | string ≤100 |
| `error.breadcrumbs` | `breadcrumbs` | `crash.breadcrumbs` | JSON string |
| — | `crash.thread`, `crash.is_main_thread` | keep (uncaught only) | string, **JSON bool** |
| — | — | `handled` (new) | JSON bool |

**Dropped:**
- `error.severity_level` (Path A) — server-computed severity (D1).
- `error.breadcrumb_count` (Path A) — derivable server-side from `crash.breadcrumbs`.
- `product_id` (Path B) — dropped from the wire. **Follow-up:** `trackError(productId=...)` and
  `setProductContext()` no longer reach the crash wire; retire or repurpose the param (not blocking
  #39).
- `crash_hash` / `severity` — never SDK-sent (server-computed).
- `tenant_id` / `location` / `device_id` top-level / `data{}` wrapper — gone with the Path B
  envelope (#30).

## Code changes

| Area | Change |
|---|---|
| `core/crash/CrashReporter.kt:66-78` | Uncaught handler: build + **synchronously** write frozen `app.crash` event to `filesDir` **before** `originalHandler.uncaughtException`. No `scope.launch` on the fatal path. |
| `core/crash/CrashReporter.kt:84-127` | `trackError(...)` overloads: enrich as `app.crash` event (`is_fatal=false, handled=true`) → **normal batch rail**, not `sendCrashData`. |
| `core/crash/CrashReporter.kt:151-190` | Delete `handleCrash` async path + `createCrashBatchEnvelope`; fatal path is the synchronous writer above. |
| `core/services/CrashReportingService.kt` | `recordCrash(throwable)` → enrich as `app.crash` event (`is_fatal=false, handled=true`) → normal batch rail; drop `telemetry_pending_crash.json` persistence. |
| `core/payload/FlutterCompatiblePayload.kt:268-369` | **Delete** `CrashBatchEnvelope` + `createCrashBatchEnvelope` overloads. |
| `core/retry/CrashRetryManager.kt` | Repurpose to: replay `filesDir` crash file on init → single-event `telemetry_batch` → shared POST helper → delete-on-2xx; WorkManager retry on failure. **Delete** bespoke Gson, `X-SDK-*` headers (`:139-140`), `FileLock`/`RandomAccessFile` (`:71,172-201`). Move store from `cacheDir` → `filesDir`. |
| `core/device/DeviceInfoCollector.kt:95` (`getCrashAttributes`) | Retire — crash uses full common enrichment. |

Single envelope, single enrichment, single transport (#30); one crash-specific mechanism only:
the synchronous durable write for fatals.

## Test plan

Golden-JSON + Robolectric (`src/test/`):

1. **Crash-during-session carries context** (the audit #1 regression guard) — start a session, set
   a user profile, fire a crash; assert the emitted `app.crash` event carries `session.id` **and**
   `user.id` matching the active session.
2. **Fatal survives process death** — install handler, write a crash to `filesDir` via the
   synchronous path, simulate restart (`resetForTesting()` + new init), assert the frozen event is
   replayed and sent with its **original** `session.id` (not a fresh one), and the file is deleted
   only after a 2xx (stub non-2xx → file remains).
3. **Native `is_fatal`** — assert `is_fatal` serializes as JSON boolean for all three entry points
   (no `"true"` string), `handled` matches the matrix.
4. **Canonical keys** — assert unprefixed keys, `crash.breadcrumbs` present, and **absence** of
   `error.*` prefixes, `severity_level`, `breadcrumb_count`, `product_id`, `tenant_id`.
5. **Path B gone** — assert no request carries `X-SDK-Version`/`X-SDK-Platform` headers and no
   `data{}`-wrapped envelope is ever produced by the crash path.

## Downstream

Depends on the unified envelope + shared enrichment/transport from **#30**. Sibling conformance
tickets (`#40` network, `#41` timestamp) reuse the same envelope; no ordering dependency on #39.
