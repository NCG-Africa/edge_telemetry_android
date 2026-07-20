package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.models.TelemetryEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for CountedEventQueue — the 500-event, drop-oldest, O(1)-size in-memory queue.
 */
class CountedEventQueueTest {

    private fun event(tag: String): TelemetryEvent =
        mockk<TelemetryEvent>(relaxed = true).also { every { it.eventName } returns tag }

    @Test
    fun `caps at MAX_EVENTS dropping the oldest`() {
        val queue = CountedEventQueue()
        repeat(CountedEventQueue.MAX_EVENTS + 1) { queue.enqueue(event("e$it")) }

        assertEquals(CountedEventQueue.MAX_EVENTS, queue.size)
        // The very first event (e0) was evicted; the oldest survivor is e1.
        assertEquals("e1", queue.peek()?.eventName)
    }

    @Test
    fun `poll decrements size and drains in FIFO order`() {
        val queue = CountedEventQueue()
        queue.enqueue(event("a"))
        queue.enqueue(event("b"))

        assertEquals("a", queue.poll()?.eventName)
        assertEquals(1, queue.size)
        assertEquals("b", queue.poll()?.eventName)
        assertEquals(0, queue.size)
        assertNull(queue.poll())
        assertEquals(0, queue.size)
    }
}
