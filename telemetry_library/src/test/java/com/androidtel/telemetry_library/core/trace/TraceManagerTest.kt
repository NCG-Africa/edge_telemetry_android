package com.androidtel.telemetry_library.core.trace

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

    @Before
    fun setup() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.onBackground() // clear any root leaked from another test
    }

    @After
    fun teardown() {
        TraceManager.traceSampleRate = 1.0
        TraceManager.onBackground()
    }

    // 1. Interaction opens a root — trace.id + span.id, no parent.span.id.
    @Test
    fun `interaction opens a root with trace and span ids, no parent`() {
        val attrs = TraceManager.onInteraction(1_000L)!!
        assertTrue(traceIdRegex.matches(attrs["trace.id"] as String))
        assertTrue(spanIdRegex.matches(attrs["span.id"] as String))
        assertFalse("root has no parent", attrs.containsKey("parent.span.id"))
    }

    // 2. Tap → network child — same trace, fresh span, parent == root span; header well-formed.
    @Test
    fun `network call after interaction is a child with matching header`() {
        val root = TraceManager.onInteraction(1_000L)!!
        val (header, attrs) = TraceManager.onNetworkCall()!!

        assertEquals(root["trace.id"], attrs["trace.id"])
        assertEquals(root["span.id"], attrs["parent.span.id"])
        assertTrue(spanIdRegex.matches(attrs["span.id"] as String))
        assertTrue("child span differs from root", attrs["span.id"] != root["span.id"])
        assertTrue(headerRegex.matches(header))
        assertTrue(header.contains(root["trace.id"] as String))
        assertTrue(header.contains(attrs["span.id"] as String))
    }

    // 3. Navigation linkage — within 1000ms is a child; a cold nav opens a new root.
    @Test
    fun `nav within window is a child of the interaction root`() {
        val root = TraceManager.onInteraction(1_000L)!!
        val nav = TraceManager.onNavigation(1_900L)!! // 900ms later

        assertEquals(root["trace.id"], nav["trace.id"])
        assertEquals(root["span.id"], nav["parent.span.id"])
    }

    @Test
    fun `nav outside window opens a new root`() {
        val root = TraceManager.onInteraction(1_000L)!!
        val nav = TraceManager.onNavigation(2_100L)!! // 1100ms later → own root

        assertTrue("new trace", nav["trace.id"] != root["trace.id"])
        assertFalse("root has no parent", nav.containsKey("parent.span.id"))
    }

    @Test
    fun `cold nav with no root opens a new root`() {
        val nav = TraceManager.onNavigation(5_000L)!!
        assertTrue(traceIdRegex.matches(nav["trace.id"] as String))
        assertFalse(nav.containsKey("parent.span.id"))
    }

    // 4. Background clears — a network call after onBackground has no trace.
    @Test
    fun `network call after background has no trace`() {
        TraceManager.onInteraction(1_000L)
        TraceManager.onBackground()
        assertNull(TraceManager.onNetworkCall())
    }

    // 5. Sampling — 0.0 yields no trace anywhere; 1.0 yields trace everywhere.
    @Test
    fun `zero sample rate yields no trace attrs or header`() {
        TraceManager.traceSampleRate = 0.0
        assertNull(TraceManager.onInteraction(1_000L))
        assertNull(TraceManager.onNetworkCall())
        // A nav with no sampled root falls through to a new root, also unsampled.
        assertNull(TraceManager.onNavigation(1_100L))
    }

    @Test
    fun `full sample rate yields trace on every eligible call`() {
        TraceManager.traceSampleRate = 1.0
        assertTrue(TraceManager.onInteraction(1_000L) != null)
        assertTrue(TraceManager.onNetworkCall() != null)
    }

    // Child inherits the sampled decision: an unsampled root means no network trace.
    @Test
    fun `network call has no trace when root was unsampled`() {
        TraceManager.traceSampleRate = 0.0
        TraceManager.onInteraction(1_000L) // unsampled root, current cleared
        assertNull(TraceManager.onNetworkCall())
    }
}
