# Android spec — ID format migration (D7)

Resolves wayfinder ticket **#34** (audit #9). Hub decision **D7** taken as given; this is the
android *how*. Independent of the envelope (#30) — ids are opaque strings on the wire. Evidence:
`sdk-audit.yaml:262-266, 335-338` + file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Given (hub D7)

> **IDs** — `<kind>_<epochMs>_<16hex>_<platform>` with crypto entropy; Android migrates format.
> (`edge_rum_spec` `SPEC_V1_DECISIONS.md`, D7.)

## Today

`IdGenerator.generateId()` = `"${System.currentTimeMillis()}_${8 random [a-z0-9]}"`, e.g.
`1736899200000_a3k9zq1m` — **same shape for device, session, and user** ids
(`core/ids/IdGenerator.kt:13-16,64-78`). `java.util.UUID` is imported but unused. Entropy is already
crypto (`SecureRandom`, `:16`); what's non-conformant is the **charset (36→hex), width (8→16), and the
missing `kind`/`platform` tokens**.

- **device.id** — persisted in `SharedPreferences("edge_telemetry_ids")` key `device_id`; emitted as
  `device.id` on every event (`device/DeviceInfoCollector.kt:30`). This is the identity join key downstream.
- **user.id** — persisted key `edge_rum_user_id`, generated on first access, never null; emitted as `user.id`.
- **session.id** — ephemeral, minted per session (`services/SessionService.kt:39,58`), never persisted.

## Decisions

### 1. Target format

`<kind>_<epochMs>_<16hex>_android`

- **kind** ∈ `device` | `session` | `user` (one token per getter, no shared `generateId()`).
- **epochMs** — `System.currentTimeMillis()`, unchanged.
- **16hex** — 16 lowercase hex chars (64 bits) from the existing `SecureRandom`. Replaces the 8-char
  base-36 string. `CHARS`/`RANDOM_LENGTH` deleted; use `Long.toHexString`-style fill or hex of 8 random bytes.
- **platform** — literal `"android"`, matching `sdk.platform` (D11 enum, locked by #30
  `docs/specs/unified-wire-envelope.md:47`). Hardcoded — this is an android-only AAR.

Example: `session_1736899200000_a3f5c9017be24d08_android`.

### 2. Migration — preserve persisted ids, new format only for newly-minted ids

**device.id and user.id already in `edge_telemetry_ids` are returned verbatim (legacy shape). The new
format applies only to ids minted from now on** (fresh installs; user.id on first-ever access; every
session.id, since sessions are never persisted).

**Why not regenerate:** device.id is the downstream identity join key; ids are **opaque strings** to the
collector (nothing parses `<kind>` or `<platform>` out of them — platform travels separately as
`sdk.platform`, D11). Rewriting a persisted device_id/user_id would fork every existing install into a
phantom "new device", orphaning its history — for zero wire benefit. So preserve.

Consequence, and it's fine: an upgraded install emits a **legacy** `device.id` next to a **new-format**
`session.id` in the same batch. They're independent opaque keys; no invariant couples their shapes.

### 3. No dual-write, no upgrade marker

No "legacy vs migrated" flag, no backfill pass. YAGNI — the SharedPreferences read already distinguishes
"present" (→ preserve) from "absent" (→ mint new). Prefs **storage keys** (`device_id`,
`edge_rum_user_id`) are unchanged; only the *value shape* of newly-minted ids changes.

## Changes

| File | Change |
|---|---|
| `core/ids/IdGenerator.kt:64-78` | `generateId()` → `generateId(kind)` = `"${kind}_${now}_${hex16()}_android"`; add `hex16()` (16 hex from `SecureRandom`); delete `CHARS`, `RANDOM_LENGTH`, `generateRandomString`. |
| `core/ids/IdGenerator.kt:37,49,57` | callers pass their kind: `generateId("device")`, `generateSessionId()`→`generateId("session")`, `getUserId()`→`generateId("user")`. |
| `core/ids/IdGenerator.kt:5` (import) | remove unused `java.util.UUID` if present (audit #9 dead-import). |
| `getOrGenerateDeviceId()` / `getUserId()` | **no change to the get-or-create logic** — persisted value still returned as-is; only the `?: run { … }` mint branch yields the new shape. |

No consumer changes: `DeviceInfoCollector`, `SessionService`, `UserProfileService`, `CrashReporter`,
`JsonEventTracker` all take the id as an opaque string.

## Test plan

- **Fresh install** — `device.id`, `user.id`, `session.id` each match `^(device|user|session)_\d+_[0-9a-f]{16}_android$`.
- **Upgraded install** — pre-seed `edge_telemetry_ids/device_id=1736899200000_a3k9zq1m` and
  `edge_rum_user_id=…`; assert `getDeviceId()` / `getUserId()` return those **legacy strings unchanged**.
- **Session always new** — on an upgraded install, `session.id` is still new-format (no persisted session to preserve).
- **Entropy** — 16 hex = 64 bits; adequate collision resistance for device/session/user id volume.

## Flag to hub

None. D7 says "Android migrates format"; the preserve-legacy-persisted-ids choice is an android storage
detail, invisible on the wire (opaque strings). No hub-contract gap surfaced.
