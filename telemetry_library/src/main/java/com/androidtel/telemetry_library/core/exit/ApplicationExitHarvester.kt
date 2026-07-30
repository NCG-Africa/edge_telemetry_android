package com.androidtel.telemetry_library.core.exit

import android.annotation.SuppressLint
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo

/**
 * One historical process-death record, decoupled from the final [ApplicationExitInfo] class so the
 * harvest logic (seam C, issue #94) is unit-testable without constructing an OS object.
 *
 * [rawTrace] is already materialized by the Android reader for ANR + native-crash records only
 * (the only reasons the OS attaches a trace to); null otherwise.
 */
internal data class ExitRecord(
    val reasonCode: Int,
    val importanceCode: Int,
    val description: String?,
    val status: Int,
    val pssKb: Long,
    val rssKb: Long,
    val timestamp: Long,
    val rawTrace: String?,
)

internal data class ExitHarvestResult(
    val events: List<Map<String, Any>>,
    val newWatermark: Long?,
)

/**
 * Pure harvest of the OS exit-reason buffer (spec: docs/specs/application-exit-info.md).
 *
 * Filters to the 8 abnormal reasons, applies the strict-`>` dedup watermark, maps reason/importance
 * ints to string enums, caps the trace, and returns one `app.exit` attribute map per surviving
 * record plus the advanced watermark. No Android runtime touched — the reason/importance references
 * below are compile-time int constants, so this is plain-JUnit testable.
 */
internal object ApplicationExitHarvester {

    // ~4000: double the SDK's 2000 stack cap. Over → head (culprit sits there) + marker.
    private const val TRACE_CAP = 4000
    private const val TRUNCATION_MARKER = "\n…[trace truncated]"

    // Abnormal reasons only — the stability-triage signal. Benign/retention reasons are absent, so
    // containsKey doubles as the drop filter.
    // InlinedApi is intentional: these API-30 constants inline as plain ints (that's what makes this
    // seam unit-testable), and the map is only consulted behind ExitInfoReader's SDK_INT>=30 gate.
    @SuppressLint("InlinedApi")
    private val REASON_ENUM: Map<Int, String> = mapOf(
        ApplicationExitInfo.REASON_ANR to "anr",
        ApplicationExitInfo.REASON_CRASH to "crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "crash_native",
        ApplicationExitInfo.REASON_LOW_MEMORY to "low_memory",
        ApplicationExitInfo.REASON_SIGNALED to "signaled",
        ApplicationExitInfo.REASON_DEPENDENCY_DIED to "dependency_died",
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "excessive_resource_usage",
        ApplicationExitInfo.REASON_PERMISSION_CHANGE to "permission_change",
    )

    @SuppressLint("InlinedApi") // same rationale as REASON_ENUM — inlined ints, gated at the reader
    private val IMPORTANCE_ENUM: Map<Int, String> = mapOf(
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND to "foreground",
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE to "foreground_service",
        RunningAppProcessInfo.IMPORTANCE_VISIBLE to "visible",
        RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE to "perceptible",
        RunningAppProcessInfo.IMPORTANCE_SERVICE to "service",
        RunningAppProcessInfo.IMPORTANCE_CACHED to "cached",
        RunningAppProcessInfo.IMPORTANCE_GONE to "gone",
    )

    fun harvest(records: List<ExitRecord>, watermark: Long?): ExitHarvestResult {
        val surviving = records.filter { r ->
            REASON_ENUM.containsKey(r.reasonCode) && (watermark == null || r.timestamp > watermark)
        }
        val events = surviving.map(::toAttributes)
        // Advance to newest emitted; keep the prior watermark when nothing was emitted (monotonic).
        val newWatermark = surviving.maxOfOrNull { it.timestamp } ?: watermark
        return ExitHarvestResult(events, newWatermark)
    }

    private fun toAttributes(r: ExitRecord): Map<String, Any> = buildMap {
        put("exit.reason", REASON_ENUM.getValue(r.reasonCode))
        put("exit.importance", IMPORTANCE_ENUM[r.importanceCode] ?: "unknown_${r.importanceCode}")
        r.description?.let { put("exit.description", it) }
        put("exit.status", r.status)
        // pss/rss are already KB from the OS; spec types these Int (KB comfortably fits Int).
        put("exit.pss_kb", r.pssKb.toInt())
        put("exit.rss_kb", r.rssKb.toInt())
        put("exit.timestamp", r.timestamp)
        capTrace(r.rawTrace)?.let { put("exit.trace", it) }
    }

    private fun capTrace(trace: String?): String? = when {
        trace == null -> null
        trace.length <= TRACE_CAP -> trace
        else -> trace.take(TRACE_CAP) + TRUNCATION_MARKER
    }
}
