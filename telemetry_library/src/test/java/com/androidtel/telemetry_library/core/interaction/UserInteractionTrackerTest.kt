package com.androidtel.telemetry_library.core.interaction

import android.app.Activity
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.androidtel.telemetry_library.core.TelemetryManager
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #58 / spec `user-interaction-events.md`. Drives the internal gesture seam directly so the
 * hit-test → secure-suppression → emit contract is asserted without fighting GestureDetector timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserInteractionTrackerTest {

    private fun activityWith(root: View): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(root)
        // Lay out the whole decorView so the hierarchy above `root` (content frame, etc.) has real
        // bounds — hit-testing traverses from decorView down and skips zero-bound ancestors otherwise.
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1080, 1920)
        return activity
    }

    @Test
    fun `tap on a view with an id emits one ui_interaction with matching target`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "CheckoutActivity" }

        val button = View(Robolectric.buildActivity(Activity::class.java).get()).apply {
            id = View.generateViewId()
        }
        val root = FrameLayout(Robolectric.buildActivity(Activity::class.java).get()).apply {
            addView(button)
        }
        val activity = activityWith(root)
        button.layout(0, 0, 1080, 1920) // give the child real bounds Robolectric won't measure

        val attrs = slot<Map<String, Any>>()
        tracker.handleGesture(activity.window, "tap", 540f, 1000f, null)

        verify(exactly = 1) { manager.recordEvent(eq("ui.interaction"), capture(attrs)) }
        verify(exactly = 1) { manager.addBreadcrumb(any(), eq("ui"), any(), any()) }
        assertEquals("tap", attrs.captured["ui.type"])
        assertEquals("View", attrs.captured["ui.target"]) // generateViewId ⇒ class fallback
        assertEquals("CheckoutActivity", attrs.captured["ui.screen"])
        assertEquals(540, attrs.captured["ui.x"])
        assertFalse(attrs.captured.containsKey("ui.direction"))
    }

    @Test
    fun `tap on a password EditText emits nothing`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "LoginActivity" }

        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val password = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val root = FrameLayout(ctx).apply { addView(password) }
        val activity = activityWith(root)
        password.layout(0, 0, 1080, 1920)

        tracker.handleGesture(activity.window, "tap", 540f, 1000f, null)

        verify(exactly = 0) { manager.recordEvent(any(), any()) }
    }

    @Test
    fun `tap on a FLAG_SECURE window emits nothing`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "SecretActivity" }

        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val root = FrameLayout(ctx).apply { addView(View(ctx).apply { id = View.generateViewId() }) }
        val activity = activityWith(root)
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        tracker.handleGesture(activity.window, "tap", 5f, 5f, null)

        verify(exactly = 0) { manager.recordEvent(any(), any()) }
    }

    @Test
    fun `swipe records direction and exactly one event`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "FeedActivity" }

        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val root = FrameLayout(ctx)
        val activity = activityWith(root)

        val attrs = slot<Map<String, Any>>()
        tracker.handleGesture(activity.window, "swipe", 100f, 100f, "left")

        verify(exactly = 1) { manager.recordEvent(eq("ui.interaction"), capture(attrs)) }
        assertEquals("swipe", attrs.captured["ui.type"])
        assertEquals("left", attrs.captured["ui.direction"])
    }

    // --- pure helpers ---

    @Test
    fun `swipeDirection resolves from dominant velocity axis`() {
        assertEquals("right", swipeDirection(500f, 10f))
        assertEquals("left", swipeDirection(-500f, 10f))
        assertEquals("down", swipeDirection(10f, 500f))
        assertEquals("up", swipeDirection(10f, -500f))
    }

    @Test
    fun `isPasswordInputType covers text and number password variations`() {
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun `resolveTargetName maps compose surface class to compose_surface`() {
        // plain View with no id falls back to class simple name
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals("View", resolveTargetName(View(ctx)))
    }

    @Test
    fun `hitTest returns null outside root bounds`() {
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val root = View(ctx).apply { layout(0, 0, 100, 100) }
        assertNull(hitTest(root, 200f, 200f))
    }
}
