package com.androidtel.telemetry_library.core.retry

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.crash.FatalCrashStore

/**
 * WorkManager retry for the durable fatal-crash file (issue #56).
 *
 * The frozen `app.crash` event on `filesDir` is replayed on every `TelemetryManager.initialize()`.
 * This worker covers the mid-session case: the init replay failed (offline) and the app keeps
 * running, so delivery isn't held hostage to the next cold start. Delete-only-after-2xx keeps the
 * crash exactly-once. Replay sends the frozen bytes as-is — no re-enrichment.
 */
internal object CrashReplay {
    private const val TAG = "CrashReplay"
    private const val WORK_NAME = "fatal_crash_replay"

    /** Best-effort schedule; a missing WorkManager (e.g. tests) must not take down the caller. */
    fun schedule(context: Context, apiKey: String, endpoint: String) {
        try {
            val work = OneTimeWorkRequestBuilder<CrashReplayWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("apiKey" to apiKey, "endpoint" to endpoint))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule fatal-crash replay", e)
        }
    }
}

class CrashReplayWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val apiKey = inputData.getString("apiKey")
        val endpoint = inputData.getString("endpoint")
        if (apiKey.isNullOrBlank() || endpoint.isNullOrBlank()) return Result.failure()

        val client = TelemetryHttpClient(endpoint, apiKey, debugMode = false)
        return if (FatalCrashStore.replayOnce(applicationContext.filesDir, client)) Result.success()
        else Result.retry()
    }
}
