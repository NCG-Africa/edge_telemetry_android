package com.androidtel.telemetry_library.core

import android.app.Application
import android.content.Context
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import com.androidtel.telemetry_library.core.device.DeviceInfoCollector
import com.androidtel.telemetry_library.core.exit.ApplicationExitHarvester
import com.androidtel.telemetry_library.core.exit.ExitRecord
import com.androidtel.telemetry_library.core.interaction.UserInteractionTracker
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.batchGson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Golden batches (#145, processor #40): every catalogue event this SDK emits, recorded through the
 * real emitters and serialized by the real transport, one batch per event plus `batch.json`.
 * `scripts/conform.sh` runs the processor's `cmd/conform` on `src/test/resources/golden` at the ref
 * in `catalogue.version`.
 *
 * Values that change per run (ids, timestamps, durations, device reads) are normalized to a fixed
 * value of the same JSON type, so the snapshot pins names, keys and types. Regenerate with
 * `./gradlew :telemetry_library:testDebugUnitTest --tests '*GoldenBatchSnapshotTest*' -PupdateGoldens`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GoldenBatchSnapshotTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val dir = File("src/test/resources/golden")
    private val update = System.getProperty("updateGoldens") != null
    private val server = MockWebServer()
    private lateinit var tm: TelemetryManager

    @Before
    fun setUp() {
        app.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).edit().clear().commit()
        FatalCrashStore.delete(app.filesDir)
        FatalCrashStore.delete(app.filesDir, FatalCrashStore.ANR_FILE_NAME)
        server.start()
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200)) }
        // A real app has a version; Robolectric's test app doesn't.
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = "1.0.0"
        // The fatal handler hands off to the previous one; keep that a no-op so the test survives.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        tm = TelemetryManager.initialize(
            app,
            TelemetryConfig(
                apiKey = "edge_golden_key", endpoint = server.url("/telemetry").toString(),
                batchSize = 1_000, flushIntervalMs = 3_600_000L
            )
        )
    }

    @After
    fun tearDown() {
        TelemetryManager.resetForTesting()
        server.shutdown()
    }

    @Test
    fun `goldens match the real wire`() {
        val events = recordEveryEvent()
        val client = TelemetryHttpClient(
            "unused", "edge_golden_key", false, DeviceInfoCollector.collectStaticDeviceContext(app)
        )
        fun json(evs: List<TelemetryEvent>): String = with(client) {
            normalize(JsonParser.parseString(TelemetryBatch(batchSize = evs.size, timestamp = "", events = evs).toJson()))
        }

        val want = events.groupBy { it.eventName ?: it.metricName!! }
            .mapKeys { (name, _) -> name.replace('.', '-') + ".json" }
            .mapValues { (_, evs) -> json(listOf(evs.first())) } + ("batch.json" to json(events))

        if (update) {
            dir.deleteRecursively()
            dir.mkdirs()
            want.forEach { (f, body) -> File(dir, f).writeText(body) }
        }
        assertEquals(want.keys.sorted(), dir.list()!!.sorted())
        want.forEach { (f, body) -> assertEquals(f, File(dir, f).readText(), body) }
    }

    private fun recordEveryEvent(): List<TelemetryEvent> {
        tm.notifyActivityResumed() // app.start
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get().let {
            ScreenLoadTracker(tm).apply { onCreated(it); it.setContentView(android.view.View(it)); onStarted(it) }
            it.window.decorView.viewTreeObserver.dispatchOnDraw()
        }
        tm.recordComposeScreenView("checkout/{id}")
        tm.recordComposeScreenEnd("checkout/{id}")
        tm.setUserProfile("Ada", "ada@example.com", "+254700000000")
        tm.trackError(IllegalStateException("card declined"), errorCode = "PAY_001", userAction = "submit_payment")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        tm.crashReportingForTesting().freezeAnr(5_000L, emptyList())
        tm.crashReportingForTesting().recordHang(1_200L, "at com.example.Main.run(Main.kt:1)")
        UserInteractionTracker(tm) { "checkout/{id}" }.emitInteraction(
            "tap", UserInteractionTracker.PendingTap("pay_button", "resource_id", 10f, 20f), null
        )
        TelemetryFrameDropCollector(tm, screenOverride = { "checkout/{id}" }, refreshRateOverride = { 60f })
            .apply { onFrame(30.0, 10.0, 10.0); stop() }
        MemoryTrackerFactory.createMemoryTracker(tm).apply {
            recordMemoryUsage(); recordStorageUsage(); recordTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
        tm.startTask("checkout").complete()
        tm.recordEvent("promo_banner_shown")
        tm.recordMetric("cart_value", 42.5)
        tm.emit(
            "app.exit",
            ApplicationExitHarvester.harvest(
                listOf(ExitRecord(6 /* REASON_ANR */, 100, "anr", 0, 100, 200, 1L, "trace")), null
            ).events.first()
        )
        OkHttpClient().newCall(Request.Builder().url(server.url("/api/items/42")).build()).let {
            TelemetryManager.instrument(OkHttpClient()).newCall(it.request()).execute().close()
        }
        tm.endCurrentSession()

        // The frozen rails (fatal crash, ANR) are replayed next launch exactly as written.
        val frozen = listOf(FatalCrashStore.FILE_NAME, FatalCrashStore.ANR_FILE_NAME)
            .mapNotNull { FatalCrashStore.readBatch(app.filesDir, batchGson, it)?.events?.first() }
        return tm.drainForTesting() + frozen
    }

    // ---- normalization: fixed values, same JSON type ----

    private fun normalize(root: JsonElement): String {
        val o = root.asJsonObject
        o.addProperty("timestamp", TS)
        o.addProperty("device_id", "device_golden")
        o.getAsJsonObject("sdk").addProperty("version", "golden")
        o.getAsJsonArray("events").forEach { ev ->
            ev.asJsonObject.addProperty("timestamp", TS)
            ev.asJsonObject.get("value")?.let { ev.asJsonObject.add("value", fixed(it)) }
            val attrs = ev.asJsonObject.getAsJsonObject("attributes")
            attrs.keySet().toList().forEach { k ->
                if (volatile(k, attrs[k])) attrs.add(k, fixed(attrs[k]))
                attrs[k].takeIf { it.isJsonObject }?.let(::fixNumbers) // e.g. sdk.capabilities' live reads
            }
        }
        return GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(sorted(o)) + "\n"
    }

    private fun volatile(k: String, v: JsonElement): Boolean =
        v.isJsonPrimitive && (k in STABLE).not() && (
            !v.asJsonPrimitive.isBoolean ||
                k.startsWith("device.") // device reads vary by host
            )

    private fun fixed(v: JsonElement): JsonElement {
        val p = v.asJsonPrimitive
        return when {
            p.isBoolean -> JsonPrimitive(false)
            p.isNumber -> if (p.asString.contains('.') || p.asString.contains('E')) JsonPrimitive(1.5) else JsonPrimitive(1)
            runCatching { java.time.OffsetDateTime.parse(p.asString) }.isSuccess -> JsonPrimitive(TS)
            else -> JsonPrimitive("golden")
        }
    }

    private fun fixNumbers(o: JsonElement) {
        o.asJsonObject.entrySet().forEach { (k, v) ->
            if (v.isJsonObject) fixNumbers(v) else if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) o.asJsonObject.add(k, fixed(v))
        }
    }

    private fun sorted(e: JsonElement): JsonElement = when {
        e.isJsonObject -> JsonObject().also { out -> e.asJsonObject.keySet().sorted().forEach { out.add(it, sorted(e.asJsonObject[it])) } }
        e.isJsonArray -> e.asJsonArray.let { a -> com.google.gson.JsonArray().also { out -> a.forEach { out.add(sorted(it)) } } }
        else -> e
    }

    private companion object {
        const val TS = "2026-01-01T00:00:00.000Z"

        // Values the SDK sets itself: kept as recorded, so an enum or literal change shows in the diff.
        val STABLE = setOf(
            "app.start.type", "navigation.method", "navigation.route_type", "navigation.to_screen",
            "navigation.from_screen", "screen.name", "screen.exit_method", "error.cause", "error.code",
            "error.type", "ui.type", "ui.target", "ui.name_source", "memory.pressure_level", "memory.source",
            "memory.trim_level", "session.reason", "trace.root_type", "traceparent.outcome", "http.method",
            "http.status_code", "http.success", "exit.reason", "task.name", "task.outcome", "event.name",
            "metric.name", "metric.unit", "user.name", "user.email", "user.phone", "device.platform",
            "network.type", "frame.total_frames", "frame.slow_frames", "frame.frozen_frames",
            "display.refresh_rate", "lifecycle.state",
        )
    }
}
