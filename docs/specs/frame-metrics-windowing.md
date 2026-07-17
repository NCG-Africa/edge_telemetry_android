# Android spec — frame-metric windowing & sampling

Resolves wayfinder ticket **#36** (audit #13). No hub contract pins a frame-metric shape (the
`edge_rum_spec` #1 map is silent on frame perf), so the emission model is decided **android-local**.
Evidence: `sdk-audit.yaml:133-144,309-321` + the file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

---

## Problem

`TelemetryFrameDropCollector` (`core/TelemetryFrameDropCollector.kt:39-69`) emits one `frame_drop`
event **per frame**, unsampled and unfiltered — even `severity="low"` fires (`:57-69`). At 60fps a
janky 10-minute session is ~36,000 events; the queue has no per-event dropping and no rate limit
(`sdk-audit.yaml:309-321`), so frame metrics are the SDK's single largest volume risk. The only
sampler (`SAMPLING_RATE=0.1`) lives in `LegacyPerformanceTracker` (`:199-211`), which the factory
never instantiates — dead code, so frame sampling is effectively **off**.

Two more defects on the active path:

1. **`target_fps` hardcoded to 60** (`:49`) → the 16.7ms budget and its 2×/3× severity tiers are
   wrong on 90/120Hz displays.
2. **No screen attribution** — the active path attaches no `screen.name` (only the dead legacy
   variant did, per `sdk-audit.yaml:140`), so a stuttering frame can't be tied to a screen.

## Decision

Replace per-frame emission with **windowed summaries**: at most one `frame.summary` event per
screen-segment (≤10s), emitted only when the window contained slow frames. All aggregation is
O(1)-memory counters updated inside the existing frame callback — no timer, no external wiring.

The old `frame_drop` event name and its per-frame severity tiers are **retired**. Nothing downstream
depends on the active-path name except the collector being rewritten.

---

### 1. Window boundary — hybrid, callback-driven

A window covers one screen segment and closes on whichever comes first:

- **screen change** — `NavigationStackTracker.getCurrentScreen()` (`navigation/NavigationStackTracker.kt:55`)
  differs from the screen captured at window start. This single source works for both Activity apps
  and single-Activity Compose apps (navigation pushes cover both).
- **10s time cap** — `elapsedRealtime - windowStart >= 10_000`.

Both checks run **inside the frame callback**, after the counters are updated. Frames arrive every
~16ms, so the checks are free and need no separate timer or navigation observer. An idle-but-visible
screen fires no callbacks → nothing accumulates → nothing to flush (and it would have 0 slow frames
anyway). The trailing partial window is flushed on `stop()` / app-background.

`screen.name` and `display.refresh_rate` are captured **at window start**.

### 2. Aggregation — jank-rate counters

Per window, maintained as plain counters/running-maxes (no retained per-frame list, no percentiles):

| Accumulator | Update per frame |
|---|---|
| `total_frames` | `+1` |
| `slow_frames` | `+1` if `total_ms > 16` |
| `frozen_frames` | `+1` if `total_ms > 700` |
| `max_total_duration_ms` | `max(prev, total_ms)` |
| `max_build_duration_ms` | `max(prev, build_ms)` |
| `max_raster_duration_ms` | `max(prev, raster_ms)` |

`slow_frame_rate = slow_frames / total_frames` computed at flush. `frozen_frames` is a subset of
`slow_frames` (700 > 16). Durations keep the existing derivation:
`total = TOTAL_DURATION`, `build = LAYOUT_MEASURE_DURATION`, `raster = DRAW_DURATION`
(`TelemetryFrameDropCollector.kt:41-47`), all ns → ms.

### 3. Thresholds — fixed, Android Vitals–aligned

- **slow** = `total_ms > 16`
- **frozen** = `total_ms > 700`

Fixed absolute thresholds map to human-perceptible jank and stay directly comparable to Google Play
Console metrics across devices. The device's actual refresh rate is **recorded, not used to move the
threshold**: `display.refresh_rate` read at window start (`Activity.display?.refreshRate` on API 30+,
else `windowManager.defaultDisplay.refreshRate`), so the backend can do refresh-aware analysis later
without the SDK guessing. The hardcoded `target_fps` and the 2×/3× `severity` tiers are **deleted**.

### 4. Emit floor — slow frames only

A window emits `frame.summary` **only if `slow_frames > 0`**. Perfectly-smooth windows are dropped
entirely. `total_frames` is still carried inside each emitted window, so per-emitted-screen jank rate
stays exact; the only thing lost is fleet-wide smooth-screen visibility, a weak signal not worth an
event per screen visit.

### 5. Sampling — none

100% of qualifying windows are sent. Windowing + the slow-only floor already collapse the ~36,000-event
session to ≤~60 window events (fewer if screens are smooth); sampling out 90% of *jank* windows would
cripple the signal exactly where it matters. The per-screen 10s cap is the only backstop. A
window-level sampling knob can be added later if a pathological app ever floods — not shipped now.

---

## Event shape — `frame.summary`

Rides the unified `telemetry_batch` envelope + common attrs (device/session/user/`sdk.*`) once #30
lands — already closed; see `docs/specs/unified-wire-envelope.md`. Payload attributes:

```
frame.total_frames            int      frames observed in the window
frame.slow_frames             int      total_ms > 16
frame.frozen_frames           int      total_ms > 700  (subset of slow)
frame.slow_frame_rate         float    slow_frames / total_frames
frame.max_total_duration_ms   float    worst single frame, total
frame.max_build_duration_ms   float    worst single frame, build (layout+measure)
frame.max_raster_duration_ms  float    worst single frame, raster (draw)
frame.window_duration_ms      float    wall-clock span of the window
display.refresh_rate          float    Hz at window start
screen.name                   string   screen the window covers
```

No per-window `severity` — the backend derives severity from `slow_frame_rate` / `frozen_frames`.

---

## Collector change — `TelemetryFrameDropCollector`

Rewrite the listener body (`:39-69`) from per-frame emit to accumulate-and-flush:

- Add a mutable window aggregator (the counters/maxes above + `windowStart: Long`,
  `windowScreen: String?`, `windowRefreshRate: Float`), guarded by the existing `@Synchronized`
  (frame callback thread vs. `stop()` on main thread).
- **On each frame:** compute `total/build/raster ms`; if no window is open, open one (capture
  `getCurrentScreen()`, refresh rate, `elapsedRealtime`); update counters/maxes; then
  `if (getCurrentScreen() != windowScreen || elapsed >= 10_000) flushWindow()` and open a fresh one.
- **`flushWindow()`:** if `slow_frames > 0`, `telemetryManager.recordEvent("frame.summary", attrs)`;
  always reset the aggregator. Still gated by `isFrameTrackingEnabled()`.
- **`stop()`** (`:91-107`) flushes the trailing window before clearing references.
- Delete `target_fps`/budget/`severity` computation.

`LegacyPerformanceTracker` stays dead; its `SAMPLING_RATE` is now irrelevant and can be swept in the
#37 dead-code pass.

---

## Test plan — golden JSON

Robolectric unit tests feeding the collector a synthetic frame sequence (bypassing the real
`FrameMetrics` via a small internal `onFrame(totalMs, buildMs, rasterMs)` seam):

1. **Counts & maxes** — feed N smooth (`≤16ms`) + M slow (`16<ms≤700`) + 1 frozen (`>700ms`) frames,
   force a flush, assert the emitted `frame.summary` JSON exactly matches a golden payload:
   `total_frames = N+M+1`, `slow_frames = M+1`, `frozen_frames = 1`,
   `slow_frame_rate = (M+1)/(N+M+1)`, and the three `max_*` equal the largest fed values.
2. **No-emit floor** — feed only smooth frames, force a flush, assert **no** event recorded.
3. **Screen-change split** — feed slow frames under screen A, flip `getCurrentScreen()` to B, feed
   more slow frames, assert **two** `frame.summary` events with `screen.name` A then B.
4. **10s cap** — advance the clock past 10s within one screen, assert the window flushes and a new
   one opens.
