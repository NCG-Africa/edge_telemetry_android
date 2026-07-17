# Android spec: timestamp correctness — true UTC everywhere

**Ticket:** [#41](https://github.com/NCG-Africa/edge_telemetry_android/issues/41) · **Map:** [#29](https://github.com/NCG-Africa/edge_telemetry_android/issues/29) · **Audit:** #5 · **Hub decision:** D6 (`edge_rum_spec` #1) · **Rides:** #30 envelope
**Status:** spec (plan-only, no merged code)

## Problem (audit #5, `sdk-audit.yaml:431-434`)

One payload carries three incompatible time representations:

1. **Fake UTC.** Every `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)` appends a literal `Z` but formats in the **device's default timezone** — no `setTimeZone(UTC)` exists anywhere in the module (verified: zero `setTimeZone`/`TimeZone`/`ZoneOffset` references). A device in UTC+3 emits `...T14:00:00Z` for an event that happened at 11:00 UTC. Server reads it as UTC → every non-UTC device is silently wrong by its offset.
2. **True UTC, different precision.** The crash path and a few others use `Instant.now().toString()` — real UTC, but variable sub-second precision (nanos when present), so it never matches the `SimpleDateFormat` shape.
3. **Raw epoch-ms ints.** Some attribute "timestamps" are `System.currentTimeMillis()` Longs, mixed into the same payload as the ISO strings above.

Two extra defects surfaced while speccing:

- **`SimpleDateFormat` is not thread-safe** and `TelemetryManager.dateFormat` (`TelemetryManager.kt:78`) is `public val`, read from IO/main/lifecycle threads (`TelemetryMemoryUsage`, emitters). Concurrent `format()` can produce garbage strings independent of the TZ bug.
- **`Instant.now()` is an API-26 landmine.** `minSdk = 24` and core-library desugaring is **disabled** (`build.gradle.kts:52,126-127` commented out, "we no longer use Java 8 time APIs"). `java.time.Instant.now()` does not exist on API 24/25 → `NoSuchMethodError` at runtime. Live sites `NavigationStackTracker.kt:21/35/50`, `Breadcrumb.kt:26`, and the crash path (#39) will crash on Android 7.0/7.1 — a crash reporter that crashes while reporting a crash. This rules out "just migrate to `java.time`" unless desugaring is re-enabled.

## Decisions

### D-1 — One representation: millisecond-precision ISO-8601, true UTC

Every point-in-time value on the wire — envelope `timestamp`, per-event `timestamp`, and every `*.timestamp` attribute — is the **same string**:

```
yyyy-MM-dd'T'HH:mm:ss.SSS'Z'   // UTC, e.g. 2026-07-16T09:41:00.123Z
```

- **Millisecond** precision (`.SSS`), fixed width — matches the #30 envelope example (`...:00.123Z`), deterministic to parse, drops `Instant`'s variable nanos.
- Satisfies D6 ("true UTC"). No epoch-ms ints on the wire for anything named a timestamp.

**Not a timestamp, stays numeric:** elapsed-duration fields (`*_ms` — `duration_ms`, `screen.duration_ms`, `http.duration_ms`, `latency_ms`) are spans, not clock points. They remain `Long`/`Double` millis. This decision only touches point-in-time values.

### D-2 — One shared, thread-safe formatter; delete `Instant.now()`

Add a single top-level helper (new file `core/TelemetryTime.kt`); every timestamp goes through it. No new dependency, no desugaring, no `java.time`.

```kotlin
object TelemetryTime {
    private val fmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }   // the fix the other 7 sites all miss
    }
    fun now(): String = isoOf(System.currentTimeMillis())
    fun isoOf(epochMs: Long): String = fmt.get()!!.format(Date(epochMs))
}
```

- `ThreadLocal` makes `SimpleDateFormat` safe without locks (stdlib only, ponytail rung 2/4).
- **Delete all 7 ad-hoc `SimpleDateFormat` fields** (`TelemetryManager.kt:78`, `BatchProcessingService.kt:37`, `CrashReportingService.kt:46`, `SessionService.kt:28`, `EventTrackingService.kt:12`, plus the inline `TrackComposeScreen.kt:52`) → call `TelemetryTime.now()`.
- **Delete all `Instant.now().toString()`** → `TelemetryTime.now()`. Kills the API-26 crash landmine at `NavigationStackTracker.kt:21/35/50`, `Breadcrumb.kt:26`, and the crash rail (#39). (`Instant` sites in already-dead code — `JsonEventTracker`, `FlutterCompatiblePayload` — are deleted by #37, not re-touched here.)
- Remove the now-unused `SimpleDateFormat`/`Instant`/`Date` imports the deletions strand.

### D-3 — Attribute timestamp policy: convert to the same ISO string

Point-in-time attributes standardize on D-1's ISO string via `TelemetryTime`:

| Site | Now | After |
|---|---|---|
| `TelemetryManager.kt:755` `initialization_timestamp` | `System.currentTimeMillis()` (Long) | `TelemetryTime.now()` |
| `MemoryTracker.kt:310` `memory.timestamp` | `System.currentTimeMillis()` (Long) | `TelemetryTime.now()` |
| `TelemetryMemoryUsage.kt:57/151/224` `memory.timestamp`/`storage.timestamp` | `dateFormat.format(Date())` (fake-UTC) | `TelemetryTime.now()` |

This also **fixes the `memory.timestamp` double-shape** (Long in `MemoryTracker` vs String in `TelemetryMemoryUsage`) — one emitter path, one type.
`TelemetryActivityLifecycleObserver.kt:77`'s epoch-ms `timestamp` rides the `screen_view` event **already retired by #35** — no action, just don't reintroduce it.

`MemoryEventInfo.timestamp` (`models/TelemetryBatch.kt:107`) is already `String` — model unchanged; the Long emitter was the mismatch.

### D-4 — Guard: no naive-local formatting can come back

Two cheap checks (ponytail: one runnable check per non-trivial decision):

1. **Behavior test** (`src/test`, JUnit, no framework): feed `TelemetryTime.isoOf(0L)` → assert exactly `"1970-01-01T00:00:00.000Z"`; feed a known epoch → assert the fixed UTC string. Fails if the TZ or pattern regresses. One `@Test`.
2. **Source guard test**: a JUnit test that greps `src/main` and **fails** if it finds `SimpleDateFormat(` outside `TelemetryTime.kt`, or any `Instant.now(`, or a `"…timestamp…" to System.currentTimeMillis()` attribute literal. Keeps the single-helper invariant enforced in CI (rides #31's `testDebugUnitTest` gate) without adding a detekt rule.

## Scope / coordination

- **In:** the 7 formatter sites, the `Instant.now()` live sites, the 3 attribute sites, `TelemetryTime.kt`, 2 tests.
- Coordinates with **#40**: `http.timestamp` takes its value from `TelemetryTime.now()` (#40 explicitly deferred format to #41). **#39** crash rail: `error.timestamp`/event `timestamp` use the helper. **#30** envelope: the top-level/event `timestamp` this helper feeds is the D6 field #30 specified.
- **No hub gap** — D6 is given; this is the android *how*.
- **Not chosen:** epoch-ms-everywhere (rejected — #30 already fixed the envelope on ISO string; aligning attributes to ISO removes the mix, epoch-ms would re-introduce it) · re-enabling core-library desugaring for `java.time` (rejected — adds a build dep + `desugar_jdk_libs` for zero benefit over `TelemetryTime`; the repo deliberately removed it).

## Out of scope → fog

- **Monotonic vs wall-clock skew** — device clocks can be wrong/adjusted; a server-side `received_at` or monotonic correction is a backend/hub concern, not an android format decision. Flag to hub if drift proves material.
