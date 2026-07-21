package com.androidtel.telemetry_library.core.trace

import com.androidtel.telemetry_library.core.ids.IdGenerator
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Distributed trace/span contract (issue #59 / spec `distributed-trace-span.md`).
 *
 * The whole contract is this one process-global "current root" holder plus four call sites:
 * an interaction opens a root, a nav is its child (if recent) else a new root, each network call
 * is a child that injects a W3C `traceparent`, and backgrounding clears the root. Spans are
 * expressed as `trace.id`/`span.id`/`parent.span.id` attributes on existing events — no new event
 * type, no span-start/-end emission.
 *
 * ceiling: current root is a single process-global AtomicReference, not per-coroutine context, so a
 * call fired from a stale foreground screen can attach to the wrong root. Accurate parenting needs
 * coroutine/thread context propagation — deferred to fog. Fine for the tap→calls 95% case.
 */
object TraceManager {

    data class TraceContext(
        val traceId: String,
        val spanId: String,
        val sampled: Boolean,
        val startedAtMs: Long
    )

    private const val ROOT_LINK_WINDOW_MS = 1000L

    private val current = AtomicReference<TraceContext?>(null)
    private val secureRandom = SecureRandom()

    /** Head-based sampling rate, set from `TelemetryConfig.traceSampleRate` at init. */
    @Volatile
    var traceSampleRate: Double = 1.0

    /**
     * #42 interaction observer. Always a fresh root: roll sampling, mint a trace + root span,
     * replace the current root. Returns the two attrs to stamp on `ui.interaction` (no
     * `parent.span.id` on a root), or null when the trace is not sampled.
     */
    fun onInteraction(nowMs: Long): Map<String, Any>? {
        val sampled = secureRandom.nextDouble() < traceSampleRate
        val ctx = TraceContext(IdGenerator.traceId(), IdGenerator.spanId(), sampled, nowMs)
        current.set(if (sampled) ctx else null)
        return if (sampled) mapOf("trace.id" to ctx.traceId, "span.id" to ctx.spanId) else null
    }

    /**
     * Screen tracker. Child of the current root if it opened within [ROOT_LINK_WINDOW_MS]
     * (tap→navigate), else this nav opens its own new root (cold start, deep link, programmatic).
     */
    fun onNavigation(nowMs: Long): Map<String, Any>? {
        val root = current.get()
        if (root != null && root.sampled && nowMs - root.startedAtMs <= ROOT_LINK_WINDOW_MS) {
            return childAttrs(root)
        }
        return onInteraction(nowMs) // no recent root → this nav IS the root
    }

    /**
     * #40 interceptor, per request. Mint a child span under the current sampled root and return
     * `(traceparentHeader, eventAttrs)`, or null when there is no sampled root (e.g. a background
     * call after [onBackground]) — such a call carries no header and no trace attrs.
     */
    fun onNetworkCall(): Pair<String, Map<String, Any>>? {
        val root = current.get()?.takeIf { it.sampled } ?: return null
        val attrs = childAttrs(root)
        val header = "00-${root.traceId}-${attrs["span.id"]}-01"
        return header to attrs
    }

    /** Mint a fresh child span under [root]: the three trace attrs a child event carries. */
    private fun childAttrs(root: TraceContext): Map<String, Any> = mapOf(
        "trace.id" to root.traceId,
        "span.id" to IdGenerator.spanId(),
        "parent.span.id" to root.spanId
    )

    /** ProcessLifecycleOwner `onStop` (already wired). Clears the root so a background sync does not attach to a stale tap. */
    fun onBackground() {
        current.set(null)
    }
}
