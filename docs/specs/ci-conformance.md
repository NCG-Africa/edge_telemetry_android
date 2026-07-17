# Android spec: CI conformance — trigger, test/lint gates, toolchain pin

**Map ticket:** [#31](https://github.com/NCG-Africa/edge_telemetry_android/issues/31) · **Audit:** #4 (`sdk-audit.yaml:446-459`) · **Status:** plan-only (spec, not merged)

## Problem

`.github/workflows/gradle.yml` ("Build Library") triggers on branch `main`, but the active
default branch is `master`. **CI effectively never runs.** On top of the dead trigger it is
build-only (`./gradlew clean assembleRelease -x lint`) — no unit tests, lint explicitly skipped —
and there is no single pinned build JDK across GitHub Actions (JDK 17) and JitPack (mainline: none;
java8 branch: `openjdk11`). The java8 branch's `jitpack.yml` also hardcodes `-Pversion=1.2.3-java8`,
two patches behind that branch's own `sdkVersion = "1.2.5-java8"`.

## Evidence

| Fact | Source |
|---|---|
| Trigger is `main`, default branch is `master` | `.github/workflows/gradle.yml:4,8`; `git symbolic-ref` → master |
| `origin/main` is a stale orphan (`a20d9ab Update README.md`), not an ancestor of master | `git log origin/main` vs `origin/master` |
| Build-only, lint skipped | `.github/workflows/gradle.yml` last step: `assembleRelease -x lint` |
| 28 unit-test files exist but never run in CI | `telemetry_library/src/test/**/*.kt` |
| `lint { abortOnError = false }` — lint never fails the build | `telemetry_library/build.gradle.kts:68` |
| Compile target Java 11, but no Gradle **toolchain** declared (relies on launcher JDK) | `build.gradle.kts:48-56` |
| No `jitpack.yml` on master → JitPack picks an arbitrary default JDK | repo root (absent) |
| java8 `jitpack.yml` pins `-Pversion=1.2.3-java8`; branch `sdkVersion = "1.2.5-java8"` | `java8-legacy-1.2.5:jitpack.yml`; `:build.gradle.kts:13` |
| `detekt` + `metalava` plugins wired with baselines, also unrun in CI | `build.gradle.kts:7-8,15-24`; `detekt-baseline.xml`, `api/current.api` present |

## Decisions

### D-31.1 — Trigger: `master` only, drop `main`

Point push + PR triggers at `master`. **Drop `main` entirely** — it is a stale orphan (one
`Update README.md` commit, not on master's history), not a maintained release line. Keeping it in
the trigger list only re-creates the "which branch is live?" ambiguity that caused this bug.

- If the orphan README edit is worth keeping, cherry-pick it onto master (separate, trivial).
- **Deleting the `origin/main` branch** is recommended but is an ops action, out of this plan-only
  ticket. Retargeting the trigger fixes CI regardless of whether the branch is deleted.

### D-31.2 — Add unit-test + lint gates (lint needs a baseline to actually gate)

Single job runs, in order: `testDebugUnitTest`, `lintDebug`, `assembleRelease` (drop `-x lint`).

`lintDebug` alone is a **no-op** today because `build.gradle.kts:68` sets `abortOnError = false`.
To make it a real gate without a red-on-day-one avalanche of pre-existing warnings:

1. Generate a lint baseline once — `lint { baseline = file("lint-baseline.xml") }` + a baseline run —
   grandfathering current violations.
2. Set `abortOnError = true` so **new** violations fail CI; keep `warningsAsErrors = false`.

Both are one-line `build.gradle.kts` edits, executed downstream (this ticket only specifies them).
`testDebugUnitTest` needs no such guard — 28 test files already exist; if any currently fail, that
is a genuine red the gate is meant to catch.

> Publish stays out of CI — JitPack builds on tag, not on push. No artifact/publish step added.

### D-31.3 — Pin the toolchain: launcher JDK 17 everywhere, compile target 11 via Gradle toolchain

"One toolchain" is two distinct knobs that were being conflated:

- **Launcher JDK** (runs Gradle/AGP): pin to **17** everywhere. Actions already uses 17. Add a
  `jitpack.yml` on **master** pinning `openjdk17` so JitPack stops defaulting to an arbitrary JDK —
  this is the only mainline reproducibility gap. AGP 8.7 requires JDK 17 to run.
- **Compile target** (bytecode level): pin to **11** by declaring a Gradle Java toolchain in
  `build.gradle.kts`, replacing the loose `sourceCompatibility`/`targetCompatibility`/`jvmTarget`
  trio with one source of truth:
  ```kotlin
  kotlin { jvmToolchain(11) }   // or java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }
  ```
  This makes the 11 target independent of the 17 launcher, so "Actions JDK 17 vs compile 11" stops
  reading as drift — it's launcher vs target, both now explicit.

The **java8 backport is a separate product** (Java 8 bytecode) and legitimately keeps its own
launcher/target; it is not folded into the mainline pin. Its only defect is D-31.4.

### D-31.4 — java8 `jitpack.yml`: delete the `-Pversion` override, don't chase it

The `-Pversion=1.2.3-java8` flag is both **stale and redundant**: `build.gradle.kts` already sets
`version = sdkVersion` (`1.2.5-java8`). Hardcoding the version in `jitpack.yml` guarantees drift on
every release. **Delete `-Pversion` (and `-Pgroup`, also already set in the build)** so the artifact
coordinates follow the build's own `group`/`sdkVersion`. This removes the drift class permanently
rather than bumping 1.2.3→1.2.5 and re-drifting next release. Applies to branch `java8-legacy-1.2.5`
(and any live java8 branch); mainline needs no such edit.

## Target workflow (`.github/workflows/gradle.yml`)

```yaml
name: Build Library

on:
  push:
    branches: [master]
  pull_request:
    branches: [master]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17          # launcher; compile target is pinned to 11 by the Gradle toolchain
          cache: gradle
      - run: chmod +x gradlew
      - name: Test + lint + build
        run: ./gradlew clean testDebugUnitTest lintDebug assembleRelease --stacktrace
```

`detekt`/`metalava` are wired with baselines and also unrun in CI. Adding `detektMain` (baseline
exists → cheap) and a `metalavaCheckCompatibility` API-lock step is a reasonable follow-up, but is
**out of scope for #31** (audit #4 named tests + lint only). Flag as fog, don't gold-plate here.

## Downstream execution checklist (not done here — plan-only)

1. `gradle.yml`: retarget `main`→`master`, drop `-x lint`, add `testDebugUnitTest lintDebug`.
2. `build.gradle.kts`: add `kotlin { jvmToolchain(11) }`; add `lint { baseline = ...; abortOnError = true }` + generate `lint-baseline.xml`.
3. New `jitpack.yml` on master pinning `openjdk17`.
4. java8 branch `jitpack.yml`: remove `-Pversion`/`-Pgroup` overrides.
5. (Ops) delete stale `origin/main`, optional cherry-pick of its README edit.
