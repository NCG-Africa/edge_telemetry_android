# Device-signals delivery model + prioritization

Decision doc for map [#85 — Native headroom](https://github.com/NCG-Africa/edge_telemetry_android/issues/85),
ticket [#86](https://github.com/NCG-Africa/edge_telemetry_android/issues/86). Cross-cutting input to the
device-context spec tickets [#90 (static)](https://github.com/NCG-Africa/edge_telemetry_android/issues/90)
and [#91 (dynamic)](https://github.com/NCG-Africa/edge_telemetry_android/issues/91).

**Plan-only.** No merged code. Downstream execution takes this as fixed.

## Delivery rails

Grounding: `TelemetryHttpClient.flatten()` stamps `device.*` onto **every** event from a single
`attrs.device` object minted once. Extending the static bundle is near-free; a per-event dynamic read is not.

### Static — mint once at init, ride `device.*` enrichment

ABI/CPU, screen density + launch-time size, locale/tz. Minted once at init, added as keys on the existing
`device.*` enrichment map (no envelope change, minSDK 24). A dumb mint-once map — no per-event re-read.

Accepted staleness: screen size/orientation and locale/tz reflect **app-launch** state and are not refreshed
mid-session. Re-reading per event to fix this pays per-event cost for a case almost no consumer notices, so
we don't.

### Dynamic — freeze onto fault only (rail D)

Battery %, charging, power-save, thermal, **and orientation** are snapshotted onto `app.crash` / `app.anr`
at fault time — nothing else. No timer, no `device.state` event type, no broadcast receivers, no listener
lifecycle. Answers the one stated demand: crash/ANR triage context ("was it hot / in power-save / in
landscape when it froze?").

A continuous stream (periodic event / on-change receivers / attach-to-every-event) is **deferred** — it
graduates only when a named consumer needs the time series. Until then it's pure ongoing cost for a signal
nobody asked for.

thermal = minSDK 29; battery/power/orientation = minSDK 24. Below-min signals are simply absent from the
freeze bundle.

## Priority order (downstream execution backlog)

Value vs cost/min-SDK:

1. **Static device-context** ([#90](https://github.com/NCG-Africa/edge_telemetry_android/issues/90)) —
   cheapest on the board, immediate crash-segmentation value, no gates. Momentum win.
2. **ApplicationExitInfo** ([#88](https://github.com/NCG-Africa/edge_telemetry_android/issues/88)) —
   biggest coverage gap closed (system ANR/OOM/kill reasons invisible today); read-once-at-next-launch is
   cheap. Gated minSDK 30 (no-op below).
3. **App-start timing** ([#87](https://github.com/NCG-Africa/edge_telemetry_android/issues/87)) — highest
   standalone user-facing metric value.
4. **Dynamic freeze-on-fault** ([#91](https://github.com/NCG-Africa/edge_telemetry_android/issues/91)) —
   good triage context, low cost, but only pays off at fault time.
5. **StrictMode** ([#89](https://github.com/NCG-Africa/edge_telemetry_android/issues/89)) — lowest
   value / highest noise (mostly debug-build territory). Kept in scope but ranked last; a demotion-to-
   out-of-scope candidate if execution bandwidth is tight.

Note: #87/#88/#89 carry their own delivery rails and are independent of the static/dynamic decision above;
they're ranked here only so execution has one ordered backlog.
