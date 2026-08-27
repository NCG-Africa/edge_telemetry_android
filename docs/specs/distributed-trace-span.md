# Android spec — distributed trace/span contract, v3 (launch-to-request)

Resolves wayfinder map **#120** (destination artifact). **v3, not greenfield** — `TraceManager`
(`core/trace/TraceManager.kt`, #109) and the W3C id helpers (`core/ids/IdGenerator.kt`, #34) ship
today, and map #101 delivered v2 (spec commit `8bfe471`). This revision folds the eight decisions
taken on map #120 — **G1–G7** (#121–#127), **G6b** (#131) and the **backend handover** (#129) — into
the shipped v2 contract, in the same shape v2 used: what changed, what carried forward unchanged,
then the decisions with their rationale and `file:line` anchors.

Plan-only. This is the implementation spec; **no SDK code is changed on this branch.** Execution
(editing `TraceManager`, `TelemetryInterceptor`, `TelemetryConfig`, `TelemetryManager`,
`UserInteractionTracker`, `EdgeTelemetryCompose`, the validators, README) hands off downstream, per
this repo's planning-by-default convention.

## Goal

**Launch-to-request traceability.** v2 made propagation correct under concurrency, safe by default,
interoperable, complete and measurable — and then it did not reach the traffic that matters. v3 closes
the four gaps that made the v2 contract true on paper and inert in the reference app:

1. the trace context never reaches an **async** OkHttp request (G1), which is ~100% of the reference
   app's traffic;
2. an action root **never closes** (G4), so the fix for (1) would attribute a background poll to a tap
   ten minutes earlier;
3. **launch** is not a root (G2), so startup requests have no action;
4. every Compose tap resolves to the **same constant name** and mints its root **~300 ms after** the
   request it causes (G6/G6b), so the action identity is unreadable and mis-parented.

Plus the three that make the result legible: allowlist ergonomics (G3), explicit span timing so the
app-side trace can be **drawn** (G5), terminal-event attribution so a crash or hang can be joined to
the action that caused it (G7) — and a wire contract the backend has actually agreed to store (#129).

## What changed from v2 — the eight deltas

| # | Delta | Ticket | v2 behaviour → v3 behaviour |
|---|---|---|---|
| 6 | **Capture at `newCall()`** | [G1 #121](https://github.com/NCG-Africa/edge_telemetry_android/issues/121) | interceptor reads the `ThreadLocal` on OkHttp's **pool** thread ⇒ null for every `suspend`/`enqueue` call → a `Call.Factory` wrapper stamps the context as a **request tag** at `newCall()` (caller's thread); one entry point `TelemetryManager.instrument(client)`; absent tag ⇒ `injected_unwired` |
| 7 | **Root lifetime** | [G4 #122](https://github.com/NCG-Africa/edge_telemetry_android/issues/122) | a root stays current until the next tap or background — minutes → **2 s idle / 10 s cap** on an injected `elapsedRealtime()` clock, extended on child start **and** request completion; aged out ⇒ `injected_expired` |
| 8 | **Launch root** | [G2 #123](https://github.com/NCG-Africa/edge_telemetry_android/issues/123) | startup requests have no action → root minted in `initialize()` from process fork time, **idle-exempt** until the first Activity resume, which ends the *exemption*, not the root |
| 9 | **Allowlist ergonomics** | [G3 #124](https://github.com/NCG-Africa/edge_telemetry_android/issues/124) | empty default is silently dark; exact-match only → one-time `Log.w` on empty; **dot-anchored suffix** entries (`.example.com`) with a minimum-label validation guard. The safety default itself is **not** reopened |
| 10 | **Explicit span timing** | [G5 #125](https://github.com/NCG-Africa/edge_telemetry_android/issues/125) | no start/duration on the wire; `http.request`'s event timestamp is the span **end** → `span.start_time` + `span.duration_ms` on every span-carrying event; root envelopes derived **server-side, at query time** |
| 11 | **Terminal-event attribution** | [G7 #126](https://github.com/NCG-Africa/edge_telemetry_android/issues/126) | crashes/hangs carry no trace keys → an **annotation-only** process-global last-action reference, read solely by the crash and hang emitters, never a parenting source. `app.exit` deliberately excluded |
| 12 | **Compose action identity** | [G6 #127](https://github.com/NCG-Africa/edge_telemetry_android/issues/127) + [G6b #131](https://github.com/NCG-Africa/edge_telemetry_android/issues/131) | every Compose tap is `compose_surface`, the manual helper emits a **rival** event, and roots mint ~300 ms late → semantics-tree naming behind a **Role gate**, `Modifier.trackTap` override, `ui.name_source` enum, the helper **renames the open root**, and minting moves to `onSingleTapUp` |
| 13 | **Wire contract** | [#129](https://github.com/NCG-Africa/edge_telemetry_android/issues/129) | four-value outcome enum, no root discrimination → **six-value** `traceparent.outcome` and a new **`trace.root_type`** (`launch`/`interaction`/`navigation`/`request`), denormalized across all spans |

## Unchanged from v2 (carried forward)

- **IDs — W3C Trace Context.** `trace.id` = 32 lowercase hex; `span.id` = 16 lowercase hex;
  `traceparent` = `00-<trace.id>-<span.id>-<flags>`, version `00`, `flags = 01`. `IdGenerator`
  supplies `traceId()`/`spanId()` on the shared `SecureRandom` (`core/ids/IdGenerator.kt:17,20`).
- **Spans are attributes on existing events** — no span-start/-end objects, no new event type. OTLP-
  shaped span objects were reconsidered under G5 and rejected again (map Out of scope).
- **Propagation is `traceparent` only** — no `tracestate`/baggage (still fog).
- **Head-based sampling, fixed at 1.0** (`core/TelemetryConfig.kt:19`). v3 does not touch sampling.
- **Empty allowlist means inject nowhere** — T2 #103's safety decision stands (`TraceManager.kt:99`).
  G3 changes only the ergonomics around it.
- **Δ1 `ThreadLocal` carrier** (`TraceManager.kt:47,62,65`) — Δ6 *adds* a tag; it does not remove the
  carrier, which is still what `newCall()` and the SDK-owned main-thread sites read.
- **Δ2 host gate, Δ3 adoption, Δ4 unattributed minting, Δ5 association** — all as specified in v2. The
  `rum.action.id` join key, the recorded-`span.id` ≡ on-wire invariant, and the adopt/malformed rules
  are unchanged.
- **`span.start_time` format is not a fresh choice** — ISO-8601 millisecond true-UTC string, forced by
  `TelemetryTime.kt:19-23,29` and enforced by `EventPayloadValidator.kt:17`'s regex.

---

## Decisions (v3 deltas)

### Δ6 — Trace capture moves to `newCall()`, carried by a request tag

**The gap.** The carrier is a `ThreadLocal` (`TraceManager.kt:47`) read by the interceptor at
`TelemetryInterceptor.kt:30`. Verified against OkHttp 4.12.0: `RealCall.enqueue()` runs `callStart()`
on the caller's thread and then hands an `AsyncCall` to the dispatcher pool, whose `run()` invokes
`getResponseWithInterceptorChain()` — **interceptors execute on a pool thread**. Application vs network
interceptor makes no difference. So every Retrofit `suspend` function, every `enqueue()`, and every
callback-style call reads a null carrier and reports `injected_unattributed`.

`TelemetryManager.traceElement()` (`TelemetryManager.kt:236`) does not rescue this: it projects the
carrier into the *coroutine's* thread, not OkHttp's pool.

**The fix — capture where the caller still owns the thread.** A `Call.Factory` wrapper reads
`TraceManager.current()` inside `newCall()`, which OkHttp runs on the **caller's** thread
(`OkHttpClient.kt:268` — `RealCall` is constructed there), and stamps the context as a typed request
tag. The interceptor reads the tag.

```kotlin
// core/trace/TraceTag.kt — a wrapper, because OkHttp's tag(Class, T) cannot store a bare null.
internal class TraceTag(val ctx: TraceManager.TraceContext?)

// core/trace/TracingCallFactory.kt
internal class TracingCallFactory(private val delegate: OkHttpClient) : Call.Factory {
    override fun newCall(request: Request): Call = delegate.newCall(
        request.newBuilder()
            // Stamped UNCONDITIONALLY, null included — see below.
            .tag(TraceTag::class.java, TraceTag(TraceManager.current()))
            .build()
    )
}
```

**Chosen over an `EventListener.Factory`** capturing into a `Call`-keyed map: `eventListenerFactory` is
a single slot and would clobber any listener the app already set, and the map needs cleanup on
`callEnd`/`callFailed` or it leaks. The tag has no cleanup surface and survives retries and redirects.

**Coverage this buys with zero app changes.** `viewModelScope` is `Dispatchers.Main.immediate`, so
`viewModelScope.launch { api.getUser() }` calls `newCall()` on the main thread, where the tap's carrier
is set — the common Retrofit pattern attributes correctly. An explicit `withContext(Dispatchers.IO)`
hop still needs `TelemetryManager.traceElement()`, but that seam **starts genuinely working**, because
`newCall()` then runs on the coroutine's own thread. Today it is inert for async calls.

**The tag is stamped unconditionally, null included.** That makes misconfiguration detectable without
sniffing OkHttp thread names:

| Tag state | Meaning | Outcome |
|---|---|---|
| **absent**, and `TraceManager.current()` is null | the factory was never wired | `injected_unwired` + one-time `Log.w` naming the missing wiring |
| **absent**, and `TraceManager.current()` is non-null | deprecated wiring, synchronous main-thread call — still correct | `injected_attributed` |
| **present**, holding null | genuinely no active action | `injected_unattributed` |
| **present**, holding an expired context | an action existed but aged out (Δ7) | `injected_expired` |
| **present**, holding a live context | attributed | `injected_attributed` |

The ThreadLocal fallback is what keeps `createNetworkInterceptor()` working for the sync path it always
covered; `injected_unwired` therefore means *"no tag and no carrier"* — indistinguishable-by-construction
from "unwired and idle", which is exactly why it doubles as the migration signal rather than a precise
error.

**Public surface: one entry point.**

```kotlin
/** Wires the interceptor and the newCall() tag capture together, so half-wiring is unrepresentable. */
fun instrument(client: OkHttpClient): Call.Factory =
    TracingCallFactory(client.newBuilder().addInterceptor(createNetworkInterceptorInternal()).build())
```

Apps pass it to Retrofit's `.callFactory(...)`:

```kotlin
Retrofit.Builder()
    .callFactory(TelemetryManager.instrument(client))   // was: .client(client)
    .build()
```

**`createNetworkInterceptor()` (`TelemetryManager.kt:217`) stays but becomes `@Deprecated`:**

```kotlin
@Deprecated(
    "Async requests (Retrofit suspend/enqueue) lose trace attribution with a bare interceptor: the " +
    "interceptor runs on OkHttp's dispatcher thread. Use TelemetryManager.instrument(client) and pass " +
    "the result to Retrofit's .callFactory(). See docs/specs/distributed-trace-span.md (v3, Δ6).",
    ReplaceWith("TelemetryManager.instrument(client)")
)
fun createNetworkInterceptor(): TelemetryInterceptor
```

**Migration note (README + release notes).** The old wiring keeps emitting `http.request` events and
keeps injecting `traceparent` — nothing breaks. What it cannot do is attribute an async call to an
action, and those calls now report `injected_unwired` instead of `injected_unattributed`. In the
reference integration this is a **one-line change** (`HttpFactory.kt:63`).

**Non-issue, resolved while deciding:** third-party-owned clients (image loaders, payment SDKs) stay
untracked, but they carry no interceptor either, so they emit no `http.request` event and cannot
pollute the enum.

> **Depends on Δ7.** The interceptor's `finally` block (`TelemetryInterceptor.kt:41-60`) writes the
> shared context obtained from this tag, which is why that context's last-activity field must be
> atomic. **Δ6 and Δ7 land as one change.**

### Δ7 — Root lifetime: 2 s idle, 10 s cap, extended on child start and request completion

**The gap.** `onInteraction()` mints a fresh root and sets the carrier (`TraceManager.kt:72-81`).
`onNavigation()` returns `childAttrs(root)` when a sampled root is live but never replaces it
(`:86-89`). The carrier clears only on an unsampled tap (`:75`) and on background (`:135-137`,
wired from `TelemetryManager.kt:548`). A tap's root therefore stays current on the main thread until
the next tap or backgrounding — minutes, or indefinitely.

**Δ6 amplifies this.** Today the bleed is bounded, because async calls read a null carrier and come out
unattributed. Once `newCall()` captures on `Dispatchers.Main.immediate`, every Retrofit call picks up
whatever stale root is sitting there — a background poll ten minutes after a tap would join that tap's
trace and appear user-caused.

**Decision — idle timeout plus a hard cap, the RUM-standard model.**

```kotlin
private const val IDLE_WINDOW_MS = 2_000L   // no activity for this long ⇒ the root is done
private const val AGE_CAP_MS   = 10_000L    // absolute ceiling regardless of activity
```

`current()` returns null — and **lazily clears the carrier** — once `now - lastActivity > IDLE_WINDOW_MS`
or `now - openedAt > AGE_CAP_MS`. An expired root means the request is reported **unattributed-by-expiry**
(`injected_expired`) rather than wrongly attributed.

**Why not a single hard TTL.** A chained flow breaks under start-only extension: tap → request A takes
4 s → on success fires request B. With a 2 s window the root would expire *during A's flight* and B
would land unattributed despite plainly belonging to the action.

**Extension rule — child span start *and* request completion.** Navigation or a network call beginning
extends the root; so does a request finishing. That survives chained flows regardless of how slow each
hop is, while a genuinely idle root dies 2 s after the last thing happened.

**Completion-extension is what forces the atomic field.** The interceptor's `finally` block runs on the
OkHttp pool thread, which cannot touch the `ThreadLocal` but *can* touch the shared `TraceContext` it
obtained from the Δ6 request tag:

```kotlin
} finally {
    // ... existing duration/status/attrs work (TelemetryInterceptor.kt:41-60) ...
    decision?.root?.lastActivity?.set(TraceManager.clock())   // Δ7 completion-extension, any thread
    telemetryManager.recordEvent(eventName = "http.request", attributes = attributes)
}
```

**Clock.** Injected as `() -> Long` backed by `SystemClock.elapsedRealtime()` — so a sleeping device
still ages roots out, and the whole thing is unit-testable without a device. The same seam
`AppStartTracker` already uses (`core/startup/AppStartTracker.kt:27`).

**Signal.** A new `traceparent.outcome` value **`injected_expired`** distinguishes an aged-out root from
`injected_unattributed` (no action ever open). This is the falsification loop on the 2 s / 10 s numbers,
which were chosen by convention: a high expired ratio means the windows are truncating real flows,
which is otherwise invisible. Same argument that justifies `injected_unwired`.

**Defaults taken without further debate:** the windows are **internal constants**, not `TelemetryConfig`
surface, matching `AppStartTracker`'s 60 s cap and importance line (`AppStartTracker.kt:20-21,52-53`)
and their "promote only if a consumer needs it" upgrade path; and `current()` clears the carrier lazily
on discovering expiry, so expiry costs nothing extra.

### Δ8 — App launch is a trace root: opened at init, idle-exempt, handed off at first resume

**The gap.** `app.start` carries only `app.start.type` and `app.start.duration_ms`
(`TelemetryManager.kt:368-371`) — no trace attributes. It is emitted from `notifyActivityResumed()` at
`TelemetryActivityLifecycleObserver.kt:44`, which runs *before* `TraceManager.onNavigation()` at `:68`.
Requests fired during startup — token refresh, remote config, splash prefetch — happen before any
Activity resume, so there is no action for them to attribute to. (The reference app has a genuine one:
`SplashViewModel.kt:26`.)

**Decision — yes, a launch root, opened at init.**

**Open.** `TraceManager` mints the launch root inside `initialize()`, at the same site that already
wires the trace config (`TelemetryManager.kt:275-278`) and immediately alongside the `AppStartTracker`
construction (`:362-373`), reusing three signals already present there:

```kotlin
val memoryState = ActivityManager.RunningAppProcessInfo()
ActivityManager.getMyMemoryState(memoryState)
TraceManager.onLaunch(
    // True fork time, not init time. getStartElapsedRealtime() (API 24) shares the timebase with the
    // Δ7 lifetime clock, so no cross-clock arithmetic. Converted to epoch once, for span.start_time.
    startElapsedRealtimeMs = Process.getStartElapsedRealtime(),
    importanceAtInit = memoryState.importance     // same guard AppStartTracker applies (:38, :52)
)
```

A background-forked process (importance worse than `IMPORTANCE_VISIBLE` = 200) opens **nothing** —
identical to the guard that already drops the bogus `app.start` sample.

**Live — exempt from the 2 s idle window.** A cold start is routinely 1–5 s, and an idle-bounded launch
root would die before first resume whenever startup makes no network call; `app.start` would then not
even carry its own launch ids. Backstopped by `AppStartTracker`'s existing 60 s cap
(`AppStartTracker.kt:53`), which covers the late-init case where the first resume is never observed.
Startup requests are children.

**Handoff — the first resume ends the *exemption*, not the root.** The idle and cap clocks reset and
normal Δ7 rules take over, so the launch root simply becomes an ordinary root and decays naturally.
Wire it in `notifyActivityResumed()`, next to the existing cold-start end marker:

```kotlin
telemetryManager.notifyActivityResumed()      // existing (:44) — fires app.start
TraceManager.onFirstResume()                  // Δ8 — ends the exemption, resets both clocks
```

**Why not close the root at `app.start` emission.** Because `notifyActivityResumed()` runs at `:44` and
`onNavigation()` at `:68`, closing there leaves the first navigation finding a null carrier and minting
its own root — the launch trace would stop short of the first screen. Ending only the exemption means
`app.start`, the first navigation, and the first screen's initial data fetches all land inside the
launch trace: **fork → first screen showing data**, which is what users mean by "launch".

**Rejected alternatives:** closing after the first navigation is stamped (the first screen's data
fetches fire just after and land unattributed — usually the requests you most wanted); staying exempt
until the first tap (a user who opens the app and reads for two minutes accumulates all polling into
one enormous launch trace).

**`app.start` becomes a span-carrying event** (Δ10): it carries the launch root's `trace.id` /
`span.id` / `rum.action.id`, `trace.root_type = launch`, and `span.start_time` = the fork instant.

> **Do not conflate `app.start.duration_ms` with `span.duration_ms`.** `app.start.duration_ms` is the
> fork→first-resume cold-start measure and is unchanged. Roots carry **no** `span.duration_ms` (Δ10) —
> the launch root's envelope is derived from its children server-side, and it is normally wider than
> the cold start, because the first screen's fetches are inside it.

### Δ9 — Allowlist ergonomics: warn on empty, dot-anchored suffix entries

`traceHostAllowlist` defaults to empty, which means `traceparent` is injected on no host at all
(`TelemetryConfig.kt:20-22`, gate at `TraceManager.kt:99`). T2 #103 chose this deliberately — safety
over back-compat — and **that decision is not reopened**. Only ergonomics and discoverability change.

**Warn on empty.** A one-time `Log.w` at init, stating plainly that no `traceparent` will be injected:

```kotlin
// TelemetryManager.kt, alongside the existing allowlist wiring (:275-278)
if (TraceManager.traceHostAllowlist.isEmpty()) {
    Log.w("TelemetryManager",
        "traceHostAllowlist is empty — NO traceparent header will be injected on any request. " +
        "Enumerate your API hosts to enable distributed tracing (see README § Distributed Tracing).")
}
```

The signal already exists server-side — an empty allowlist makes every request report
`skipped_off_allowlist`, so a dashboard shows it immediately — but nothing told a developer **on-device**
that they had wired everything correctly and still propagated nothing. This catches the dark-on-upgrade
case during integration rather than in production.

**Suffix entries.** A leading-dot entry matches any host ending in it: `.example.com` matches
`api.example.com` and `api-v2.example.com`. This preserves T2's rejection of substring matching, which
was unsafe:

- `api.example.com.evil.com` ends in `.evil.com`, **not** `.example.com` → no match.
- `evil-example.com` does not end in `.example.com` → no match.

Dot-anchoring is a true suffix test, not a `contains`. The `isTelemetryRequest` heuristic that once used
`url.contains(...)` is **not** prior art here — it was itself a bug (`TelemetryInterceptor.kt:68-70`,
fixed in 2.2.2).

**Gate, replacing the exact-match line at `TraceManager.kt:99`:**

```kotlin
private fun isAllowed(host: String): Boolean {
    val h = host.lowercase()
    return traceHostAllowlist.any { entry ->
        if (entry.startsWith(".")) h.endsWith(entry) else h == entry
    }
}
```

**New validation guard.** The existing `init{}` block (`TelemetryConfig.kt:32-38`) rejects `/` and `:`,
so a leading-dot entry already passes. It needs one addition — a suffix entry must carry at least two
further labels, so `.com` (which would match every `.com` host) fails fast the way a scheme or port does
today:

```kotlin
traceHostAllowlist.forEach {
    val h = it.trim().lowercase()
    require(h.isNotBlank()) { "traceHostAllowlist entry must not be blank" }
    require(!h.contains("/") && !h.contains(":")) {
        "traceHostAllowlist entry must be a bare host (no scheme, path, or port), got '$it'"
    }
    // Δ9 — a suffix entry needs at least two labels after the dot: '.example.com' ok, '.com' is not.
    require(!h.startsWith(".") || h.drop(1).count { c -> c == '.' } >= 1) {
        "traceHostAllowlist suffix entry must have at least two labels, got '$it'"
    }
}
```

**Also update the comment at `TelemetryConfig.kt:20-22`** — "exact-match case-insensitive" stops being
the whole story.

> **Untested by any real integration.** The reference app's API shares the collector's host
> (`telemetry.ncgafrica.com`, differing only by path), so it sidesteps the allowlist question entirely.
> #128 established that no other integrating app exists. Δ9 is therefore reasoned, not observed.

### Δ10 — Explicit span timing: `span.start_time` + `span.duration_ms`

**The hazard.** `http.request` is emitted inside the interceptor's `finally` block
(`TelemetryInterceptor.kt:41-60`), *after* the response — so its event timestamp is the span's **end**
time, while `ui.interaction`'s timestamp is its **start**. A monitoring team that reasonably assumes
"event timestamp = span start" draws every network span shifted right by its own duration.
`http.duration_ms` makes the start recoverable, but only if you know the convention, and nothing on the
wire says so.

**Decision — put both on the wire.** `span.start_time` and `span.duration_ms` on every span-carrying
event: `ui.interaction`, `navigation`, `http.request`, `app.start`.

| Attribute | Type | Notes |
|---|---|---|
| `span.start_time` | String | ISO-8601 millisecond true-UTC, via `TelemetryTime.isoOf(epochMs)` (`TelemetryTime.kt:29`) — the format `EventPayloadValidator.kt:17` already enforces |
| `span.duration_ms` | Number (ms) | **children only**; absent on roots |

An absolute `span.start_time` removes the convention entirely — no backend has to infer whether a given
timestamp means start or end, so the shifted-network-span bug cannot ship.

**Roots carry `start_time` only.** The backend derives the root envelope from its children, which it can
do because `rum.action.id` gives it every child of the action. **#129 pinned the shape:** derivation is a
**query-time view**, explicitly not processor state —

```sql
-- root envelope, per action
MAX(child.span_start_time + child.span_duration_ms) - root.span_start_time  GROUP BY rum_action_id
```

— because `OfflineBatchStorage` persists up to 200 envelopes and replays them hours or days late, so any
write-time envelope (a processor holding open roots and closing them as children land) is **wrong by
construction**: the replayed batch arrives long after any in-memory root was evicted, and the envelope
silently under-reports. Query-time derivation is immune, because the aggregate recomputes over whatever
rows exist. That confirmation **retires #125's root-close fallback for good**, saving one event per user
action.

Two properties the contract states rather than lets anyone discover:

- **A childless root has NULL duration.** Accepted.
- **An envelope is never final** — it widens when a late batch lands, so any dashboard caching it needs a
  recompute window rather than treating first-seen as complete.

**Where each start time comes from — this is the part that is easy to get wrong:**

| Event | `span.start_time` source |
|---|---|
| `http.request` | a real epoch instant captured at request **start**, next to `System.nanoTime()` (`TelemetryInterceptor.kt:35`) |
| `ui.interaction` | the root's **mint** time — `ACTION_UP` / `onSingleTapUp`, **not** the `onSingleTapConfirmed` emit time (Δ12) |
| `navigation` | the moment `onNavigation()` is called |
| `app.start` | the process **fork** instant, epoch-converted once at init (Δ8) |

`System.nanoTime()` is monotonic and **not a wall clock** — `span.start_time` must be captured
separately as a real epoch timestamp, never back-computed from the nanoTime delta. `span.duration_ms`
continues to come from the nanoTime delta (`TelemetryInterceptor.kt:42-43`), which is the correct clock
for a duration.

**The `ui.interaction` case is load-bearing.** Under Δ12 the root is minted at `ACTION_UP` and the event
is emitted ~300 ms later at tap-confirm. Stamping the emit time would start the root ~300 ms *after* the
request it parents — drawing children before their parent begins.

### Δ11 — Terminal events carry the last action, by annotation only

**The gap.** `app.crash` and `app.hang` are emitted at `TelemetryManager.kt:312-313`, `app.exit` at
`:422`. None carries trace attributes, so none can be joined to the user action that caused it. The three
paths differ by which thread can read the carrier:

- **`app.crash`** — the uncaught handler runs on the crashing thread. A main-thread crash reads the live
  root fine; a **background-thread** crash reads an empty `ThreadLocal` even though the action sits on main.
- **`app.hang`** — the ANR watchdog detects from its **own** thread (`CrashReportingService.recordHang`,
  `:178-188`) and can never read the main thread's `ThreadLocal`. This path gets nothing without a
  cross-thread reference — and hangs are where "what was the user doing" matters most.
- **`app.exit`** — harvested from `ApplicationExitInfo` on the *next* process launch, so the previous
  process's context is gone.

**Decision — a process-global last-action reference, annotation-only.**

```kotlin
// core/trace/TraceManager.kt
@Volatile private var lastRoot: TraceContext? = null   // written when ANY root opens

/**
 * Δ11 — read ONLY by the crash and hang emitters. Never a parenting source: onNetworkCall() and
 * onNavigation() must not call this. Returns empty when the root has aged out under Δ7.
 */
fun annotateTerminal(): Map<String, Any> {
    val r = lastRoot?.takeIf { it.isLive(clock()) } ?: return emptyMap()
    return mapOf("trace.id" to r.traceId, "rum.action.id" to r.spanId, "trace.root_type" to r.rootType)
}
```

**This does not undo T1 #102 — and the spec states that boundary hard, because the distinction is the
entire justification.** T1 killed the process-global `AtomicReference` as a **propagation** mechanism: it
leaked across threads and actions and produced *wrong span parenting*. This reference is:

- **never read by `onNetworkCall()` or `onNavigation()`** — those read the Δ1 carrier and the Δ6 tag, and
  nothing else;
- **never a parent** — it mints no span and sets no `parent.span.id`;
- **read at exactly two call sites**, both terminal emitters, both of which produce an event with no
  duration and no children.

It annotates; it does not propagate. If a future change reads `lastRoot` from a parenting path, that
change re-opens T1 #102 and must be rejected on those grounds. A `DeletedSymbolGuardTest`-style
compile-or-source guard is the cheapest way to keep it honest (see Test plan §12).

**Events carry `trace.id`, `rum.action.id` and `trace.root_type` as join keys** — not a new child span,
since a crash has no duration and no `traceparent` is ever emitted for it.

**Δ7's expiry applies.** If the root has aged out under the 2 s / 10 s rules, the crash stamps **nothing**
rather than claiming an action the user finished five minutes ago. Δ7 is what makes "which action was
live at crash time" a well-defined question at all.

**`app.exit` stays unattributed.** Attributing a harvested `ApplicationExitInfo` requires persisting the
active root across process death, and action lifetime is frequent enough that a disk write tied to it
needs its own design. Deliberate boundary, not an oversight — recorded as fog on map #120.

### Δ12 — Compose action identity: semantics naming, `trackTap` override, and the mint/emit split

Three defects, one seam. Δ12 folds **G6 #127** (the double-count) and **G6b #131** (naming + a live
ordering defect found while specifying it).

#### 12a — The double-count: the manual helper renames the root, it does not emit a rival event

> **Symbol correction.** #127 refers to `trackComposeInteraction()` at `compose/EdgeTelemetryCompose.kt:197`.
> The actual public symbol is **`trackUserInteraction(action, target, attributes)`**, declared at
> `compose/EdgeTelemetryCompose.kt:175`; `:197` is the `recordEvent("user.interaction", …)` line inside it.
> The decision is unchanged; the spec names the real symbol so execution edits the right function.

By the time an app calls `trackUserInteraction()` from its own tap handler, the window callback has
already fired and emitted `ui.interaction` with `ui.target = "compose_surface"` plus a trace root. A
Compose app adopting the helper therefore gets **two events for one tap**: the coordinate-derived one
carrying the trace but no usable name, and `user.interaction` carrying the real name but no trace. Split
identity, double-counting, across two differently-named event schemas.

**Decision:** `trackUserInteraction()` stops emitting `user.interaction`
(`EdgeTelemetryCompose.kt:195-198`). It **names the trace root the window callback has already opened**:

```kotlin
fun trackUserInteraction(action: String, target: String, attributes: Map<String, String>? = null) {
    EdgeTelemetry.getInstance().addBreadcrumb(...)      // unchanged
    TraceManager.nameCurrentRoot(target)                // Δ12a — rename the open root; no rival event
}
```

This removes the double-count and the split identity, and collapses the two interaction schemas back to
one. Ordering ruled out the obvious alternative — `dispatchTouchEvent` runs before Compose's gesture
handling (`UserInteractionTracker.kt:116-123`), so the tracker cannot read a name Compose has not
produced yet. **12c is what makes the root exist by the time `onClick` runs.**

> **Wire-breaking for anyone consuming `user.interaction`.** #128 verified that no integrating app calls
> this helper, and `ncg_voting_android` is the only integration, so the blast radius is zero today. Call
> it out in the release notes regardless.

#### 12b — Naming: semantics-first, Role-gated, `trackTap` as the override

`UserInteractionTracker.kt:159-160` maps the Compose host view to the literal `compose_surface`. #128
established that the reference app is **single-Activity, 100 % Compose, zero XML layouts** — so *every*
trace root in the only app the SDK instruments carries the identical name. Action identity there is not
coarse, it is a **single constant**, which would make launch-to-request attribution unreadable even once
Δ6 and Δ7 land.

**#127 scored the semantics-tree option dead because the app uses no `testTag`. That check was too
narrow** — `testTag` is one property among several, and not the one carrying names in this app. Measured
against a fresh clone (70 `.kt`, 0 XML layouts):

| Signal | Count |
|---|---|
| `testTag` | **0** |
| `onClick` sites | **35** |
| `Modifier.clickable` | 2 |
| `contentDescription` | 5 (all on `Icon`, none `null`) |

All 35 `onClick` sites are Material `Button` / `TextButton` / `IconButton`, and Compose merges the child
`Text` into the clickable's semantics node. So a coordinate hit-test against the **merged** semantics tree
recovers `"Vote"`, `"Confirm vote"`, `"Sign in"`, `"Create account"`, `"Send reset link"`, `"Save"`,
`"Retry"` — real action names, on **zero** consumer adoption. Every one is a string literal; no
interpolation at any button site.

**Decision: semantics-first, `trackTap` as override.** Registry-only would ship a feature that names
nothing until someone performs 35 edits, while the whole premise is that action identity is currently one
constant.

**Name chain** — resolve the innermost merged semantics node containing the tap point, then:

| # | Source | `ui.name_source` |
|---|---|---|
| 1 | `Modifier.trackTap("…")` — explicit, always wins | `track_tap` |
| 2 | `SemanticsProperties.TestTag` — free if present | `test_tag` |
| 3 | **only if `Role` ∈ {Button, Tab, Checkbox, RadioButton, Switch}:** `ContentDescription`, then merged `Text` | `content_description` / `text` |
| 4 | nothing survives | `none` |

**Normalization.** Join merged text with `_`, trim, lowercase, non-alphanumeric → `_`, collapse runs, cap
**64 chars**. `"Send reset link"` → `send_reset_link`. This aligns the Compose path with the View path's
`getResourceEntryName` shape (`btn_login`, `UserInteractionTracker.kt:151`), so the two produce comparable
cardinality.

**The Role gate is the privacy control**, and the evidence shows it is an exact proxy rather than a
heuristic:

- Material components set a `Role` **and** carry authored, literal labels.
- A bare `Modifier.clickable` wrapping rendered data does **not** set a Role.

The reference app's entire PII surface is one node — `VoteHomeScreen.kt:140`, an `NcgCard` with
`.clickable { onCandidate(candidate) }` whose merged text is **a person's name**. The gate names 35/35
buttons and declines 1/1 PII node.

*Rejected:* a regex/PII scrubber — it would have to guess whether `"John Kamau"` is a label. *Rejected:*
reading merged `Text` regardless of Role — names the candidate card automatically at the cost of shipping
candidate names to the backend. The gate leaves the candidate card — arguably the most interesting tap in
the app — unnamed. That is correct, and it is exactly what earns `trackTap` its place.

**`Modifier.trackTap` is a semantics property, not a registry.** #131's items on registry lifetime,
non-retention and innermost-bounds lookup **dissolve** — `trackTap` writes into the tree already being read:

```kotlin
// compose/EdgeTelemetryTrackTap.kt
internal val EdgeActionKey = SemanticsPropertyKey<String>("EdgeTelemetryAction")
var SemanticsPropertyReceiver.edgeAction by EdgeActionKey

fun Modifier.trackTap(name: String): Modifier = semantics { edgeAction = name }
```

```kotlin
NcgCard(modifier = Modifier.fillMaxWidth().clickable { onCandidate(candidate) }.trackTap("candidate_card"))
```

Compose's semantics tree solves every hard part: bounds are maintained by the layout system, nodes leave
the tree when they leave composition (**no retention, nothing to unregister**), and innermost-hit
resolution is tree descent. `SemanticsPropertyKey`'s default merge policy propagates a child's value into
a merged parent, and semantics modifiers combine on the same layout node — so `.clickable{}.trackTap("x")`
and `.trackTap("x").clickable{}` are identical, retiring #127's ordering concern.

**Name source: explicit string only, no inference** — inference is what the chain already does.
**A custom `SemanticsPropertyKey`, not `testTag`** — reusing `testTag` is one line shorter but writes into
the app's test-identification namespace, colliding with their tests and with `testTagsAsResourceId`. A
private key cannot collide.

**No coordinate registry is built.** One would only be needed for a `SubcomposeLayout` edge case or a
non-Compose overlay, and it would reintroduce the whole lifetime problem to get there.

**What an unnamed tap reports:**

| Tap lands on | `ui.target` | `ui.name_source` |
|---|---|---|
| a semantics node, nothing survives the Role gate (the candidate card, pre-`trackTap`) | **`unnamed`** — actionable, someone should add `trackTap` | `none` |
| no semantics node — background, padding, a `Spacer` | **`compose_surface`** — noise, nothing to fix | `none` |

Collapsing both into one value recreates the original disease in miniature: `40% unnamed` with no way to
tell missing instrumentation from people tapping whitespace. Keeping them apart is free — it is the
branch `resolveTargetName` already has (`:149-160`).

**Plus `ui.name_source` ∈ `{track_tap, test_tag, content_description, text, resource_id, class_name, none}`,
always present.** #128 established that the v2 trace contract has been **write-only since 2.2.0** — no
store persists `traceparent.outcome`, so nobody could measure whether v2 worked. The identical failure was
available here: ship semantics naming and you cannot tell whether names come from real labels or whether
the Role gate is silently eating half the app. One low-cardinality enum makes the `trackTap` adoption ask
evidence-driven instead of a guess. `resource_id` / `class_name` cover the View path, so Compose-vs-View
slices without a second attribute.

*Rejected:* qualifying the sentinel by screen (`unnamed@vote_home`) — `ui.screen` is already on the event
(`UserInteractionTracker.kt:75`); group by `(screen, target)` rather than have `target` secretly contain
the screen.

**Reaching the semantics tree — the accessor, pinned.**

`compose-ui` resolves to **1.7.8** on this project's `releaseCompileClasspath` — the BOM
(`libs.versions.toml:13`, `2024.04.01`) pins 1.6.6, and `navigation-compose 2.9.3`
(`libs.versions.toml:17,38`, an `api` dependency at `build.gradle.kts:103`) upgrades it. Verified with
`./gradlew :telemetry_library:dependencies --configuration releaseCompileClasspath`. #131 deliberately
left the exact symbol to this spec; against 1.7.8 it is:

```
androidx.compose.ui.platform.AndroidComposeView
  implements androidx.compose.ui.platform.ViewRootForTest       (extends androidx.compose.ui.node.RootForTest)

androidx.compose.ui.node.RootForTest
  SemanticsOwner getSemanticsOwner()                             ← the accessor

androidx.compose.ui.semantics.SemanticsOwner
  SemanticsNode getRootSemanticsNode()                           ← MERGED tree (what we want)
  SemanticsNode getUnmergedRootSemanticsNode()                   ← not this

androidx.compose.ui.semantics.SemanticsNode
  Rect getBoundsInWindow()                                       ← window-relative, see below
  SemanticsConfiguration getConfig()
  List<SemanticsNode> getChildren()

androidx.compose.ui.semantics.SemanticsConfigurationKt
  static <T> T getOrNull(SemanticsConfiguration, SemanticsPropertyKey<T>)

androidx.compose.ui.semantics.SemanticsProperties
  SemanticsPropertyKey<String>                    getTestTag()
  SemanticsPropertyKey<List<String>>              getContentDescription()
  SemanticsPropertyKey<List<AnnotatedString>>     getText()
  SemanticsPropertyKey<Role>                      getRole()
```

Three consequences to carry into execution:

1. **Cast to `RootForTest`, not `ViewRootForTest`.** We already hold the `View` from the existing hit test
   (`UserInteractionTracker.kt:64`), so `ViewRootForTest.getView()` buys nothing; `RootForTest` is the
   narrower surface and the one that actually declares `getSemanticsOwner()`.
2. **`boundsInWindow` is in window-relative pixels — the exact space `handleGesture` already works in**
   (`UserInteractionTracker.kt:62-64`). No coordinate conversion, no screen-vs-window skew.
3. **`Role` is an inline value class over `Int`** (`Role.Companion.getButton-o7Vup1c()` in the bytecode).
   Compare it from **Kotlin** (`role == Role.Button`); the mangled JVM name is not a stable Java surface.

`View.getAccessibilityNodeProvider()` was the alternative — fully public, zero Compose coupling — and is
**rejected**: it is the a11y projection, so `Role.Button` flattens to a `className` string, the custom
`SemanticsPropertyKey` does not survive at all (killing `trackTap`), and Compose does not reliably
populate it with no a11y service attached. It cannot carry the two things the chain and the gate rest on.

**Failure discipline** — the file's existing stance, *"never let telemetry break input dispatch"*
(`UserInteractionTracker.kt:117-121`):

- Resolution runs inside `try/catch`.
- Any failure degrades to `ui.target = "compose_surface"`, `ui.name_source = "none"` — exactly today's
  behaviour, never a crash.
- **One-time warning on first failure**, so an androidx break is visible rather than silent (the
  `injected_unwired` lesson from Δ6).
- **No new dependency** — `compose-ui` already arrives via the BOM + `navigation-compose` as `api`
  (`build.gradle.kts:92-106`).

#### 12c — The ordering defect: roots mint ~300 ms too late

Found while specifying the 12a/12b seam; neither #127 nor #131 originally recorded it.

`UserInteractionTracker.kt:116-123` feeds the detector **before** delegating:

```kotlin
override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    try { detector.onTouchEvent(event) } catch (_: Exception) { }
    return delegate.dispatchTouchEvent(event)   // Compose sees the event only after the detector
}
```

but the root mints inside `handleGesture` (`:79`), called from **`onSingleTapConfirmed`** (`:91`) — which
`GestureDetector` withholds for the ~300 ms double-tap timeout. Compose's `clickable` fires on
`ACTION_UP`. So on every Compose tap today:

1. `ACTION_UP` → Compose `onClick` runs → the app fires its Retrofit call → **`newCall()` stamps whatever
   root is current: the *previous* action's, or none**
2. ~300 ms later → `onSingleTapConfirmed` → `TraceManager.onInteraction()` opens the "vote" root, which
   the request it caused has already missed

This is not a naming problem. It is **launch-to-request attribution misattributing every Compose tap** —
this map's destination. It stayed invisible because every root was named `compose_surface`, so
misattribution and correct attribution looked identical.

**Decision — split minting from emitting:**

| Moment | What happens |
|---|---|
| `ACTION_UP` → **`onSingleTapUp`** *(currently unimplemented on `GestureListener`, `:85-109`)* | run the secure-surface and password suppression checks (`:60,65`), hit-test, resolve the name via the 12b chain, **mint + name the root**. Runs before the delegate dispatches to Compose, so it beats `onClick` |
| ~300 ms later → `onSingleTapConfirmed` (`:91`) | emit the `ui.interaction` event with the confirmed type, attached to the root already open, carrying the name resolved at mint |

`onLongPress` (`:96`) and `onFling` (`:100`) keep minting where they are — `GestureDetector` never fires
`onSingleTapUp` for a scroll, fling or long press, so this adds **no** extra roots.

**Suppression happens at mint, not at emit.** A secure window or a password field must open no root at
all, or a suppressed tap would still parent the requests that follow it.

**The 12a seam then resolves cleanly.** Automatic naming sets the root name at `ACTION_UP`;
`trackUserInteraction()`, firing later inside `onClick`, renames that same open root exactly as #127
specified. Manual wins, consistent with `trackTap` outranking the chain — one root, one name, no rivalry.

**Known wrinkle:** a genuine double-tap fires `onSingleTapUp` twice with no `onSingleTapConfirmed`, so two
roots open and neither carries an event. Δ7's 2 s idle reaps them. Not worth code.

**The View path inherits the same fix, and does not regress:**

- Semantics lookup is gated on the hit view being `AndroidComposeView` — the branch `resolveTargetName`
  already has (`:159-160`). XML apps keep `getResourceEntryName` (`:151`) untouched, reporting
  `ui.name_source = resource_id` or `class_name`.
- A `ComposeView` inside an XML layout works by construction — `hitTest` (`:130-141`) descends to the
  `AndroidComposeView` child and the Compose branch takes over there.
- The mint/emit split **helps** the View path: `View.OnClickListener` also fires on `ACTION_UP`, so XML
  apps have had the same 300 ms misattribution all along.

### Δ13 — Wire contract: the six-value outcome enum and `trace.root_type`

#129 grounded the handover and found the premise was too narrow. Two facts change what this delta must say:

1. **The backend consumes a 38-key allowlist and drops the other 138 by omission**
   (`EDGETELEMETRYPROCESSORGO/internal/telemetry/extract.go`); there is no JSONB bag in any of its seven
   tables. `traceparent.outcome` appears in **no backend repo**. So **the v2 trace contract has been
   write-only since 2.2.0** — every attribute Δ5 defined has been emitted and stored nowhere.
2. **The RUM stores are being authored right now**, so v3 supplies a contract to a schema still being
   written rather than asking a settled backend for a migration. Advantage and deadline both.

The agreed model is **bag-first plus additive columns, never a rename**, with a three-clause promotion rule
(join key / bounded dimension / aggregated measure). The property that makes it the right answer:
**bag-first makes promotion retroactive by backfill** — the exact property whose absence makes the
`injected_unattributed` ratio unanswerable today. Full rationale, the four-way cross-SDK frame/memory diff,
and the table-by-table asks live on #129; the 169-key inventory is split out as
[#132](https://github.com/NCG-Africa/edge_telemetry_android/issues/132). **This spec stays trace-only.**

**`traceparent.outcome` — six values.** `injected_unwired` (Δ6) and `injected_expired` (Δ7) join the four
from v2. Both exist to make a failure mode measurable rather than silent.

| # | Value | Meaning | New in v3 |
|---|---|---|---|
| 1 | `skipped_off_allowlist` | host not allowlisted; ids recorded locally, **no header sent** | |
| 2 | `adopted` | valid inbound `traceparent` mirrored; header + action root untouched | |
| 3 | `injected_attributed` | header injected; call belongs to a known, live action | |
| 4 | `injected_unwired` | header injected; **no request tag** — `instrument()` was never wired, or the app is on the deprecated `createNetworkInterceptor()` path with no active carrier | ✅ |
| 5 | `injected_expired` | header injected; an action existed but aged out under Δ7's 2 s / 10 s windows | ✅ |
| 6 | `injected_unattributed` | header injected; tag present and genuinely no action | |

**Precedence:** `skipped_off_allowlist` > `adopted` > `injected_attributed` > `injected_unwired` >
`injected_expired` > `injected_unattributed`.

Values 3–6 are mutually exclusive branches that cannot collide in practice, so the ordering among them is
**documentation for consumers**, not a runtime tiebreak. It matters for one reason: `injected_unwired`
outranks the last two because with no tag present we cannot know whether a root existed — a fact the other
two assert. Values 1 and 2 short-circuit genuinely, exactly as in v2.

**Not-traced requests** — the SDK's own telemetry POSTs (`TelemetryInterceptor.kt:23,72-76`) and the
unsampled-context case (moot at rate 1.0) — carry **no** `traceparent.outcome` attribute. **Absence = not
traced**; there is no seventh member.

**`trace.root_type` — added, denormalized across all spans.**

| Value | Minted by |
|---|---|
| `launch` | Δ8, in `initialize()` |
| `interaction` | Δ12c, at `onSingleTapUp` / `onLongPress` / `onFling` |
| `navigation` | `onNavigation()` when no root is live (`TraceManager.kt:87`) |
| `request` | Δ4's parentless request-as-root (`TraceManager.kt:120-124`) |

*Added*, because the backend cannot currently distinguish a cold-start trace from a tap trace, and "are
startup requests slower than in-session requests" is the first question anyone asks of launch-to-request
tracing — the entire point of this map. Purely additive to a store with no consumers: cheap now, expensive
to retrofit. Confirmed `root_type` appears nowhere in the SDK today, so nothing migrates.

*Denormalized* onto every child span, not root-only, because root-only forces every interesting query to
join children back to their root on `rum_action_id` purely to filter by root type — and that **is** the hot
query, the one the Δ10 envelope view already groups by. `p95 of http.request where root_type = launch`
becomes a single-table scan, at the cost of one short string on a payload already carrying ~30 attributes.

`request` is mildly redundant with `traceparent.outcome = injected_unattributed`. **Kept anyway** for
uniformity — the field is never absent and needs no special case — with the contract stating
**`traceparent.outcome` is authoritative** if the two ever disagree.

**Two attributes from Δ12 also enter the contract:** `ui.target` gains `unnamed` (distinct from
`compose_surface`) and `ui.name_source` is new. Both are bounded-cardinality dimensions under #129's
promotion rule and were carried into the schema being authored.

---

## The outcome ladder (as evaluated per request)

```
off-allowlist?  --yes-->  skipped_off_allowlist       (Δ2/Δ9 — outermost; header suppressed, ids stamped)
     | no  (exact match, or dot-anchored suffix)
     v
inbound traceparent present & VALID? --yes--> adopted (Δ3 — mirror foreign ids, header + root untouched)
     | no (absent OR malformed→overwrite)
     v
Δ6 request tag ...
   ABSENT  + carrier null      --> injected_unwired         (instrument() not wired; mint parentless, inject)
   ABSENT  + carrier non-null  --> injected_attributed      (deprecated path, sync main-thread call)
   PRESENT + ctx == null       --> injected_unattributed    (Δ4 — mint parentless, inject)
   PRESENT + ctx EXPIRED       --> injected_expired         (Δ7 — mint parentless, inject)
   PRESENT + ctx !sampled      --> (no trace, no header)    (null Decision; moot at rate 1.0)
   PRESENT + ctx LIVE          --> injected_attributed      (Δ1 — child of the action root, inject)
```

Every `injected_*` branch also **extends the root** on completion (Δ7) where one exists, from the
interceptor's `finally` block.

## Lifecycle model (v3 — the carrier, the tag, and the clock)

```kotlin
// core/trace/TraceManager.kt — v3. Carrier (Δ1) + request tag (Δ6) + lifetime (Δ7) + last-action (Δ11).

class TraceContext(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean,
    val rootType: String,                 // Δ13 — launch | interaction | navigation | request
    val startTimeEpochMs: Long,           // Δ10 — span.start_time source; MINT time for a tap root
    val openedAt: Long,                   // Δ7 — elapsedRealtime at open; the 10s cap clock
) {
    val lastActivity = AtomicLong(openedAt)          // Δ7 — written from the OkHttp pool thread
    val idleExempt = AtomicBoolean(false)            // Δ8 — launch root only, until first resume
    @Volatile var name: String? = null               // Δ12 — set at mint, renamed by 12a

    fun isLive(now: Long): Boolean =
        (idleExempt.get() || now - lastActivity.get() <= IDLE_WINDOW_MS) && now - openedAt <= AGE_CAP_MS

    fun touch(now: Long) { lastActivity.set(now) }   // Δ7 extension — child start OR request completion
}

object TraceManager {
    private const val IDLE_WINDOW_MS = 2_000L
    private const val AGE_CAP_MS = 10_000L

    private val carrier = ThreadLocal<TraceContext?>()          // Δ1, unchanged (TraceManager.kt:47)
    @Volatile private var lastRoot: TraceContext? = null        // Δ11 — annotation only, NEVER parents
    /** Δ7 seam — injected like AppStartTracker's (AppStartTracker.kt:27); real clock in production. */
    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Δ7 — expiry is lazy: discovering it clears the slot, so expiry costs nothing extra. */
    fun current(): TraceContext? {
        val c = carrier.get() ?: return null
        if (c.isLive(clock())) return c
        carrier.set(null)
        return null
    }

    private fun open(rootType: String, startEpochMs: Long, openedAt: Long): TraceContext { /* mint, set
        carrier, set lastRoot */ }

    /** Δ8 — launch root, gated by the same importance line AppStartTracker uses (:38, :52). */
    fun onLaunch(startElapsedRealtimeMs: Long, importanceAtInit: Int) { /* idleExempt = true */ }
    /** Δ8 — first Activity resume ends the EXEMPTION, not the root; both clocks reset. */
    fun onFirstResume() { /* idleExempt = false; touch(clock()); reset openedAt */ }

    /** Δ12c — mint at onSingleTapUp (ACTION_UP), named from the 12b chain. Returns the root. */
    fun onInteractionStart(name: String?): TraceContext?
    /** Δ12c — emit-time attrs for the ui.interaction event; does NOT mint. */
    fun onInteractionEmit(): Map<String, Any>?
    /** Δ12a — trackUserInteraction() renames the open root; no rival event. */
    fun nameCurrentRoot(name: String)

    /** Child of the live root, else this nav IS the root (rootType = navigation). Extends on start. */
    fun onNavigation(): Map<String, Any>?

    /**
     * Δ6 — the interceptor hands over three facts now: host, any inbound traceparent, and the request
     * tag. `tag == null` means the Call.Factory was never wired. Returns the header action, the attrs,
     * and the root so the interceptor's finally can extend it (Δ7).
     */
    fun onNetworkCall(host: String, inboundTraceparent: String?, tag: TraceTag?): Decision?

    /** Δ11 — crash/hang emitters ONLY. Never called from onNetworkCall/onNavigation. */
    fun annotateTerminal(): Map<String, Any>

    /** ProcessLifecycleOwner onStop (TelemetryManager.kt:548). Main-thread slot only, as in v2. */
    fun onBackground() { carrier.set(null) }
}

data class Decision(val newHeader: String?, val attrs: Map<String, Any>, val root: TraceContext?)
```

*(Illustrative. `TraceContext` stops being a `data class` — it now carries mutable atomics, so generated
`equals`/`copy` would be misleading.)*

Resulting tree for a cold launch that navigates, fetches, then takes a tap that fires a call:

```
app.start       span=L rum.action.id=L root_type=launch       start=<fork>            trace=T1   (root, no parent)
  navigation    span=M parent=L rum.action.id=L root_type=launch                      trace=T1
  http.request  span=N parent=L rum.action.id=L root_type=launch   00-T1-N-01         trace=T1   outcome=injected_attributed
--- 2s idle: T1's root expires ---
ui.interaction  span=A rum.action.id=A root_type=interaction  start=<ACTION_UP>       trace=T2   ui.target=send_reset_link
                                                                                                 ui.name_source=text
  http.request  span=C parent=A rum.action.id=A root_type=interaction  00-T2-C-01     trace=T2   outcome=injected_attributed
--- app crashes on a background thread ---
app.crash       trace.id=T2 rum.action.id=A root_type=interaction   (Δ11 — annotation, no span)
```

## Wire contract summary (v3)

| Attribute | On | Notes |
|---|---|---|
| `trace.id` | every span-carrying event, `app.crash`, `app.hang` | 32 hex |
| `span.id` | every span-carrying event | 16 hex; adopted ⇒ mirrored foreign parent-id |
| `parent.span.id` | children only | omitted on roots, adopted, unattributed, unwired, expired |
| `rum.action.id` | action + child events, `app.crash`, `app.hang` | the root's `span.id`; the stable join key |
| `traceparent.outcome` | `http.request` only | six values (Δ13); **absent = not traced** |
| `trace.root_type` | **new** — every span-carrying event + `app.crash`/`app.hang` | `launch`/`interaction`/`navigation`/`request`, denormalized |
| `span.start_time` | **new** — every span-carrying event | ISO-8601 ms true-UTC string |
| `span.duration_ms` | **new** — children only | number of ms; roots derive server-side |
| `ui.target` | `ui.interaction` | value set widens: a resolved name, `unnamed`, or `compose_surface` |
| `ui.name_source` | **new** — `ui.interaction` | `track_tap`/`test_tag`/`content_description`/`text`/`resource_id`/`class_name`/`none` |
| `session.id` | every event | already auto-enriched |

**Invariant carried from v2:** whenever a `traceparent` is emitted, the recorded `span.id` is identical to
the header's span-id field. `skipped_off_allowlist` stamps ids locally with **no** wire header. This is
what makes "missing DB join ⇒ header stripped in transit" computable platform-side.

## README config section (destination deliverable)

The README already documents v2 (`README.md:119-205, 305, 342`). v3 changes it in four places:

1. **§3 "Add the OkHttp interceptor" → "Instrument your OkHttpClient"** (`README.md:119-123`):

   ```kotlin
   val client = OkHttpClient.Builder().build()

   Retrofit.Builder()
       .baseUrl(BASE_URL)
       .callFactory(TelemetryManager.instrument(client))   // wires the interceptor AND trace capture
       .build()
   ```

   > **Upgrading from 2.2.x:** `TelemetryManager.createNetworkInterceptor()` is deprecated. It still
   > records `http.request` and still injects `traceparent`, but it **cannot attribute asynchronous
   > requests** (Retrofit `suspend`, `enqueue`, callbacks) to the user action that caused them — OkHttp
   > runs interceptors on its dispatcher pool. Those calls report `traceparent.outcome =
   > injected_unwired`. Switch `.client(client)` to `.callFactory(TelemetryManager.instrument(client))`.
   > Calls made after an explicit `withContext(Dispatchers.IO)` hop still need
   > `TelemetryManager.traceElement()`.

2. **`traceHostAllowlist`** (`README.md:167-187`) — the dark-on-upgrade warning stands. Add: the SDK now
   logs a one-time warning at init when the list is empty, and entries may be **dot-anchored suffixes**
   (`.example.com` matches `api.example.com` and `api-v2.example.com`; it does **not** match
   `api.example.com.evil.com` or `evil-example.com`). A suffix entry needs at least two labels — `.com`
   throws at `initialize()`.

3. **`traceparent.outcome`** (`README.md:189-200`) — replace the four-row table with the six-value table
   from Δ13, and state that absence means the request was not traced.

4. **New rows in the data-consumer table:** `trace.root_type`, `span.start_time`, `span.duration_ms`,
   `ui.name_source`, plus the widened `ui.target` value set. Note that **roots carry no
   `span.duration_ms`** — the envelope is derived at query time — and that an envelope is never final,
   because offline batches replay late.

Also worth a short "Naming Compose taps" subsection: taps on Material components are named automatically
from their labels; taps on a bare `Modifier.clickable` report `ui.target = unnamed` and want
`Modifier.trackTap("…")`; taps on background report `compose_surface`.

## Execution notes (downstream, per file)

| File | Change |
|---|---|
| `core/trace/TraceManager.kt` | Δ6/Δ7/Δ8/Δ11/Δ12/Δ13 — `TraceContext` gains `rootType`, `startTimeEpochMs`, `openedAt`, `lastActivity`, `idleExempt`, `name`; injected `clock`; lazy expiry in `current()`; `onLaunch`/`onFirstResume`; `onInteractionStart`/`onInteractionEmit`/`nameCurrentRoot`; `onNetworkCall(host, inbound, tag)`; `annotateTerminal()`; `lastRoot`; `isAllowed()` suffix match; extend `OUTCOMES` to six |
| `core/trace/TraceTag.kt` | **new** — `internal class TraceTag(val ctx: TraceContext?)`; a wrapper because OkHttp's `tag(Class, T)` cannot store a bare null |
| `core/trace/TracingCallFactory.kt` | **new** — `Call.Factory` stamping `TraceTag` at `newCall()` |
| `core/TelemetryInterceptor.kt` | read `request.tag(TraceTag::class.java)` and pass it to `onNetworkCall` (`:30`); capture an epoch `span.start_time` next to `System.nanoTime()` (`:35`); add `span.start_time`/`span.duration_ms` to the attrs (`:46-57`); extend the root in `finally` (`:41-60`) |
| `core/TelemetryManager.kt` | add `instrument(client)`; `@Deprecated` on `createNetworkInterceptor()` (`:217`); empty-allowlist warning next to the allowlist wiring (`:275-278`); `TraceManager.onLaunch(...)` beside the `AppStartTracker` build (`:362-373`); merge `annotateTerminal()` into the crash and hang sinks (`:312-313`); add trace attrs to the `app.start` emit (`:368-371`); `onNavigation()` timing attrs (`:634`) |
| `core/TelemetryActivityLifecycleObserver.kt` | `TraceManager.onFirstResume()` next to `notifyActivityResumed()` (`:44`); nav timing attrs (`:68`) |
| `core/TelemetryConfig.kt` | Δ9 suffix-entry validation guard in `init{}` (`:32-38`); update the stale comment (`:20-22`) |
| `core/interaction/UserInteractionTracker.kt` | Δ12c — implement `onSingleTapUp` (mint + name + suppress) and reduce `onSingleTapConfirmed` to emit (`:85-109`); split `handleGesture` (`:50-83`) accordingly; Δ12b semantics resolution in `resolveTargetName` (`:147-161`) behind the existing `AndroidComposeView` branch; emit `ui.name_source` and the `unnamed`/`compose_surface` split |
| `core/interaction/ComposeSemanticsNamer.kt` | **new** — `RootForTest.semanticsOwner.rootSemanticsNode`, innermost hit by `boundsInWindow`, the four-step chain, the Role gate, 64-char `snake_case` normalization, `try/catch` + one-time warn |
| `compose/EdgeTelemetryTrackTap.kt` | **new** — `EdgeActionKey: SemanticsPropertyKey<String>` + `Modifier.trackTap(name)` |
| `compose/EdgeTelemetryCompose.kt` | Δ12a — `trackUserInteraction()` (`:175`) keeps the breadcrumb, drops the `user.interaction` emit (`:195-198`), calls `TraceManager.nameCurrentRoot(target)` |
| `core/services/CrashReportingService.kt` | Δ11 — merge `annotateTerminal()` into `recordHang` (`:178-188`) and `buildCrashAttributes` (`:237`) |
| `core/validation/AttributeValidator.kt` | add `trace.root_type`, `span.start_time`, `span.duration_ms`, `ui.name_source` to `KNOWN_OPTIONAL_TRACE_ATTRIBUTES` (`:61-63`) |
| `core/validation/EventPayloadValidator.kt` | outcome check already reads `TraceManager.OUTCOMES` (`:68-73`) — extending the set is enough; add `span.start_time` to the ISO-8601 checks (`:17`) and a `trace.root_type` membership check |
| `README.md` | the four edits above |
| `CLAUDE.md` | Project Structure refresh (below) |

**`CLAUDE.md` correction (in scope for this ticket, and applied on this branch).** The Project
Structure block listed three directories that no longer exist — `payload/` (`FlutterCompatiblePayload`),
`location/` (`IpLocationProvider`), `events/` (`JsonEventTracker`) — and omitted five that do: `trace/`,
`anr/`, `exit/`, `interaction/`, `startup/`, plus `TelemetryTime.kt`, `CountedEventQueue.kt` and
`PerformanceTracker.kt` at the `core/` root. The Opt-in Features table also advertised
`enableLocationTracking`, which is not a field on `TelemetryConfig` (`TelemetryConfig.kt:3-23`); it now
documents `traceHostAllowlist`, which is the SDK's one genuinely opt-in surface. Documentation, not SDK
code, so it does not breach plan-only.

## Test plan (v3 — extends v2)

v2's ten cases stand. Added:

1. **Async dispatch through a real OkHttp `Call.Factory` — the case that would have caught G1.**
   `TraceManagerTest.kt:226,248` calls `onNetworkCall` **directly on the coroutine thread**, which is
   precisely why the G1 gap survived to production: the test never crossed OkHttp's dispatcher boundary.
   The new case must drive a real request through `MockWebServer`:

   ```kotlin
   @Test fun `enqueued call attributes to the action that started it`() {
       TraceManager.onInteractionStart("checkout")            // main-thread mint
       val factory = TelemetryManager.instrument(client)      // Δ6 wiring under test
       val latch = CountDownLatch(1)
       factory.newCall(request).enqueue(...)                  // real dispatcher hop
       latch.await(5, TimeUnit.SECONDS)
       // header on the RECORDED request, not on the one we built
       val sent = server.takeRequest().getHeader("traceparent")
       assertEquals(rootTraceId, sent!!.split("-")[1])
       assertEquals("injected_attributed", captured["traceparent.outcome"])
   }
   ```

   Assert on `server.takeRequest()`, never on the outgoing `Request` object — a `traceparent` there proves
   only that we asked. **Call `OkHttpClient.release()` from `@After`** (`test/.../OkHttpTestUtil.kt:11`) —
   OkHttp's dispatcher threads are non-daemon and will hang the forked test JVM otherwise.
2. **Unwired detection** — the same request through a bare `client.newCall(...)` with only
   `createNetworkInterceptor()` and a null carrier: `traceparent.outcome == injected_unwired`, warning
   logged once. With a live main-thread carrier and no tag: `injected_attributed` (deprecated sync path
   still works).
3. **`Dispatchers.IO` hop** — `withContext(Dispatchers.IO + TelemetryManager.traceElement()) { … }` on an
   instrumented factory still attributes; without `traceElement()` it reports `injected_unattributed`.
4. **Root expiry (Δ7)** — with an injected clock: at +1.9 s the root is live; at +2.1 s idle `current()`
   is null and a call reports `injected_expired`; at +10.1 s total age it expires even under continuous
   activity. A chained flow — mint, request A completing at +4 s, request B at +4.5 s — keeps **both**
   under the same root, proving completion-extension.
5. **Completion-extension crosses threads** — the extension written from a non-main thread (the
   interceptor's `finally`) is visible to a subsequent `current()` on main. The `AtomicLong`, not the
   `ThreadLocal`, is what carries it.
6. **Launch root (Δ8)** — with importance `IMPORTANCE_VISIBLE`: a request fired before any resume is a
   child of the launch root with `trace.root_type == launch`; the root survives 5 s of idle before first
   resume (exemption); after `onFirstResume()` it expires 2 s later. With importance worse than
   `IMPORTANCE_VISIBLE`: **no root opened**, and the startup request reports `injected_unattributed`.
7. **Allowlist suffix (Δ9)** — entry `.example.com` matches `api.example.com` and `api-v2.example.com`;
   does **not** match `example.com`, `api.example.com.evil.com`, or `evil-example.com`. `.com` throws at
   `initialize()`. Empty list logs the warning exactly once.
8. **Span timing (Δ10)** — `http.request` carries `span.start_time` parsing to an instant **before** the
   event timestamp, and `span.start_time + span.duration_ms ≈ event timestamp` within tolerance. Roots
   (`ui.interaction`, `app.start`) carry `span.start_time` and **no** `span.duration_ms`.
   `ui.interaction`'s `span.start_time` equals the **mint** time, verified by asserting it precedes the
   `http.request` it parents.
9. **Compose mint/emit split (Δ12c)** — drive `onSingleTapUp` then assert a root is open **before**
   `onSingleTapConfirmed` runs; one `ui.interaction` emitted at confirm, carrying the name resolved at
   mint. `onLongPress`/`onFling` mint exactly one root each. Two `onSingleTapUp` with no confirm (double
   tap) emit no event and both roots expire.
10. **Suppression at mint** — a secure window (`FLAG_SECURE`) and a password `EditText` open **no** root,
    so a request fired immediately after is unattributed rather than parented by a suppressed tap.
11. **Naming chain + Role gate (Δ12b)** — Robolectric or a Compose UI test: a Material `Button` labelled
    "Send reset link" → `ui.target == send_reset_link`, `ui.name_source == text`; the same node with
    `Modifier.trackTap("x")` → `x` / `track_tap`; a **roleless** `Box(Modifier.clickable{})` whose merged
    text is a person's name → `unnamed` / `none` (**the privacy assertion — this must never leak the
    name**); a tap on padding → `compose_surface` / `none`; an XML `Button` with an id → `resource_id`,
    unchanged. A thrown semantics lookup degrades to `compose_surface`/`none` and does **not** propagate.
12. **Δ11 boundary guard** — a `DeletedSymbolGuardTest`-style source assertion that `lastRoot` /
    `annotateTerminal()` appear **only** in the crash and hang emit paths, and never in `onNetworkCall`,
    `onNavigation` or `onInteraction*`. This is the mechanical restatement of "Δ11 does not undo T1 #102".
13. **Terminal attribution (Δ11)** — a crash raised on a **background** thread while a main-thread root is
    live stamps `trace.id`/`rum.action.id`/`trace.root_type`; a hang detected from the watchdog thread does
    the same; after Δ7 expiry both stamp **nothing**; `app.exit` stamps nothing, ever.
14. **Six-value enum + `root_type`** — every value is reachable, `EventPayloadValidator` accepts all six
    and rejects a seventh, and every span-carrying event carries a non-null `trace.root_type`.
15. **No `user.interaction` (Δ12a)** — `trackUserInteraction("tap", "vote")` emits **zero** events and
    renames the open root; the subsequent `ui.interaction` carries `ui.target == vote`.

## Out of scope (v3)

Carried from map #120, plus what v3 added:

- **Cross-SDK vocabulary convergence.** Four SDKs ship four incompatible `frame.*` models and three
  incompatible `memory.*` models; `extract.go` implements Flutter's alone. #129 ships the four-way diff as
  a flagged finding, but authoring a canonical vocabulary spans four repos and four release cycles and
  belongs to its own effort — attempting it inside an Android trace ticket is how a fifth vocabulary gets
  created.
- **OTLP-shaped span objects** — reconsidered under Δ10 and rejected again: it reverses v2's deliberate
  "spans are attributes on existing events" design for by far the largest wire and SDK change on the table.
- **An explicit root-close event** — considered under Δ10 for exact envelopes including childless roots,
  and **retired for good** by #129's replay argument (`OfflineBatchStorage` makes any write-time envelope
  wrong by construction). Saves one event per user action.
- **Reopening empty-allowlist-means-nowhere** — T2 #103 stands; Δ9 changes ergonomics only.
- **A coordinate registry for Compose naming** — dissolved by `trackTap`-as-`SemanticsPropertyKey` (Δ12b).
- **Sampling design** — `traceSampleRate` stays 1.0. Its interaction with the now six-value enum is
  unexamined (map fog).
- **Non-OkHttp HTTP stacks** — Ktor, Volley, `HttpURLConnection`, Apollo, gRPC have no `Call.Factory` to
  wrap and no interceptor today. #128 established that no such app exists to observe, so this can never be
  settled by evidence; it is purely a scope question about intended consumers (map fog).
- **`app.exit` cross-process attribution**, **warm/hot start roots**, **`tracestate` / baggage** — map fog,
  unchanged.
- **The 169-key emission inventory** — split out as
  [#132](https://github.com/NCG-Africa/edge_telemetry_android/issues/132).
- **The platform-side "did `traceparent` survive" comparison** — computed in the shared store from the
  outcome enum + `rum.action.id` join. Backend's job, not this SDK's.
