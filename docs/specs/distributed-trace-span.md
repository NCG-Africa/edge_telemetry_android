# Android spec — distributed trace/span contract (android-first, new)

Resolves wayfinder ticket **#43**. **Net-new datapoint** — no trace/span code exists today
(`sdk-audit.yaml` has no trace/span entries). The contract is **decided android-first**; other SDKs
and the backend conform (per the map destination). Rides the unified envelope (#30), reuses the
SecureRandom id machinery of #34, and its propagation rides the network interceptor owned by #40.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Goal

End-to-end visibility: an **app action → the network calls it triggers → backend spans** stitched
into one trace. The app action is a user interaction (#42) or a screen entry; each network call is a
child; the backend continues the same trace because the SDK injects a standard `traceparent` header.

## Decisions

Three forks were the human's call (grilling #43): **(a)** ID format = **W3C traceparent**;
**(b)** a root span opens on a **user-interaction, else a screen**; **(c)** sampling = **100% by
default, configurable**. The rest follows.

### 1. IDs — W3C traceparent, not the `<kind>_..._android` family

Trace/span identity is **W3C Trace Context**, because propagation only buys backend correlation if
the wire header is the ubiquitous standard any OTel-compatible collector already reads:

- **trace.id** — 32 lowercase hex (128-bit), 16 random bytes from `SecureRandom`.
- **span.id** — 16 lowercase hex (64-bit), 8 random bytes.
- **`traceparent` header** — `00-<trace.id>-<span.id>-<flags>`; version `00`, `flags = 01` (sampled).
  We only ever inject when sampled, so `flags` is always `01` in v1 (see §6).

These are **separate** from the `<kind>_<epochMs>_<16hex>_android` device/session/user join keys (#34):
those stay opaque app-identity keys; trace/span ids must be W3C-shaped to interoperate. `IdGenerator`
gains raw-hex helpers (`traceId()` = 32 hex, `spanId()` = 16 hex) reusing its existing `SecureRandom`.

### 2. Spans are attributes on existing events — no separate span emission

We do **not** emit span-start / span-end objects or a new event type. A span is expressed as three
attributes on the event that already marks the work:

| Attribute | On | Value |
|---|---|---|
| `trace.id` | every trace-participating event | 32 hex, same across the whole trace |
| `span.id` | every trace-participating event | 16 hex, this event's span |
| `parent.span.id` | child spans only (**omitted** on the root) | 16 hex, the parent's `span.id` |

The **root span** rides the triggering `ui.interaction` (#42) or `navigation` event. Each network
**child span** rides its `http.request` event (#40). The backend reconstructs the tree from these
attributes plus the `traceparent` it received. No new machinery — reuses #30 enrichment, #42, #40.

### 3. Root span lifecycle

A single process-global "current root" holds the active trace. `TraceManager` (new,
`core/trace/TraceManager.kt`):

- **User-interaction → always a new root.** The #42 observer calls `onInteraction()`; it rolls
  sampling, mints `trace.id` + a root `span.id`, replaces the current root, and returns the two attrs
  to stamp on the `ui.interaction` event (no `parent.span.id`).
- **Navigation → child if it followed an interaction, else a new root.** The screen tracker calls
  `onNavigation()`. Within `ROOT_LINK_WINDOW_MS` (1000 ms) of the current root's open, the navigation
  is treated as **caused by** that interaction (tap → navigate): it gets a child `span.id` under the
  current root. Otherwise (cold start, deep link, programmatic nav) it opens its **own** root. Either
  way the current root pointer is what later calls parent to.
- **Background → cleared.** The already-wired `ProcessLifecycleOwner` `onStop` calls `onBackground()`,
  nulling the current root so a background sync fired after the user leaves does not attach to a stale
  tap's trace.

<!-- ceiling: current root is a single process-global AtomicReference, not per-coroutine context.
     A call fired from a stale foreground screen can attach to the wrong root. Accurate parenting needs
     coroutine/thread context propagation — deferred to fog. Fine for the tap→calls 95% case. -->

### 4. Child spans — network calls

The interceptor (#40) per outgoing request calls `TraceManager.onNetworkCall()`:

- If a **sampled** current root exists → mint a child `span.id`, inject
  `traceparent: 00-<trace.id>-<childSpanId>-01` into the request headers, and return
  `{trace.id, span.id=childSpanId, parent.span.id=rootSpanId}` for the interceptor to merge into the
  `http.request` event.
- If no active root (e.g. background call after §3 clear) → return null: the call emits `http.request`
  with **no** trace attrs and **no** header, exactly as #40 already does. We do not manufacture a root
  for every stray call — a call with no app-action parent is not part of an app-action trace.
- If the request already carries a `traceparent` (an app set one) → **do not overwrite**; still stamp
  our attrs from the header we would have used only when we own it. v1: skip injection when present.

Duration is the request duration #40 already measures; the `http.request` event *is* the child span —
no separate close.

### 5. Propagation — `traceparent` only

Inject `traceparent`; **no `tracestate`** in v1 (we carry no vendor trace-state; YAGNI). This lives in
the #40 interceptor, before `chain.proceed()` for the header and after the response for the event
attrs. #43 owns the trace additions to that interceptor; #40 owns the `http.*` re-keying.

### 6. Sampling — head-based, `traceSampleRate` default 1.0

Decided **once, at root-span open**. `TelemetryConfig` gains `traceSampleRate: Double = 1.0`
(validated `0.0..1.0`). `onInteraction()` / new-root `onNavigation()` roll `SecureRandom.nextDouble()
< traceSampleRate`; the boolean is stored on the root and **inherited** by every child. Unsampled root
→ `TraceManager` returns null everywhere for that trace: no attrs, no header, no trace on our side.

<!-- ceiling: not W3C-strict — an unsampled trace is dropped, not propagated with flags=00. Tail /
     deferred sampling and sampled-out context propagation are fog, graduate if the backend needs them. -->

## Prototype — the lifecycle model to react to

```kotlin
// core/trace/TraceManager.kt  — NEW. The whole contract is this one holder + three call sites.
data class TraceContext(val traceId: String, val spanId: String, val sampled: Boolean, val startedAtMs: Long)

object TraceManager {
    private val current = AtomicReference<TraceContext?>(null)
    @Volatile var traceSampleRate = 1.0          // set from TelemetryConfig
    private const val ROOT_LINK_WINDOW_MS = 1000L

    /** #42 interaction observer. Always a fresh root. → attrs for the ui.interaction event, or null (unsampled). */
    fun onInteraction(nowMs: Long): Map<String, Any>? {
        val sampled = SecureRandom().nextDouble() < traceSampleRate
        val ctx = TraceContext(IdGenerator.traceId(), IdGenerator.spanId(), sampled, nowMs)
        current.set(if (sampled) ctx else null)
        return if (sampled) mapOf("trace.id" to ctx.traceId, "span.id" to ctx.spanId) else null
    }

    /** Screen tracker. Child of the current root if it just opened (tap→nav), else a new root. */
    fun onNavigation(nowMs: Long): Map<String, Any>? {
        val root = current.get()
        if (root != null && root.sampled && nowMs - root.startedAtMs <= ROOT_LINK_WINDOW_MS) {
            val childId = IdGenerator.spanId()
            return mapOf("trace.id" to root.traceId, "span.id" to childId, "parent.span.id" to root.spanId)
        }
        return onInteraction(nowMs)   // no recent root → this nav IS the root
    }

    /** #40 interceptor, per request. → (headerValue, event attrs) or null when there's no sampled root. */
    fun onNetworkCall(): Pair<String, Map<String, Any>>? {
        val root = current.get()?.takeIf { it.sampled } ?: return null
        val childId = IdGenerator.spanId()
        val header = "00-${root.traceId}-$childId-01"
        return header to mapOf("trace.id" to root.traceId, "span.id" to childId, "parent.span.id" to root.spanId)
    }

    /** ProcessLifecycleOwner onStop (already wired). */
    fun onBackground() = current.set(null)
}
```

Resulting tree for a tap that navigates and fires two calls (flat, two-level):

```
ui.interaction  span=A            (root, no parent)          trace=T
  navigation    span=B parent=A                              trace=T
  http.request  span=C parent=A   traceparent 00-T-C-01      trace=T
  http.request  span=D parent=A   traceparent 00-T-D-01      trace=T
```

<!-- ceiling: two-level flat tree — network calls parent to the root, not to the nav span. Deeper
     nesting (tap→nav→http) needs an active-parent stack; deferred to fog. -->

## Coordination

| Ticket | What #43 adds |
|---|---|
| **#42** (`ui.interaction`) | observer calls `onInteraction()`; merges returned attrs onto the interaction event |
| **#40** (network interceptor) | inject `traceparent` before `proceed()`; merge trace attrs onto `http.request` |
| screen tracker (#35) | `navigation` event calls `onNavigation()`; merges returned attrs |
| **#30** (envelope) | `trace.id`/`span.id`/`parent.span.id` are optional attrs through the existing enrichment path — no envelope change, only present on trace-participating events |
| **#34** (ids) | `IdGenerator` gains `traceId()`/`spanId()` raw-hex helpers on the existing `SecureRandom` |
| lifecycle observer | `ProcessLifecycleOwner` `onStop` → `onBackground()` |

## Flag to hub (upstream proposal — android decided, backend + other SDKs must conform)

The **whole contract** is an android-first proposal for `NCG-Africa/edge_rum_spec`:

1. **Backend must read `traceparent`** and continue the same `trace.id` for the app-side trace to
   stitch to backend spans. Without this the injection is inert. **Hard dependency on the backend.**
2. **Semantic-convention names** `trace.id` / `span.id` / `parent.span.id` proposed as the cross-SDK
   trace attribute set on the unified envelope.
3. **W3C Trace Context** (128-bit trace, 64-bit span, `traceparent` v0) proposed as the shared
   propagation format for all SDKs.

Upstream-rejection risk is accepted per the map ("new features decided android-local").

## Test plan

1. **Interaction opens a root** — a `ui.interaction` carries `trace.id` (`^[0-9a-f]{32}$`) + `span.id`
   (`^[0-9a-f]{16}$`) and **no** `parent.span.id`.
2. **Tap → network child** — an `http.request` after an interaction carries the **same** `trace.id`, a
   fresh `span.id`, `parent.span.id` == the root span; the outgoing request has header
   `^00-[0-9a-f]{32}-[0-9a-f]{16}-01$` with the matching trace/parent ids.
3. **Navigation linkage** — nav within 1000 ms of an interaction is a **child** (parent = root);
   a cold nav (no recent interaction) opens a **new** root (no parent).
4. **Background clears** — a network call after `onBackground()` emits `http.request` with **no** trace
   attrs and **no** `traceparent` header.
5. **Sampling** — with `traceSampleRate = 0.0`, no event carries trace attrs and no request carries a
   header; with `1.0`, every eligible event/request does.
6. **No overwrite** — a request that already has a `traceparent` keeps it unchanged.

## Files touched (execution, downstream)

| File | Change |
|---|---|
| `core/trace/TraceManager.kt` | **new** — the holder + `onInteraction`/`onNavigation`/`onNetworkCall`/`onBackground` |
| `core/ids/IdGenerator.kt` | add `traceId()` (32 hex) + `spanId()` (16 hex) on the existing `SecureRandom` |
| `core/TelemetryConfig.kt` | add `traceSampleRate: Double = 1.0` (+ `0.0..1.0` validation); wire into `TraceManager` at init |
| #42 interaction observer | call `onInteraction()`; merge attrs onto `ui.interaction` |
| screen/nav tracker (#35) | call `onNavigation()`; merge attrs onto `navigation` |
| `core/TelemetryInterceptor.kt` (#40) | inject `traceparent`; merge trace attrs onto `http.request` |
| lifecycle observer | `ProcessLifecycleOwner` `onStop` → `onBackground()` |

## Fog graduated

- **Accurate cross-coroutine parenting** — replace the process-global current-root with
  thread/coroutine context propagation so a call parents to the span that actually launched it.
- **Deeper span nesting** — active-parent stack for tap → nav → http trees beyond the flat two-level.
- **Manual span API** — `startSpan()/endSpan()` (or a `withSpan {}` block) for developer-defined work
  units (e.g. a JSON parse, a DB read) inside a trace.
- **`tracestate` / baggage** propagation and **tail / deferred sampling** (W3C-strict `flags=00`
  propagation of unsampled traces).
- **Orphan background-call traces** — give background/no-interaction network calls their own root if
  backend wants them traced.
