package com.androidtel.telemetry_library.core.anr

import android.os.Handler
import android.os.Looper
import com.androidtel.telemetry_library.release
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.UserInfo
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Issue #60 conformance (spec `docs/specs/anr-detection.md`): a main-thread watchdog fires exactly
 * one `app.anr` past the 5s line with an all-thread dump (main flagged), a healthy main thread fires
 * nothing, and the event delivers on the durable `filesDir` rail (freeze → replay → delete-after-2xx).
 *
 * The watchdog's [AnrWatchdog.scanOnce] seam is driven directly with a fake clock — no real daemon
 * thread, no wall-clock sleeps. "Main thread blocked" = leaving the Robolectric main looper paused so
 * the posted heartbeat never runs; "healthy" = idling the looper so it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AnrWatchdogTest {

    private var fakeNow = 0L
    private val fires = mutableListOf<Pair<Long, List<ThreadDump>>>()
    private val hangs = mutableListOf<Pair<Long, String>>()
    private lateinit var watchdog: AnrWatchdog

    @Before
    fun setUp() {
        fakeNow = 0L
        fires.clear()
        hangs.clear()
        watchdog = AnrWatchdog(
            onAnr = { durationMs, threads -> fires.add(durationMs to threads) },
            onHang = { durationMs, stack -> hangs.add(durationMs to stack) },
            mainHandler = Handler(Looper.getMainLooper()),
            clock = { fakeNow }
        )
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // AC1: main blocked past 5s → exactly one app.anr, all-thread dump with main flagged.
    @Test
    fun `blocking main past 5s fires one anr with main flagged dump`() {
        watchdog.scanOnce()                     // posts the heartbeat; main looper left paused → never runs
        fakeNow = AnrWatchdog.ANR_THRESHOLD_MS  // 5s elapse with no pong
        watchdog.scanOnce()

        assertEquals(1, fires.size)
        val (durationMs, threads) = fires.first()
        assertTrue("duration >= 5000", durationMs >= AnrWatchdog.ANR_THRESHOLD_MS)
        assertTrue("dump flags the main thread", threads.any { it.main })
    }

    // AC1 (no false positive): main answers before 5s → zero events.
    @Test
    fun `main answering before threshold fires nothing`() {
        watchdog.scanOnce()   // posts heartbeat
        idleMain()            // main runs the pong → up to date
        fakeNow = AnrWatchdog.ANR_THRESHOLD_MS
        watchdog.scanOnce()

        assertEquals(0, fires.size)
    }

    // §6 intra-hang dedup: a 12s block is one anr event, not one per scan.
    @Test
    fun `long hang dedups to a single event`() {
        watchdog.scanOnce()
        for (t in longArrayOf(6000, 9000, 12000)) {
            fakeNow = t
            watchdog.scanOnce()
        }
        assertEquals(1, fires.size)

        // Main recovers, then hangs again → a fresh event (latch cleared by the pong).
        idleMain()
        watchdog.scanOnce()                     // re-arm
        fakeNow += AnrWatchdog.ANR_THRESHOLD_MS
        watchdog.scanOnce()
        assertEquals(2, fires.size)
    }

    // --- Issue #61: hang detection, band [2000,5000) off the same watchdog ---

    // AC (#61): main blocked ≥2s but <5s → exactly one app.hang, main-thread stack only, no anr.
    @Test
    fun `blocking main past 2s but under 5s fires one hang and no anr`() {
        watchdog.scanOnce()                      // posts heartbeat; main paused
        fakeNow = AnrWatchdog.HANG_THRESHOLD_MS  // 2s elapse, no pong
        watchdog.scanOnce()

        assertEquals(1, hangs.size)
        assertEquals(0, fires.size)
        val (durationMs, stack) = hangs.first()
        assertTrue("duration >= 2000", durationMs >= AnrWatchdog.HANG_THRESHOLD_MS)
        assertTrue("duration < 5000", durationMs < AnrWatchdog.ANR_THRESHOLD_MS)
        assertTrue("main-thread stack captured", stack.isNotBlank())
    }

    // AC (#61): a healthy main thread that answers before 2s fires nothing.
    @Test
    fun `main answering before 2s fires no hang`() {
        watchdog.scanOnce()
        idleMain()
        fakeNow = AnrWatchdog.HANG_THRESHOLD_MS
        watchdog.scanOnce()

        assertEquals(0, hangs.size)
        assertEquals(0, fires.size)
    }

    // AC (#61) escalation: one unbroken 6s block → exactly one hang AND one anr (not two hangs).
    @Test
    fun `single unbroken block crossing both bands fires one hang and one anr`() {
        watchdog.scanOnce()                          // posts heartbeat
        fakeNow = AnrWatchdog.HANG_THRESHOLD_MS       // 2s → hang
        watchdog.scanOnce()
        fakeNow = AnrWatchdog.ANR_THRESHOLD_MS        // 5s → anr, same stall
        watchdog.scanOnce()
        fakeNow = 6000L                               // still blocked → no dupes
        watchdog.scanOnce()

        assertEquals(1, hangs.size)
        assertEquals(1, fires.size)
    }

    // AC (#61) intra-hang dedup: a 3s block is one hang, not one per scan.
    @Test
    fun `three second block dedups to a single hang`() {
        watchdog.scanOnce()
        for (t in longArrayOf(2000, 2500, 3000)) {
            fakeNow = t
            watchdog.scanOnce()
        }
        assertEquals(1, hangs.size)
        assertEquals(0, fires.size)
    }

    // AC (#61) band boundary: a 4.9s block → one hang, no anr.
    @Test
    fun `block just under 5s fires hang but not anr`() {
        watchdog.scanOnce()
        fakeNow = 4900L
        watchdog.scanOnce()

        assertEquals(1, hangs.size)
        assertEquals(0, fires.size)
    }

    // AC3: delivered on the durable filesDir rail — frozen app.anr replays and deletes only on a 2xx.
    @Test
    fun `frozen anr replays on its own slot and deletes only on 2xx`() {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        FatalCrashStore.delete(filesDir, FatalCrashStore.ANR_FILE_NAME)

        val mockWebServer = MockWebServer()
        mockWebServer.start()
        val httpClient = TelemetryHttpClient(mockWebServer.url("/telemetry").toString(), "edge_test", false)
        try {
            FatalCrashStore.writeBlocking(
                filesDir, Gson().toJson(anrBatch()), FatalCrashStore.ANR_FILE_NAME
            )

            // Non-2xx: file survives for retry. 400 fails immediately (5xx would burn 3 retry+backoff).
            mockWebServer.enqueue(MockResponse().setResponseCode(400))
            runBlocking {
                FatalCrashStore.replayOnce(filesDir, httpClient, Gson(), FatalCrashStore.ANR_FILE_NAME)
            }
            assertTrue(FatalCrashStore.file(filesDir, FatalCrashStore.ANR_FILE_NAME).exists())

            // 2xx: is_fatal:false / handled:false on the wire, file deleted.
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            runBlocking {
                FatalCrashStore.replayOnce(filesDir, httpClient, Gson(), FatalCrashStore.ANR_FILE_NAME)
            }
            val body = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            val wire = JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("events").get(0).asJsonObject
            assertEquals("app.anr", wire.get("eventName").asString)
            val attrs = wire.getAsJsonObject("attributes")
            assertFalse(attrs.get("is_fatal").asBoolean)
            assertFalse(attrs.get("handled").asBoolean)
            assertFalse(FatalCrashStore.file(filesDir, FatalCrashStore.ANR_FILE_NAME).exists())
        } finally {
            httpClient.getOkHttpClient().release()
            mockWebServer.shutdown()
            FatalCrashStore.delete(filesDir, FatalCrashStore.ANR_FILE_NAME)
        }
    }

    private fun anrBatch(): TelemetryBatch = TelemetryBatch(
        batchSize = 1,
        timestamp = "2026-07-20T00:00:00.000Z",
        events = listOf(
            TelemetryEvent(
                type = "event",
                eventName = "app.anr",
                timestamp = "2026-07-20T00:00:00.000Z",
                attributes = EventAttributes(
                    app = AppInfo("TestApp", "1.0.0", "100", "com.test.app"),
                    device = DeviceInfo(
                        deviceId = "d", platform = "android", platformVersion = "13",
                        model = "M", manufacturer = "Mf", brand = "B", androidSdk = "33",
                        androidRelease = "13", fingerprint = "fp", hardware = "hw", product = "p"
                    ),
                    user = UserInfo(userId = "u"),
                    session = SessionInfo(
                        sessionId = "s", startTime = "2026-07-20T00:00:00.000Z", durationMs = 1L,
                        eventCount = 1, metricCount = 0, screenCount = 0, visitedScreens = "",
                        isFirstSession = true, totalSessions = 1, networkType = "wifi"
                    ),
                    customAttributes = mapOf("is_fatal" to false, "handled" to false, "anr.duration_ms" to 5000)
                )
            )
        )
    )
}
