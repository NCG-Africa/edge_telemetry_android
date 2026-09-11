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
import com.androidtel.telemetry_library.core.trace.TraceManager
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

    /** What a mint resolved, held until the matching emit ~300 ms later. */
    internal data class PendingTap(val target: String, val nameSource: String, val x: Float, val y: Float)

    /**
     * Delta 12c, mint half - runs at ACTION_UP, BEFORE the delegate dispatches to Compose.
     *
     * Compose's `clickable` fires on ACTION_UP, so an app's Retrofit call goes out there. The root used
     * to mint ~300 ms later at onSingleTapConfirmed (GestureDetector withholds it for the double-tap
     * timeout), which meant every Compose tap's own request was stamped with the PREVIOUS action's root
     * or none at all. That was launch-to-request attribution misattributing every tap, and it stayed
     * invisible because every root was named `compose_surface` -- misattribution and correct
     * attribution looked identical.
     *
     * Suppression happens here, at mint, not at emit: a secure window or a password field must open no
     * root at all, or a suppressed tap would still parent the requests that follow it.
     *
     * Returns null when suppressed; otherwise the resolved tap, for the emit half to stamp.
     */
    internal fun mintRoot(window: Window, x: Float, y: Float): PendingTap? {
        // Secure surfaces: suppress everything. FLAG_SECURE is a window flag here (view-level
        // FLAG_SECURE on a SurfaceView is not readable via public API - window + password field
        // are the detectable surfaces, per spec §Privacy residual).
        if (isWindowSecure(window)) return null

        // x/y are window-relative pixels - the same space the decorView is hit-tested in, so the
        // recorded point always aligns with the resolved target (no screen-vs-window skew).
        val target = hitTest(window.decorView, x, y)
        if (target is EditText && isPasswordInputType(target.inputType)) return null // secure input

        val named = target?.let { resolveTarget(it, x, y) } ?: Named("unknown", "none")
        TraceManager.onInteractionStart(named.target)
        return PendingTap(named.target, named.source, x, y)
    }

    /**
     * Delta 12c, emit half - runs at gesture confirmation, attached to the root already opened at mint.
     * [pending] carries the name resolved at mint; a null [pending] means the tap was suppressed, so
     * nothing is emitted.
     */
    internal fun emitInteraction(type: String, pending: PendingTap?, direction: String?) {
        if (pending == null) return
        val attrs = mutableMapOf<String, Any>(
            "ui.type" to type,
            "ui.target" to pending.target,
            "ui.name_source" to pending.nameSource,
            "ui.x" to pending.x.toInt(),
            "ui.y" to pending.y.toInt()
        )
        if (direction != null) attrs["ui.direction"] = direction
        currentScreen()?.let { attrs["ui.screen"] = it }

        // The root was minted at ACTION_UP; this stamps its ids and its MINT time as span.start_time,
        // so the event never starts after the request it parents. Null (unsampled, or the root already
        // expired) leaves the event with no trace attrs.
        TraceManager.onInteractionEmit()?.let { attrs.putAll(it) }

        // Delta 12a - trackUserInteraction() may have renamed the root inside onClick, between mint and
        // emit. The root's name is authoritative when it differs: manual wins, exactly as trackTap
        // outranks the automatic chain.
        TraceManager.currentRootName()?.takeIf { it != pending.target }?.let {
            attrs["ui.target"] = it
            attrs["ui.name_source"] = ComposeSemanticsNamer.SOURCE_TRACK_TAP
        }

        telemetryManager.recordEvent("ui.interaction", attrs)
        telemetryManager.addBreadcrumb("$type ${attrs["ui.target"]}", category = "ui")
    }

    /**
     * Mint-then-emit in one call, for the gestures GestureDetector reports once (long press, fling).
     * Internal seam: exercised directly by tests without driving GestureDetector timing.
     */
    internal fun handleGesture(
        window: Window,
        type: String,
        x: Float,
        y: Float,
        direction: String?
    ) {
        emitInteraction(type, mintRoot(window, x, y), direction)
    }

    private inner class GestureListener(
        private val window: Window
    ) : GestureDetector.SimpleOnGestureListener() {

        /** Delta 12c - the tap resolved at ACTION_UP, waiting for the double-tap timeout to confirm. */
        private var pending: PendingTap? = null

        override fun onDown(e: MotionEvent): Boolean = true

        // Delta 12c - fires on ACTION_UP, before the delegate dispatches the event to Compose, so the
        // root is open by the time onClick runs and fires its request.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pending = mintRoot(window, e.x, e.y)
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            emitInteraction("tap", pending, null)
            pending = null
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

/** A resolved target name plus the source it came from, reported as `ui.name_source`. */
internal data class Named(val target: String, val source: String)

/**
 * `getResourceEntryName` when the view has an id; else the Compose semantics chain (Delta 12b) for a
 * Compose host, else the class simple name.
 *
 * `ui.name_source` exists because the v2 trace contract was write-only for two releases and nobody
 * could measure whether it worked. Ship semantics naming without it and the identical failure repeats:
 * you could not tell whether names come from real labels or whether the Role gate is silently eating
 * half the app.
 */
internal fun resolveTarget(view: View, x: Float, y: Float): Named {
    val id = view.id
    if (id != View.NO_ID) {
        try {
            return Named(view.resources.getResourceEntryName(id), "resource_id")
        } catch (_: Exception) {
            // fall through
        }
    }
    // Compose renders its whole tree into one AndroidComposeView (no per-composable id in the View
    // tree), so names come from the semantics tree instead - see ComposeSemanticsNamer.
    val simple = view.javaClass.simpleName
    if (simple == "AndroidComposeView") {
        val named = ComposeSemanticsNamer.resolve(view, x, y)
        return Named(named.target, named.source)
    }
    return Named(simple, "class_name")
}

/** Back-compat seam for existing callers/tests that only want the name. */
internal fun resolveTargetName(view: View): String = resolveTarget(view, 0f, 0f).target

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
