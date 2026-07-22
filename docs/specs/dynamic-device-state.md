# Spec: dynamic device-state (freeze-on-fault)

Spec for map [#85 — Native headroom](https://github.com/NCG-Africa/edge_telemetry_android/issues/85),
ticket [#91](https://github.com/NCG-Africa/edge_telemetry_android/issues/91). Takes the **dynamic** delivery
rail from [#86](https://github.com/NCG-Africa/edge_telemetry_android/issues/86)
(`docs/specs/device-signals-delivery-model.md`) as fixed input. Ranked #4 in the execution backlog.

**Plan-only.** No merged code. Downstream execution takes this as the fixed key set.

## Delivery (fixed by #86): freeze onto fault only

A point-in-time snapshot read **synchronously at fault time** and merged into the fault event's attribute
bundle before enrichment. **No timer, no `device.state` event, no broadcast receivers, no listener
lifecycle** — one `readSnapshot()` helper called at the freeze sites, nothing else. Answers the one stated
demand: crash/ANR triage context ("was it hot / in power-save / in landscape when it froze?").

A continuous stream (periodic event / on-change receivers / attach-to-every-event) stays **deferred** (#86) —
it graduates only when a named consumer needs the time series.

### Fault sites (where the snapshot is read)

All three run **off** the dying/blocked main thread already, so an extra handful of point reads is free:

| Event | Read site | Thread |
|---|---|---|
| `app.crash` | `CrashReportingService.freezeFatalCrash()` (`services/CrashReportingService.kt:97`) | crashing thread, pre-death |
| `app.anr` | `CrashReportingService.freezeAnr()` (`services/CrashReportingService.kt:148`) | watchdog thread |
| `app.hang` | hang record path (`recordHangEventFn`, `TelemetryManager.kt:284`) | watchdog thread |

`app.hang` (#61) landed after #86 was written; it's the same watchdog fault with identical triage value, so
the bundle rides it too. This extends #86's literal "crash/anr" wording to its hang sibling — one helper,
three call sites.

**Must not throw.** Reads happen on the crashing thread. Each read is individually `try`-guarded; on failure
or unsupported API the key is simply **omitted** (never a sentinel like `-1`), and the fault event still
freezes. Absent key = "couldn't read", not "false".

## Keys (5 — the set #86 named)

Native types (`Int`/`Boolean`/`String`), not stringified — wire bag is `Map<String, Any?>`
(`core/models/TelemetryBatch.kt:16-23`), same rationale as [#90](https://github.com/NCG-Africa/edge_telemetry_android/issues/90).
Namespace `device.*` (subject = the device); present **only on fault events**, unlike the always-present
static `device.*` bundle — a per-key `always_present` distinction, not a namespace one.

| Key | Type | minSDK | Source | Notes |
|---|---|---|---|---|
| `device.battery_level` | Int (0–100) | 24 | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` | Omit if it returns `Integer.MIN_VALUE` (unsupported). |
| `device.battery_charging` | Boolean | 24 | `BatteryManager.isCharging()` | API 23; always available at floor 24. |
| `device.power_save` | Boolean | 24 | `PowerManager.isPowerSaveMode()` | API 21. |
| `device.thermal_status` | Int (0–6) | **29** | `PowerManager.getCurrentThermalStatus()` | Platform ordinal `THERMAL_STATUS_NONE`(0) … `SHUTDOWN`(6). Key **absent below 29** (same gate pattern as app-exit/thermal). String label derivable backend-side. |
| `device.orientation` | String | 24 | `context.resources.configuration.orientation` | `"portrait"` / `"landscape"` (moved here from #90). |

`BatteryManager`/`PowerManager` via `context.getSystemService(...)`; all reads are point reads safe on any
thread. No new manifest/runtime permission (scope fence).

## Signals considered and dropped

The ticket floated three more; none earn a fault-time slot:

- **`isDeviceIdleMode` (Doze, API 23)** — a faulting process is actively running in foreground, so this is
  near-always `false` at crash/ANR time. Correlates with `power_save` anyway. Dropped.
- **App standby bucket (`UsageStatsManager.getAppStandbyBucket`, API 28)** — describes how the system rations
  *background* scheduling by usage frequency; little crash-triage value, API-28 gate. Dropped → fog.
- **Thermal headroom (`getThermalHeadroom`, API 30)** — first call can return `NaN`, is rate-limited
  (<1/sec), and a single fault-time forecast float adds little over the categorical `thermal_status`.
  Dropped → fog. `thermal_status` is the keeper.

Each graduates from fog only if a named consumer asks (see map "Not yet specified").

## Already collected (not re-specced)

None of these five exist today. Crash/ANR events currently carry `device.*` **identity** only
(`DeviceInfoCollector`), no live device *state*. Purely additive.
