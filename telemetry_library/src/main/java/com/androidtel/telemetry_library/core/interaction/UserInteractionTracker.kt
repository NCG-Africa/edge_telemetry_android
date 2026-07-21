package com.androidtel.telemetry_library.core.interaction

import android.app.Activity
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import com.androidtel.telemetry_library.core.TelemetryManager
import kotlin.math.abs

/**
 * Automatic user-interaction capture (issue #58 / spec `user-interaction-events.md`).
 *
 * One `ui.interaction` event per completed gesture, discriminated by `ui.type`
 * (tap / long_press / swipe). Attaches by wrapping the Activity's [Window.Callback] on resume
 * and restoring the original on pause. A [GestureDetector] collapses the raw MotionEvent stream
 * into one callback per gesture; the touch point is hit-tested against the decorView to resolve
 * a target, and capture is suppressed outright on secure surfaces (password field / FLAG_SECURE).
 */
class UserInteractionTracker(
    private val telemetryManager: TelemetryManager,
    private val currentScreen: () -> String?
) {

    /** Wrap the window callback so touches feed a per-window [GestureDetector]. Idempotent. */
    fun attach(activity: Activity) {
        val window = activity.window ?: return
        val original = window.callback ?: return
        if (original is InteractionCallback) return // already wrapped
        val detector = GestureDetector(activity, GestureListener(window))
        window.callback = InteractionCallback(original, detector)
    }

    /** Restore the original callback. Idempotent — safe if never attached. */
    fun detach(activity: Activity) {
        val window = activity.window ?: return
        val cb = window.callback
        if (cb is InteractionCallback) window.callback = cb.delegate
    }

    /**
     * Resolve target, suppress secure surfaces, and emit the event + breadcrumb.
     * Internal seam: exercised directly by tests without driving GestureDetector timing.
     */
    internal fun handleGesture(
        window: Window,
        type: String,
        x: Float,
        y: Float,
        direction: String?
    ) {
        // Secure surfaces: suppress everything. FLAG_SECURE is a window flag here (view-level
        // FLAG_SECURE on a SurfaceView is not readable via public API — window + password field
        // are the detectable surfaces, per spec §Privacy residual).
        if (isWindowSecure(window)) return

        // x/y are window-relative pixels — the same space the decorView is hit-tested in, so the
        // recorded point always aligns with the resolved target (no screen-vs-window skew).
        val target = hitTest(window.decorView, x, y)
        if (target is EditText && isPasswordInputType(target.inputType)) return // secure input: suppress

        val name = target?.let { resolveTargetName(it) } ?: "unknown"
        val attrs = mutableMapOf<String, Any>(
            "ui.type" to type,
            "ui.target" to name,
            "ui.x" to x.toInt(),
            "ui.y" to y.toInt()
        )
        if (direction != null) attrs["ui.direction"] = direction
        currentScreen()?.let { attrs["ui.screen"] = it }

        telemetryManager.recordEvent("ui.interaction", attrs)
        telemetryManager.addBreadcrumb("$type $name", category = "ui")
    }

    private inner class GestureListener(
        private val window: Window
    ) : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            handleGesture(window, "tap", e.x, e.y, null)
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            handleGesture(window, "long_press", e.x, e.y, null)
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            handleGesture(window, "swipe", e2.x, e2.y, swipeDirection(velocityX, velocityY))
            return false
        }
    }

    /** Delegating [Window.Callback] that also feeds touches to a [GestureDetector]. */
    internal class InteractionCallback(
        val delegate: Window.Callback,
        private val detector: GestureDetector
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            try {
                detector.onTouchEvent(event)
            } catch (_: Exception) {
                // never let telemetry break input dispatch
            }
            return delegate.dispatchTouchEvent(event)
        }
    }
}

// --- Pure helpers (internal, unit-tested directly) ---

/** Deepest View containing (x, y), searched child-first in reverse z-order. Null if outside root. */
internal fun hitTest(root: View, x: Float, y: Float): View? {
    if (!contains(root, x, y)) return null
    if (root is ViewGroup) {
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val hit = hitTest(child, x - root.left, y - root.top)
            if (hit != null) return hit
        }
    }
    return root
}

private fun contains(v: View, x: Float, y: Float): Boolean =
    x >= v.left && x < v.right && y >= v.top && y < v.bottom

/** `getResourceEntryName` when the view has an id; else class simple name (compose surface mapped). */
internal fun resolveTargetName(view: View): String {
    val id = view.id
    if (id != View.NO_ID) {
        try {
            return view.resources.getResourceEntryName(id)
        } catch (_: Exception) {
            // fall through to class name
        }
    }
    // ponytail: Compose renders its whole tree in one AndroidComposeView (no per-composable id in
    // the View tree). v1 is coordinate-only — map that surface to a stable name. Per-composable
    // identity graduates via opt-in Modifier.trackTap (fog).
    val simple = view.javaClass.simpleName
    return if (simple == "AndroidComposeView") "compose_surface" else simple
}

internal fun swipeDirection(velocityX: Float, velocityY: Float): String =
    if (abs(velocityX) > abs(velocityY)) {
        if (velocityX > 0) "right" else "left"
    } else {
        if (velocityY > 0) "down" else "up"
    }

internal fun isWindowSecure(window: Window): Boolean =
    (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

/** True for text/number password variations — coordinates over these leak keystrokes (spec §Privacy). */
internal fun isPasswordInputType(inputType: Int): Boolean {
    val variation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
    return variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
        variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
        variation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
        variation == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}
