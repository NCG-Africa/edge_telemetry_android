# Edge Telemetry SDK 3.0 — Implementation Tasks

See plan.md for full architectural context. Follow phases in order.

## Phase 0 — Foundation
- [ ] Create multi-module Gradle structure per plan.md §3 (telemetry-core, telemetry-api, telemetry-okhttp, telemetry-compose, telemetry-room, telemetry-replay, telemetry-exporter-otlp, telemetry-exporter-edge, telemetry-gradle-plugin, telemetry-testing, telemetry-bom)
- [ ] Add OpenTelemetry SDK dependencies (api, sdk, exporter-otlp-http, semconv) to telemetry-core
- [ ] Create TracerProvider, MeterProvider, LoggerProvider wiring in telemetry-core
- [ ] Add binary-compatibility-validator plugin and baseline current v2.1.12 public API
- [ ] Create telemetry-bom module with version alignment
- [ ] Wire existing SessionService onto OTel session semantic conventions behind feature flag
- [ ] Wire existing CrashReporting to emit OTel span with recordException
- [ ] Build telemetry-exporter-edge adapter that converts OTel spans → existing Flask JSON payload
- [ ] Verify existing sample app builds and emits data to Flask backend unchanged

## Phase 1 — Gradle Plugin + HTTP Auto-Instrumentation
- [ ] Scaffold telemetry-gradle-plugin with AGP AsmClassVisitorFactory
- [ ] Implement OkHttpClient.Builder weaving to auto-inject EdgeOkHttpInterceptor
- [ ] Implement Retrofit.Builder.client() argument wrapping
- [ ] Implement HttpURLConnection call-site rewriting via ByteBuddy
- [ ] Implement Ktor HttpClient plugin installation via ASM
- [ ] Add R8 mapping upload Gradle task hooked to assemble<Variant>
- [ ] Add exporters { otlp(endpoint, headers) } DSL
- [ ] Implement OTLP/HTTP exporter in telemetry-exporter-otlp
- [ ] Write TestKit fixture tests for each weaving transform

## Phase 2 — Database + UI + Performance
- [ ] Weave Room @Dao generated _Impl classes with db.query spans
- [ ] Weave SupportSQLiteDatabase.query/execSQL call sites
- [ ] Weave SQLDelight generated QueriesImpl
- [ ] Weave ContentResolver.query calls
- [ ] Implement slow query detection (>100ms threshold)
- [ ] Weave View.setOnClickListener to emit ui.tap events
- [ ] Add Modifier.edgeTrack for Compose
- [ ] Implement ANR watchdog (5s main-thread ping timeout)
- [ ] Implement FrameMetricsAggregator + Choreographer jank metrics
- [ ] Implement app.startup span with cold/warm/hot classification

## Phase 3 — Replay + Logs + Redaction
- [ ] Create telemetry-replay module with event ring buffer (500 events, 5min retention)
- [ ] Define replay event schema (ts, type, target, data)
- [ ] Implement replay sampling (100% on crash, 10% on error, 0% otherwise)
- [ ] Implement Logcat capture pipeline (opt-in)
- [ ] Create RedactionPolicy DSL
- [ ] Add default PII patterns (email, phone, credit card, IBAN, JWT, bearer, AWS keys)
- [ ] Implement HTTP body/header capture with redaction and 32KB cap
- [ ] Add @EdgeNoCapture annotation support

## Phase 4 — Crash Parity + Release Health
- [ ] Refine CrashFingerprinter with normalized frames
- [ ] Stamp edge.build.id on all crashes matching uploaded mapping
- [ ] Implement release health metrics (crash_free_sessions, crash_free_users)
- [ ] Add CoroutineContext EdgeSpanElement for span propagation
- [ ] Weave ThreadPoolExecutor.submit/execute for thread propagation (opt-in)

## Phase 5 — Hardening & GA
- [ ] Add benchmark module with androidx.benchmark
- [ ] Enforce APK size budget (≤450KB) in CI
- [ ] Enforce cold start budget (≤25ms P50) in macrobenchmark
- [ ] Write docs/migration-2.x-to-3.0.md
- [ ] Write docs/plugin-reference.md
- [ ] Write docs/semconv-mapping.md
- [ ] Create Views-only sample app
- [ ] Create Compose-only sample app
- [ ] Create mixed Views+Compose sample app
- [ ] Tag and publish 3.0.0 to JitPack