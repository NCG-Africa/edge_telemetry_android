package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.TelemetryTime
import com.androidtel.telemetry_library.core.breadcrumbs.BreadcrumbManager
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.retry.CrashReplay
import com.google.gson.Gson
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CrashReportingService — the single crash pipeline (issue #56, Path B retired).
 *
 * Every crash is a standard `app.crash` event enriched through the same `buildAttributes` path as
 * any other event, so it automatically carries `session.*`/`user.*`/`sdk.*` — the audit #1 fix,
 * since old Path B dropped that context and the processor (which keys on session.id/user.id) dropped
 * every crash. Two rails, keyed on whether the process is dying:
 *
 *  - **Durable-fatal** (uncaught handler): synchronously build the enriched event on the crashing
 *    thread and freeze it to `filesDir` (blocking write + fsync) BEFORE re-throwing. Replayed on the
 *    next `initialize()` and deleted only after a 2xx — survives an offline process death with no
 *    loss and no duplication.
 *  - **Normal batch** (`trackError` / `recordCrash`): enrich as `app.crash` and hand to the standard
 *    event sink — same batching, flush, and offline retry as every event.
 *
 * `is_fatal`/`handled` are native JSON bools; keys are D1-canonical (unprefixed).
 */
internal class CrashReportingService(
    private val context: Context,
    private val config: TelemetryConfig,
    private val httpClient: TelemetryHttpClient
) {
    private val gson = Gson()
    private var breadcrumbManager: BreadcrumbManager? = null

    // Atomic so concurrent initialize() calls can't double-install the UncaughtExceptionHandler.
    private val crashHandlerInstalled = AtomicBoolean(false)

    // Enrichment + normal-batch sink, injected from TelemetryManager at initialize().
    private var buildAttributesFn: ((Map<String, Any>) -> EventAttributes?)? = null
    private var recordCrashEventFn: ((Map<String, Any>) -> Unit)? = null

    // trackError context. product_id no longer reaches the wire (D1) — setProductContext is retained
    // only so the public API doesn't break. user_action still rides the canonical key set.
    private var lastUserAction: String? = null

    fun initialize(
        buildAttributesFn: (Map<String, Any>) -> EventAttributes?,
        recordCrashEventFn: (Map<String, Any>) -> Unit
    ) {
        this.buildAttributesFn = buildAttributesFn
        this.recordCrashEventFn = recordCrashEventFn
        breadcrumbManager = BreadcrumbManager()

        if (config.enableCrashReporting && crashHandlerInstalled.compareAndSet(false, true)) {
            installFatalHandler()
            Log.d(TAG, "Fatal crash handler installed")
        }
        Log.d(TAG, "CrashReportingService initialized")
    }

    // --- Durable-fatal rail ---

    private fun installFatalHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                freezeFatalCrash(thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to freeze fatal crash", e)
            } finally {
                // Preserve normal crash behaviour: hand off to the platform handler, which kills the
                // process. The freeze above has already completed synchronously by this point.
                original?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Synchronously, on the crashing thread, before the process dies: build the fully-enriched
     * `app.crash` event and freeze it. Replay sends it as-is — never re-enriched at replay time
     * (that would stamp a new session onto an old crash).
     */
    private fun freezeFatalCrash(thread: Thread, throwable: Throwable) {
        val attrs = buildCrashAttributes(
            throwable = throwable,
            isFatal = true,
            handled = false,
            extra = mapOf(
                "crash.thread" to thread.name,
                "crash.is_main_thread" to (thread.name == "main")
            )
        )
        val enriched = buildAttributesFn?.invoke(attrs) ?: return
        val event = TelemetryEvent(
            type = "event",
            eventName = "app.crash",
            timestamp = TelemetryTime.now(),
            attributes = enriched
        )
        val batch = TelemetryBatch(batchSize = 1, timestamp = TelemetryTime.now(), events = listOf(event))
        FatalCrashStore.writeBlocking(context.filesDir, gson.toJson(batch))
    }

    /**
     * Replay the frozen fatal crash on init via the shared transport; delete only on a 2xx. On
     * failure leave the file and let WorkManager retry, so crash delivery stays independent of the
     * normal batch-flush timing and survives across launches until actually delivered.
     */
    suspend fun replayFatalCrashIfAny() {
        if (!FatalCrashStore.replayOnce(context.filesDir, httpClient, gson)) {
            Log.w(TAG, "Fatal crash replay failed; scheduling WorkManager retry")
            CrashReplay.schedule(context, config.apiKey, config.endpoint)
        }
    }

    // --- Normal batch rail ---

    fun recordCrash(throwable: Throwable) = enqueueHandledCrash(throwable, null, null, null)

    fun trackError(error: Throwable, attributes: Map<String, String>? = null) =
        enqueueHandledCrash(error, null, null, attributes)

    fun trackError(
        error: Throwable,
        errorCode: String? = null,
        productId: String? = null,
        userAction: String? = null,
        attributes: Map<String, String>? = null
    ) {
        // buildCrashAttributes applies the lastUserAction fallback; just record + forward the raw arg.
        userAction?.let { lastUserAction = it.take(500) }
        enqueueHandledCrash(error, errorCode, userAction, attributes)
    }

    fun trackError(message: String, stackTrace: String? = null, attributes: Map<String, String>? = null) =
        enqueueHandledCrash(RuntimeException(message), null, null, attributes, stackTraceOverride = stackTrace)

    private fun enqueueHandledCrash(
        throwable: Throwable,
        errorCode: String?,
        userAction: String?,
        attributes: Map<String, String>?,
        stackTraceOverride: String? = null
    ) {
        if (!config.enableCrashReporting) {
            Log.w(TAG, "Crash reporting not enabled")
            return
        }
        val attrs = buildCrashAttributes(
            throwable = throwable,
            isFatal = false,
            handled = true,
            errorCode = errorCode,
            userAction = userAction,
            stackTraceOverride = stackTraceOverride,
            extra = attributes?.mapValues { it.value as Any } ?: emptyMap()
        )
        recordCrashEventFn?.invoke(attrs) ?: Log.w(TAG, "Crash event sink not wired")
    }

    // --- Canonical app.crash attribute set (D1: unprefixed, natively-typed) ---

    internal fun buildCrashAttributes(
        throwable: Throwable,
        isFatal: Boolean,
        handled: Boolean,
        errorCode: String? = null,
        userAction: String? = null,
        stackTraceOverride: String? = null,
        extra: Map<String, Any> = emptyMap()
    ): Map<String, Any> {
        val stacktrace = stackTraceOverride ?: stackTraceOf(throwable)
        val attrs = mutableMapOf<String, Any>(
            "message" to "${throwable.javaClass.name}: ${throwable.message ?: ""}".take(1000),
            "stacktrace" to stacktrace.take(2000),
            "exception_type" to throwable.javaClass.simpleName.take(255),
            "cause" to (throwable.cause?.message ?: "unknown").take(255),
            "error_context" to extractErrorContext(stacktrace).take(500),
            "is_fatal" to isFatal,
            "handled" to handled,
            "crash.breadcrumbs" to (breadcrumbManager?.getBreadcrumbsAsJson() ?: "[]")
        )
        (userAction ?: lastUserAction)?.let { attrs["user_action"] = it.take(500) }
        errorCode?.let { attrs["error_code"] = it.take(100) }
        attrs.putAll(extra)
        return attrs
    }

    // --- Breadcrumbs & context ---

    fun addBreadcrumb(
        message: String,
        category: String = "custom",
        level: String = "info",
        data: Map<String, String>? = null
    ) {
        breadcrumbManager?.addBreadcrumb(message, category, level, data)
            ?: Log.w(TAG, "Breadcrumb manager not initialized")
    }

    // product_id is dropped from the wire (D1 follow-up); retained as a no-op so callers don't break.
    fun setProductContext(productId: String) { /* no-op: product_id no longer reaches the crash wire */ }

    fun setLastUserAction(action: String) { lastUserAction = action.take(500) }

    fun testCrashReporting(customMessage: String? = null) {
        val message = customMessage ?: "Test crash from EdgeTelemetry SDK"
        breadcrumbManager?.addCustom("Test crash initiated", mapOf("test" to "true"))
        trackError(RuntimeException(message), mapOf("test.crash" to "true"))
        Log.i(TAG, "Test crash reported: $message")
    }

    fun getBreadcrumbManager(): BreadcrumbManager? = breadcrumbManager

    // --- Helpers ---

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun extractErrorContext(stackTrace: String): String {
        return try {
            val firstFrame = stackTrace.lines().firstOrNull { it.trim().startsWith("at ") } ?: return "unknown"
            val atIndex = firstFrame.indexOf("at ")
            if (atIndex == -1) return "unknown"
            val methodPart = firstFrame.substring(atIndex + 3).trim()
            val parenIndex = methodPart.indexOf("(")
            val fullMethod = if (parenIndex > 0) methodPart.substring(0, parenIndex) else methodPart
            val parts = fullMethod.split(".")
            if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else fullMethod
        } catch (e: Exception) {
            "unknown"
        }
    }

    companion object {
        private const val TAG = "CrashReportingService"
    }
}
