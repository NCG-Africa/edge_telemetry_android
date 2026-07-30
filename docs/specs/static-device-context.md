# Spec: static device-context bundle

Spec for map [#85 — Native headroom](https://github.com/NCG-Africa/edge_telemetry_android/issues/85),
ticket [#90](https://github.com/NCG-Africa/edge_telemetry_android/issues/90). Takes the **static** delivery
rail from [#86](https://github.com/NCG-Africa/edge_telemetry_android/issues/86)
(`docs/specs/device-signals-delivery-model.md`) as fixed input.

**Plan-only.** No merged code. Downstream execution takes this as the fixed key set.

## Delivery (fixed by #86)

Mint **once at init**, add as keys on the existing `device.*` enrichment map that
`TelemetryHttpClient.flatten()` already stamps on every event. No per-event re-read, no envelope change.
All keys permission-free, **minSDK 24** (no gates — every source is available at 24).

Accepted staleness: screen size, `dark_mode`, `locale`, and `timezone` reflect **app-launch** state and are
not refreshed mid-session. Re-reading per event to fix this pays per-event cost for a case almost no consumer
notices, so we don't.

## Keys (9 new)

Native types (`Boolean`/`Int`), not stringified — the wire bag is `Map<String, Any?>`
(`core/models/TelemetryBatch.kt:16-23`) and the backend indexes `true`/`1080` more usefully than the string
forms. The static mint path widens to `Map<String, Any?>` rather than reusing the all-`String`
`DeviceInfoCollector.collectDeviceInfo()` return.

### CPU / ABI

| Key | Type | Source |
|---|---|---|
| `device.cpu_abi` | String | `Build.SUPPORTED_ABIS[0]` — **primary ABI only** (e.g. `"arm64-v8a"`); carries arch + 32/64-bit in one string. Full list is noise. |
| `device.cpu_cores` | Int | `Runtime.getRuntime().availableProcessors()` |
| `device.low_ram` | Boolean | `ActivityManager.isLowRamDevice()` — cleanly separates the cheap-device cohort that dominates OOM/jank. |

### Screen

| Key | Type | Source |
|---|---|---|
| `device.screen_density` | Int | `DisplayMetrics.densityDpi` (e.g. `420`) — the DPI bucket. |
| `device.screen_width_px` | Int | **physical** width, real metrics (e.g. `1080`) |
| `device.screen_height_px` | Int | **physical** height, real metrics (e.g. `2340`) |
| `device.dark_mode` | Boolean | `Configuration.uiMode & UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES` |

Physical resolution (real metrics) over app-window size — the stable device fact, not a windowing artifact.
No dp-dimensions key: derivable as `px / (densityDpi / 160)`.

### Locale / timezone

| Key | Type | Source |
|---|---|---|
| `device.locale` | String | `Locale.getDefault().toLanguageTag()` (BCP-47, e.g. `"en-US"`) — one string carries language + region; splitting is derivable. |
| `device.timezone` | String | `TimeZone.getDefault().id` (IANA, e.g. `"Africa/Nairobi"`) — richer + stable vs. a DST-ambiguous UTC offset. |

## Explicitly excluded

- **Orientation** — owned by [#91](https://github.com/NCG-Africa/edge_telemetry_android/issues/91)
  (dynamic freeze-on-fault). A launch-time static value is near-worthless (orientation flips constantly) and
  would duplicate the useful freeze value. Not a static key.
- **Full ABI list** (`SUPPORTED_ABIS`) — primary is sufficient; full list is rarely segmented on.
- **dp-dimensions / density scaling-factor** — derivable from `px` + `densityDpi`.
- **Split `language`/`country`** — derivable from the BCP-47 tag.

## Already collected (not re-specced)

`DeviceInfoCollector.collectDeviceInfo()` already emits: `device.id`, `device.platform`,
`device.platform_version`, `device.model`, `device.manufacturer`, `device.brand`, `device.android_sdk`,
`device.android_release`, `device.fingerprint`, `device.hardware`, `device.product`. This spec is purely
additive to that set.
