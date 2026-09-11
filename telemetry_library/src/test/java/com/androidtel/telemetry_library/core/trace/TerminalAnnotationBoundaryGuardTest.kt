package com.androidtel.telemetry_library.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Δ11's boundary, enforced mechanically: the process-global last-action reference annotates terminal
 * events and **never parents a span**.
 *
 * T1 (#102) killed a process-global `AtomicReference` as a *propagation* mechanism — it leaked across
 * threads and actions and produced wrong span parenting. Δ11 reintroduces a process-global reference
 * for a deliberately narrower job, and the entire justification rests on that distinction. If a future
 * edit reads `annotateTerminal()` or `lastRoot` from a parenting path, that edit re-opens #102 and must
 * be rejected on those grounds — so this test fails instead of letting it land quietly.
 *
 * ponytail: a grep-in-a-test, same shape as [com.androidtel.telemetry_library.core.DeletedSymbolGuardTest],
 * riding the existing testDebugUnitTest gate rather than new tooling.
 */
class TerminalAnnotationBoundaryGuardTest {

    private val srcMain: File =
        listOf(File("src/main"), File("telemetry_library/src/main")).first { it.isDirectory }

    private fun kotlinSources(): List<File> =
        srcMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The only two files allowed to call it, plus its own declaration site. */
    private val permittedCallers = setOf(
        "CrashReportingService.kt",   // app.crash (both rails) + app.hang
        "TraceManager.kt"             // the declaration itself
    )

    @Test
    fun `annotateTerminal is called only from the crash and hang emitters`() {
        val callers = kotlinSources()
            .filter { it.readText().contains("annotateTerminal()") }
            .map { it.name }
            .toSet()

        val offenders = callers - permittedCallers
        assertTrue(
            "Δ11 annotates terminal events only. Calling annotateTerminal() from $offenders makes a " +
                "process-global reference a propagation source again, which is exactly what T1 #102 " +
                "removed. Parenting reads the Δ1 carrier and the Δ6 tag, and nothing else.",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the parenting paths never read the last-action reference`() {
        val traceManager = kotlinSources().first { it.name == "TraceManager.kt" }.readText()

        // Everything from onNetworkCall's declaration to the start of annotateTerminal's doc block:
        // the whole per-request ladder plus the navigation/interaction entry points above it.
        val parentingPaths = traceManager
            .substringAfter("fun onNavigation()")
            .substringBefore("fun annotateTerminal()")

        assertEquals(
            "The ladder and the navigation/interaction paths must not read lastRoot — they read the " +
                "carrier and the request tag. Found a reference inside them.",
            0,
            Regex("\\blastRoot\\b").findAll(parentingPaths).count()
        )
    }

    @Test
    fun `annotateTerminal returns join keys only, never a span`() {
        TraceManager.resetForTesting()
        TraceManager.clock = { 1_000L }
        TraceManager.onInteractionStart("checkout")

        val attrs = TraceManager.annotateTerminal()
        assertEquals(
            "a crash has no duration and no children, so it gets join keys — not a span",
            setOf("trace.id", "rum.action.id", "trace.root_type"),
            attrs.keys
        )
        TraceManager.resetForTesting()
    }
}
