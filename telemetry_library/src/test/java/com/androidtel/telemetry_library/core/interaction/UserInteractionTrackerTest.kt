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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.androidtel.telemetry_library.core.trace.TraceManager
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

    // --- Delta 12c: the mint/emit split ---

    @Test
    fun `mint opens the root before the event is emitted`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "VoteActivity" }
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val activity = activityWith(FrameLayout(ctx))

        TraceManager.resetForTesting()
        val pending = tracker.mintRoot(activity.window, 100f, 100f)

        // This is the whole point of Delta 12c: at ACTION_UP, before the ~300 ms double-tap timeout,
        // Compose has already run onClick and fired its request. The root must exist by now.
        val root = TraceManager.current()
        assertNotNull("root is open at ACTION_UP, not at tap-confirm", root)
        verify(exactly = 0) { manager.recordEvent(any(), any()) }

        tracker.emitInteraction("tap", pending, null)
        val attrs = slot<Map<String, Any>>()
        verify(exactly = 1) { manager.recordEvent(eq("ui.interaction"), capture(attrs)) }
        assertEquals(root!!.spanId, attrs.captured["rum.action.id"])
        TraceManager.resetForTesting()
    }

    @Test
    fun `a double tap mints two roots and emits no event`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "VoteActivity" }
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val activity = activityWith(FrameLayout(ctx))

        TraceManager.resetForTesting()
        tracker.mintRoot(activity.window, 10f, 10f)
        val first = TraceManager.current()!!.spanId
        tracker.mintRoot(activity.window, 10f, 10f)   // onSingleTapConfirmed never fires

        assertNotEquals("second tap mints its own root", first, TraceManager.current()!!.spanId)
        verify(exactly = 0) { manager.recordEvent(any(), any()) }  // Delta 7's idle window reaps both
        TraceManager.resetForTesting()
    }

    @Test
    fun `suppression happens at mint, so a suppressed tap parents nothing`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "LoginActivity" }
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val password = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val activity = activityWith(FrameLayout(ctx).apply { addView(password) })
        password.layout(0, 0, 1080, 1920)

        TraceManager.resetForTesting()
        val pending = tracker.mintRoot(activity.window, 540f, 1000f)

        assertNull("no pending tap", pending)
        assertNull("and crucially no root, or the next request would be parented by it",
            TraceManager.current())
        tracker.emitInteraction("tap", pending, null)
        verify(exactly = 0) { manager.recordEvent(any(), any()) }
        TraceManager.resetForTesting()
    }

    @Test
    fun `trackUserInteraction renaming the open root wins at emit`() {
        val manager = mockk<TelemetryManager>(relaxed = true)
        val tracker = UserInteractionTracker(manager) { "VoteActivity" }
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val activity = activityWith(FrameLayout(ctx))

        TraceManager.resetForTesting()
        val pending = tracker.mintRoot(activity.window, 10f, 10f)
        TraceManager.nameCurrentRoot("vote")          // what the app's onClick handler does, mid-gesture
        tracker.emitInteraction("tap", pending, null)

        val attrs = slot<Map<String, Any>>()
        verify(exactly = 1) { manager.recordEvent(eq("ui.interaction"), capture(attrs)) }
        assertEquals("vote", attrs.captured["ui.target"])
        assertEquals("track_tap", attrs.captured["ui.name_source"])
        TraceManager.resetForTesting()
    }

    // --- Delta 12b: naming ---

    @Test
    fun `the View path reports its name source`() {
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals("class_name", resolveTarget(View(ctx), 0f, 0f).source)
        assertEquals(
            "resource_id",
            resolveTarget(View(ctx).apply { id = android.R.id.text1 }, 0f, 0f).source
        )
    }

    @Test
    fun `names normalize to the same snake_case shape the View path produces`() {
        assertEquals("send_reset_link", ComposeSemanticsNamer.normalize("Send reset link"))
        assertEquals("confirm_vote", ComposeSemanticsNamer.normalize("  Confirm vote!  "))
        assertEquals("sign_in", ComposeSemanticsNamer.normalize("Sign-In"))
        assertNull("nothing usable survives", ComposeSemanticsNamer.normalize("   ***   "))
        assertEquals(64, ComposeSemanticsNamer.normalize("a".repeat(100))!!.length)
    }

    @Test
    fun `hitTest returns null outside root bounds`() {
        val ctx = Robolectric.buildActivity(Activity::class.java).get()
        val root = View(ctx).apply { layout(0, 0, 100, 100) }
        assertNull(hitTest(root, 200f, 200f))
    }
}
