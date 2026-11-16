package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.SystemClock
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/**
 * `screen.load` (#145, catalogue #37): time from an Activity's creation to its first draw.
 *
 * The clock starts at create, but the draw listener is attached at start: touching the decor view
 * during onCreate would build it before the host's setContentView / requestWindowFeature.
 *
 * ponytail: first frame only, no `screen.load.settled_ms`; Activities only, not Compose destinations.
 * Add settled when a screen can report "content ready", and Compose via the nav listener if asked.
 */
internal class ScreenLoadTracker(
    private val telemetryManager: TelemetryManager,
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {
    private val created = WeakHashMap<Activity, Long>() // main thread only

    fun onCreated(activity: Activity) {
        created[activity] = clock()
    }

    fun onStarted(activity: Activity) {
        val start = created.remove(activity) ?: return // a restart is not a load
        val decor = activity.window?.peekDecorView() ?: return
        val screen = activity.javaClass.simpleName
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var fired = false
            override fun onDraw() {
                if (fired) return
                fired = true
                // A draw listener can't remove itself during dispatch.
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                telemetryManager.emit(
                    "screen.load",
                    mapOf("screen.name" to screen, "screen.load.first_frame_ms" to clock() - start)
                )
            }
        })
    }
}
