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
 * Main-thread watchdog (issue #60 ANR, extended by issue #61 hang; specs `docs/specs/anr-detection.md`
 * and `docs/specs/hang-detection.md`).
 *
 * A daemon thread posts a heartbeat to the main `Looper` and waits. One measurement — how long the
 * heartbeat took to run — is read against two lines:
 *  - [HANG_THRESHOLD_MS] (2s): a visible freeze below the ANR line → [onHang] with the main thread's
 *    stack only (#61). Ships on the normal batch.
 *  - [ANR_THRESHOLD_MS] (5s): Google's ANR line → [onAnr] with an all-thread dump, main flagged (#60).
 *    Ships on the durable rail.
 * Works on minSdk 24, no API gate.
 *
 * One unbroken stall can cross both lines and fires BOTH — a 6s block is a hang AND an anr; they're
 * distinct signals counted independently (#61 §2). Dedup is intra-threshold (one hang per stall, one
 * anr per stall), not cross-threshold: the terminal anr does not suppress its own precursor hang. Each
 * latch clears when the main thread runs a heartbeat again.
 *
 * Foreground-only: the SDK calls [start] on foreground and [stop] on background (spec §Lifecycle).
 *
 * ponytail: thresholds + interval are internal constants, not config — the lines are the platform's
 * and won't vary per consumer. Upgrade path: promote to TelemetryConfig only if one needs a different line.
 */
class AnrWatchdog(
    private val onAnr: (durationMs: Long, threads: List<ThreadDump>) -> Unit,
    private val onHang: (durationMs: Long, stack: String) -> Unit,
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
    // Per-threshold latches so one stall = one hang + one anr; both cleared when the main thread answers.
    @Volatile private var hangFired = false
    @Volatile private var anrFired = false
    @Volatile private var running = false
    private var worker: Thread? = null

    // The main thread runs this when it's free again: heartbeat answered, re-arm for the next stall.
    private val pong = Runnable {
        waiting = false
        hangFired = false
        anrFired = false
    }

    fun start() {
        if (running) return
        running = true
        waiting = false
        hangFired = false
        anrFired = false
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
        // Two independent lines off one measurement; both can trip on the same scan (a 6s stall seen
        // in one read is a hang and an anr). Not else-if — intra-threshold dedup, not cross-threshold.
        if (!hangFired && blockedFor >= HANG_THRESHOLD_MS) {
            hangFired = true
            onHang(blockedFor, mainThreadStack())
        }
        if (!anrFired && blockedFor >= ANR_THRESHOLD_MS) {
            anrFired = true
            onAnr(blockedFor, captureThreads())
        }
    }

    // Main-thread stack only — a sub-5s stall is slow synchronous main-thread work; the culprit is on
    // this stack. The all-thread dump stays exclusive to app.anr (#61 §4).
    private fun mainThreadStack(): String =
        Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "at $it" }

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
        // ponytail: fixed 2s — the "user noticed a freeze" line below ANR (hang spec §1).
        const val HANG_THRESHOLD_MS = 2000L
        // ponytail: fixed 5s — the system input-dispatch ANR line (spec §2).
        const val ANR_THRESHOLD_MS = 5000L
        // Heartbeat interval = min(hangThreshold/2, anrThreshold/2) = 1000ms, so a real 2s stall is
        // caught in one scan (#61 §2). Cheaper posts, still negligible.
        const val INTERVAL_MS = 1000L
        private const val THREAD_NAME = "edge-anr-watchdog"
    }
}
