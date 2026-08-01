package com.androidtel.telemetry_library.core.trace

import com.androidtel.telemetry_library.core.ids.IdGenerator
import kotlinx.coroutines.asContextElement
import java.security.SecureRandom

/**
 * Distributed trace/span contract, v2 (issue #109 / spec `distributed-trace-span.md` v2).
 *
 * v1 stitched only the main-thread happy path off a single process-global root. v2 makes propagation
 * correct under concurrency (a [ThreadLocal] carrier projected into coroutines via [asElement]), safe
 * by default (the [traceHostAllowlist] gate — no `traceparent` leaks to a host you didn't enumerate),
 * interoperable (adopts an inbound APM `traceparent`), complete (no-action calls still get a trace),
 * and measurable (a per-request `traceparent.outcome` enum + a stable `rum.action.id` join key).
 *
 * The whole outcome ladder resolves in [onNetworkCall] — the interceptor is a thin adapter. Spans are
 * expressed as `trace.id`/`span.id`/`parent.span.id`/`rum.action.id` attrs on existing events; no new
 * event type, no span-start/-end emission.
 */
object TraceManager {

    data class TraceContext(
        val traceId: String,
        val spanId: String,
        val sampled: Boolean
    )

    /**
     * What the interceptor should do per request. [newHeader] non-null ⇒ set the outgoing `traceparent`
     * to it (inject/replace); null ⇒ leave the outgoing header untouched (adopt an inbound one, or
     * suppress ours off-allowlist). [attrs] merge onto the `http.request` event.
     */
    data class Decision(val newHeader: String?, val attrs: Map<String, Any>)

    /** `traceparent.outcome` values (Δ5). Authored once here; the payload validator reads [OUTCOMES]. */
    const val OUTCOME_INJECTED_ATTRIBUTED = "injected_attributed"
    const val OUTCOME_INJECTED_UNATTRIBUTED = "injected_unattributed"
    const val OUTCOME_ADOPTED = "adopted"
    const val OUTCOME_SKIPPED_OFF_ALLOWLIST = "skipped_off_allowlist"
    val OUTCOMES = setOf(
        OUTCOME_INJECTED_ATTRIBUTED, OUTCOME_INJECTED_UNATTRIBUTED,
        OUTCOME_ADOPTED, OUTCOME_SKIPPED_OFF_ALLOWLIST
    )

    // Δ1 — per-thread carrier, projected into coroutines via asContextElement so the interceptor's
    // plain get() stays correct after a Dispatchers.IO hop. Never a process-global root.
    private val carrier = ThreadLocal<TraceContext?>()
    private val secureRandom = SecureRandom()

    /** Head-based sampling rate, set from `TelemetryConfig.traceSampleRate` at init (fixed 1.0 in v2). */
    @Volatile
    var traceSampleRate: Double = 1.0

    /**
     * Δ2 — normalized (trim + lowercase) bare-host allowlist, injected once at init from
     * `TelemetryConfig.traceHostAllowlist`. Empty (the default) = inject on NO host.
     */
    @Volatile
    var traceHostAllowlist: Set<String> = emptySet()

    /** Carrier read site (interceptor/nav/interaction), correct on any thread. */
    fun current(): TraceContext? = carrier.get()

    /** Opt-in coroutine seam: projects [ctx] so `current()` survives a `Dispatchers.IO` hop. */
    fun asElement(ctx: TraceContext) = carrier.asContextElement(value = ctx)

    /**
     * #42 interaction observer (main thread). Always a fresh root: roll sampling, mint a trace + root
     * span, set the carrier. Returns the attrs to stamp on `ui.interaction` (`rum.action.id` == the
     * root span on a root), or null when unsampled.
     */
    fun onInteraction(): Map<String, Any>? {
        val sampled = secureRandom.nextDouble() < traceSampleRate
        if (!sampled) {
            carrier.set(null)
            return null
        }
        val ctx = TraceContext(IdGenerator.traceId(), IdGenerator.spanId(), true)
        carrier.set(ctx)
        return mapOf("trace.id" to ctx.traceId, "span.id" to ctx.spanId, "rum.action.id" to ctx.spanId)
    }

    /**
     * Screen tracker. Child of the current sampled root if one is active, else this nav IS the root.
     */
    fun onNavigation(): Map<String, Any>? {
        val root = current()?.takeIf { it.sampled } ?: return onInteraction()
        return childAttrs(root)
    }

    /**
     * #40 interceptor, per request. Resolves the full outcome ladder (Δ2 allowlist gate → Δ3 adoption
     * → Δ4 unattributed → Δ1 attributed) from the two request facts and the carrier. Reads the carrier;
     * never writes it. Returns the header action + event attrs, or null for the unsampled-context case
     * only (carrier present, `sampled == false`) — no trace, header untouched.
     */
    fun onNetworkCall(host: String, inboundTraceparent: String?): Decision? {
        // Δ2 — allowlist gate, outermost. Off-allowlist ⇒ stamp local ids, suppress the header.
        if (host.lowercase() !in traceHostAllowlist) {
            val root = current()?.takeIf { it.sampled }
            val attrs = if (root != null) childAttrs(root) else parentlessAttrs()
            attrs["traceparent.outcome"] = OUTCOME_SKIPPED_OFF_ALLOWLIST
            return Decision(newHeader = null, attrs = attrs)
        }

        // Δ3 — adopt a valid inbound traceparent: mirror foreign ids, leave the header untouched.
        parseInbound(inboundTraceparent)?.let { (foreignTrace, foreignParent) ->
            val attrs = mutableMapOf<String, Any>(
                "trace.id" to foreignTrace,
                "span.id" to foreignParent,
                "traceparent.outcome" to OUTCOME_ADOPTED
            )
            current()?.let { attrs["rum.action.id"] = it.spanId } // link to our action if one is active
            return Decision(newHeader = null, attrs = attrs)
        }

        // Δ4 / Δ1 — mint path (inbound absent or malformed → we set/replace the header).
        val root = current()
        return when {
            root == null -> { // Δ4 — unattributed: a single parentless span
                val attrs = parentlessAttrs()
                attrs["traceparent.outcome"] = OUTCOME_INJECTED_UNATTRIBUTED
                Decision("00-${attrs["trace.id"]}-${attrs["span.id"]}-01", attrs)
            }
            !root.sampled -> null // context present but unsampled → no trace, header untouched
            else -> { // Δ1 — attributed: child of the action root
                val attrs = childAttrs(root)
                attrs["traceparent.outcome"] = OUTCOME_INJECTED_ATTRIBUTED
                Decision("00-${root.traceId}-${attrs["span.id"]}-01", attrs)
            }
        }
    }

    /** ProcessLifecycleOwner `onStop`. Clears the main-thread carrier slot only, not a global null. */
    fun onBackground() {
        carrier.set(null)
    }

    /** The four trace attrs a child span carries: same trace, a fresh child span, parent + action root. */
    private fun childAttrs(root: TraceContext): MutableMap<String, Any> = mutableMapOf(
        "trace.id" to root.traceId,
        "span.id" to IdGenerator.spanId(),
        "parent.span.id" to root.spanId,
        "rum.action.id" to root.spanId
    )

    /** A fresh parentless span: `trace.id` + `span.id` only (no parent, no action link). */
    private fun parentlessAttrs(): MutableMap<String, Any> = mutableMapOf(
        "trace.id" to IdGenerator.traceId(),
        "span.id" to IdGenerator.spanId()
    )

    /**
     * Δ3 parse — W3C `version-traceid-parentid-flags`, split on `-`, positional read (extra fields
     * ignored). Returns `(traceId, parentId)` lowercased, or null if absent/malformed (version `ff`,
     * wrong length, all-zero, or non-hex) — the caller then runs the mint path and overwrites.
     */
    private fun parseInbound(header: String?): Pair<String, String>? {
        if (header == null) return null
        val parts = header.split("-")
        if (parts.size < 4) return null
        val version = parts[0]
        val traceId = parts[1]
        val parentId = parts[2]
        val flags = parts[3]
        if (version.length != 2 || !isHex(version) || version.lowercase() == "ff") return null
        if (traceId.length != 32 || !isHex(traceId) || isAllZero(traceId)) return null
        if (parentId.length != 16 || !isHex(parentId) || isAllZero(parentId)) return null
        if (flags.length != 2 || !isHex(flags)) return null
        return traceId.lowercase() to parentId.lowercase()
    }

    private fun isHex(s: String): Boolean = s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    private fun isAllZero(s: String): Boolean = s.all { it == '0' }
}
