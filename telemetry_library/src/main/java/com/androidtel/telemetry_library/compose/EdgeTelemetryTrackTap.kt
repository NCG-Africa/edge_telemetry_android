package com.androidtel.telemetry_library.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * Δ12b — the explicit name for a Compose tap target, written into the semantics tree the interaction
 * tracker already reads.
 *
 * A `SemanticsPropertyKey`, not a coordinate registry: the semantics tree already maintains bounds
 * through the layout system, nodes leave it when they leave composition (nothing to unregister, no
 * retention), and innermost-hit resolution is plain tree descent. The registry design that #131 worried
 * about — lifetime, cleanup, bounds — simply dissolves.
 *
 * A private key rather than `testTag`: reusing `testTag` would write into the app's test-identification
 * namespace, colliding with their tests and with `testTagsAsResourceId`. A private key cannot collide.
 *
 * Semantics modifiers combine on the same layout node, so `.clickable{}.trackTap("x")` and
 * `.trackTap("x").clickable{}` are identical — order does not matter.
 *
 * ```
 * NcgCard(modifier = Modifier.fillMaxWidth().clickable { onCandidate(candidate) }.trackTap("candidate_card"))
 * ```
 */
internal val EdgeActionKey = SemanticsPropertyKey<String>("EdgeTelemetryAction")

internal var SemanticsPropertyReceiver.edgeAction by EdgeActionKey

/**
 * Names this node for user-interaction tracking. Outranks every automatic naming source, so it is the
 * override for taps the Role gate deliberately declines to name — a bare `Modifier.clickable` wrapping
 * rendered data, which reports `ui.target = unnamed` until you name it here.
 */
fun Modifier.trackTap(name: String): Modifier = semantics { edgeAction = name }
