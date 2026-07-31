# Android spec — distributed trace/span contract, v2 (android-first)

Resolves wayfinder map **#101** (destination artifact). **v2, not greenfield** — `TraceManager`
(`core/trace/TraceManager.kt`, #59) and the W3C id helpers (`core/ids/IdGenerator.kt`, #34) already
ship. This revision folds the four CONTEXT deltas — decided in tickets **T1–T5** (#102–#106) — into the
shipped v1 contract. Reqs 1/2/5-id and single-header injection were already satisfied in v1 and are
unchanged; only the deltas below are new.

Plan-only. This is the implementation spec; **no code is changed on this branch.** Execution (editing
`TraceManager`, `TelemetryInterceptor`, `TelemetryConfig`, `IdGenerator`, the validators) hands off
downstream, per this repo's planning-by-default convention.

## Goal

End-to-end visibility: an **app action → the network calls it triggers → backend spans** stitched into
one trace. v1 delivered the happy path (tap → sync calls, single `traceparent`). v2 makes propagation
**correct under concurrency**, **safe by default** (no header leaks to hosts you didn't allowlist),
**interoperable** (adopts an inbound APM `traceparent`), **complete** (no-action calls still get a
trace), and **measurable** (a per-request outcome enum + a stable action-join key that the platform
uses to detect stripped headers).

## What changed from v1 — the four deltas

| # | Delta | Ticket | v1 behaviour → v2 behaviour |
|---|---|---|---|
| 1 | **Action-scoped context** | [T1 #102](https://github.com/NCG-Africa/edge_telemetry_android/issues/102) | process-global `AtomicReference` current-root (leaks across threads/actions) → `ThreadLocal` + `asContextElement` carrier; null ⇒ unattributed, **never** borrow a global root |
| 2 | **Host allowlist** | [T2 #103](https://github.com/NCG-Africa/edge_telemetry_android/issues/103) | inject on every sampled call → inject **only** to allowlisted hosts; empty default = inject nowhere; header-only suppression |
| 3 | **Inbound header adoption** | [T3 #104](https://github.com/NCG-Africa/edge_telemetry_android/issues/104) | app-set `traceparent` present → skip entirely → **adopt** it: mirror foreign ids onto the event, leave the header + action root untouched |
| 4 | **Unattributed traces** | [T4 #105](https://github.com/NCG-Africa/edge_telemetry_android/issues/105) | no active root → return null, no trace → **mint a parentless span**, inject, mark unattributed |
| 5 | **Association + outcome enum** | [T5 #106](https://github.com/NCG-Africa/edge_telemetry_android/issues/106) | (new) explicit `rum.action.id` join key on action + network events; `traceparent.outcome` enum on `http.request`; recorded-`span.id` ≡ on-wire invariant |

## Unchanged from v1 (carried forward)

- **IDs — W3C Trace Context.** `trace.id` = 32 lowercase hex (128-bit); `span.id` = 16 lowercase hex
  (64-bit); `traceparent` = `00-<trace.id>-<span.id>-<flags>`, version `00`, `flags = 01` (we inject
  only when sampled). Separate from the `<kind>_<epochMs>_<16hex>_android` join keys. `IdGenerator`
  supplies `traceId()`/`spanId()` on the shared `SecureRandom` (`core/ids/IdGenerator.kt:17,20`).
- **Spans are attributes on existing events** — no span-start/span-end objects, no new event type. The
  root span rides the triggering `ui.interaction` (#42) or `navigation` event; each network child span
  rides its `http.request` event (#40).
- **Propagation is `traceparent` only** — no `tracestate`/baggage in v2 (still fog).
- **Sampling is head-based, decided once at root open.** `traceSampleRate` stays at its default `1.0`
  (`core/TelemetryConfig.kt:19`); **v2 does not touch sampling** (map Out-of-scope). The wrinkle T4
  adds below only matters if the rate ever drops.

## Decisions (v2 deltas)

### Δ1 — Action-scoped trace context (replaces v1 §3 "single process-global current root")

The v1 ceiling — one process-global `AtomicReference<TraceContext?>` — **contradicts** the CONTEXT's
thread-safety requirement (correct across threads and coroutines, no leak between unrelated actions).
It is replaced by a **`ThreadLocal` carrier projected into coroutines**.

**Why `ThreadLocal`, not a `CoroutineContext` element alone:** the OkHttp interceptor
(`core/TelemetryInterceptor.kt:25`) is **synchronous, non-suspend** code — it cannot read
`coroutineContext`. The carrier must be a `ThreadLocal` so the interceptor's plain `get()` works, and
it must be projected via `ThreadLocal.asContextElement(value)` so that `get()` stays correct after a
coroutine hops to `Dispatchers.IO`. A **bare** `ThreadLocal` is lost the instant the coroutine changes
threads (proven in the T1 prototype, scenario S2b).

Carrier shape (lift into the `TraceManager` rewrite at execution):

```kotlin
private val tl = ThreadLocal<TraceContext?>()

fun current(): TraceContext? = tl.get()              // read site (interceptor/nav/interaction), any thread
fun setCurrent(ctx: TraceContext?) { tl.set(ctx) }   // SDK-owned main-thread sites
fun asElement(ctx: TraceContext) = tl.asContextElement(value = ctx)  // survives Dispatchers.IO hops
```

**Coverage model — carrier + one opt-in seam:**

- **Auto (SDK-owned sites):** the interaction/nav observers set the carrier on the main thread. Covers
  main-thread-synchronous calls.
- **Opt-in seam (app-owned coroutine calls):** the SDK's tap/nav listeners run **outside** the app's
  coroutines, so the SDK cannot install the element into an app's `viewModelScope.launch { api.get() }`.
  The SDK exposes a coroutine-element / scope helper the app can adopt, e.g.
  `withContext(TelemetryManager.traceElement()) { api.get() }` or a `traceScope { }` wrapper. Adopted
  scopes attribute precisely. *(Public name is an execution detail — direction pinned, naming deferred.)*
- **Un-adopted / detached calls:** carrier is null → **unattributed** (Δ4) — mint a parentless trace,
  mark it unattributed. **Never** borrows a global root.

**Invariant:** correctness is unconditional (0 leaks by construction); attribution *coverage* is opt-in.
Marking a call unattributed is chosen deliberately over guessing its parent.

**Background clear** (`onBackground`, wired from `ProcessLifecycleOwner` `onStop`,
`TelemetryManager.kt:530`): clears the **main-thread carrier slot only** — no longer a global null. A
call in flight on another thread keeps the context its scope/element carries, so backgrounding one
screen no longer nulls another action's in-flight trace.

### Δ2 — Host allowlist gate (`traceparent` never leaks off-allowlist)

New config surface (`core/TelemetryConfig.kt`, after `traceSampleRate:19`):

```kotlin
val traceHostAllowlist: List<String> = emptyList()
```

**Empty (the default) = inject on NO host.** A deliberate choice of the CONTEXT's "`traceparent` must
never leak off-allowlist" over back-compat: **safety wins.** There is intentionally **no** "inject
everywhere" escape hatch — enumerate-only. *(⚠️ dark-on-upgrade — see README.)*

**Match — exact host, case-insensitive, scheme/port-agnostic:**

- Compare `request.url.host.lowercase()` ∈ the normalized allowlist set. Nothing else — not scheme,
  not port, not path.
- `api.example.com` matches host `api.example.com` **only**. `example.com` and
  `api.example.com.evil.com` do **not** match.
- **No substring matching.** The `isTelemetryRequest` `url.contains(...)` heuristic
  (`TelemetryInterceptor.kt:61-74`) is a self-exclusion shortcut, **unsafe for a security gate** — do
  not copy it here.

**Entry validation + normalization** (`TelemetryConfig.init{}`, matching the existing `require(...)`
pattern) — fail-fast at `initialize()`; store trimmed + lowercased into a `Set<String>` once for O(1)
membership:

```kotlin
traceHostAllowlist.forEach {
    val h = it.trim().lowercase()
    require(h.isNotBlank()) { "traceHostAllowlist entry must not be blank" }
    require(!h.contains("/") && !h.contains(":")) {
        "traceHostAllowlist entry must be a bare host (no scheme, path, or port), got '$it'"
    }
}
```

This kills the silent-dark footgun where `https://api.example.com/v1` (scheme + path) never matches
`url.host = api.example.com`.

**Gate placement** (`TelemetryInterceptor`, before `onNetworkCall()` at `:25`; `request.url.host` is
available at `:14`). The host check gates **only the outbound `traceparent` header**, not the whole
trace:

- **On-allowlist:** mint the child span, inject `traceparent`, stamp attrs.
- **Off-allowlist:** still mint the child span and stamp `trace.id`/`span.id` on the `http.request`
  event (device-side → your own collector, **no leak**), but **do not inject** the header. You keep
  observability of off-allowlist calls correlated to their action; the third party sees nothing.

The allowlist check **short-circuits before** the attributed/unattributed branch, so
`skipped_off_allowlist` outranks every other outcome (see the ladder below).

### Δ3 — Adopt an existing inbound `traceparent` (adopt `trace_id`, don't overwrite)

Reverses v1 §4's "if a `traceparent` is present, skip injection." When a request already carries a
valid inbound `traceparent` (e.g. a Dynatrace/AppDynamics APM agent set one), **adopt it per-event**:

**Parse + validate** (W3C `version-trace_id-parent_id-flags`, split on `-`, read positionally —
forward-compatible, extra fields ignored):

- **Lenient version:** any version `00`–`fe` adopts (extract `trace_id`/`parent_id` from fixed
  positions). Realistic agents emit `00`; leniency avoids clobbering a valid future-version trace.
- **Malformed** ⇒ treat as absent + generate (see below), iff: version = `ff`; `trace_id` ≠ 32 hex
  **or** all-zero; `parent_id` ≠ 16 hex **or** all-zero; `flags` ≠ 2 hex.
- **Uppercase accepted**, lowercased on read/stamp (we're reading, not gatekeeping).

**Adopt semantics — per-event only (mirror the header):**

```
trace.id       = <foreign trace_id>
span.id        = <foreign parent_id>       # the APM's own span for this call — MIRROR, don't mint a child
parent.span.id = (omitted)
rum.action.id  = <ours, if current() root present>   # T5 link to the RUM action
session.id     = <ours>
```

- **The action root (`current()`) is left untouched** — a third-party header on one call must not
  hijack the whole action's trace; sibling calls under the same action keep our `trace.id`.
- **Mirror, not child:** `span.id` = the header's `parent_id`, the exact span the agent minted, so
  device and backend record identical ids → maximal join. Minting our own child would join to nothing.
- **Header untouched** — we only read a valid inbound header, never overwrite it.
- **Adoption bypasses sampling** — always record adopted ids + `outcome = adopted`, regardless of
  `traceSampleRate` and the foreign `flags` bit. `traceSampleRate` governs the trace *we* mint;
  adoption observes a decision another system already made. (Moot at 1.0, but the spec states it; note
  the shipped `onNetworkCall()` gates on `sampled` — adoption must **not**.)
- **No active root (adopted + unattributed):** if `current()` is null, the adopted event carries the
  foreign ids with no action keys. Outcome is still `adopted` — **`adopted` outranks unattributed.**

**Malformed ⇒ treat as absent + overwrite.** A broken header (`ff` / wrong length / all-zero) is not
valid propagation — downstream rejects it anyway. Run the normal Δ1/Δ4 inject path and **replace** the
header with our valid `traceparent`; outcome is `injected_attributed` (root present) or
`injected_unattributed` (root absent) — **not** `adopted`.

### Δ4 — Unattributed-request traces (generate + inject, mark unattributed)

When an outbound request has **no active action** (Δ1 carrier absent), reverse v1 §4's "return null"
(shipped `TraceManager.kt:67-72`): mint a trace and record it rather than dropping the request.

1. **Trace shape — single parentless span.** The request *is* the root: fresh `trace.id` + `span.id`,
   **no** `parent.span.id`. No synthetic phantom root above it. Wire header: `00-<traceId>-<spanId>-01`.
2. **Carrier — ephemeral, never written.** The interceptor stays a pure *reader* of the Δ1 carrier;
   the unattributed root is never written back as a current root. Two back-to-back no-action requests →
   two distinct `trace.id`s. Writing a process-visible root from an arbitrary OkHttp thread would
   reintroduce exactly the cross-action bleed Δ1 killed; two unattributed requests are genuinely
   unrelated, so distinct traces is correct.
3. **Allowlist interaction — mint always, gate the header only.** Minting is allowlist-independent
   (consistent with Δ2). Off-allowlist + no-action → mint the parentless trace, stamp
   `trace.id`/`span.id` locally, suppress the header, outcome = `skipped_off_allowlist`. Therefore
   `injected_unattributed` requires **both** no-action **and** on-allowlist.
4. **Sampling wrinkle — unattributed = absence of context, not a falsy context.** Carrier-null must
   not be conflated with "action existed but wasn't sampled." Interceptor rule:
   - **carrier absent → unattributed** (mint + inject);
   - **carrier present but `sampled == false` → skip** (no trace, matches shipped behaviour).

   Needs the `sampled` flag on the carrier `TraceContext` (shipped already has it). Moot at 1.0. The
   unattributed header itself is always `-01` — it exists, so it's sampled.
5. **Representation — `injected_unattributed`, no action link.** No `rum.action.id` (no action to
   associate); `session.id` is still stamped by the event-enrichment pipeline (untouched by
   `TraceManager`). Precedence floor — chosen only when nothing richer applies.

**Execution note:** shipped `onNetworkCall()` returns `Pair<String, Map>?`. Under Δ4 it no longer
returns null for the no-action case — it returns an unattributed pair; **null is reserved for the
unsampled-context case (#4 above).** The interceptor's `request.header("traceparent") == null` guard
(`TelemetryInterceptor.kt:25`) is unchanged — a caller-supplied header still short-circuits into Δ3's
adopt path.

### Δ5 — Trace association + `traceparent.outcome` enum (device-side detection)

**Association.** On both action events (`ui.interaction`/`navigation`) and network events
(`http.request`), stamped through the existing `recordEvent` enrichment path:

| Attribute | Meaning |
|---|---|
| `trace.id` | 32 hex, the trace |
| `span.id` | 16 hex, this event's span (adopted ⇒ mirrored foreign parent-id) |
| `parent.span.id` | 16 hex, present only where a parent exists (omitted on roots, adopted, unattributed) |
| `rum.action.id` | **new** — the action root's `span.id`; the stable action-join key |
| `session.id` | already auto-enriched on every event |

**`rum.action.id`** = the action's root `span.id`. On `ui.interaction`/`navigation` it equals the
event's own `span.id`; on `http.request` it is propagated from the Δ1 carrier's root spanId. It is
**independent of `trace.id`/`span.id`**, so it survives the adopted (Δ3) and unattributed (Δ4) branches
where `parent.span.id` is absent. Unattributed requests omit it. Single named DB join column:
`network.rum.action.id == action.rum.action.id`.

**Invariant — recorded `span.id` ≡ on-wire `span.id`.** Whenever a `traceparent` is emitted, the
recorded `span.id` is identical to the header's span-id field: attributed/unattributed = the minted
child span; adopted = the mirrored foreign parent-id (header untouched). `skipped_off_allowlist` stamps
`trace.id`/`span.id` locally with **no** wire header. This invariant is what makes "missing DB join ⇒
header stripped in transit" computable platform-side.

**Outcome enum — `traceparent.outcome`** (string), on `http.request` **only** (action events never
emit a header). First-match strict precedence, mutually exclusive and total over *traced* requests:

| # | Condition | `traceparent.outcome` |
|---|---|---|
| 1 | host off allowlist | `skipped_off_allowlist` |
| 2 | valid inbound `traceparent` | `adopted` |
| 3 | active action (Δ1 carrier present) | `injected_attributed` |
| 4 | else (no action, on allowlist) | `injected_unattributed` |

The one overlap — off-allowlist **and** an app-set inbound header — resolves to `skipped_off_allowlist`
(allowlist wins uniformly; off-allowlist hosts are intentionally uninstrumented). **Not-traced**
requests (the SDK's own telemetry POSTs; unsampled context — moot at rate 1.0) carry **no**
`traceparent.outcome` attribute: **absence = not traced**, there is no 5th member.

## The outcome ladder (Δ2 → Δ3 → Δ4, as evaluated per request)

```
off-allowlist?  --yes-->  skipped_off_allowlist          (Δ2, outermost — header suppressed, stamp local)
     | no
     v
inbound traceparent present & VALID? --yes--> adopted    (Δ3 — mirror foreign ids, header + root untouched)
     | no (absent OR malformed→overwrite)
     v
current() carrier ...
   absent            --> injected_unattributed            (Δ4 — mint parentless, inject)
   present & sampled --> injected_attributed              (Δ1 — child of action root, inject)
   present & !sampled--> (no trace, no header)            (Δ4 #4 — skip; moot at rate 1.0)
```

Precedence: `skipped_off_allowlist` > `adopted` > `injected_attributed` > `injected_unattributed`.

## Lifecycle model (v2 — the carrier + four call sites)

```kotlin
// core/trace/TraceManager.kt — REWRITE. Process-global AtomicReference → ThreadLocal carrier.
data class TraceContext(val traceId: String, val spanId: String, val sampled: Boolean)

object TraceManager {
    private val tl = ThreadLocal<TraceContext?>()
    @Volatile var traceSampleRate = 1.0                 // from TelemetryConfig (fixed 1.0 this map)

    fun current(): TraceContext? = tl.get()
    fun asElement(ctx: TraceContext) = tl.asContextElement(value = ctx)

    /** #42 interaction observer (main thread). Fresh root; sets the carrier. → attrs for the event. */
    fun onInteraction(): Map<String, Any>? {
        val sampled = SecureRandom().nextDouble() < traceSampleRate
        if (!sampled) { tl.set(null); return null }
        val ctx = TraceContext(IdGenerator.traceId(), IdGenerator.spanId(), true)
        tl.set(ctx)
        // rum.action.id == span.id on the root
        return mapOf("trace.id" to ctx.traceId, "span.id" to ctx.spanId, "rum.action.id" to ctx.spanId)
    }

    /** Screen tracker. Child of the current root if one is active, else a new root. */
    fun onNavigation(): Map<String, Any>? {
        val root = current()?.takeIf { it.sampled }
        if (root != null) {
            val childId = IdGenerator.spanId()
            return mapOf("trace.id" to root.traceId, "span.id" to childId,
                         "parent.span.id" to root.spanId, "rum.action.id" to root.spanId)
        }
        return onInteraction()   // no active root → this nav IS the root
    }

    /** #40 interceptor, per request, INSIDE the on-allowlist branch. Reads the carrier; never writes it.
     *  Returns the header to inject + the attrs (incl. traceparent.outcome) to merge onto http.request.
     *  Adoption (Δ3) and the allowlist gate (Δ2) are handled in the interceptor around this call. */
    fun onNetworkCall(): Pair<String?, Map<String, Any>> {
        val root = current()
        return when {
            root == null -> {                            // Δ4 unattributed — mint a parentless root
                val t = IdGenerator.traceId(); val s = IdGenerator.spanId()
                "00-$t-$s-01" to mapOf("trace.id" to t, "span.id" to s,
                                       "traceparent.outcome" to "injected_unattributed")
            }
            !root.sampled -> null to emptyMap()          // Δ4 #4 — context exists but unsampled → skip
            else -> {                                    // Δ1 attributed — child of the action root
                val childId = IdGenerator.spanId()
                "00-${root.traceId}-$childId-01" to mapOf(
                    "trace.id" to root.traceId, "span.id" to childId,
                    "parent.span.id" to root.spanId, "rum.action.id" to root.spanId,
                    "traceparent.outcome" to "injected_attributed")
            }
        }
    }

    /** ProcessLifecycleOwner onStop. Clears the MAIN-THREAD slot only (not a global null). */
    fun onBackground() = tl.set(null)
}
```

*(Illustrative — the interceptor wraps this with the Δ2 host gate and Δ3 adoption; app-owned coroutine
calls attribute via `TraceManager.asElement(ctx)` / the opt-in scope helper.)*

Resulting tree for a tap that navigates and fires two on-allowlist calls:

```
ui.interaction  span=A rum.action.id=A           (root, no parent)     trace=T  outcome=(n/a, action event)
  navigation    span=B parent=A rum.action.id=A                        trace=T
  http.request  span=C parent=A rum.action.id=A  traceparent 00-T-C-01 trace=T  outcome=injected_attributed
  http.request  span=D parent=A rum.action.id=A  traceparent 00-T-D-01 trace=T  outcome=injected_attributed
```

## README config section (destination deliverable)

Add to the README's config documentation:

1. **`traceHostAllowlist`** — `List<String>`, default `emptyList()`. Bare hosts (no scheme/port/path),
   exact-match, case-insensitive. `traceparent` is injected **only** to hosts in this list.
   > ⚠️ **Upgrading to v2:** distributed tracing goes **dark** on upgrade — no `traceparent` is
   > injected on any request — until you enumerate your backend hosts in `traceHostAllowlist`. There
   > is intentionally **no** "inject everywhere" option; the header must never reach a host you didn't
   > name. Off-allowlist calls are still recorded locally (you keep `trace.id`/`span.id` on your own
   > `http.request` events); only the outbound header is withheld.
2. **Sampling is fixed** — `traceSampleRate` stays `1.0`; it is **not** a v2 knob.
3. **`traceparent.outcome`** — value table for data consumers:

   | Value | Meaning |
   |---|---|
   | `injected_attributed` | header injected; call belongs to a known RUM action |
   | `injected_unattributed` | header injected; no active action (parentless trace) |
   | `adopted` | inbound `traceparent` mirrored; header + trace left as the caller set them |
   | `skipped_off_allowlist` | host not in `traceHostAllowlist`; recorded locally, no header sent |
   | *(absent)* | request not traced (e.g. the SDK's own telemetry POSTs) |

## Execution notes (downstream, per file)

| File | Change |
|---|---|
| `core/trace/TraceManager.kt` | **rewrite** — `ThreadLocal` carrier + `asElement`; `onInteraction`/`onNavigation` set carrier + return `rum.action.id`; `onNetworkCall` reads carrier, mints unattributed on null, returns `traceparent.outcome`; `onBackground` clears main-thread slot only; expose the opt-in coroutine-element / scope seam |
| `core/TelemetryInterceptor.kt` | Δ2 host gate before injection (build normalized set at init); Δ3 parse/adopt an inbound `traceparent`; merge `traceparent.outcome` + trace attrs onto `http.request`; keep the `header==null` short-circuit routing into adopt |
| `core/TelemetryConfig.kt` | add `traceHostAllowlist: List<String> = emptyList()` + `init{}` bare-host validation (after `traceSampleRate:19`); wire the normalized set into the interceptor |
| `core/ids/IdGenerator.kt` | **all-zeros guard** — `traceId()`/`spanId()` (`:17,:20`) have no non-zero guard today; an all-zero id is an invalid W3C id and, on the adopt side, a malformed marker. Re-roll (or OR-in a bit) so a generated id is never all-zero. Astronomically rare, but the contract must forbid it. |
| `core/validation/EventPayloadValidator.kt` / `AttributeValidator.kt` | register `traceparent.outcome` and `rum.action.id` as **known keys** so enrichment doesn't flag them |
| #42 interaction observer | call `onInteraction()`; merge attrs onto `ui.interaction` |
| screen/nav tracker (#35) | call `onNavigation()`; merge attrs onto `navigation` |
| lifecycle observer | `ProcessLifecycleOwner` `onStop` → `onBackground()` |
| README | the config section above |

## Test plan (v2 — extends v1)

1. **Attributed inject** — on-allowlist `http.request` after an interaction: same `trace.id`, fresh
   `span.id`, `parent.span.id` == root, `rum.action.id` == root span, header
   `^00-[0-9a-f]{32}-[0-9a-f]{16}-01$`, `traceparent.outcome == injected_attributed`.
2. **Allowlist gate** — off-allowlist call: `http.request` carries `trace.id`/`span.id` but **no**
   header on the request; `outcome == skipped_off_allowlist`. Empty allowlist ⇒ **no** request gets a
   header. Substring near-miss (`api.example.com.evil.com` vs entry `api.example.com`) is **off**.
3. **Config fail-fast** — `traceHostAllowlist = listOf("https://api.example.com/v1")` throws at
   `initialize()` (scheme + path). Blank entry throws.
4. **Adoption** — request with valid inbound `traceparent 00-<T>-<P>-01`: `http.request` carries
   `trace.id == T`, `span.id == P`, **no** `parent.span.id`, header **unchanged**, `rum.action.id` ==
   our action root (if any), `outcome == adopted`. Uppercase inbound accepted. Version `2a` adopted.
5. **Malformed inbound → overwrite** — inbound `ff-...` / wrong length / all-zero: header **replaced**
   with ours; `outcome == injected_attributed`/`injected_unattributed`, **not** `adopted`.
6. **Unattributed** — no-action on-allowlist call: parentless span (no `parent.span.id`, no
   `rum.action.id`), header `-01`, `outcome == injected_unattributed`. Two back-to-back no-action
   calls → **two distinct** `trace.id`s.
7. **Concurrency (T1 invariant)** — action A on one coroutine and action B on another, each firing a
   call: A's call carries A's trace, B's carries B's. **0 cross-action leaks.** A call after an
   `IO`-dispatcher hop, launched with `asElement(ctx)`, still attributes to `ctx`.
8. **Background** — `onBackground()` clears the main-thread slot; a subsequent detached call is
   **unattributed** (not attached to a stale action), while an unrelated in-flight call on another
   thread keeps its context.
9. **Invariant** — for every traced request, recorded `span.id` == the emitted header's span-id field;
   `skipped_off_allowlist` records `span.id` with no header.
10. **Known keys** — `traceparent.outcome` and `rum.action.id` pass validation (not flagged unknown).

## Out of scope (v2)

Per map #101: WebView / HttpURLConnection / Cronet / gRPC / third-party-SDK traffic; build-time
Gradle-plugin instrumentation; sampling controls (fixed at 1.0); the Java 8 variant and non-Android
SDKs; deep span nesting (tap→nav→http active-parent stack); `tracestate` / baggage; and the platform-
side "did `traceparent` survive" comparison (computed in the shared DB from the Δ5 outcome enum +
`rum.action.id` join — backend's job, not this SDK's).
