# Edge Telemetry Android

Android telemetry SDK (AAR) that auto-instruments consumer apps — crashes, screens, network, sessions — and ships events to the Edge collector.

## Language

### Wire

**Event**:
A single telemetry record (crash, screen view, network request, metric, custom) with a type, timestamp, and attributes.
_Avoid_: Log, message, signal

**Batch**:
A group of events sent to the collector in one HTTP POST (default cap 50, flush every 30s).
_Avoid_: Bundle, chunk

**Envelope**:
The wire wrapper around one batch, closed at 8 keys (contract 4.0.0): it carries the batch's identity — `device_id`, `sdk`, `app` — once, so events never repeat it. Session and user context stay on each event.
_Avoid_: Payload, packet

**Contract version**:
The semver of the envelope shape shared by all SDKs (`contract_version`, currently `4.0.0`); independent of the SDK's own version.

**Collector**:
The backend endpoint that receives batches.
_Avoid_: Server, backend API

### Sessions & screens

**Session**:
A continuous period of app use, rotated after timeout (default 30min) or on explicit restart.

**Screen**:
A user-visible destination — an Activity or a Compose navigation route.
_Avoid_: Page, view

**Breadcrumb**:
A short, timestamped note of what happened before an error (circular buffer, max 50).
_Avoid_: Trail, log entry

### Errors & exits

**Crash**:
An uncaught exception that terminates the process; captured with breadcrumbs and stack trace, persisted for next-launch delivery.
_Avoid_: Fatal error, exception (use only for the Throwable itself)

**Fingerprint**:
A stable hash grouping crashes with the same root cause.
_Avoid_: Signature, group key

**ANR**:
The main thread blocked past the 5s system threshold, detected by the watchdog.

**Hang**:
A sub-5s main-thread stall — degraded responsiveness that is not yet an ANR.
_Avoid_: Freeze, jank (jank is frame-level)

**App exit**:
A process death reason harvested from `ApplicationExitInfo` on next launch (`app.exit` event).

### Tracing

**Trace**:
The end-to-end story of one flow (e.g. launch-to-first-request), identified by a W3C trace ID.

**Span**:
One timed operation inside a trace, attached to events rather than emitted standalone.

**Trace host allowlist**:
The opt-in list of hosts that receive `traceparent` headers; empty means inject on none.

### Buffering & delivery

**Pre-init queue**:
Calls buffered before `initialize()` completes (max 50), replayed once ready.

**Offline storage**:
SharedPreferences persistence of unsent envelopes (200-envelope cap) for retry.

**Eviction**:
Dropping the oldest buffered items when a bounded buffer overflows, counted and reported.
_Avoid_: Overflow, discard

**Retryable failure**:
A send that failed with 429, 5xx, another non-4xx code or a network error; retried, then kept in
offline storage for replay.

**Rejection**:
A send the collector refused with any 4xx but 429; the batch is dropped, never stored or replayed.
_Avoid_: Client error (429 is a 4xx but is retryable)
