package com.androidtel.telemetry_library.core.exit

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android glue for the exit-reason harvest (issue #94). Reads the OS
 * `getHistoricalProcessExitReasons()` buffer once, applies the pure [ApplicationExitHarvester], and
 * emits one `app.exit` event per surviving abnormal death.
 *
 * Blocking I/O (buffer read + `getTraceInputStream()`) — call only off the main thread. Gated
 * `SDK_INT >= 30` by the single [harvest] entry point; absent, not stubbed, below.
 */
internal object ExitInfoReader {

    private const val TAG = "ExitInfoReader"
    private const val PREFS = "telemetry_exit"
    private const val KEY_WATERMARK = "telemetry_last_exit_ts"

    /** No-op below API 30. On API 30+, harvests and emits via [emit]; each map is one `app.exit`. */
    fun harvest(context: Context, emit: (Map<String, Any>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        harvestApi30(context, emit)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun harvestApi30(context: Context, emit: (Map<String, Any>) -> Unit) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            // packageName=null (this app), pid=0 (all), maxNum=0 (all available) — newest first.
            val infos = am.getHistoricalProcessExitReasons(null, 0, 0)
            if (infos.isEmpty()) return

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val watermark = prefs.getLong(KEY_WATERMARK, -1L).takeIf { it >= 0 }

            val result = ApplicationExitHarvester.harvest(infos.map(::toRecord), watermark)
            result.events.forEach(emit)
            result.newWatermark?.let { prefs.edit().putLong(KEY_WATERMARK, it).apply() }
        } catch (e: Exception) {
            // Never let a diagnostic read take down init. Buffer read can throw on odd OEM builds.
            Log.w(TAG, "Exit-reason harvest failed", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toRecord(info: ApplicationExitInfo) = ExitRecord(
        reasonCode = info.reason,
        importanceCode = info.importance,
        description = info.description,
        status = info.status,
        pssKb = info.pss,
        rssKb = info.rss,
        timestamp = info.timestamp,
        // OS attaches a trace only to ANR and native-crash deaths; skip the stream read otherwise.
        rawTrace = if (info.reason == ApplicationExitInfo.REASON_ANR ||
            info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        ) readTrace(info) else null,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "Exit trace read failed", e)
        null
    }
}
