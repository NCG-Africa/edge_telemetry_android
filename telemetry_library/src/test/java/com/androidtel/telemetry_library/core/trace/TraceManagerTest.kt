package com.androidtel.telemetry_library.core.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TraceManagerTest {

    private val traceIdRegex = Regex("^[0-9a-f]{32}$")
    private val spanIdRegex = Regex("^[0-9a-f]{16}$")
    private val headerRegex = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$")

    private val ALLOWED = "api.example.com"

    @Before
    fun setup() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = setOf(ALLOWED)
        TraceManager.onBackground()
    }

    @After
    fun teardown() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.traceHostAllowlist = emptySet()
        TraceManager.onBackground()
    }

    // ---- roots ------------------------------------------------------------

    @Test
    fun `interaction opens a root with trace, span, and rum action id, no parent`() {
        val attrs = TraceManager.onInteraction()!!
        assertTrue(traceIdRegex.matches(attrs["trace.id"] as String))
        assertTrue(spanIdRegex.matches(attrs["span.id"] as String))
        assertEquals("rum.action.id == root span", attrs["span.id"], attrs["rum.action.id"])
        assertFalse("root has no parent", attrs.containsKey("parent.span.id"))
    }

    @Test
    fun `nav with an active root is its child`() {
        val root = TraceManager.onInteraction()!!
        val nav = TraceManager.onNavigation()!!
        assertEquals(root["trace.id"], nav["trace.id"])
        assertEquals(root["span.id"], nav["parent.span.id"])
        assertEquals(root["span.id"], nav["rum.action.id"])
    }

    @Test
    fun `cold nav with no root opens a new root`() {
        val nav = TraceManager.onNavigation()!!
        assertTrue(traceIdRegex.matches(nav["trace.id"] as String))
        assertFalse(nav.containsKey("parent.span.id"))
    }

    // ---- attributed inject (Δ1) ------------------------------------------

    @Test
    fun `attributed inject - child of root, header, outcome`() {
        val root = TraceManager.onInteraction()!!
        val d = TraceManager.onNetworkCall(ALLOWED, null)!!

        assertEquals(root["trace.id"], d.attrs["trace.id"])
        assertEquals(root["span.id"], d.attrs["parent.span.id"])
        assertEquals(root["span.id"], d.attrs["rum.action.id"])
        assertTrue(spanIdRegex.matches(d.attrs["span.id"] as String))
        assertTrue("child differs from root", d.attrs["span.id"] != root["span.id"])
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
        assertTrue(headerRegex.matches(d.newHeader!!))
        // recorded span.id ≡ on-wire span.id
        assertTrue(d.newHeader!!.contains(d.attrs["span.id"] as String))
        assertTrue(d.newHeader!!.contains(root["trace.id"] as String))
    }

    // ---- allowlist gate (Δ2) ---------------------------------------------

    @Test
    fun `off-allowlist - attrs stamped but no header, skipped outcome`() {
        TraceManager.onInteraction()
        val d = TraceManager.onNetworkCall("third-party.com", null)!!

        assertNull("no header off-allowlist", d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
        assertTrue(spanIdRegex.matches(d.attrs["span.id"] as String))
        assertTrue(traceIdRegex.matches(d.attrs["trace.id"] as String))
    }

    @Test
    fun `empty allowlist - no request gets a header`() {
        TraceManager.traceHostAllowlist = emptySet()
        TraceManager.onInteraction()
        val d = TraceManager.onNetworkCall(ALLOWED, null)!!
        assertNull(d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `substring near-miss does not match the allowlist`() {
        TraceManager.onInteraction()
        val d = TraceManager.onNetworkCall("api.example.com.evil.com", null)!!
        assertNull(d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `allowlist match is case-insensitive`() {
        TraceManager.onInteraction()
        val d = TraceManager.onNetworkCall("API.Example.COM", null)!!
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
        assertTrue(headerRegex.matches(d.newHeader!!))
    }

    @Test
    fun `off-allowlist with inbound header still skipped (allowlist wins)`() {
        val inbound = "00-${"a".repeat(32)}-${"b".repeat(16)}-01"
        val d = TraceManager.onNetworkCall("third-party.com", inbound)!!
        assertNull("inbound header left on the wire, ours not injected", d.newHeader)
        assertEquals("skipped_off_allowlist", d.attrs["traceparent.outcome"])
    }

    // ---- adoption (Δ3) ----------------------------------------------------

    @Test
    fun `adopt valid inbound - mirror foreign ids, header untouched`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val root = TraceManager.onInteraction()!!
        val d = TraceManager.onNetworkCall(ALLOWED, "00-$t-$p-01")!!

        assertNull("header left untouched", d.newHeader)
        assertEquals(t, d.attrs["trace.id"])
        assertEquals(p, d.attrs["span.id"])
        assertFalse("no parent on adopt", d.attrs.containsKey("parent.span.id"))
        assertEquals(root["span.id"], d.attrs["rum.action.id"])
        assertEquals("adopted", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `adopt uppercase inbound, lowercased on stamp`() {
        val t = "A".repeat(32)
        val p = "C".repeat(16)
        val d = TraceManager.onNetworkCall(ALLOWED, "00-$t-$p-01")!!
        assertEquals(t.lowercase(), d.attrs["trace.id"])
        assertEquals(p.lowercase(), d.attrs["span.id"])
        assertEquals("adopted", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `adopt lenient version 2a`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val d = TraceManager.onNetworkCall(ALLOWED, "2a-$t-$p-01")!!
        assertEquals("adopted", d.attrs["traceparent.outcome"])
        assertNull(d.newHeader)
    }

    @Test
    fun `adopt with no active action still adopted, no rum action id`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        val d = TraceManager.onNetworkCall(ALLOWED, "00-$t-$p-01")!!
        assertEquals("adopted", d.attrs["traceparent.outcome"])
        assertFalse(d.attrs.containsKey("rum.action.id"))
    }

    // ---- malformed inbound → overwrite -----------------------------------

    @Test
    fun `malformed version ff - header replaced, injected not adopted`() {
        val t = "a".repeat(32)
        val p = "b".repeat(16)
        TraceManager.onInteraction()
        val d = TraceManager.onNetworkCall(ALLOWED, "ff-$t-$p-01")!!
        assertTrue("header replaced", headerRegex.matches(d.newHeader!!))
        assertEquals("injected_attributed", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `malformed all-zero trace id - overwritten, unattributed`() {
        val d = TraceManager.onNetworkCall(ALLOWED, "00-${"0".repeat(32)}-${"b".repeat(16)}-01")!!
        assertTrue(headerRegex.matches(d.newHeader!!))
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    @Test
    fun `malformed wrong length - overwritten`() {
        val d = TraceManager.onNetworkCall(ALLOWED, "00-tooshort-b-01")!!
        assertTrue(headerRegex.matches(d.newHeader!!))
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    // ---- unattributed (Δ4) -----------------------------------------------

    @Test
    fun `unattributed - parentless span, header, outcome`() {
        val d = TraceManager.onNetworkCall(ALLOWED, null)!!
        assertFalse(d.attrs.containsKey("parent.span.id"))
        assertFalse(d.attrs.containsKey("rum.action.id"))
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
        assertTrue(headerRegex.matches(d.newHeader!!))
    }

    @Test
    fun `two back-to-back no-action calls get two distinct traces`() {
        val a = TraceManager.onNetworkCall(ALLOWED, null)!!
        val b = TraceManager.onNetworkCall(ALLOWED, null)!!
        assertTrue("distinct traces", a.attrs["trace.id"] != b.attrs["trace.id"])
    }

    // ---- unsampled context ------------------------------------------------

    @Test
    fun `unsampled context yields null decision (no trace, no header)`() = runBlocking {
        // The only way to hold a present-but-unsampled carrier: project one (an unsampled interaction
        // clears the slot instead, giving the unattributed path). null == no trace, header untouched.
        val ctx = TraceManager.TraceContext("a".repeat(32), "b".repeat(16), sampled = false)
        val d = withContext(TraceManager.asElement(ctx)) { TraceManager.onNetworkCall(ALLOWED, null) }
        assertNull(d)
    }

    // ---- background -------------------------------------------------------

    @Test
    fun `background clears the slot - a later call is unattributed`() {
        TraceManager.onInteraction()
        TraceManager.onBackground()
        val d = TraceManager.onNetworkCall(ALLOWED, null)!!
        assertEquals("injected_unattributed", d.attrs["traceparent.outcome"])
    }

    // ---- concurrency (T1 invariant) --------------------------------------

    @Test
    fun `concurrent actions never leak across coroutines`() = runBlocking {
        val a = TraceManager.TraceContext("a".repeat(32), "1".repeat(16), true)
        val b = TraceManager.TraceContext("b".repeat(32), "2".repeat(16), true)

        val results = listOf(a, b).map { ctx ->
            async(Dispatchers.IO + TraceManager.asElement(ctx)) {
                // hop threads again to prove the element survives a Dispatchers.IO switch
                withContext(Dispatchers.IO) { TraceManager.onNetworkCall(ALLOWED, null)!! }
            }
        }.awaitAll()

        assertEquals(a.traceId, results[0].attrs["trace.id"])
        assertEquals(a.spanId, results[0].attrs["parent.span.id"])
        assertEquals(b.traceId, results[1].attrs["trace.id"])
        assertEquals(b.spanId, results[1].attrs["parent.span.id"])
        // zero cross-leak
        assertTrue(results[0].attrs["trace.id"] != results[1].attrs["trace.id"])
    }
}
