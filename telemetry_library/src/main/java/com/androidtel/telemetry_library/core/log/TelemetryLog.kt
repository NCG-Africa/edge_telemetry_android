package com.androidtel.telemetry_library.core.log

import android.util.Log

/**
 * The SDK's only door to logcat (#140, SDK-001 / CWE-532).
 *
 * v/d/i print only when [TelemetryConfig.debugMode] is on; w/e always print, so they must never
 * carry profile fields (name/email/phone) or user/session/device ids. `TelemetryLogGuardTest`
 * fails the build on any `Log.*` call outside this file.
 */
internal object TelemetryLog {

    @Volatile
    var debug: Boolean = false

    fun v(tag: String, msg: String) { if (debug) Log.v(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable? = null) { if (debug) Log.d(tag, msg, tr) }
    fun i(tag: String, msg: String, tr: Throwable? = null) { if (debug) Log.i(tag, msg, tr) }
    fun w(tag: String, msg: String, tr: Throwable? = null) { Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String, tr: Throwable? = null) { Log.e(tag, msg, tr) }
}
