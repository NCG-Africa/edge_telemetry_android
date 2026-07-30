package com.androidtel.telemetry_library.core.startup

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cold-start timing (issue #95, spec `docs/specs/app-start-timing.md`). One `app.start` per process:
 * start = `Process.getStartUptimeMillis()` (true fork time, stable regardless of when init runs),
 * end = the first Activity `onResume` this process observes. Only that first resume is timed.
 *
 * The sample is dropped (nothing emitted) when either background-start guard trips:
 *  1. importance at init worse than [IMPORTANCE_VISIBLE] — process forked for background work, so the
 *     "first resume" would be a bogus multi-minute cold start.
 *  2. duration > [MAX_DURATION_MS] — absolute backstop for anything the importance check misses.
 *
 * Seam B (spec §Test plan; prior art AnrWatchdogTest): start-time / importance / clock are injected so
 * the emit-vs-drop decision is unit-tested without a real process or clock. Production reads the real
 * values at SDK init and the real uptime clock.
 *
 * ponytail: guards + importance line are internal constants, not config — the 60s cap and the VISIBLE
 * line are the platform's, not per-consumer. Upgrade path: promote to TelemetryConfig only if one needs it.
 */
class AppStartTracker(
    private val startUptimeMs: Long,
    private val importanceAtInit: Int,
    private val emit: (type: String, durationMs: Int) -> Unit,
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {
    // One-shot latch: the first observed resume is timed, all later resumes are ignored. Resumes can
    // arrive on the main thread only, but AtomicBoolean keeps the contract explicit and cheap.
    private val timed = AtomicBoolean(false)

    /** Call on every Activity onResume; acts on the first only. Emits one `app.start` or drops. */
    fun onFirstResume() {
        if (!timed.compareAndSet(false, true)) return

        // Guard 1: not foreground-intended at init → background-forked start, drop.
        if (importanceAtInit > IMPORTANCE_VISIBLE) return

        val durationMs = clock() - startUptimeMs
        // Guard 2: absolute duration cap, drop.
        if (durationMs > MAX_DURATION_MS) return

        emit(TYPE_COLD, durationMs.toInt())
    }

    companion object {
        // Only value today; key reserved so warm/hot graduate without a schema break (spec §Decision).
        const val TYPE_COLD = "cold"
        // RunningAppProcessInfo.IMPORTANCE_VISIBLE (200). Keep FOREGROUND(100)/VISIBLE(200), drop worse
        // (lower numeric importance = more foreground). Inlined so this class stays android-free (seam).
        const val IMPORTANCE_VISIBLE = 200
        const val MAX_DURATION_MS = 60_000L
    }
}
