package com.androidtel.telemetry_library.core.task

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A user task (processor #34): started with `TelemetryManager.startTask(name)`, ended by exactly one
 * of [complete], [fail] or [abandon], which sends the single terminal `task.complete`. Later calls are
 * ignored. A task still open when its session ends is abandoned with source `session_end`.
 *
 * ponytail: no `launch_recovery` — a task open at process death is lost, not reported. Add by
 * persisting open tasks if the completion rate shows the gap.
 */
class TelemetryTask internal constructor(
    val name: String,
    private val emit: (Map<String, Any>) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val onDone: (TelemetryTask) -> Unit = {}
) {
    private val start = clock()
    private val ended = AtomicBoolean(false)

    fun complete() = end("completed")

    fun fail(reason: String? = null) = end("failed", reason?.let { "task.failure_reason" to it })

    /** [source] is one of [SOURCE_SESSION_END], [SOURCE_TIMEOUT]; null when the app gave up on the task. */
    fun abandon(source: String? = null) = end("abandoned", source?.let { "task.abandon_source" to it })

    private fun end(outcome: String, extra: Pair<String, String>? = null) {
        if (!ended.compareAndSet(false, true)) return
        emit(
            mapOf("task.name" to name, "task.outcome" to outcome, "span.duration_ms" to clock() - start) +
                listOfNotNull(extra)
        )
        onDone(this)
    }

    companion object {
        const val SOURCE_SESSION_END = "session_end"
        const val SOURCE_TIMEOUT = "timeout"
    }
}
