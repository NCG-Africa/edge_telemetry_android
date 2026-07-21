package com.androidtel.telemetry_library.core.anr

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * One thread of every thread's stack, main flagged — the payload of an `app.anr` event (issue #60).
 */
data class ThreadDump(
    val name: String,
    val state: String,
    val main: Boolean,
    val stack: String
)

/**
 * Main-thread watchdog (issue #60, spec `docs/specs/anr-detection.md`).
 *
 * A daemon thread posts a heartbeat to the main `Looper` and waits. If the main thread doesn't run
 * the heartbeat within [THRESHOLD_MS] (Google's 5s ANR line), the main thread is blocked → capture an
 * all-thread dump (main flagged) and hand it to [onAnr]. Works on minSdk 24, no API gate.
 *
 * Foreground-only: the SDK calls [start] on foreground and [stop] on background (spec §Lifecycle).
 * Intra-hang dedup: one long hang fires exactly once — the fire latches until the main thread runs a
 * heartbeat again (spec §6).
 *
 * ponytail: threshold + interval are internal constants, not config — the line is the platform's and
 * won't vary per consumer. Upgrade path: promote to TelemetryConfig only if one needs a different line.
 */
class AnrWatchdog(
    private val onAnr: (durationMs: Long, threads: List<ThreadDump>) -> Unit,
    // Seams for unit testing (spec §Test plan, "Seam 2, fake clock"). Production reads the real clock
    // and posts to the real main Looper.
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {
    // Whether a heartbeat is outstanding, and when it was posted. A boolean flag (not a 0-sentinel on
    // the timestamp) so a clock reading of 0 can't masquerade as "answered". @Volatile: the pong runs
    // on the main thread, scanOnce on the watchdog thread.
    @Volatile private var waiting = false
    @Volatile private var pendingSince = 0L
    // Latches after a fire so one hang = one event; cleared when the main thread finally answers.
    @Volatile private var alreadyFired = false
    @Volatile private var running = false
    private var worker: Thread? = null

    // The main thread runs this when it's free again: heartbeat answered, re-arm for the next hang.
    private val pong = Runnable {
        waiting = false
        alreadyFired = false
    }

    fun start() {
        if (running) return
        running = true
        waiting = false
        alreadyFired = false
        worker = Thread(::loop, THREAD_NAME).apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        while (running) {
            scanOnce()
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /**
     * One heartbeat cycle. Seam: tests drive this directly with a fake clock instead of the daemon
     * loop. Snapshots `pending` once so a concurrent pong can't turn a live gap into a false positive.
     */
    internal fun scanOnce() {
        if (!waiting) {
            waiting = true
            pendingSince = clock()
            mainHandler.post(pong)
            return
        }
        val blockedFor = clock() - pendingSince
        if (!alreadyFired && blockedFor >= THRESHOLD_MS) {
            alreadyFired = true
            onAnr(blockedFor, captureThreads())
        }
    }

    private fun captureThreads(): List<ThreadDump> {
        val main = Looper.getMainLooper().thread
        return Thread.getAllStackTraces().map { (thread, frames) ->
            ThreadDump(
                name = thread.name,
                state = thread.state.name,
                main = thread === main,
                stack = frames.joinToString("\n") { "at $it" }
            )
        }
    }

    companion object {
        // ponytail: fixed 5s — the system input-dispatch ANR line (spec §2).
        const val THRESHOLD_MS = 5000L
        // Heartbeat interval = threshold/2, floored at 500ms, so a real 5s hang is caught in one scan.
        const val INTERVAL_MS = 2500L
        private const val THREAD_NAME = "edge-anr-watchdog"
    }
}
