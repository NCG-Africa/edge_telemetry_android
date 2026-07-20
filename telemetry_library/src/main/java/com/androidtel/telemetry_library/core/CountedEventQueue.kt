package com.androidtel.telemetry_library.core

import com.androidtel.telemetry_library.core.models.TelemetryEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, drop-oldest in-memory event queue with an O(1) size.
 *
 * `ConcurrentLinkedQueue.size` is O(n) — it walks the list to count — so a per-enqueue cap check on
 * it would make enqueue O(n²). The count is tracked in an [AtomicInteger] instead.
 *
 * Cap is [MAX_EVENTS], drop-oldest: a burst or send-stall trims the front (oldest events) rather
 * than growing without bound. Recent events are closer to the current user state / a crash, so the
 * oldest are the cheapest to lose.
 *
 * ponytail: the cap check and the offer are not one atomic step, so under heavy concurrent enqueue
 * the count can briefly exceed MAX_EVENTS before the next drop-oldest trims it back — fine for a
 * soft memory cap. Add a lock only if a hard ceiling is ever required.
 */
class CountedEventQueue {
    private val queue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val count = AtomicInteger(0)

    /** O(1) authoritative count. */
    val size: Int get() = count.get()

    fun isEmpty(): Boolean = count.get() == 0
    fun isNotEmpty(): Boolean = !isEmpty()

    /** Append, evicting the oldest first if already at capacity. */
    fun enqueue(event: TelemetryEvent) {
        if (count.get() >= MAX_EVENTS && queue.poll() != null) count.decrementAndGet()
        queue.offer(event)
        count.incrementAndGet()
    }

    /** Remove and return the oldest event, or null if empty. */
    fun poll(): TelemetryEvent? {
        val event = queue.poll()
        if (event != null) count.decrementAndGet()
        return event
    }

    /** Peek the oldest event without removing it. */
    fun peek(): TelemetryEvent? = queue.peek()

    companion object {
        // 10× the default batchSize (50): absorbs a send-stall or burst, well above one batch.
        const val MAX_EVENTS = 500
    }
}
