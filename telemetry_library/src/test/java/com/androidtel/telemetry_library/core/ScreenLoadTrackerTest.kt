package com.androidtel.telemetry_library.core

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** screen.load (#145): Activity created → its first draw, sent once per screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenLoadTrackerTest {

    @Test
    fun `create does not touch the window, so the host's setContentView still owns the decor`() {
        val activity = mockk<Activity>(relaxed = true)
        ScreenLoadTracker(mockk(relaxed = true)).onCreated(activity)
        verify(exactly = 0) { activity.window }
    }

    @Test
    fun `first draw sends one screen_load with the time since create`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val attrs = mutableListOf<Map<String, Any>>()
        every { manager.emit(eq("screen.load"), capture(attrs), any()) } returns Unit
        var now = 1_000L
        val tracker = ScreenLoadTracker(manager) { now }
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()

        tracker.onCreated(activity)
        activity.setContentView(android.view.View(activity))
        tracker.onStarted(activity)
        tracker.onStarted(activity) // a restart attaches nothing new
        now += 240
        repeat(2) { activity.window.decorView.viewTreeObserver.dispatchOnDraw() }
        ShadowLooper.idleMainLooper()

        verify(exactly = 1) { manager.emit(eq("screen.load"), any(), any()) }
        assertEquals(mapOf("screen.name" to "Activity", "screen.load.first_frame_ms" to 240L), attrs.single())
    }
}
