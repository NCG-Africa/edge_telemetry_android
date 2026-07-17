# Android spec — fix the broken timed flush

Resolves wayfinder ticket **#32** (audit #7). Independent — no hub decision, no envelope
coupling. Evidence: `sdk-audit.yaml:280-286` + file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Problem

`config.flushIntervalMs` (default 30 000 ms, `core/TelemetryConfig.kt:7`) schedules a periodic
flush, but the Runnable body is **empty** — a lone comment, no call:

```kotlin
// BatchProcessingService.kt:197-203
flushTimer?.scheduleWithFixedDelay({
    try {
        // Timer will trigger batch send check in TelemetryManager   ← never wired
    } catch (e: Exception) { Log.e(TAG, "Error in flush timer", e) }
}, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS)
```

So the timer fires every 30 s and does nothing. Batches actually leave only on two triggers:

- **queue-size threshold** — `maybeSendBatch()` (`TelemetryManager.kt:637-646`) sends when
  `queueSize >= config.batchSize` (default 50).
- **lifecycle transitions** — `onStop` flushes to **offline storage** (SharedPreferences), *not*
  the network (`TelemetryManager.kt:431-447`; `sdk-audit.yaml:288`).

**Impact:** a low-activity session (< 50 events, app stays foregrounded) holds all telemetry
client-side **indefinitely**. The 30 s guarantee the config advertises does not exist.

### Why the obvious fix is wrong

You cannot just drop `maybeSendBatch()` into the Runnable. That call is **size-gated** — below
`batchSize` it no-ops. The timer's entire job is to flush **partial** batches, i.e. exactly the
case `maybeSendBatch()` skips. The timed path must force-send.

### Why it can't be fixed inside the service alone

`BatchProcessingService` holds no reference to the event queue or the current location — both live
in `TelemetryManager`, passed *into* `sendBatch(...)` per call (`TelemetryManager.kt:657-662`).
The timer thread inside the service has nothing to flush. The fix has to bridge that gap.

## Decision

**Inject a flush callback from `TelemetryManager` into the timer.** The service owns the schedule;
the manager owns the queue + location. Callback keeps that split intact — no queue reference leaks
into the service.

### 1. `startFlushTimer` takes an `onFlush` lambda

```kotlin
// BatchProcessingService
private var onFlush: (() -> Unit)? = null

fun initialize(onFlush: () -> Unit) {
    this.onFlush = onFlush
    startFlushTimer()
}

fun startFlushTimer() {
    flushTimer = Executors.newSingleThreadScheduledExecutor()
    flushTimer?.scheduleWithFixedDelay({
        try {
            onFlush?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Error in flush timer", e)
        }
    }, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS)
}
```

`resumeFlushTimer()` already calls `startFlushTimer()`, so storing `onFlush` as a field (not a
`startFlushTimer` param) keeps resume working after a stop without re-threading the lambda.

### 2. `TelemetryManager` supplies the force-flush

The manager already has a force-send path — `sendBatch(forceSend = true)` (`:650-663`), which
resolves location and delegates to `batchProcessingService.sendBatch(...)`. The timed flush is that
path launched on the IO scope:

```kotlin
// Step 8, TelemetryManager.kt:288-291 — was batchProcessingService.initialize()
batchProcessingService.initialize(onFlush = {
    scope.launch { sendBatch(forceSend = true) }
})
```

That's the two-line fix in practice: one param added to `initialize`, one lambda passed.

### 3. Empty-queue is already safe — no extra guard

`sendBatch(forceSend = true, ...)` polls the queue into `eventsToSend` and returns early when it's
empty (`BatchProcessingService.kt:93`). So a timer tick on an idle session builds no batch and
POSTs nothing. **No empty/heartbeat requests.** No new guard needed.

### 4. `flushIntervalMs` semantics — unchanged

`scheduleWithFixedDelay(initialDelay = flushIntervalMs, period = flushIntervalMs)` stays as-is.
`fixedDelay` (gap *after* completion) over `fixedRate` is correct here: a slow/blocked send must
not let ticks pile up. First flush at 30 s, not at t=0 — matches the current schedule and avoids a
startup send before any events exist. Concurrency with a size-triggered send is safe:
`ConcurrentLinkedQueue.poll()` hands each event to exactly one caller, so the two paths can't
double-send an event.

## Failing test

`BatchProcessingServiceTest` — the timer must flush a **partial** (sub-`batchSize`) queue. Fails
today (empty Runnable → `sendBatch` never called); passes after the fix.

```kotlin
@Test
fun `timed flush force-sends a partial batch`() = runTest {
    // batchSize = 50, flushIntervalMs small for the test (e.g. 100ms)
    val queue = ConcurrentLinkedQueue<TelemetryEvent>().apply { offer(anEvent()) } // 1 < 50
    coEvery { httpClient.sendBatch(any()) } returns Result.success(Unit)
    service.setIdsInitialized(true)

    service.initialize(onFlush = { scope.launch { service.sendBatch(queue, forceSend = true) } })

    // advance past one interval
    coVerify(timeout = 1000) { httpClient.sendBatch(match { it.batchSize == 1 }) }
}

@Test
fun `timed flush on an empty queue sends nothing`() = runTest {
    service.setIdsInitialized(true)
    service.initialize(onFlush = { scope.launch { service.sendBatch(ConcurrentLinkedQueue(), forceSend = true) } })
    advanceTimeBy(interval * 2)
    coVerify(exactly = 0) { httpClient.sendBatch(any()) }
}
```

Real-timer scheduling is awkward under `runTest`; the pragmatic version drives the interval with a
short real `flushIntervalMs` + a Robolectric idle, or refactors the schedule behind an injectable
executor. Either is fine — the assertion (`httpClient.sendBatch` called with a sub-threshold batch)
is what must hold.

## Files touched (execution, downstream)

| File | Change |
|---|---|
| `core/services/BatchProcessingService.kt` | `initialize(onFlush)`; store `onFlush`; Runnable calls `onFlush?.invoke()` |
| `core/TelemetryManager.kt` | pass `onFlush = { scope.launch { sendBatch(forceSend = true) } }` at step 8 (`:289-290`) |
| `core/services/BatchProcessingServiceTest.kt` | the two tests above |

No config change, no wire-format change, no interaction with the #30 envelope work.
