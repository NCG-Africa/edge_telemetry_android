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
    const val FILE_NAME = "pending_fatal_crash.json"
    // ANR rides the same rail on its own slot (issue #60): same freeze → replay → delete-after-2xx,
    // a separate file so a later fatal crash can't overwrite a pending ANR. is_fatal:false in the
    // record distinguishes it downstream.
    const val ANR_FILE_NAME = "pending_anr.json"

    fun file(filesDir: File, fileName: String = FILE_NAME): File = File(filesDir, fileName)

    /**
     * Synchronous, fsync'd write on the crashing thread. Blocks until the bytes are on disk so the
     * frozen crash survives an immediate process death.
     */
    fun writeBlocking(filesDir: File, json: String, fileName: String = FILE_NAME) {
        val f = file(filesDir, fileName)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    /** Reads the frozen batch, or null if absent. A corrupt file is dropped so replay can't loop. */
    fun readBatch(filesDir: File, gson: Gson, fileName: String = FILE_NAME): TelemetryBatch? {
        val f = file(filesDir, fileName)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            gson.fromJson(f.readText(), TelemetryBatch::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt frozen-event file $fileName — deleting", e)
            f.delete()
            null
        }
    }

    fun delete(filesDir: File, fileName: String = FILE_NAME) {
        val f = file(filesDir, fileName)
        if (f.exists()) f.delete()
    }

    /**
     * The exactly-once delivery invariant, in one place: send the frozen record as-is, delete only on
     * a 2xx. Returns true when there's nothing to send or it was delivered; false when it must be
     * retried. Every replay rail (crash init, ANR init, WorkManager) calls this so the invariant
     * can't drift.
     */
    suspend fun replayOnce(
        filesDir: File,
        client: TelemetryHttpClient,
        gson: Gson = Gson(),
        fileName: String = FILE_NAME
    ): Boolean {
        val batch = readBatch(filesDir, gson, fileName) ?: return true
        return if (client.sendBatch(batch).isSuccess) {
            delete(filesDir, fileName)
            true
        } else {
            false
        }
    }
}
