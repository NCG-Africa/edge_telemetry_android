# Android spec — session-timeout & duplicate `screen_view` correctness

Resolves wayfinder ticket **#35** (audit #10). Two independent correctness fixes. No hub
session contract exists (the `edge_rum_spec` #1 map pins no session-timeout decision), so the
evaluation model is decided **android-local**. Evidence: `sdk-audit.yaml:66-74,245-266` + the
file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

---

## Part 1 — Session timeout evaluation model

### Problem

Timeout is evaluated **only on foreground return** (`onStart`), against `last_active_timestamp`,
which is written **only on background** (`onStop`) — so it really means *last-backgrounded-at*.
Two concrete defects, not a wrong model:

1. **Cold-start double-session / double-count.** `SessionService.initialize()`
   (`core/services/SessionService.kt:38-48`) *always* mints a new `session_id` and increments
   persisted `total_sessions`. The very next `onStart` runs `hasSessionTimedOut()`
   (`TelemetryManager.kt:405-421`) and, if timed out, emits `session.finalized` + calls
   `startNewSession()` — minting **again** and incrementing `total_sessions` **again**. A cold
   start after >30 min in background produces two sessions back-to-back and double-counts.

2. **`session_id` is not persisted**, so the "reopen within 30 min = same session" intent the
   timeout implies is already broken across process death: the OS routinely kills backgrounded
   Android apps, and today every reopen after a kill gets a fresh id regardless of elapsed time.

Foreground-return evaluation itself is *fine* — it matches the standard mobile model (Firebase/GA4
rotate on return-to-foreground after N minutes of background). No background timer is wanted: waking
the process to rotate a session no one is looking at is pure cost.

### Decision

**Foreground-return rotation, made correct by persisting the session and resuming it across process
death.** No background timer. No per-event idle clock.

#### 1. Persist the session on background

On `onStop` (`TelemetryManager.kt:431-447`), alongside the existing `last_active` write, persist
`session_id` and `session_start` to `telemetry_prefs`:

```kotlin
// SessionService — new
fun persistSession() {
    prefs.edit()
        .putString("session_id", sessionId)
        .putLong("session_start", sessionStartTime)
        .putLong("last_active_timestamp", System.currentTimeMillis())
        .apply()
}
```

`total_sessions` is already persisted (`SessionService.kt:40-41`).

#### 2. Move the session decision into `initialize()`

`initialize()` currently mints unconditionally. Replace with **load-then-decide** (this is where
the first `session.started` fires, at init step 15 — before any Activity `onStart`):

```kotlin
fun initialize() {
    val savedId    = prefs.getString("session_id", null)
    val lastActive = prefs.getLong("last_active_timestamp", 0L)
    val withinTimeout = savedId != null &&
        System.currentTimeMillis() - lastActive < config.sessionTimeoutMs

    if (withinTimeout) {
        // RESUME — silent continuation
        sessionId        = savedId!!
        sessionStartTime = prefs.getLong("session_start", System.currentTimeMillis())
        totalSessions    = prefs.getInt("total_sessions", 1)     // no increment
        resumed = true                                            // suppress session.started (step 15)
    } else {
        // NEW (or timed-out) session
        timedOutOnInit = savedId != null                         // signals finalize below
        sessionId        = idGenerator.generateSessionId()
        sessionStartTime = System.currentTimeMillis()
        totalSessions    = prefs.getInt("total_sessions", 0) + 1
        prefs.edit().putInt("total_sessions", totalSessions).apply()
    }
    // Make the immediately-following first onStart a no-op:
    prefs.edit().putLong("last_active_timestamp", System.currentTimeMillis()).apply()
}
```

Writing `last_active = now` at the end means the first `onStart` after init sees `elapsed ≈ 0` →
not timed out → the existing resume branch logs and does nothing. **No double-decision, no
double-mint** — defect 1 fixed. Subsequent *warm* returns (background→foreground, same process) keep
using the unchanged `onStart` path and rotate correctly with live in-memory stats.

#### 3. Emission behavior

- **Resume (within timeout):** emit **nothing** — silent continuation. `TelemetryManager` init step
  15 (`:341-344`) checks `sessionService.wasResumed()` and **skips** `emitSessionStartedEvent()`.
  The session-id continuity is the signal; the backend already saw the original `session.started`
  from the prior process.
- **Timed-out on init:** before minting, emit `session.finalized(session.reason = "timeout")` for
  the old id with duration from persisted `session_start → last_active`. **Minimal stats only** —
  in-memory counters (event/screen counts, `visited_screens`) died with the prior process; the
  backend reconstructs real counts by grouping the event stream on `session_id`. Then emit
  `session.started` for the new session (existing step-15 path).
- **Warm timeout rotation** (`onStart`, same process): unchanged — `session.finalized` here carries
  **accurate** live in-memory stats, then `session.started`.

### Known limitation (accepted)

A resumed session's in-memory counters restart at 0 in the new process, so client-side per-session
counts under-report after any process death. This is inherent to resuming across death and is why
post-death `session.finalized` is minimal — the backend is the source of truth for per-session
aggregates.

### Files touched (execution, downstream)

| File | Change |
|---|---|
| `core/services/SessionService.kt` | load-then-decide `initialize()`; `persistSession()`; `wasResumed()` / `timedOutOnInit()` flags; expose persisted-derived duration for finalize |
| `core/TelemetryManager.kt` | init step 15 skips `session.started` when resumed; emit `session.finalized(timeout, minimal)` when `timedOutOnInit`; `onStop` calls `persistSession()` (replaces bare `updateLastActiveTimestamp()`) |
| `core/services/SessionServiceTest.kt` | tests below |

### Failing tests

```kotlin
@Test fun `cold start after timeout mints exactly one session and increments total once`() { … }
@Test fun `cold start within timeout resumes persisted id, no total increment, no session_started`() { … }
@Test fun `timed-out cold start finalizes old id with reason=timeout before starting new`() { … }
```

The double-mint test fails today (two ids, `total_sessions += 2`); passes after the fix.

---

## Part 2 — Duplicate / timing-stealing `screen_view`

### Problem — worse than "duplicate shape"

`screen_view` is emitted from two places under one wire name:

- **ungated**, on `onActivityPaused` (`TelemetryActivityLifecycleObserver.kt:71-79`) —
  `{screen_name, duration_ms, session_id, timestamp}`.
- **gated** (`enableLegacyScreenEvents`, default off) + `@Deprecated`, via
  `recordScreenView()` (`TelemetryManager.kt:556-568`) — `{screen_name}` only. The deprecation text
  states plainly: *"Legacy screen_view event is not supported by backend."*

`ScreenTimingTracker.endScreen()` **removes** the entry it reads
(`ScreenTimingTracker.kt` — `screenStartTimes.remove(...)`). Because `onActivityPaused` **always**
precedes `onActivityStopped`, the pause path calls `endScreen` first and consumes the timing; the
stop path's `endScreen` then returns **null**, so **`recordScreenDuration(...)` → `screen.duration`
never fires on the normal Activity flow.** `screen.duration` is the *backend-supported* screen event
(routed by `event_processor.py` → `rum_screen_durations`, carries `exit_method`). Today it is dead
on the Activity path — starved by an unsupported event.

### Decision

**Retire `screen_view` entirely on Android.** No shape survives.

1. **Delete the pause emission**, `TelemetryActivityLifecycleObserver.kt:68-80` (the
   `endScreen` + `recordEvent("screen_view", …)` block). Keep `performanceTracker.stop()` in
   `onActivityPaused` (leak prevention). `onActivityStopped` becomes the sole owner of `endScreen`
   for the pause→stop flow → **`screen.duration` (with `exit_method`) revives.**
2. **Delete `recordScreenView()`** (`TelemetryManager.kt:551-569`) and the
   `enableLegacyScreenEvents` field (`TelemetryConfig.kt:16`) and its reader
   (`TelemetryManager.kt:134`, `isLegacyScreenEventsEnabled`). Removes the last `screen_view`
   emitter and the opt-in flag. Source-breaking for any consumer still calling the deprecated,
   default-off method — acceptable at a conformance milestone (already `@Deprecated(WARNING)` and a
   no-op for them by default). Drop the `enableLegacyScreenEvents` row from the CLAUDE.md opt-in
   table.

After this, screen lifecycle on Android is: `navigation` (entry, `onActivityResumed`) +
`screen.duration` (exit, `onActivityStopped`/`Destroyed`/`SaveInstanceState`, with `exit_method`).
Both backend-supported; no `screen_view` anywhere.

### Files touched (execution, downstream)

| File | Change |
|---|---|
| `core/TelemetryActivityLifecycleObserver.kt` | delete the `screen_view` emission block (`:68-80`); `onActivityStopped` owns `endScreen` |
| `core/TelemetryManager.kt` | delete `recordScreenView()` (`:551-569`) and `isLegacyScreenEventsEnabled()` (`:134`) |
| `core/TelemetryConfig.kt` | delete `enableLegacyScreenEvents` (`:16`) |
| `CLAUDE.md` | remove the `enableLegacyScreenEvents` opt-in row |
| test | assert `screen.duration` fires on a pause→stop cycle (fails today: pause steals the timing) |

### Failing test

```kotlin
@Test fun `pause then stop emits screen_duration, never screen_view`() {
    // drive onActivityPaused then onActivityStopped for one Activity
    // assert: exactly one screen.duration event (with exit_method), zero screen_view events
}
```

Fails today — pause fires `screen_view` and consumes the timing, so `screen.duration` never emits.

---

No wire-format coupling to the #30 envelope. Both parts are client-internal correctness; Part 2
*removes* a wire name, Part 1 changes only session bookkeeping + the `session.finalized`/`started`
firing conditions.
