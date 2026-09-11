package com.androidtel.telemetry_library.core.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

/**
 * v3 (#120) contract tests. v2's cases are carried forward, re-expressed against the v3 entry points
 * (`onInteractionStart`/`onInteractionEmit` replace `onInteraction`; `onNetworkCall` takes the Δ6 tag).
 *
 * The clock is injected throughout, so Δ7's 2 s / 10 s windows are exercised without a real device and
 * without sleeping.
 */
class TraceManagerTest {

    private val traceIdRegex = Regex("^[0-9a-f]{32}$")
    private val spanIdRegex = Regex("^[0-9a-f]{16}$")
    private val headerRegex = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$")
    private val isoRegex = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    private val ALLOWED = "api.example.com"

    /** Injected monotonic clock; every test drives it explicitly. */
    private var now = 10_000L

    @Before
    fun setup() {
        TraceManager.resetForTesting()
        now = 10_000L
        TraceManager.clock = { now }
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = setOf(ALLOWED)
    }

    @After
    fun teardown() {
        TraceManager.resetForTesting()
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = emptySet()
    }

    /** A live Δ6 tag for the root currently on this thread — what `newCall()` would have stamped. */
    private fun tag() = TraceTag(TraceManager.current())

    // ---- roots ------------------------------------------------------------

    @Test
    fun `interaction opens a root with trace, span, and rum action id, no parent`() {
        TraceManager.onInteractionStart("checkout")
        val attrs = TraceManager.onInteractionEmit()!!
        assertTrue(traceIdRegex.matches(attrs["trace.id"] as String))
        assertTrue(spanIdRegex.matches(attrs["span.id"] as String))
        assertEquals("rum.action.id == root span", attrs["span.id"], attrs["rum.action.id"])
        assertFalse("root has no parent", attrs.containsKey("parent.span.id"))
        assertEquals("interaction", attrs["trace.root_type"])
    }

    @Test
    fun `nav with an active root is its child`() {
        val root = TraceManager.onInteractionStart(null)!!
        val nav = TraceManager.onNavigation()!!
        assertEquals(root.traceId, nav["trace.id"])
        assertEquals(root.spanId, nav["parent.span.id"])
        assertEquals(root.spanId, nav["rum.action.id"])
        assertEquals("child denormalizes the root's type", "interaction", nav["trace.root_type"])
    }

    @Test
    fun `cold nav with no root opens a new root typed navigation`() {
        val nav = TraceManager.onNavigation()!!
        assertTrue(traceIdRegex.matches(nav["trace.id"] as String))
        assertFalse(nav.containsKey("parent.span.id"))
        assertEquals("navigation", nav["trace.root_type"])
    }

    // ---- attributed inject (Δ1) ------------------------------------------

    @Test
    fun `attributed inject - child of root, header, outcome`() {
        val root = TraceManager.onInteractionStart(null)!!
        val d = TraceManager.onNetworkCall(ALLOWED, null, tag())!!

        assertEquals(root.traceId, d.attrs["trace.id"])
        assertEquals(root.spanId, d.attrs["parent.span.id"])
        assertEquals(root.spanId, d.attrs["rum.action.id"])
        assertTrue(spanIdRegex.matches(d.attrs["span.id"] as String))
        assertTrue("child differs from root", d.attrs["span.id"] != root.spanId)
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
        assertTrue(headerRegex.matches(d.newHeader!!))
        // recorded span.id ≡ on-wire span.id
        assertTrue(d.newHeader!!.contains(d.attrs["span.id"] as String))
        assertTrue(d.newHeader!!.contains(root.traceId))
        assertEquals("the interceptor gets the root back, to extend on completion", root, d.root)
    }

    // ---- allowlist gate (Δ2 / Δ9) ----------------------------------------

    @Test
    fun `off-allowlist - attrs stamped but no header, skipped outcome`() {
        TraceManager.onInteractionStart(null)
        val d = TraceManager.onNetworkCall("third-party.com", null, tag())!!

        assertNull("no header off-allowlist", d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
        assertTrue(spanIdRegex.matches(d.attrs["span.id"] as String))
        assertTrue(traceIdRegex.matches(d.attrs["trace.id"] as String))
    }

    @Test
    fun `empty allowlist - no request gets a header`() {
        TraceManager.traceHostAllowlist = emptySet()
        TraceManager.onInteractionStart(null)
        val d = TraceManager.onNetworkCall(ALLOWED, null, tag())!!
        assertNull(d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `substring near-miss does not match the allowlist`() {
        TraceManager.onInteractionStart(null)
        val d = TraceManager.onNetworkCall("api.example.com.evil.com", null, tag())!!
        assertNull(d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `allowlist match is case-insensitive`() {
        TraceManager.onInteractionStart(null)
        val d = TraceManager.onNetworkCall("API.Example.COM", null, tag())!!
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
        assertTrue(headerRegex.matches(d.newHeader!!))
    }

    @Test
    fun `off-allowlist with inbound header still skipped (allowlist wins)`() {
        val inbound = "00-${"a".repeat(32)}-${"b".repeat(16)}-01"
        val d = TraceManager.onNetworkCall("third-party.com", inbound, tag())!!
        assertNull("inbound header left on the wire, ours not injected", d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
    }

    /** Δ9 — a dot-anchored entry is a true suffix test, never a `contains`. */
    @Test
    fun `suffix allowlist entry matches subdomains only`() {
        TraceManager.traceHostAllowlist = setOf(".example.com")
        assertTrue(TraceManager.isAllowed("api.example.com"))
        assertTrue(TraceManager.isAllowed("api-v2.example.com"))
        assertTrue(TraceManager.isAllowed("API.Example.com"))
        assertFalse("the apex itself is not a subdomain", TraceManager.isAllowed("example.com"))
        assertFalse("ends in .evil.com, not .example.com", TraceManager.isAllowed("api.example.com.evil.com"))
        assertFalse("not dot-anchored", TraceManager.isAllowed("evil-example.com"))
    }

    // ---- adoption (Δ3) ----------------------------------------------------

    @Test
    fun `adopt valid inbound - mirror foreign ids, header untouched`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val root = TraceManager.onInteractionStart(null)!!
        val d = TraceManager.onNetworkCall(ALLOWED, "00-$t-$p-01", tag())!!

        assertNull("header left untouched", d.newHeader)
        assertEquals(t, d.attrs["trace.id"])
        assertEquals(p, d.attrs["span.id"])
        assertFalse("no parent on adopt", d.attrs.containsKey("parent.span.id"))
        assertEquals(root.spanId, d.attrs["rum.action.id"])
        assertEquals("adopted", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `adopt uppercase inbound, lowercased on stamp`() {
        val t = "A".repeat(32)
        val p = "C".repeat(16)
        val d = TraceManager.onNetworkCall(ALLOWED, "00-$t-$p-01", tag())!!
        assertEquals(t.lowercase(), d.attrs["trace.id"])
        assertEquals(p.lowercase(), d.attrs["span.id"])
        assertEquals("adopted", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `adopt lenient version 2a`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val d = TraceManager.onNetworkCall(ALLOWED, "2a-$t-$p-01", tag())!!
        assertEquals("adopted", d.attrs["traceparent.outcome"])
        assertNull(d.newHeader)
    }

    @Test
    fun `adopt with no active action still adopted, no rum action id`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val d = TraceManager.onNetworkCall(ALLOWED, "00-$t-$p-01", tag())!!
        assertEquals("adopted", d.attrs["traceparent.outcome"])
        assertFalse(d.attrs.containsKey("rum.action.id"))
    }

    // ---- malformed inbound → overwrite -----------------------------------

    @Test
    fun `malformed version ff - header replaced, injected not adopted`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        TraceManager.onInteractionStart(null)
        val d = TraceManager.onNetworkCall(ALLOWED, "ff-$t-$p-01", tag())!!
        assertTrue("header replaced", headerRegex.matches(d.newHeader!!))
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `malformed all-zero trace id - overwritten, unattributed`() {
        val d = TraceManager.onNetworkCall(ALLOWED, "00-${"0".repeat(32)}-${"b".repeat(16)}-01", tag())!!
        assertTrue(headerRegex.matches(d.newHeader!!))
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `malformed wrong length - overwritten`() {
        val d = TraceManager.onNetworkCall(ALLOWED, "00-tooshort-b-01", tag())!!
        assertTrue(headerRegex.matches(d.newHeader!!))
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    // ---- unattributed / unwired / expired (Δ4, Δ6, Δ7) --------------------

    @Test
    fun `unattributed - tag present holding null, parentless span, header, outcome`() {
        val d = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(null))!!
        assertFalse(d.attrs.containsKey("parent.span.id"))
        assertFalse(d.attrs.containsKey("rum.action.id"))
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
        assertEquals("request", d.attrs["trace.root_type"])
        assertTrue(headerRegex.matches(d.newHeader!!))
        assertNull("nothing to extend", d.root)
    }

    /** Δ6 — no tag AND no carrier: the factory was never wired. Distinct from "genuinely no action". */
    @Test
    fun `unwired - no tag and no carrier reports injected_unwired`() {
        val d = TraceManager.onNetworkCall(ALLOWED, null, null)!!
        assertEquals("injected_unwired", d.attrs["traceparent.outcome"])
        assertTrue(headerRegex.matches(d.newHeader!!))
    }

    /** Δ6 — no tag but a live carrier: the deprecated sync main-thread path, still correct. */
    @Test
    fun `deprecated path - no tag with a live carrier still attributes`() {
        val root = TraceManager.onInteractionStart(null)!!
        val d = TraceManager.onNetworkCall(ALLOWED, null, null)!!
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
        assertEquals(root.spanId, d.attrs["rum.action.id"])
    }

    @Test
    fun `two back-to-back no-action calls get two distinct traces`() {
        val a = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(null))!!
        val b = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(null))!!
        assertTrue("distinct traces", a.attrs["trace.id"] != b.attrs["trace.id"])
    }

    // ---- Δ7 root lifetime --------------------------------------------------

    @Test
    fun `root is live just inside the idle window and gone just outside it`() {
        TraceManager.onInteractionStart(null)
        now += 1_900
        assertNotNull("1.9 s idle is still live", TraceManager.current())
        now += 200 // 2.1 s since last activity
        assertNull("2.1 s idle has expired", TraceManager.current())
    }

    @Test
    fun `expired root reports injected_expired, not unattributed`() {
        val root = TraceManager.onInteractionStart(null)!!
        val stale = TraceTag(root)          // captured at newCall() while the root was live
        now += 3_000
        val d = TraceManager.onNetworkCall(ALLOWED, null, stale)!!
        assertEquals("injected_expired", d.attrs["traceparent.outcome"])
        assertFalse("an expired root parents nothing", d.attrs.containsKey("parent.span.id"))
        assertTrue(headerRegex.matches(d.newHeader!!))
    }

    @Test
    fun `age cap expires a root even under continuous activity`() {
        val root = TraceManager.onInteractionStart(null)!!
        repeat(11) {                        // touched every second for 11 s — never idle, but too old
            now += 1_000
            root.touch(now)
        }
        assertNull("10 s cap wins over activity", TraceManager.current())
    }

    /** The chained flow the 2 s window would otherwise break: tap → A (4 s) → B on A's success. */
    @Test
    fun `completion-extension keeps a chained flow under one root`() {
        val root = TraceManager.onInteractionStart(null)!!
        val a = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(root))!!
        now += 4_000
        a.root!!.touch(now)                  // request A completes — the interceptor's finally
        now += 500
        val b = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(TraceManager.current()))!!
        assertEquals("B joins A's root", root.traceId, b.attrs["trace.id"])
        assertEquals(root.spanId, b.attrs["rum.action.id"])
        assertEquals("injected_attributed", b.attrs["traceparent.outcome"])
    }

    /** Δ7's extension is written from OkHttp's pool thread; an AtomicLong is what carries it back. */
    @Test
    fun `completion-extension crosses threads`() {
        val root = TraceManager.onInteractionStart(null)!!
        now += 1_500
        thread { root.touch(now) }.join()    // the interceptor's finally, off-main
        now += 1_000                         // 2.5 s since mint, but only 1 s since the extension
        assertNotNull("main sees the off-thread extension", TraceManager.current())
        assertEquals(root.spanId, TraceManager.current()!!.spanId)
    }

    // ---- Δ8 launch root ----------------------------------------------------

    @Test
    fun `launch root is opened at init and survives long idle before first resume`() {
        TraceManager.resetForTesting()
        now = 10_000L
        TraceManager.clock = { now }
        TraceManager.traceHostAllowlist = setOf(ALLOWED)

        TraceManager.onLaunch(startElapsedRealtimeMs = 8_000L, importanceAtInit = 200)
        now += 5_000                          // 5 s of total silence during startup
        val root = TraceManager.current()
        assertNotNull("idle-exempt until the first resume", root)
        assertEquals("launch", root!!.rootType)

        val d = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(root))!!
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
        assertEquals("launch", d.attrs["trace.root_type"])
        assertEquals(root.spanId, d.attrs["rum.action.id"])
    }

    @Test
    fun `first resume ends the exemption, not the root`() {
        TraceManager.onLaunch(startElapsedRealtimeMs = now - 2_000, importanceAtInit = 100)
        TraceManager.onFirstResume()
        assertNotNull("the root survives the handoff", TraceManager.current())
        now += 2_100
        assertNull("and now decays under the normal idle rule", TraceManager.current())
    }

    @Test
    fun `background-forked process opens no launch root`() {
        TraceManager.resetForTesting()
        now = 10_000L
        TraceManager.clock = { now }
        TraceManager.traceHostAllowlist = setOf(ALLOWED)

        assertNull(TraceManager.onLaunch(startElapsedRealtimeMs = 9_000L, importanceAtInit = 400))
        assertNull(TraceManager.current())
        assertNull(TraceManager.launchRootAttrs())
        val d = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(null))!!
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `launch root attrs carry the fork instant as span start_time`() {
        val forkEpoch = 1_700_000_000_000L
        TraceManager.epochClock = { forkEpoch + 3_000 }   // init runs 3 s after fork
        TraceManager.onLaunch(startElapsedRealtimeMs = now - 3_000, importanceAtInit = 100)

        val attrs = TraceManager.launchRootAttrs()!!
        assertEquals("launch", attrs["trace.root_type"])
        assertTrue(isoRegex.matches(attrs["span.start_time"] as String))
        assertFalse("roots carry no duration", attrs.containsKey("span.duration_ms"))
        assertEquals("rum.action.id == the launch root span", attrs["span.id"], attrs["rum.action.id"])
    }

    // ---- Δ10 span timing ---------------------------------------------------

    @Test
    fun `interaction root start_time is the MINT time, not the emit time`() {
        val mintEpoch = 1_700_000_000_000L
        TraceManager.epochClock = { mintEpoch }
        TraceManager.onInteractionStart("vote")

        TraceManager.epochClock = { mintEpoch + 300 }      // ~300 ms later, at tap-confirm
        val attrs = TraceManager.onInteractionEmit()!!
        assertEquals(
            "stamping the emit time would start the root after the request it parents",
            com.androidtel.telemetry_library.core.TelemetryTime.isoOf(mintEpoch),
            attrs["span.start_time"]
        )
        assertFalse("roots carry no duration", attrs.containsKey("span.duration_ms"))
    }

    @Test
    fun `navigation child carries its own start_time`() {
        TraceManager.onInteractionStart(null)
        val nav = TraceManager.onNavigation()!!
        assertTrue(isoRegex.matches(nav["span.start_time"] as String))
    }

    // ---- Δ12 naming --------------------------------------------------------

    @Test
    fun `trackUserInteraction renames the open root and the name survives to emit`() {
        TraceManager.onInteractionStart("compose_surface")
        TraceManager.nameCurrentRoot("vote")
        assertEquals("vote", TraceManager.currentRootName())
    }

    // ---- Δ11 terminal annotation -------------------------------------------

    @Test
    fun `terminal annotation carries the live action, from any thread`() {
        val root = TraceManager.onInteractionStart("checkout")!!
        var fromOtherThread: Map<String, Any> = emptyMap()
        thread { fromOtherThread = TraceManager.annotateTerminal() }.join()

        assertEquals(root.traceId, fromOtherThread["trace.id"])
        assertEquals(root.spanId, fromOtherThread["rum.action.id"])
        assertEquals("interaction", fromOtherThread["trace.root_type"])
        assertFalse("annotation, not a span: no parent", fromOtherThread.containsKey("parent.span.id"))
        assertFalse("annotation, not a span: no span.id", fromOtherThread.containsKey("span.id"))
    }

    @Test
    fun `terminal annotation stamps nothing once the action has expired`() {
        TraceManager.onInteractionStart(null)
        now += 3_000
        assertTrue(
            "better to claim no action than one the user finished minutes ago",
            TraceManager.annotateTerminal().isEmpty()
        )
    }

    // ---- unsampled context -------------------------------------------------

    @Test
    fun `unsampled context yields null decision (no trace, no header)`() {
        val ctx = TraceContext(
            traceId = "a".repeat(32), spanId = "b".repeat(16), sampled = false,
            rootType = TraceManager.ROOT_INTERACTION, startTimeEpochMs = 0L, openedAt = now
        )
        assertNull(TraceManager.onNetworkCall(ALLOWED, null, TraceTag(ctx)))
    }

    // ---- background --------------------------------------------------------

    @Test
    fun `background clears the slot - a later call is unattributed`() {
        TraceManager.onInteractionStart(null)
        TraceManager.onBackground()
        val d = TraceManager.onNetworkCall(ALLOWED, null, TraceTag(TraceManager.current()))!!
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    // ---- concurrency (T1 invariant) ----------------------------------------

    @Test
    fun `concurrent actions never leak across coroutines`() = runBlocking {
        val a = TraceContext("a".repeat(32), "1".repeat(16), true, TraceManager.ROOT_INTERACTION, 0L, now)
        val b = TraceContext("b".repeat(32), "2".repeat(16), true, TraceManager.ROOT_INTERACTION, 0L, now)

        val results = listOf(a, b).map { ctx ->
            async(Dispatchers.IO + TraceManager.asElement(ctx)) {
                // hop threads again to prove the element survives a Dispatchers.IO switch — this is
                // what the Δ6 Call.Factory reads at newCall()
                withContext(Dispatchers.IO) {
                    TraceManager.onNetworkCall(ALLOWED, null, TraceTag(TraceManager.current()))!!
                }
            }
        }.awaitAll()

        assertEquals(a.traceId, results[0].attrs["trace.id"])
        assertEquals(a.spanId, results[0].attrs["parent.span.id"])
        assertEquals(b.traceId, results[1].attrs["trace.id"])
        assertEquals(b.spanId, results[1].attrs["parent.span.id"])
        // zero cross-leak
        assertTrue(results[0].attrs["trace.id"] != results[1].attrs["trace.id"])
    }

    // ---- Δ13 enum completeness ---------------------------------------------

    @Test
    fun `all six outcome values are reachable and declared`() {
        assertEquals(6, TraceManager.OUTCOMES.size)
        val seen = mutableSetOf<String>()

        seen += TraceManager.onNetworkCall("third-party.com", null, TraceTag(null))!!
            .attrs["traceparent.outcome"] as String
        seen += TraceManager.onNetworkCall(
            ALLOWED, "00-${"a".repeat(32)}-${"b".repeat(16)}-01", TraceTag(null)
        )!!.attrs["traceparent.outcome"] as String
        seen += TraceManager.onNetworkCall(ALLOWED, null, null)!!.attrs["traceparent.outcome"] as String
        seen += TraceManager.onNetworkCall(ALLOWED, null, TraceTag(null))!!
            .attrs["traceparent.outcome"] as String

        val root = TraceManager.onInteractionStart(null)!!
        seen += TraceManager.onNetworkCall(ALLOWED, null, TraceTag(root))!!
            .attrs["traceparent.outcome"] as String
        now += 3_000
        seen += TraceManager.onNetworkCall(ALLOWED, null, TraceTag(root))!!
            .attrs["traceparent.outcome"] as String

        assertEquals(TraceManager.OUTCOMES, seen)
    }
}
