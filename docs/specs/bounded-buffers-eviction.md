# Android spec — bounded buffers & eviction policy

Resolves wayfinder ticket **#33** (audit #8 + #14, hub decision **D9**). Evidence:
`sdk-audit.yaml:299-321` + file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

D9 (hub, `edge_rum_spec` #1) is taken as **given**: durable offline storage is a **200-envelope,
drop-oldest** bounded buffer. This spec is the android *how* for that, plus the two other unbounded
stores the audit found that D9 doesn't cover (the in-memory queue and the pre-init buffer).

## Problem

Three client-side stores accumulate telemetry. Two are unbounded — a long-offline or chatty session
grows them without limit until OOM. One is already bounded and just needs confirming.

| Store | Holds | Cap today | Evidence |
|---|---|---|---|
| Offline batch store | `TelemetryBatch` envelopes | **none** | `OfflineBatchStorage.kt:23-28`, `sdk-audit.yaml:311-313` |
| In-memory `eventQueue` | `TelemetryEvent` | **none** | `ConcurrentLinkedQueue`, `sdk-audit.yaml:314` |
| Pre-init buffer | `() -> Unit` closures | 50, FIFO | `TelemetryManager.kt:83,1053-1057` |

Two defects beyond "no cap":

1. **Non-atomic offline writes.** `storeBatch` / `removeBatch` do read-modify-write of the *whole*
   batch list as one Gson blob in SharedPreferences (`OfflineBatchStorage.kt:23-46`). Two concurrent
   stores race → last-write-wins silently drops a batch. `sdk-audit.yaml:312`.

2. **O(n²) queue enqueue.** Any cap check on `ConcurrentLinkedQueue` via `.size` is O(n) — the class
   walks the list to count. Checking size on every `offer` makes enqueue O(n²). The existing size
   reads (`BatchProcessingService.kt:62,79,83`) already pay this; a naive cap would make it worse.

## Decisions

### 1. Offline batch store — 200 envelopes, drop-oldest, atomic (D9)

Keep SharedPreferences; **do not** move to Room. Room is declared **runtime + ktx only**
(`build.gradle.kts:123-124`) with **no `room-compiler` and no ksp/kapt plugin** — it is dead weight
in the AAR, not a free installed dependency. Wiring it means adding a compiler dep + the ksp plugin +
schema export: build machinery this ticket doesn't justify for a 200-item store that fills only while
offline (low write frequency).

Two fixes, both in `OfflineBatchStorage.kt`:

- **Serialize all mutations with a `Mutex`.** `storeBatch` / `removeBatch` / eviction run under one
  `kotlinx.coroutines.sync.Mutex.withLock`. Kills the read-modify-write race. (Class-scoped lock; a
  single process, single prefs file — no cross-process contention to design for.)

- **Cap at 200, drop-oldest on insert.** After appending, if `size > 200`, drop from the front
  (`subList(size - 200, size)` — the list is append-ordered, so front = oldest). One line inside the
  locked `storeBatch`.

```kotlin
// OfflineBatchStorage.kt
private val mutex = Mutex()
companion object { const val MAX_ENVELOPES = 200 }   // D9

suspend fun storeBatch(batch: TelemetryBatch) = withContext(Dispatchers.IO) {
    mutex.withLock {
        val batches = getStoredBatchesLocked().toMutableList()
        batches.add(batch)
        val capped = if (batches.size > MAX_ENVELOPES)
            batches.subList(batches.size - MAX_ENVELOPES, batches.size) else batches
        prefs.edit().putString(PREFS_KEY, gson.toJson(capped)).apply()
    }
}
```

**Ceiling (named):** the whole ≤200-envelope blob is (de)serialized in memory and rewritten on every
store — O(n), n≤200. Acceptable because stores happen only on send-failure (offline), i.e. at most
one per flush cycle. Upgrade path if offline depth becomes real: finish the Room wiring, one
`@Entity` per envelope, cap via `DELETE … ORDER BY seq LIMIT`. Tracked as fog, not this ticket.

**Also drop the dead Room deps** (`build.gradle.kts:123-124`) — nothing in `src/main` references
Room (`sdk-audit.yaml:307-308`), so the two `api(libs.androidx.room.*)` lines only bloat every
consumer's app. Deletion over addition. (If the Room upgrade path above is later taken, they come
back *with* the compiler + plugin — as a set, not half of one.)

### 2. In-memory `eventQueue` — 500 events, drop-oldest, counted

- **Cap = 500 events** — 10× the default `batchSize` (50, `TelemetryConfig.kt`). Absorbs a send-stall
  or burst without OOM; well above one batch so normal flushing never trips it. Fixed constant, not
  config — no evidence any consumer needs to tune it. Promote to `TelemetryConfig` only on request.

- **Drop-oldest**, matching D9 and the pre-init buffer. Recent events are worth more (closer to a
  crash / the current user state), so evict the front.

- **Track size in an `AtomicInteger`**, not `.size` — increment on `offer`, decrement on `poll`.
  Avoids the O(n²) enqueue. All enqueues go through one helper (mirror of the pre-init pattern at
  `TelemetryManager.kt:1053-1057`):

```kotlin
private val queueCount = AtomicInteger(0)   // ponytail: authoritative count; ConcurrentLinkedQueue.size is O(n)

private fun enqueueEvent(ev: TelemetryEvent) {
    if (queueCount.get() >= MAX_QUEUED_EVENTS) {   // 500
        if (eventQueue.poll() != null) queueCount.decrementAndGet()   // drop-oldest
    }
    eventQueue.offer(ev); queueCount.incrementAndGet()
}
```

Every `eventQueue.poll()` on the drain paths (`BatchProcessingService.kt:89,175`) must
`queueCount.decrementAndGet()` to keep the counter honest. Since those live in the service and the
counter in the manager, the drain either moves into a helper the service calls back into, or the
counter is passed in — same split already used for the flush callback in spec #32. **Simplest:** make
the count the service's own field (the service owns every enqueue/poll site) and expose it; the
pre-init buffer stays the manager's.

### 3. Pre-init buffer — confirmed, no change

Already 50, FIFO drop-oldest (`TelemetryManager.kt:1051-1057`). Correct and already aligned with
drop-oldest. It drains once at init and never refills, so 50 is ample. **Decision: keep as-is.**

### 4. Replay throttling — bounded, oldest-first, stop-on-failure

A device offline long enough to fill 200 envelopes must not, on reconnect, fire 200 back-to-back
POSTs. Today `sendStoredBatches` loops over *all* stored batches with no delay and no early exit
(`BatchProcessingService.kt:118-133`).

- **Drain at most `REPLAY_BATCH_LIMIT = 10` oldest envelopes per flush cycle**, oldest-first. The
  30 s flush timer (spec #32) paces the rest — 200 envelopes clear in ~20 cycles, not one burst. No
  new scheduler; reuse the flush tick.

- **Stop on the first failure** in a cycle. A 4xx/5xx/network error means the collector isn't ready
  for more; the remaining stored envelopes wait for the next tick. (Current code logs and keeps
  going — wasteful when the endpoint is down.)

- **Delete `restoreOfflineBatches`** (`BatchProcessingService.kt:151-166`). It un-batches stored
  envelopes back into the in-memory queue, which then re-batches them — pointless double handling,
  and now actively harmful: it would push events straight into the newly-capped 500-event queue and
  trigger drop-oldest eviction of live events. Replay goes offline-store → network directly, never
  back through the queue.

## Migration & compatibility

- All three changes are **client-internal** — no wire-format impact, independent of the envelope
  cutover (#30). Envelope *shape* is D1/#30's; this ticket only bounds *how many* are buffered.
- Existing on-device offline blobs deserialize unchanged; the 200-cap applies from the next store on.
- Dropping the Room deps is source-compatible (nothing imports Room) and shrinks the AAR.

## Test plan

- `OfflineBatchStorage`: store 201 envelopes → assert size == 200 and the *first* is gone
  (drop-oldest). Concurrent `storeBatch` from two coroutines → assert no batch lost (atomicity).
- `eventQueue`: enqueue 501 events → assert size == 500, oldest dropped, `queueCount` == 500.
- Replay: seed 25 stored envelopes, one flush cycle → assert ≤10 sent; inject a failure at #3 →
  assert send stops and 23 remain.
- Pre-init: unchanged — existing FIFO-eviction test still passes.

## Flags to hub

- **D9 names the offline (durable) buffer at 200 envelopes but is silent on the live in-memory
  queue.** The 500-event queue cap is decided android-local here. If other SDKs also carry an
  in-memory queue, the hub may want a common guidance number — raise on `edge_rum_spec`.
