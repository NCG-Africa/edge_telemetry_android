# Migration Guide: v2.x to v4.0.0

> ⚠️ **Install the EdgeTelemetry backend release (deployment, analytics, processor, collector) before
> shipping any 4.0 build.** 4.0 sends identity once per batch, not per event; an older backend loses
> it. "4.0 SDK + any key on an old backend" is unsupported.

There is no 3.x: the SDK version now tracks the shared batch contract, which starts at `4.0.0`.
Full detail in the [CHANGELOG](../CHANGELOG.md#400---unreleased).

## Steps

1. Confirm the backend release is installed on the collector your app points at.
2. Bump the dependency:

   ```kotlin
   implementation("com.github.NCG-Africa:edge_telemetry_android:4.0.0")
   ```

3. No code changes are required. Optionally move `endpoint` to the canonical route
   `https://<host>/telemetry`; `/collector/telemetry` still works as a collector alias. The URL is
   used exactly as given — the SDK appends nothing.

## What changes on the wire

- **Envelope**: closed at 8 keys with `contract_version: "4.0.0"`, root `device_id`, `sdk` and `app`
  objects ([schema](EVENT_SCHEMA_REFERENCE.md#batch-envelope-contract-400)). Events no longer carry
  `app.*`, `sdk.*` or `device.id`; dashboards or pipelines that read those per-event keys must read
  the envelope (the backend release does this for you).
- **Unknown app version/build** is omitted instead of `"unknown"`.
- **Envelope `timestamp`** is the send time, also for replayed batches.

## What changes in delivery

- **429** is now retried (honouring `Retry-After` up to 30 s) and then queued offline, like 5xx.
- **Any other 4xx** (400, 401, 403, 413…) is dropped: not retried, not queued, and it no longer
  blocks the offline queue behind it. A revoked key therefore loses the batches sent with it.
- Batches stored offline by 2.x replay as 4.0.0 envelopes automatically.
