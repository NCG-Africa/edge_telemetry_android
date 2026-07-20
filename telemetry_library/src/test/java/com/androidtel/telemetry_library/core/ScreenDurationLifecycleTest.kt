package com.androidtel.telemetry_library.core

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #53, Part 2: screen lifecycle emits `screen.duration` (with exit_method) on the pause→stop
 * flow and never the retired `screen_view`. Fails before the fix — the pause path fired `screen_view`
 * and consumed the timing, starving `screen.duration`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenDurationLifecycleTest {

    @Test
    fun `pause then stop emits screen_duration, never screen_view`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        every { manager.getSessionId() } returns "session-1"

        val observer = TelemetryActivityLifecycleObserver(manager)
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

        observer.onActivityResumed(activity)   // start screen timing
        observer.onActivityPaused(activity)    // must NOT emit screen_view or consume timing
        observer.onActivityStopped(activity)   // owns endScreen -> emits screen.duration

        verify(exactly = 0) { manager.recordEvent(eq("screen_view"), any()) }
        verify(exactly = 1) { manager.recordScreenDuration(any(), any(), eq("closed")) }
    }
}
