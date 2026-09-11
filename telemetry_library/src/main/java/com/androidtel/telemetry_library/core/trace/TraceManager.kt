package com.androidtel.telemetry_library.core.trace

import android.os.SystemClock
import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryTime
import com.androidtel.telemetry_library.core.ids.IdGenerator
import kotlinx.coroutines.asContextElement
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A single trace root (or the context a child span hangs off).
 *
 * Not a `data class`: it carries mutable atomics, so generated `equals`/`copy` would be misleading.
 * [lastActivity] is an [AtomicLong] because the Δ7 completion-extension is written from OkHttp's
 * dispatcher thread while [current] reads it from main.
 */
class TraceContext(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean,
    /** Δ13 — `launch` | `interaction` | `navigation` | `request`. Denormalized onto every child. */
    val rootType: String,
    /** Δ10 — `span.start_time` source; the MINT instant for a tap root, the fork instant for launch. */
    val startTimeEpochMs: Long,
    openedAt: Long
) {
    /** Δ7 — the 10 s cap clock. Reset (not restarted from fork) when the launch exemption ends. */
    @Volatile
    var openedAt: Long = openedAt
        internal set

    /** Δ7 — extended on child-span start AND on request completion, from any thread. */
    val lastActivity = AtomicLong(openedAt)

    /** Δ8 — launch root only, until the first Activity resume. Exempts the idle window, not the cap. */
    val idleExempt = AtomicBoolean(false)

    /** Δ12 — resolved at mint from the naming chain; renamed by `trackUserInteraction` (Δ12a). */
    @Volatile
    var name: String? = null

    fun isLive(now: Long): Boolean =
        (idleExempt.get() || now - lastActivity.get() <= TraceManager.IDLE_WINDOW_MS) &&
            now - openedAt <= TraceManager.AGE_CAP_MS

    /** Δ7 extension — child span start OR request completion. */
    fun touch(now: Long) {
        lastActivity.set(now)
    }
}

/**
 * Distributed trace/span contract, v3 — launch-to-request
 * (issue #120 / spec `docs/specs/distributed-trace-span.md` v3).
 *
 * v2 made propagation correct under concurrency, safe by default, interoperable and measurable — and
 * then never reached the traffic that matters. v3 closes the four gaps that made it inert:
 *
 *  - **Δ6** capture moves to `newCall()` via a request tag ([TraceTag]/[TracingCallFactory]), because
 *    OkHttp runs interceptors on its dispatcher pool — so every `suspend`/`enqueue` call read a null
 *    [ThreadLocal] carrier and came out unattributed;
 *  - **Δ7** roots expire (2 s idle / 10 s cap on an injected [clock]), extended on child start and on
 *    request completion, so a background poll can't join a tap from ten minutes ago;
 *  - **Δ8** app launch is a root, opened at init and idle-exempt until the first resume, so startup
 *    requests have an action;
 *  - **Δ11** a process-global [lastRoot] annotates terminal events only — never a parenting source
 *    (that distinction is what keeps T1 #102 dead);
 *  - **Δ12/Δ13** roots carry a name and a [TraceContext.rootType], and the outcome enum grows to six.
 *
 * Spans remain attributes on existing events: no span-start/-end emission, no new event type.
 */
object TraceManager {

    /**
     * What the interceptor should do per request. [newHeader] non-null ⇒ set the outgoing `traceparent`
     * to it (inject/replace); null ⇒ leave the outgoing header untouched (adopt an inbound one, or
     * suppress ours off-allowlist). [attrs] merge onto the `http.request` event. [root] is the context
     * the interceptor's `finally` extends on completion (Δ7) — null when no action was live.
     */
    data class Decision(
        val newHeader: String?,
        val attrs: Map<String, Any>,
        val root: TraceContext?
    )

    /** `traceparent.outcome` values (Δ13). Authored once here; the payload validator reads [OUTCOMES]. */
    const val OUTCOME_INJECTED_ATTRIBUTED = "injected_attributed"
    const val OUTCOME_INJECTED_UNATTRIBUTED = "injected_unattributed"
    const val OUTCOME_INJECTED_UNWIRED = "injected_unwired"
    const val OUTCOME_INJECTED_EXPIRED = "injected_expired"
    const val OUTCOME_ADOPTED = "adopted"
    const val OUTCOME_SKIPPED_OFF_ALLOWLIST = "skipped_off_allowlist"
    val OUTCOMES = setOf(
        OUTCOME_SKIPPED_OFF_ALLOWLIST, OUTCOME_ADOPTED, OUTCOME_INJECTED_ATTRIBUTED,
        OUTCOME_INJECTED_UNWIRED, OUTCOME_INJECTED_EXPIRED, OUTCOME_INJECTED_UNATTRIBUTED
    )

    /** Δ13 — `trace.root_type` values. */
    const val ROOT_LAUNCH = "launch"
    const val ROOT_INTERACTION = "interaction"
    const val ROOT_NAVIGATION = "navigation"
    const val ROOT_REQUEST = "request"
    val ROOT_TYPES = setOf(ROOT_LAUNCH, ROOT_INTERACTION, ROOT_NAVIGATION, ROOT_REQUEST)

    /** Δ7 — internal constants, not config: the same "promote only if a consumer needs it" line
     * `AppStartTracker` draws around its 60 s cap and importance guard. */
    internal const val IDLE_WINDOW_MS = 2_000L
    internal const val AGE_CAP_MS = 10_000L

    /** Δ8 — `RunningAppProcessInfo.IMPORTANCE_VISIBLE`; worse (higher) means background-forked. */
    private const val IMPORTANCE_VISIBLE = 200

    // Δ1 — per-thread carrier, projected into coroutines via asContextElement so the newCall() capture
    // stays correct after a Dispatchers.IO hop. Never a process-global root.
    private val carrier = ThreadLocal<TraceContext?>()
    private val secureRandom = SecureRandom()

    /**
     * Δ11 — the last root opened, in ANY thread. Read by the crash and hang emitters and nowhere else.
     * It annotates; it does not propagate. Reading this from a parenting path re-opens T1 #102.
     */
    @Volatile
    private var lastRoot: TraceContext? = null

    /**
     * Δ8 — the launch root, kept for one reader: the `app.start` emit, which fires at the first resume
     * and must carry the launch ids even if the root has since aged out. Not a parenting source.
     */
    @Volatile
    private var launchRoot: TraceContext? = null

    /** Δ7 seam — injected like `AppStartTracker`'s (`AppStartTracker.kt:27`). Monotonic, survives sleep. */
    @Volatile
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Δ10 seam — wall clock, for `span.start_time` only. Never used for durations or lifetime. */
    @Volatile
    var epochClock: () -> Long = { System.currentTimeMillis() }

    /** Head-based sampling rate, set from `TelemetryConfig.traceSampleRate` at init (fixed 1.0 in v3). */
    @Volatile
    var traceSampleRate: Double = 1.0

    /**
     * Δ2/Δ9 — normalized (trim + lowercase) host allowlist, injected once at init from
     * `TelemetryConfig.traceHostAllowlist`. Exact entries match a host outright; a leading-dot entry
     * (`.example.com`) is a true suffix match. Empty (the default) = inject on NO host.
     */
    @Volatile
    var traceHostAllowlist: Set<String> = emptySet()

    /** Δ6 — one-time "you never wired instrument()" warning, so the migration signal isn't spam. */
    private val unwiredWarned = AtomicBoolean(false)

    /** Carrier read site, with Δ7 lazy expiry: discovering expiry clears the slot, so it costs nothing. */
    fun current(): TraceContext? {
        val c = carrier.get() ?: return null
        if (c.isLive(clock())) return c
        carrier.set(null)
        return null
    }

    /** Opt-in coroutine seam: projects [ctx] so `newCall()` still sees it after a `Dispatchers.IO` hop. */
    fun asElement(ctx: TraceContext) = carrier.asContextElement(value = ctx)

    // --- Root minting ------------------------------------------------------

    /** Mint a root, set the carrier, and record it as the Δ11 last action. */
    private fun open(rootType: String, startEpochMs: Long, openedAt: Long): TraceContext {
        val ctx = TraceContext(
            traceId = IdGenerator.traceId(),
            spanId = IdGenerator.spanId(),
            sampled = true,
            rootType = rootType,
            startTimeEpochMs = startEpochMs,
            openedAt = openedAt
        )
        carrier.set(ctx)
        lastRoot = ctx
        return ctx
    }

    /**
     * Δ8 — the launch root, opened from `initialize()`. [startElapsedRealtimeMs] is
     * `Process.getStartElapsedRealtime()`, which shares a timebase with [clock], so the fork instant
     * converts to epoch with no cross-clock arithmetic. A background-forked process
     * ([importanceAtInit] worse than `IMPORTANCE_VISIBLE`) opens nothing — the same guard that already
     * drops the bogus `app.start` sample.
     */
    fun onLaunch(startElapsedRealtimeMs: Long, importanceAtInit: Int): TraceContext? {
        if (importanceAtInit > IMPORTANCE_VISIBLE) return null
        val now = clock()
        val forkEpochMs = epochClock() - (now - startElapsedRealtimeMs)
        val ctx = open(ROOT_LAUNCH, forkEpochMs, now)
        // A cold start is routinely 1–5 s and may make no network call at all, so the idle window would
        // reap the launch root before app.start could even carry its own ids. The 10 s cap still applies.
        ctx.idleExempt.set(true)
        launchRoot = ctx
        return ctx
    }

    /**
     * Δ8/Δ10 — the launch root's span attrs for the `app.start` event, whose `span.start_time` is the
     * process **fork** instant. Returns null when no launch root was opened (background-forked start).
     *
     * Deliberately not liveness-gated: `app.start` fires at the first resume and describes the launch
     * whether or not the root has aged out by then. Roots carry no `span.duration_ms` — and note that
     * `app.start.duration_ms` (fork→first-resume) is a different measure that stays untouched.
     */
    fun launchRootAttrs(): Map<String, Any>? = launchRoot?.let { rootAttrs(it) }

    /**
     * Δ8 — the first Activity resume ends the *exemption*, not the root: both clocks reset and normal
     * Δ7 rules take over, so the launch root decays naturally instead of being closed here. Closing it
     * would leave the first navigation minting its own root, stopping the launch trace short of the
     * first screen.
     */
    fun onFirstResume() {
        val ctx = carrier.get() ?: return
        if (ctx.rootType != ROOT_LAUNCH) return
        val now = clock()
        ctx.idleExempt.set(false)
        ctx.openedAt = now
        ctx.touch(now)
    }

    /**
     * Δ12c — mint at `ACTION_UP` (`onSingleTapUp`), NOT at tap-confirm: Compose's `clickable` fires on
     * `ACTION_UP`, so a root minted ~300 ms later at `onSingleTapConfirmed` would miss the very request
     * the tap caused. [name] comes from the Δ12b naming chain and can be renamed later by Δ12a.
     */
    fun onInteractionStart(name: String?): TraceContext? {
        val sampled = secureRandom.nextDouble() < traceSampleRate
        if (!sampled) {
            carrier.set(null)
            return null
        }
        val ctx = open(ROOT_INTERACTION, epochClock(), clock())
        ctx.name = name
        return ctx
    }

    /**
     * Δ12c — emit-time attrs for `ui.interaction`, ~300 ms after the mint. Does NOT mint: a tap that
     * opened no root (unsampled, or suppressed on a secure surface) emits no trace attrs at all.
     */
    fun onInteractionEmit(): Map<String, Any>? {
        val root = current()?.takeIf { it.sampled && it.rootType == ROOT_INTERACTION } ?: return null
        return rootAttrs(root)
    }

    /** Δ12a — `trackUserInteraction()` renames the root the window callback already opened. */
    fun nameCurrentRoot(name: String) {
        current()?.name = name
    }

    /** The name resolved at mint, for the emitter to stamp as `ui.target`. */
    fun currentRootName(): String? = current()?.name

    /**
     * Screen tracker. Child of the live sampled root if one is active (and extends it), else this nav
     * IS the root (`trace.root_type = navigation`).
     */
    fun onNavigation(): Map<String, Any>? {
        val root = current()?.takeIf { it.sampled }
            ?: return rootAttrs(open(ROOT_NAVIGATION, epochClock(), clock()))
        root.touch(clock())            // Δ7 — a child starting extends the root
        return childAttrs(root).also { it["span.start_time"] = TelemetryTime.isoOf(epochClock()) }
    }

    // --- The per-request outcome ladder ------------------------------------

    /**
     * Δ6 — the interceptor hands over three facts: the host, any inbound `traceparent`, and the Δ6
     * request tag stamped at `newCall()`. A null [tag] means the `Call.Factory` was never wired, which
     * is a different fact from "tag present, holding null" (genuinely no action) — hence the six-value
     * enum. Returns null only for the unsampled-context case (no trace, header untouched).
     *
     * Reads the carrier and the tag; never writes the carrier.
     */
    internal fun onNetworkCall(host: String, inboundTraceparent: String?, tag: TraceTag? = null): Decision? {
        val now = clock()

        // Δ2/Δ9 — allowlist gate, outermost. Off-allowlist ⇒ stamp local ids, suppress the header.
        if (!isAllowed(host)) {
            val root = liveRoot(tag, now)
            val attrs = if (root != null) childAttrs(root) else parentlessAttrs()
            attrs["traceparent.outcome"] = OUTCOME_SKIPPED_OFF_ALLOWLIST
            return Decision(newHeader = null, attrs = attrs, root = root)
        }

        // Δ3 — adopt a valid inbound traceparent: mirror foreign ids, leave the header untouched.
        parseInbound(inboundTraceparent)?.let { (foreignTrace, foreignParent) ->
            val root = liveRoot(tag, now)
            val attrs = mutableMapOf<String, Any>(
                "trace.id" to foreignTrace,
                "span.id" to foreignParent,
                "traceparent.outcome" to OUTCOME_ADOPTED
            )
            root?.let {                       // link to our action if one is live
                attrs["rum.action.id"] = it.spanId
                attrs["trace.root_type"] = it.rootType
            }
            return Decision(newHeader = null, attrs = attrs, root = root)
        }

        // Δ6/Δ7/Δ4/Δ1 — mint path (inbound absent or malformed → we set/replace the header).
        if (tag == null) {
            // No tag at all: either instrument() was never wired, or this is the deprecated
            // createNetworkInterceptor() path on a synchronous main-thread call, where the carrier is
            // still readable and attribution is still correct.
            val root = current()?.takeIf { it.sampled }
            if (root == null) {
                warnUnwiredOnce()
                val attrs = parentlessAttrs(ROOT_REQUEST)
                attrs["traceparent.outcome"] = OUTCOME_INJECTED_UNWIRED
                return Decision(header(attrs), attrs, null)
            }
            root.touch(now)
            val attrs = childAttrs(root)
            attrs["traceparent.outcome"] = OUTCOME_INJECTED_ATTRIBUTED
            return Decision("00-${root.traceId}-${attrs["span.id"]}-01", attrs, root)
        }

        val ctx = tag.ctx
        return when {
            ctx == null -> {                              // Δ4 — genuinely no action open at newCall()
                val attrs = parentlessAttrs(ROOT_REQUEST)
                attrs["traceparent.outcome"] = OUTCOME_INJECTED_UNATTRIBUTED
                Decision(header(attrs), attrs, null)
            }
            !ctx.sampled -> null                          // unsampled context → no trace, header untouched
            !ctx.isLive(now) -> {                         // Δ7 — an action existed but aged out
                val attrs = parentlessAttrs(ROOT_REQUEST)
                attrs["traceparent.outcome"] = OUTCOME_INJECTED_EXPIRED
                Decision(header(attrs), attrs, null)
            }
            else -> {                                     // Δ1 — child of the live action root
                ctx.touch(now)                            // Δ7 — a child starting extends the root
                val attrs = childAttrs(ctx)
                attrs["traceparent.outcome"] = OUTCOME_INJECTED_ATTRIBUTED
                Decision("00-${ctx.traceId}-${attrs["span.id"]}-01", attrs, ctx)
            }
        }
    }

    /**
     * Δ11 — read ONLY by the crash and hang emitters. Never a parenting source: [onNetworkCall],
     * [onNavigation] and the interaction entry points must not call this. Returns empty once the root
     * has aged out under Δ7, rather than claiming an action the user finished five minutes ago.
     *
     * A crash has no duration and no children, so this stamps join keys — not a span.
     */
    fun annotateTerminal(): Map<String, Any> {
        val r = lastRoot?.takeIf { it.sampled && it.isLive(clock()) } ?: return emptyMap()
        return mapOf(
            "trace.id" to r.traceId,
            "rum.action.id" to r.spanId,
            "trace.root_type" to r.rootType
        )
    }

    /** ProcessLifecycleOwner `onStop`. Clears the main-thread carrier slot only, not a global null. */
    fun onBackground() {
        carrier.set(null)
    }

    /** Test seam: drop the Δ11 reference too, which [onBackground] deliberately leaves alone. */
    internal fun resetForTesting() {
        carrier.set(null)
        lastRoot = null
        launchRoot = null
        unwiredWarned.set(false)
        clock = { SystemClock.elapsedRealtime() }
        epochClock = { System.currentTimeMillis() }
    }

    // --- Attribute shapes --------------------------------------------------

    /** A root: no parent, `rum.action.id` == its own span, and its mint instant as `span.start_time`. */
    private fun rootAttrs(root: TraceContext): MutableMap<String, Any> = mutableMapOf(
        "trace.id" to root.traceId,
        "span.id" to root.spanId,
        "rum.action.id" to root.spanId,
        "trace.root_type" to root.rootType,
        "span.start_time" to TelemetryTime.isoOf(root.startTimeEpochMs)
    )

    /** A child: same trace, a fresh span, parent + action root, and the root's type denormalized (Δ13). */
    private fun childAttrs(root: TraceContext): MutableMap<String, Any> = mutableMapOf(
        "trace.id" to root.traceId,
        "span.id" to IdGenerator.spanId(),
        "parent.span.id" to root.spanId,
        "rum.action.id" to root.spanId,
        "trace.root_type" to root.rootType
    )

    /** A fresh parentless span: no parent and no action link, so no `rum.action.id`. */
    private fun parentlessAttrs(rootType: String? = null): MutableMap<String, Any> {
        val attrs = mutableMapOf<String, Any>(
            "trace.id" to IdGenerator.traceId(),
            "span.id" to IdGenerator.spanId()
        )
        rootType?.let { attrs["trace.root_type"] = it }
        return attrs
    }

    private fun header(attrs: Map<String, Any>) = "00-${attrs["trace.id"]}-${attrs["span.id"]}-01"

    /** The live root for this request: the Δ6 tag first, then the carrier (deprecated sync path). */
    private fun liveRoot(tag: TraceTag?, now: Long): TraceContext? =
        tag?.ctx?.takeIf { it.sampled && it.isLive(now) } ?: current()?.takeIf { it.sampled }

    private fun warnUnwiredOnce() {
        if (!unwiredWarned.compareAndSet(false, true)) return
        Log.w(
            "TraceManager",
            "No trace tag on this request — TelemetryManager.instrument(client) was never wired, so " +
                "asynchronous calls (Retrofit suspend/enqueue) cannot be attributed to a user action. " +
                "Pass TelemetryManager.instrument(client) to Retrofit's .callFactory()."
        )
    }

    // --- Host matching -----------------------------------------------------

    /**
     * Δ9 — exact match, or a dot-anchored suffix entry. Dot-anchoring is a true suffix test, never a
     * `contains`: `.example.com` matches `api.example.com` but NOT `api.example.com.evil.com` (which
     * ends in `.evil.com`) or `evil-example.com`.
     */
    internal fun isAllowed(host: String): Boolean {
        val h = host.lowercase()
        return traceHostAllowlist.any { entry ->
            if (entry.startsWith(".")) h.endsWith(entry) else h == entry
        }
    }

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
