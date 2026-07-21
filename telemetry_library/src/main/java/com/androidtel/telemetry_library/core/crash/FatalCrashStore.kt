package com.androidtel.telemetry_library.core.crash

import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/**
 * Durable single-slot store for a frozen fatal crash (issue #56).
 *
 * On `filesDir` — not `cacheDir` — so the OS never evicts it under storage pressure; cleared only on
 * uninstall / clear-data. It has exactly ONE writer: the uncaught-exception handler on the single
 * dying thread, so no `FileLock`/`RandomAccessFile` machinery is needed — a plain blocking write +
 * `fd.sync()` is enough, and it must complete before the process is killed.
 */
internal object FatalCrashStore {
    private const val TAG = "FatalCrashStore"
    private const val FILE_NAME = "pending_fatal_crash.json"

    fun file(filesDir: File): File = File(filesDir, FILE_NAME)

    /**
     * Synchronous, fsync'd write on the crashing thread. Blocks until the bytes are on disk so the
     * frozen crash survives an immediate process death.
     */
    fun writeBlocking(filesDir: File, json: String) {
        val f = file(filesDir)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    /** Reads the frozen batch, or null if absent. A corrupt file is dropped so replay can't loop. */
    fun readBatch(filesDir: File, gson: Gson): TelemetryBatch? {
        val f = file(filesDir)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            gson.fromJson(f.readText(), TelemetryBatch::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt fatal-crash file — deleting", e)
            f.delete()
            null
        }
    }

    fun delete(filesDir: File) {
        val f = file(filesDir)
        if (f.exists()) f.delete()
    }

    /**
     * The exactly-once delivery invariant, in one place: send the frozen crash as-is, delete only on
     * a 2xx. Returns true when there's nothing to send or it was delivered; false when it must be
     * retried. Both replay rails (init + WorkManager) call this so the invariant can't drift.
     */
    suspend fun replayOnce(filesDir: File, client: TelemetryHttpClient, gson: Gson = Gson()): Boolean {
        val batch = readBatch(filesDir, gson) ?: return true
        return if (client.sendBatch(batch).isSuccess) {
            delete(filesDir)
            true
        } else {
            false
        }
    }
}
