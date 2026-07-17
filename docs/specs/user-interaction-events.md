# Android spec: user-interaction events — `ui.interaction` (new datapoint)

**Ticket:** [#42](https://github.com/NCG-Africa/edge_telemetry_android/issues/42) · **Map:** [#29](https://github.com/NCG-Africa/edge_telemetry_android/issues/29) · **Depends on:** [#30 unified envelope](https://github.com/NCG-Africa/edge_telemetry_android/issues/30) (closed)

## Summary

A net-new datapoint. `enableUserInteractionEvents` already exists (default `true`, `TelemetryConfig.kt:17`) but **nothing emits interaction events today**. This spec designs automatic capture of taps / long-presses / swipes and their wire shape on the unified envelope.

One event: **`ui.interaction`**, discriminated by a `type` attribute. Rides the standard `recordEvent` → envelope path (`TelemetryManager.kt:472`), auto-enriched with `session.*` / `user.*` / `sdk.*`, batched (50). Plus a compact crash breadcrumb per interaction.

Plan-only: this is a spec, not merged code.

## Capture mechanism

**View world — automatic.** In `TelemetryActivityLifecycleObserver.onActivityResumed(activity)` (`:36`), wrap the Activity's `Window.Callback` (`window.callback`) with a delegating wrapper that forwards every call to the original and additionally feeds `dispatchTouchEvent`'s `MotionEvent`s to a `GestureDetector`. Restore the original callback in `onActivityPaused(activity)` (`:61`) to avoid leaks and double-wrapping on re-resume. No permission, no per-View listener injection — the only approach that is both automatic and permission-free (rejected: AccessibilityService = heavy + user-granted permission; consumer-attached listeners = not automatic).

`GestureDetector` collapses a raw `MotionEvent` stream into one callback per completed gesture:
- `onSingleTapConfirmed` → `type = "tap"`
- `onLongPress` → `type = "long_press"`
- `onFling` → `type = "swipe"` (direction `up`/`down`/`left`/`right` from velocity sign/magnitude; emitted **once** at fling end)

On a completed gesture, hit-test `activity.window.decorView` for the deepest View whose bounds contain the touch point and that carries an id (else its class) — that is the `target`.

**Compose world — coordinate-only in v1.** Compose renders its whole tree inside one `AndroidComposeView`, so View-tree hit-testing resolves to that single view, not the composable. v1 accepts this: Compose taps still emit `{type, x, y, screen}` with `target = "compose_surface"`. **No reflection into Compose internals** (`SemanticsOwner` is private API — a version-fragility landmine). Per-composable identity graduates via an opt-in `Modifier.trackTap("tag")` (see fog).

## Privacy — secure-surface suppression (bank-compliance requirement)

Raw tap coordinates over an in-app numeric keypad / PIN pad / password field are a **keystroke-inference risk**: a sequence of `(x, y)` taps can reconstruct an entered PIN or password. Same class of leak as full-URL query strings in [#40](https://github.com/NCG-Africa/edge_telemetry_android/issues/40). Fogging content-desc does **not** cover this — the coordinates alone leak it.

**Bound:** the system soft keyboard (IME) runs in a **separate window**, so the Activity's `Window.Callback` never sees IME taps. Exposure is **in-app surfaces only**.

**Rule (decided here):** suppress `ui.interaction` capture — emit nothing — when the hit-tested target is a secure input surface:
- an `EditText` (or subclass) whose `inputType` has a password variation (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`), **or**
- the view or its window carries `FLAG_SECURE`.

This is a single check in the hit-test path. It is a **hard default, not a config flag** (a compliance control should not be switchable off).

**Residual → flagged to the hub.** A *custom* in-app PIN pad built from plain `Button`/`View`s (no password `inputType`, no `FLAG_SECURE`) cannot be inferred by the SDK. Flagged to `edge_rum_spec` as a cross-SDK privacy rule (alongside #40's path-PII), same as content-desc: the SDK can only suppress surfaces it can *detect*. Consumers with custom keypads must set `FLAG_SECURE` on that screen (which they should anyway) to be covered.

## Event shape

`ui.interaction` attributes:

| key | example | notes |
|---|---|---|
| `ui.type` | `tap` \| `long_press` \| `swipe` | discriminator |
| `ui.target` | `checkout_button` | `getResourceEntryName(id)`; falls back to View class simple name (`MaterialButton`); `compose_surface` for Compose |
| `ui.x` | `540` | raw pixel x |
| `ui.y` | `1180` | raw pixel y |
| `ui.direction` | `left` | swipe only; omitted for tap/long_press |
| `ui.screen` | `CheckoutActivity` | from `NavigationStackTracker.getCurrentScreen()` (`:55`) — reuse the observer's existing tracker instance |

**Never captured:** entered text, field values, content-description (fogged). Timestamp + `session.*`/`user.*`/`sdk.*` are added by standard enrichment; no per-event timestamp field of its own beyond the envelope's.

## Delivery

- **Full event** via `telemetryManager.recordEvent("ui.interaction", attrs)` — standard envelope (#30) path, batched (50), no special rail.
- **Plus a compact breadcrumb** per interaction (`addBreadcrumb("tap checkout_button", category = "ui")`). Interactions are the highest-value crash-context trail ("what did the user tap before the crash"). The `BreadcrumbManager` 50-cap eviction is **correct here** — recent taps are what crash triage wants; evicting stale crumbs is the circular buffer's job.

## Volume / sampling

Gesture-completion **is** the dedup: `GestureDetector` emits once per gesture, never per raw `MotionEvent`, bounding volume to human tapping speed (~1–3/s, hundreds/session) — nothing like the per-frame flood #36 fought. **No sampling in v1.** Distinct consecutive taps are not deduped (they are real interactions).

## v1 scope boundary

**In:** `{tap, long_press, swipe}`, View-world automatic target identity, Compose coordinate-only, secure-surface suppression.

**Out (→ fog / hub):**
- opt-in `Modifier.trackTap("tag")` for per-composable Compose identity
- content-description as a target identifier (PII-sanitization burden)
- normalized (0–1) coordinates for cross-device heatmaps
- `scroll`/`drag` and `double-tap` gestures
- custom-keypad PII rule → flagged to `edge_rum_spec` hub

## Test plan

- Behavior: instrument an Activity, dispatch a synthetic tap on a view with a known id → assert one `ui.interaction` with `type=tap`, matching `ui.target`.
- Privacy: dispatch a tap on a password `EditText` and on a `FLAG_SECURE` view → assert **no** event emitted.
- Swipe: synthetic fling → `type=swipe` with correct `ui.direction`, exactly one event.
- Dedup: a drag (multiple move events) ending in a fling → exactly one `swipe` event, zero per-move events.
