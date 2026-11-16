package com.androidtel.telemetry_library.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Task API (#145, processor #34): one terminal `task.complete` per task. */
class TelemetryTaskTest {

    private var now = 1_000L
    private val sent = mutableListOf<Map<String, Any>>()
    private var done = 0

    private fun task(name: String = "checkout") =
        TelemetryTask(name, emit = { sent += it }, clock = { now }, onDone = { done++ })

    @Test
    fun `complete sends the outcome and duration once`() {
        val t = task()
        now += 2_500
        t.complete()
        t.fail("late") // already ended: ignored

        assertEquals(
            listOf(mapOf("task.name" to "checkout", "task.outcome" to "completed", "span.duration_ms" to 2_500L)),
            sent
        )
        assertEquals(1, done)
    }

    @Test
    fun `fail carries the reason, abandon the source`() {
        task().fail("card declined")
        task().abandon(TelemetryTask.SOURCE_SESSION_END)
        task().abandon()

        assertEquals("failed", sent[0]["task.outcome"])
        assertEquals("card declined", sent[0]["task.failure_reason"])
        assertEquals("abandoned", sent[1]["task.outcome"])
        assertEquals("session_end", sent[1]["task.abandon_source"])
        assertFalse(sent[2].containsKey("task.abandon_source"))
    }
}
