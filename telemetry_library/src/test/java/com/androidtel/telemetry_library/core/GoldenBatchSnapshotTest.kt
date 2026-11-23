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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Golden batches (#145, #151): every catalogue event this SDK emits, recorded through the real
 * emitters and serialized by the real transport into one raw batch, dumped to the gitignored
 * `build/golden-raw/`. `scripts/conform.sh` runs the processor's `cmd/conform` on it at the tag in
 * `catalogue.version`: catalogue check, then the derived shape (event, key, type; no values) must
 * equal the committed `src/test/shape.txt`. Nothing here compares values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GoldenBatchSnapshotTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val dir = File("build/golden-raw")
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
    fun `dump the golden batch`() {
        val events = recordEveryEvent()
        val client = TelemetryHttpClient(
            "unused", "edge_golden_key", false, DeviceInfoCollector.collectStaticDeviceContext(app)
        )
        val body = with(client) { TelemetryBatch(batchSize = events.size, timestamp = "", events = events).toJson() }
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "batch.json").writeText(body)
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
}
