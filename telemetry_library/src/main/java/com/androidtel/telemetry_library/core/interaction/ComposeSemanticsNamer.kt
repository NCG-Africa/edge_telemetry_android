package com.androidtel.telemetry_library.core.interaction

import android.util.Log
import android.view.View
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.androidtel.telemetry_library.compose.EdgeActionKey
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Δ12b — resolves a human-meaningful name for a tap that landed on Compose content.
 *
 * Without this, every Compose tap maps to the literal `compose_surface` (the whole tree renders into a
 * single `AndroidComposeView`, which has no per-composable View id). In a 100 % Compose app that makes
 * action identity a **single constant** — launch-to-request attribution would be unreadable even with
 * Δ6 and Δ7 landed.
 *
 * The name chain, innermost merged semantics node first:
 *
 *  1. [EdgeActionKey] — `Modifier.trackTap("…")`, explicit, always wins → `track_tap`
 *  2. `TestTag` — free when the app already uses it → `test_tag`
 *  3. **only behind the Role gate** — `ContentDescription`, then merged `Text`
 *  4. nothing survives → `unnamed` / `none`
 *
 * **The Role gate is the privacy control.** Material components (`Button`, `TextButton`, `IconButton`,
 * tabs, switches) set a `Role` *and* carry authored, literal labels; a bare `Modifier.clickable`
 * wrapping rendered data does not set one. So the gate reads authored labels and declines data: a card
 * whose merged text is a person's name stays `unnamed` rather than shipping that name to the backend.
 * `trackTap` is the escape hatch for exactly those nodes.
 *
 * Failure discipline follows this package's existing stance — never let telemetry break input dispatch.
 * Any failure degrades to today's behaviour (`compose_surface` / `none`), warning once so an androidx
 * break is visible rather than silent.
 */
internal object ComposeSemanticsNamer {

    /** Roles that mark an authored control, as opposed to a clickable wrapper around content. */
    private val NAMEABLE_ROLES = setOf(
        Role.Button, Role.Tab, Role.Checkbox, Role.RadioButton, Role.Switch
    )

    private const val MAX_NAME_LENGTH = 64
    private val warned = AtomicBoolean(false)

    /** A resolved name plus where it came from, so `ui.name_source` can make adoption evidence-driven. */
    internal data class Named(val target: String, val source: String)

    const val SOURCE_TRACK_TAP = "track_tap"
    const val SOURCE_TEST_TAG = "test_tag"
    const val SOURCE_CONTENT_DESCRIPTION = "content_description"
    const val SOURCE_TEXT = "text"
    const val SOURCE_NONE = "none"

    const val TARGET_UNNAMED = "unnamed"
    const val TARGET_COMPOSE_SURFACE = "compose_surface"

    /**
     * Resolve the tap at window-relative ([x], [y]) — the same coordinate space `handleGesture` and
     * `boundsInWindow` both work in, so there is no screen-vs-window skew and no conversion.
     *
     * Returns `compose_surface` when the tap hit no semantics node at all (background, padding, a
     * `Spacer`): noise, nothing to fix. Returns `unnamed` when it hit a node that the chain declined to
     * name: actionable, someone should add `trackTap`. Collapsing the two would recreate the original
     * disease in miniature — "40 % unnamed" with no way to tell missing instrumentation from people
     * tapping whitespace.
     */
    fun resolve(view: View, x: Float, y: Float): Named = try {
        val root = (view as? RootForTest)?.semanticsOwner?.rootSemanticsNode
        val hit = root?.let { innermostAt(it, x, y) }
        if (hit == null) Named(TARGET_COMPOSE_SURFACE, SOURCE_NONE) else nameOf(hit)
    } catch (t: Throwable) {
        warnOnce(t)
        Named(TARGET_COMPOSE_SURFACE, SOURCE_NONE)
    }

    /** Deepest node whose window bounds contain the point; children first, so the innermost wins. */
    private fun innermostAt(node: SemanticsNode, x: Float, y: Float): SemanticsNode? {
        if (!node.boundsInWindow.contains(androidx.compose.ui.geometry.Offset(x, y))) return null
        for (child in node.children.asReversed()) {
            innermostAt(child, x, y)?.let { return it }
        }
        return node
    }

    private fun nameOf(node: SemanticsNode): Named {
        val config = node.config

        config.getOrNull(EdgeActionKey)?.let { explicit ->
            return Named(normalize(explicit) ?: TARGET_UNNAMED, SOURCE_TRACK_TAP)
        }
        config.getOrNull(SemanticsProperties.TestTag)?.let { tag ->
            normalize(tag)?.let { return Named(it, SOURCE_TEST_TAG) }
        }

        // The gate. Role is an inline value class over Int, so this comparison must stay in Kotlin —
        // the mangled JVM accessor is not a stable Java surface.
        val role = config.getOrNull(SemanticsProperties.Role)
        if (role == null || role !in NAMEABLE_ROLES) return Named(TARGET_UNNAMED, SOURCE_NONE)

        config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            ?.let { normalize(it) }
            ?.let { return Named(it, SOURCE_CONTENT_DESCRIPTION) }

        config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            ?.let { normalize(it) }
            ?.let { return Named(it, SOURCE_TEXT) }

        return Named(TARGET_UNNAMED, SOURCE_NONE)
    }

    /**
     * `"Send reset link"` → `send_reset_link`. Aligns the Compose path with the View path's
     * `getResourceEntryName` shape (`btn_login`), so the two produce comparable cardinality.
     */
    internal fun normalize(raw: String): String? {
        val slug = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(MAX_NAME_LENGTH)
            .trim('_')
        return slug.ifBlank { null }
    }

    private fun warnOnce(t: Throwable) {
        if (!warned.compareAndSet(false, true)) return
        Log.w(
            "ComposeSemanticsNamer",
            "Compose semantics lookup failed; tap names fall back to compose_surface. This usually " +
                "means an androidx.compose.ui API changed.",
            t
        )
    }
}
