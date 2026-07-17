# Android spec — hang detection (sub-5s main-thread stalls)

Resolves wayfinder ticket **#44** (feature-matrix `hang-detection: planned`, `sdk-audit.yaml`).
Net-new datapoint — no hang / sub-ANR stall code today. Complement to **ANR** (#38,
`docs/specs/anr-detection.md`): ANR owns the ≥5s upper band and built the main-thread watchdog
primitive; hang detection is the "slow but not an ANR" band users still feel.

Fixed input:
- **ANR watchdog** (#38, `docs/specs/anr-detection.md`) — the heartbeat primitive this extends.
- **frame.summary** (#36, `docs/specs/frame-metrics-windowing.md`) — the lower neighbour; counts
  `frozen_frames` (>700ms) as a windowed *rate*.
- **unified envelope + enrichment** (#30, `docs/specs/unified-wire-envelope.md`).
- **bounded buffers** (#33, `docs/specs/bounded-buffers-eviction.md`) — the `eventQueue` 500-cap
  drop-oldest that backstops volume.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Problem

The main thread can stall for 1–5 seconds — a slow DB query on the UI thread, a synchronous image
decode, a large JSON parse, a blocking disk read — long enough that the user sees a visible freeze,
but **short of the 5s system ANR line** (#38). This band is uninstrumented as a diagnosable event.

The two neighbours don't cover it:
- **#36 `frame.summary`** counts `frozen_frames` (>700ms) but only as an **aggregate rate**, and
  only for frames that eventually render — it carries **no stack trace**. It tells you a screen was
  janky, not *what code* froze it. It also can't see a true hang where the `Choreographer` callback
  itself is blocked and no frame is submitted.
- **#38 `app.anr`** only fires at ≥5s. A 2.5s freeze — clearly felt by the user — produces nothing.

So the value hang detection adds over both: a **stack trace of what blocked the main thread**, for
stalls in the band `frame.summary` can only count and `app.anr` never sees.

## Decisions (from grilling #44)

### 1. Threshold — single 2000 ms line, no tiers

Hang is defined as **main thread blocked ≥ 2000 ms and < 5000 ms**. Band `[2000, 5000)`.

- **Lower bound 2000 ms**, not the 700ms frozen-frame line. 700ms–2s is already served by
  `frame.summary`'s frozen-frame *rate* (#36); adding stack-trace events there would be high-volume
  for a weak signal. 2s is unambiguously "the user noticed a freeze."
- **Upper bound 5000 ms** = the ANR line (#38 §2). Non-overlapping by construction: a stall that
  reaches 5s is an ANR, not a hang (see §2 for the escalation rule).
- **No severity tiers.** Severity is just `hang.duration_ms`; the backend buckets it — same posture
  as #36 ("backend derives severity") and #38's fixed threshold. Tiers would re-import the volume
  problem inside the band.
- **Internal constant, not a config flag** (ponytail: no config for a value that won't change;
  matches #38 §2). Upgrade path: promote to `TelemetryConfig` only if a consumer needs a different
  line.

The threshold choice **is** the primary volume control (see §3): 2s keeps discrete hang events rare
enough that no sampler or aggregate is needed.

### 2. Mechanism — reuse the #38 `AnrWatchdog`, two thresholds off one heartbeat

Detecting a 2s stall vs a 5s stall is the **same measurement** (how long the heartbeat `Runnable`
took to run on the main `Looper`) read against two constants. So the #38 watchdog is **extended**,
not duplicated:

- **One daemon thread, one heartbeat, two comparisons.** No second detector.
- **Heartbeat interval drops** to catch a 2s stall in one scan: `min(hangThreshold/2, anrInterval)`
  ≈ **1000 ms** (#38 floored at 500ms for the 5s line; keeping 500ms is also fine). Cheaper posts,
  still negligible.
- **One stall can cross both lines → emit both events.** A single unbroken 6s block is *both* a
  hang (≥2s) *and* an ANR (≥5s). The watchdog fires **`app.hang` when the stall crosses 2s**, and if
  that *same* stall later crosses 5s it *also* fires **`app.anr`**. They're distinct signals
  (one "it froze," one "system-level ANR") and dashboards want **hang-free** and **ANR-free** rates
  independently countable. Dedup is **intra-threshold** (one hang per stall, one anr per stall),
  **not** cross-threshold — the terminal ANR does not suppress its own precursor hang.
- **Intra-hang dedup** reuses #38's post-fire sleep: after firing `app.hang`, the watchdog waits out
  the rest of the stall before re-scanning, so one unbroken freeze = **one** `app.hang`.

### 3. Volume control — discrete events, no sampling, no aggregation

**Discrete `app.hang` events.** No `hang.summary` aggregate, no sampler.

A slow frame is only meaningful as a *rate* (36k of them, individually meaningless) → #36 aggregates.
A hang is the **opposite**: a discrete, individually-diagnosable freeze **with a stack trace**.
Aggregating hangs into a count+max would discard the stack — the entire reason hang detection exists
over `frame.summary`'s frozen counter. So aggregation here destroys the signal it carries.

Volume stays bounded without a sampler:
- **Physics caps it** — you cannot fit more than ~30 two-second stalls into a foreground minute; a
  real app has a handful per session. Nothing like the per-frame firehose that forced #36.
- **Intra-hang dedup** (§2) — one event per unbroken stall.
- **Global backstop already exists** — #33 bounds `eventQueue` at 500 events, drop-oldest. A
  pathological app that somehow floods hangs is capped by that **shared** bound. No hang-specific cap
  or sampler is built (ponytail: don't add a second limiter for what #33 already limits).

### 4. Capture — main-thread stack only

`app.hang` dumps **only the main thread's** stack. The all-thread dump stays **exclusive to
`app.anr`** (#38 §3).

- A 2s stall is overwhelmingly **slow synchronous main-thread work** — the culprit *is* on the main
  thread's own stack. An all-thread dump adds noise, not signal.
- The one case needing the lock-*holder* thread — a genuine deadlock — **does not resolve in 2s**.
  It keeps blocking, escalates past 5s, and becomes an `app.anr`, which #38 *already* captures with
  the full `Thread.getAllStackTraces()` dump. So the expensive diagnostic is preserved exactly where
  it pays off and never duplicated.
- **Frequency-matched cost:** hangs fire more often than ANRs, so the cheaper single-thread capture
  is the right weight (ponytail).

Result: `anr.threads` (array, main flagged) becomes, for hang, a single `hang.stack` string.

### 5. Event identity + shape — `app.hang`

Distinct event name, mirroring `app.anr` minus the all-thread array. Emitted through the same
`EventAttributes` enrichment as everything else (#30), so it automatically carries
`session.*` / `user.*` / `sdk.*`, frozen at detection time (same freeze discipline as crash/ANR,
audit #1).

Shape (D1 unprefixed keys, JSON-typed, consistent with #38/#39):

```
event  app.hang
  is_fatal      false      process survives a sub-5s stall (below the ANR line)
  handled       false      auto-detected, not caught by app code (matches #38)
attributes
  hang.duration_ms   <int>     main thread blocked this long (SystemClock.uptimeMillis delta)
  screen             <string>  current screen at detection (getCurrentScreen(), Activity + Compose — same source as #36/#38)
  hang.stack         <string>  main-thread stack, "at a.b(f:1)\n..." (single thread, §4)
  session.*, user.*  frozen at detection time
```

No `severity` field (§1 — duration is the severity). No `hang.threads` array (§4).

### 6. Delivery — normal in-memory batch (NOT the durable rail)

`app.hang` enqueues to the **normal batch**. It does **not** use #39's synchronous `filesDir`
durable-fatal rail.

#38 persists because the system can kill the app *during* a ≥5s ANR, losing an in-memory event. A
hang is **below** the ANR line by definition — the process virtually always survives a 2s stall
(that's what makes it a hang and not an ANR). So the durability justification does not hold; writing
every hang to disk is I/O nothing needs (ponytail: don't persist what doesn't need to survive).

The durable case stays covered anyway: a stall severe enough to get the app killed has crossed 5s →
it is *also* an `app.anr` (§2), which *does* persist on the #39 rail. So no hang is lost that matters,
and no new persistence code is added.

## Lifecycle

Inherited from the #38 watchdog it extends — no new lifecycle wiring:
- **Start** after `initialize()` completes (SDK ready), alongside the ANR threshold.
- **Foreground-only:** paused on background / resumed on foreground via the existing
  `ProcessLifecycleOwner` observer. Sub-5s stalls are user-facing foreground events; no reason to
  wake a backgrounded device.
- Daemon thread; stops on process teardown / `resetForTesting()`.

## Files (touched)

| File | Change |
|---|---|
| `core/anr/AnrWatchdog.kt` | **Extend** (#38's new file). Add the 2000ms hang threshold + ~1000ms heartbeat; on a stall crossing 2s emit `app.hang` (main-thread stack), on the same stall crossing 5s also emit `app.anr`; intra-threshold dedup for each. |
| `core/services/CrashReportingService.kt` (or the #38 `AnrService`) | Emits `app.hang` via the enrichment path to the **normal batch** (§6) — no durable-rail wiring for hang. |
| `TelemetryConfig.kt` | No public flag (§1). |

No changes to `TelemetryManager` lifecycle beyond what #38 already adds (same watchdog start/stop).

## Test plan

Extends #38's watchdog test harness:

- **Unit (Robolectric):** block the main `Looper` for ~2.5s (a `Thread.sleep` posted to it) →
  exactly **one** `app.hang`, `hang.duration_ms ≥ 2000` and `< 5000`, `hang.stack` non-empty and
  main-thread only, `is_fatal:false`, and **no** `filesDir` record (normal batch, §6). Unblock
  before 2s → **zero** events (no false positive).
- **Escalation (both events):** a single unbroken ~6s block → **exactly one** `app.hang` *and*
  **exactly one** `app.anr` (§2), not two hangs and not a suppressed hang.
- **Intra-hang dedup:** a 3s block → exactly one `app.hang`, not ~3.
- **Band boundary:** a 4.9s block → one `app.hang`, **no** `app.anr`; a 5.1s block → one `app.hang`
  + one `app.anr`.
- **Lifecycle:** background → no heartbeats posted (spy the handler); foreground resumes (shared
  with #38).

## Not a hub gap

Hang detection is decided android-local (no hub hang contract). The `app.hang` event name +
attribute shape is proposed android-first; other SDKs conform if/when they add hang detection. Flag
the name to the hub for cross-SDK consistency (alongside `app.anr`), don't block on it.
