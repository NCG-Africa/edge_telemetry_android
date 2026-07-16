# Android spec — unify network schema + URL sanitization

Resolves wayfinder ticket **#40** (audit #2 + #6). Hub decisions **D3** (dotted `http.*`
keys, ratified) and **D8** (strip all query by default, opt-in allowlist) are fixed input —
linked, not re-litigated. Rides the unified envelope (#30). Evidence:
`sdk-audit.yaml:76-99, 330-334` + file:line refs below.

Plan-only. This is the implementation spec; no code is changed on this branch.

## Problem

Two disjoint network events exist under two names with **zero key overlap**, and only the
unused one is legible server-side:

- **`http_request`** — the *wired* interceptor (`core/TelemetryInterceptor.kt:39-49`, returned
  by `createNetworkInterceptor()`, so this is what real traffic uses). Keys: `url`, `method`,
  `response_code`, `latency_ms`, `request_size_bytes`, `response_size_bytes`. The Processor
  reads `http.url` / `http.method` / `http.status_code` / `http.duration_ms` — **none of these
  keys match**, so all automatic HTTP is **dark server-side**.
- **`http.request`** — the manual `recordNetworkRequest()` public API
  (`core/services/EventTrackingService.kt:101-126`). Keys are already the `http.*` set the
  Processor reads, but the API is rarely used, so little real HTTP lands.

URL sanitization is inconsistent across the three paths:

| Path | URL handling |
|---|---|
| `TelemetryInterceptor` (wired) | strips query via `substringBefore('?')` — OK |
| `EdgeTelemetryInterceptor` (public, **unwired**) | sends **full URL incl. query** into `http.url` + network breadcrumbs (`core/http/EdgeTelemetryInterceptor.kt:28,98`) |
| `recordNetworkRequest()` | stores the **raw URL** unmodified (`EventTrackingService.kt:113`) |

No path/PII redaction anywhere.

## Fixed input (do not re-decide)

- **D3** — `http.*` ratified: `http.url` (sanitized per D8), `http.method`, `http.status_code`,
  `http.duration_ms`, `http.success`, `http.timestamp`, plus the optional pair
  `http.request_size` / `http.response_size` (integer bytes). **`http.success` = 2xx only (D3a)**;
  the Processor stores the flag verbatim. Android's interceptor migrates event name + keys; its
  duplicate public-API network path is removed.
- **D8** — strip all query strings by default; opt-in allowlist; applies to `http.url`, resource
  names, breadcrumbs.

## Decisions

### 1. One event, one key set — `http.request`

The single network event is **`http.request`** (two-part namespace, matching `app.crash`,
`screen.duration`, `navigation`, `frame.summary`). The wired interceptor migrates
`http_request` → `http.request` and re-keys onto the D3 set:

| Wire key | Source (interceptor) | Notes |
|---|---|---|
| `http.url` | `request.url`, **query stripped** | sanitized per §3 |
| `http.method` | `request.method` | |
| `http.status_code` | `response?.code ?: 0` | `0` on transport failure |
| `http.duration_ms` | `nanoTime` delta → ms | |
| `http.success` | `code in 200..299` | **2xx-only, D3a** — not `< 400` |
| `http.timestamp` | event time | **format/correctness owned by #41**; #40 does not set its own local-time value |
| `http.request_size` | `request.body?.contentLength()` | optional; **omitted** when `< 0` (§4) |
| `http.response_size` | `response.body?.contentLength()` | optional; **omitted** when `< 0` (§4) |

Attributes flow through the existing `recordEvent(name, attributes)` → `flattenAttributes`
path into the #30 envelope; `session.*` / `user.* `/ `sdk.*` enrichment is added there, not
here.

Renames vs today's wired interceptor: `url`→`http.url`, `method`→`http.method`,
`response_code`→`http.status_code`, `latency_ms`→`http.duration_ms`,
`request_size_bytes`→`http.request_size`, `response_size_bytes`→`http.response_size`; **add**
`http.success`.

### 2. Delete the manual `recordNetworkRequest()` path

Per D3 ("duplicate public-API network path is removed") and pre-1.0 API freedom. Both ends go:

- `TelemetryManager.recordNetworkRequest(...)` public method **and** its pre-init-queue branch
  (`core/TelemetryManager.kt:506, 517`).
- `EventTrackingService.recordNetworkRequest(...)` (`:101-126`).

The wired interceptor becomes the **single** network path. No caller inside the SDK uses the
manual method (verified: only the two `TelemetryManager` sites above). Apps on OkHttp+our
interceptor are unaffected; apps on a **non-OkHttp** client (HttpURLConnection, Cronet, Ktor-CIO)
lose manual HTTP capture — a fog item (§ Fog), graduated only if a real consumer needs it.

### 3. Strip all query by default; defer the allowlist

Every url-carrying path strips the full query string (`substringBefore('?')`, already correct in
the wired interceptor). The wire behaviour with **no** allowlist configured is identical to an
unconfigured allowlist, so this is fully D8-conformant. The **opt-in query-param allowlist**
mechanism (`httpQueryAllowlist` config + selective rebuild) is **not built now** — deferred to
fog, graduated when a consumer needs a specific non-sensitive param.

`EdgeTelemetryInterceptor` (unwired, leaks full URL + query into `http.url` and breadcrumbs) is
**deleted** here — this ticket owns the URL-leak fix that #37 deferred to #40. It is entirely
unreferenced (only its own `TAG` constant), so deletion is inert.

**No network breadcrumbs.** The surviving interceptor adds none today; #40 keeps it that way and
does not port `EdgeTelemetryInterceptor`'s breadcrumb trail. D8's "applies to breadcrumbs" clause
therefore has **no live android target** after this ticket. A sanitized network-breadcrumb trail
for crash triage is a fog item.

### 4. Failure + unknown-size edges

- **Transport failure** (`IOException`, no response): still emit `http.request`, with
  `http.status_code = 0` and `http.success = false`. This keeps timeouts / DNS failures visible.
  **No `http.error` field** — it is not in the D3 ratified set; failure is fully signalled by
  `success=false` + `status_code=0`. An `IOException` that propagates uncaught becomes an
  `app.crash` on the #39 path.
- **Unknown body length**: OkHttp's `contentLength()` returns `-1` for chunked/streamed bodies.
  Today's `?: 0` coerces that to `0`, which reads as an empty body. Instead, when
  `contentLength() < 0`, **omit** `http.request_size` / `http.response_size` entirely (true
  "optional pair" — backend column stays null, not a false `0`).

### 5. Out of scope — path/PII redaction

D8 ratifies **query** stripping only. URL **path** PII (`/accounts/12345/balance`) needs route
patterns the SDK cannot infer and is not in the hub contract. Path templating / redaction is
**out of scope** for #40 — a fog item, and **flagged to the hub** (`edge_rum_spec`) as a
cross-SDK gap.

## Failing test

`TelemetryInterceptorTest` — auto HTTP must produce the `http.*` key set with a stripped URL.
Fails today (`http_request` + `url` keys, dark server-side); passes after the migration.

```kotlin
@Test
fun `auto HTTP emits http_dot keys, 2xx success, no query string`() {
    val recorded = slot<Map<String, Any>>()
    every { telemetryManager.recordEvent("http.request", capture(recorded)) } returns null

    server.enqueue(MockResponse().setResponseCode(200))
    client.newCall(Request.Builder().url(server.url("/pay?token=SECRET&acct=123")).build()).execute()

    val a = recorded.captured
    assertEquals("http.request keys", true, a.containsKey("http.status_code"))
    assertEquals(200, a["http.status_code"])
    assertEquals(true, a["http.success"])
    assertFalse("no query on the wire", (a["http.url"] as String).contains("?"))
    assertFalse("no token leak", (a["http.url"] as String).contains("SECRET"))
}

@Test
fun `transport failure emits status 0, success false, still recorded`() {
    every { telemetryManager.recordEvent("http.request", capture(recorded)) } returns null
    // point client at a dead port so chain.proceed throws IOException
    runCatching { client.newCall(Request.Builder().url("http://127.0.0.1:1/x").build()).execute() }
    assertEquals(0, recorded.captured["http.status_code"])
    assertEquals(false, recorded.captured["http.success"])
    assertFalse(recorded.captured.containsKey("http.error"))
}
```

## Files touched (execution, downstream)

| File | Change |
|---|---|
| `core/TelemetryInterceptor.kt` | event name `http_request`→`http.request`; re-key to `http.*`; add `http.success = code in 200..299`; omit size keys when `contentLength() < 0` |
| `core/services/EventTrackingService.kt` | delete `recordNetworkRequest(...)` (`:101-126`) |
| `core/TelemetryManager.kt` | delete public `recordNetworkRequest(...)` + its pre-init-queue branch (`:506, 517`) |
| `core/http/EdgeTelemetryInterceptor.kt` | **delete file** (unwired, full-URL leak) |
| `core/TelemetryInterceptorTest.kt` | the tests above |

Depends on: #30 (envelope) for enrichment + serializer; coordinates with #41 (`http.timestamp`
value/format) and #37 (this ticket owns the `EdgeTelemetryInterceptor` deletion #37 deferred).
