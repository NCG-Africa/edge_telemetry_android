# Android spec — ANR detection (feature gap)

Resolves wayfinder ticket **#38** (feature-matrix `anr-detection: planned`, `sdk-audit.yaml:413`).
Net-new datapoint — there is **no** ANR / watchdog / main-thread-block code in the SDK today
(`sdk-audit.yaml:413`, grep-confirmed). Reference implementation:
`edge_telemetry_ionic_angular_capacitor/.../com/nathanclaire/rum/AnrWatchdog.kt` — the only
EdgeRUM SDK that ships Android ANR detection.

Rides the unified envelope (**#30**, `docs/specs/unified-wire-envelope.md`) and reuses the
durable-fatal persistence rail from the crash spec (**#39**, `docs/specs/retire-crash-path-b.md`).
Both are fixed input.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Problem

ANRs (Application Not Responding — main thread blocked long enough that the framework would show
the "app isn't responding" dialog) are a top user-facing quality signal and are entirely
uninstrumented here. `web-vitals` is `na` (native), so ANR + frame-stats + app-start are the
native performance story; frame-stats ships (#36), ANR does not.

## Decisions (from prototype/grilling #38)

The watchdog **loop mechanism is already prototyped** (the reference `AnrWatchdog`), so the open
work was the four design forks below, not the loop shape.

### 1. Mechanism — main-thread watchdog only

A single daemon background thread posts a heartbeat `Runnable` to the main `Looper` and waits.
If the main thread doesn't run the heartbeat within the threshold, it's blocked → capture + emit.
This is the reference approach.

- **Works on minSdk 24** (whole install base); no API-level gate.
- Captures the hang **live**, with our own screen/session/user context at detection time.
- **Rejected: `ApplicationExitInfo.REASON_ANR`** (API 30+, next-launch harvest of the system's
  authoritative trace). Better fidelity (native frames, only fires on real system ANRs) but adds
  a second source to dedup against, a next-launch harvest path, and covers only API 30+. Deferred
  to fog (see map **Not yet specified**) as an *enrichment* over the watchdog, not a replacement.
- **Accepted trade-off:** the watchdog reports "main thread blocked ≥ threshold," which the system
  might not itself have declared an ANR (e.g. a slow device, a debugger pause). The 5s threshold
  (§2) gates this; downstream can reconcile against `ApplicationExitInfo` later if the fog ticket
  graduates.

### 2. Threshold — fixed 5000 ms

Matches Google's input-dispatch ANR line. This deliberately draws the boundary against
**hang-detection** (map fog, the sibling feature), which owns the sub-5s "slow but not an ANR"
band. Keeping ANR at exactly the system threshold means an `app.anr` event corresponds to what the
platform itself would call an ANR.

- Internal constant, **not** a public config flag (ponytail: no config for a value that won't
  change). Upgrade path: promote to `TelemetryConfig` only if a consumer needs a different line.
- Heartbeat interval = `threshold / 2`, floored at 500 ms (reference), so a real 5s hang is caught
  in one scan.

### 3. Capture — all-thread dump

The event captures **every** thread's stack, main thread flagged. Main-thread-only (the reference)
is blind to the most common ANR root cause: a **deadlock / lock contention**, where the blocked
main thread's stack shows it *waiting* but the culprit is the background thread *holding* the lock.
ANRs are rare, so the full `Thread.getAllStackTraces()` dump cost is negligible against its
diagnostic value.

### 4. Event identity — new `app.anr` event

Distinct event name, **not** reused `app.crash`. An ANR is not a crash (with a watchdog the
process usually survives), and dashboards want a separate **ANR-free rate** alongside crash-free
rate. Emitted as a standard event through the same `EventAttributes` enrichment as everything else
(#30), so it automatically carries `session.*` / `user.*` / `sdk.*`.

Shape (D1 unprefixed keys, JSON-typed, consistent with #39):

```
event  app.anr
  is_fatal      false        (watchdog fires; the process is not guaranteed dead)
  handled       false        (auto-detected, not caught by app code — matches #39's handled=false for uncaught)
attributes
  anr.duration_ms   <int>    main thread blocked this long (SystemClock.uptimeMillis delta)
  screen            <string> current screen at detection (getCurrentScreen(), Activity + Compose — same source as #36)
  anr.threads       [ { name, state, main:bool, stack:"at a.b(f:1)\n..." }, ... ]   all threads, main flagged
  session.*, user.* frozen at detection time (same freeze discipline as crash, audit #1)
```

### 5. Delivery — persist-then-batch on the durable-fatal rail (reuse #39)

The system can kill the app **during** the ANR (after its own ~5s window), so an in-memory-only
event would be lost with the unflushed batch. On detection the watchdog:

1. **Synchronously writes** the `app.anr` record to `filesDir` (the eviction-exempt store #39
   established — **not** `cacheDir`), then
2. enqueues it to the normal batch.

On next `initialize()` the existing #39 replay reads pending records, sends, and **deletes only
after a 2xx**. `is_fatal:false` distinguishes it from a fatal crash record on the same rail. No new
persistence code — this is the crash durable-fatal rail with an ANR record type.

### 6. Sampling & dedup

- **No sampling** — consistent with the SDK's effective 100%-capture posture; ANRs are rare.
- **Intra-hang dedup:** after firing, the watchdog sleeps ≥ `threshold` before re-scanning
  (reference), so one long hang = **one** `app.anr` event, not one per scan.
- **No cross-session suppression.** Each distinct hang is worth counting for ANR-rate; grouping is
  a downstream concern (fingerprint on the main-thread stack), not client-side drop.

## Lifecycle

- **Start** after `initialize()` completes (SDK ready).
- **Foreground-only:** pause the watchdog on background, resume on foreground, via the
  `ProcessLifecycleOwner` observer the SDK already runs (CLAUDE.md — lifecycle tracking). Avoids
  waking a backgrounded/dozing device to post heartbeats; user-facing ANRs are foreground events.
  (Background broadcast-receiver ANRs are out of scope for v1 — note.)
- Daemon thread; `stop()` on process teardown / `resetForTesting()`.

## Files (new + touched)

| File | Change |
|---|---|
| `core/anr/AnrWatchdog.kt` | **New.** Port of the reference watchdog: heartbeat loop, 5s threshold, all-thread dump, intra-hang dedup. Emits an `app.anr` event via the enrichment path. |
| `core/services/CrashReportingService.kt` (or a small `AnrService`) | Owns watchdog start/stop, wires foreground gating + the persist-then-batch rail (#39). |
| `TelemetryManager.kt` | Start watchdog post-init; stop on teardown. |
| `TelemetryConfig.kt` | No public flag (§2). |

## Test plan

- **Unit (Robolectric):** block the main `Looper` past 5s (a `Thread.sleep` posted to it) → exactly
  one `app.anr` emitted, `anr.duration_ms ≥ 5000`, `anr.threads` contains a `main:true` entry, and
  a record lands in `filesDir`. Unblock before 5s → **zero** events (no false positive).
- **Intra-hang dedup:** a 12s block → exactly one event, not ~2.
- **Lifecycle:** background → no heartbeats posted (spy the handler); foreground resumes.
- **Delivery:** a persisted `app.anr` record replays on next `initialize()` and is deleted only
  after a stubbed 2xx (shares #39's replay test harness).

## Not a hub gap

ANR is decided android-local (no hub ANR contract). The `app.anr` event name + attribute shape is
proposed android-first; other SDKs conform if/when they add ANR. Flag the name to the hub for
cross-SDK consistency, don't block on it.
