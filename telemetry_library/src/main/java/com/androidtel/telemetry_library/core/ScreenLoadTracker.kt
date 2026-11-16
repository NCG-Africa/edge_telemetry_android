package com.androidtel.telemetry_library.core

import android.app.Activity
import android.os.SystemClock
import android.view.ViewTreeObserver

/**
 * `screen.load` (#145, catalogue #37): time from an Activity's creation to its first draw.
 *
 * ponytail: first frame only, no `screen.load.settled_ms`; Activities only, not Compose destinations.
 * Add settled when a screen can report "content ready", and Compose via the nav listener if asked.
 */
internal class ScreenLoadTracker(
    private val telemetryManager: TelemetryManager,
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {
    fun onCreated(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val start = clock()
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
