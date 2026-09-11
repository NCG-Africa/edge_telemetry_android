package com.androidtel.telemetry_library.core.trace

/**
 * Δ6 — the trace context captured at `newCall()`, carried on the request.
 *
 * A wrapper rather than the bare [TraceContext] because OkHttp's `tag(Class, T)` cannot store null:
 * "tag present, holding null" (genuinely no action was open) and "no tag at all" (`instrument()` was
 * never wired) are different facts, and the six-value outcome enum reports them differently.
 */
internal class TraceTag(val ctx: TraceContext?)
