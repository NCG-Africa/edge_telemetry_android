package com.androidtel.telemetry_library.core.crash

import com.androidtel.telemetry_library.release
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryHttpClient
import com.androidtel.telemetry_library.core.models.AppInfo
import com.androidtel.telemetry_library.core.models.DeviceInfo
import com.androidtel.telemetry_library.core.models.EventAttributes
import com.androidtel.telemetry_library.core.models.SessionInfo
import com.androidtel.telemetry_library.core.models.TelemetryBatch
import com.androidtel.telemetry_library.core.models.TelemetryEvent
import com.androidtel.telemetry_library.core.models.UserInfo
import com.androidtel.telemetry_library.core.services.CrashReportingService
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
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Issue #56 conformance: one `app.crash` pipeline, D1 canonical keys, and a durable filesDir rail
 * for fatal crashes (freeze → replay → delete-only-after-2xx). Mirrors the spec test plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CrashPathTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var httpClient: TelemetryHttpClient
    private lateinit var service: CrashReportingService
    private val gson = Gson()
    private val testApiKey = "edge_test_crash_key"
    private val crashSessionId = "session_frozen_001"
    private val crashUserId = "user_frozen_001"

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        httpClient = TelemetryHttpClient(mockWebServer.url("/telemetry").toString(), testApiKey, false)
        service = CrashReportingService(
            context = RuntimeEnvironment.getApplication(),
            config = TelemetryConfig(apiKey = testApiKey, endpoint = mockWebServer.url("/telemetry").toString()),
            httpClient = httpClient
        )
        FatalCrashStore.delete(RuntimeEnvironment.getApplication().filesDir)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        httpClient.getOkHttpClient().release()
        mockWebServer.shutdown()
        FatalCrashStore.delete(RuntimeEnvironment.getApplication().filesDir)
    }

    // Enrichment stub: wraps crash attrs into EventAttributes carrying a fixed session + user, the
    // way TelemetryManager.buildAttributes does for every event.
    private fun enrich(attrs: Map<String, Any>): EventAttributes = EventAttributes(
        app = AppInfo("TestApp", "1.0.0", "100", "com.test.app"),
        device = DeviceInfo(
            deviceId = "device_001", platform = "android", platformVersion = "13",
            model = "M", manufacturer = "Mf", brand = "B", androidSdk = "33",
            androidRelease = "13", fingerprint = "fp", hardware = "hw", product = "p"
        ),
        user = UserInfo(userId = crashUserId),
        session = SessionInfo(
            sessionId = crashSessionId, startTime = "2026-07-20T00:00:00.000Z", durationMs = 1L,
            eventCount = 1, metricCount = 0, screenCount = 0, visitedScreens = "",
            isFirstSession = true, totalSessions = 1, networkType = "wifi"
        ),
        customAttributes = attrs
    )

    private fun wireAttributesOf(batch: TelemetryBatch): com.google.gson.JsonObject {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        runBlocking { httpClient.sendBatch(batch) }
        val body = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        return JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("events").get(0).asJsonObject.getAsJsonObject("attributes")
    }

    private fun crashBatch(attrs: Map<String, Any>): TelemetryBatch = TelemetryBatch(
        batchSize = 1,
        timestamp = "2026-07-20T00:00:00.000Z",
        events = listOf(
            TelemetryEvent("event", eventName = "app.crash", timestamp = "2026-07-20T00:00:00.000Z", attributes = enrich(attrs))
        )
    )

    // 1. Crash-during-session carries context (audit #1 regression guard).
    @Test
    fun `app_crash carries active session and user`() {
        val attrs = service.buildCrashAttributes(RuntimeException("boom"), isFatal = false, handled = true)
        val wire = wireAttributesOf(crashBatch(attrs))

        assertEquals(crashSessionId, wire.get("session.id").asString)
        assertEquals(crashUserId, wire.get("user.id").asString)
    }

    // 3. Native is_fatal / handled for every entry point.
    @Test
    fun `is_fatal and handled serialize as native booleans`() {
        val fatal = service.buildCrashAttributes(RuntimeException("f"), isFatal = true, handled = false)
        val handled = service.buildCrashAttributes(RuntimeException("h"), isFatal = false, handled = true)

        wireAttributesOf(crashBatch(fatal)).let {
            assertTrue(it.get("is_fatal").asJsonPrimitive.isBoolean)
            assertTrue(it.get("is_fatal").asBoolean)
            assertFalse(it.get("handled").asBoolean)
        }
        wireAttributesOf(crashBatch(handled)).let {
            assertFalse(it.get("is_fatal").asBoolean)
            assertTrue(it.get("handled").asBoolean)
        }
    }

    // 4. Canonical unprefixed keys; no dropped/legacy keys.
    @Test
    fun `canonical keys present and legacy keys absent`() {
        val attrs = service.buildCrashAttributes(
            RuntimeException("boom"), isFatal = false, handled = true, errorCode = "E1", userAction = "tap"
        )
        val wire = wireAttributesOf(crashBatch(attrs))

        listOf("message", "stacktrace", "exception_type", "cause", "error_context",
            "is_fatal", "handled", "crash.breadcrumbs", "user_action", "error_code").forEach {
            assertTrue("missing canonical key $it", wire.has(it))
        }
        listOf("error.message", "error.stack_trace", "error.is_fatal", "error.severity_level",
            "error.breadcrumb_count", "severity_level", "breadcrumb_count", "product_id").forEach {
            assertFalse("legacy key $it must be gone", wire.has(it))
        }
    }

    // 5. Path B gone: no X-SDK-* headers, no data{} wrapper.
    @Test
    fun `crash send has no X-SDK headers and no data wrapper`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        val attrs = service.buildCrashAttributes(RuntimeException("boom"), isFatal = true, handled = false)
        runBlocking { httpClient.sendBatch(crashBatch(attrs)) }
        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!

        assertNull(request.getHeader("X-SDK-Version"))
        assertNull(request.getHeader("X-SDK-Platform"))
        assertEquals(testApiKey, request.getHeader("X-API-Key"))
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertFalse(json.has("data"))
        assertEquals("telemetry_batch", json.get("type").asString)
    }

    // 2a. The synchronous freeze seam: the installed uncaught handler writes a fully-enriched,
    //     frozen app.crash to filesDir BEFORE chaining to the platform handler (re-throw).
    @Test
    fun `uncaught handler freezes enriched crash before rethrow`() {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        var chainedAfterFreeze = false
        // The platform handler that installFatalHandler chains to. Asserting the file already exists
        // here proves the freeze ran synchronously *before* the re-throw.
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            chainedAfterFreeze = FatalCrashStore.file(filesDir).exists()
        }
        service.initialize(
            buildAttributesFn = { enrich(it) },
            recordCrashEventFn = { /* not used on the fatal rail */ },
            recordHangEventFn = { /* not used on the fatal rail */ }
        )

        val installed = Thread.getDefaultUncaughtExceptionHandler()!!
        installed.uncaughtException(Thread.currentThread(), RuntimeException("fatal boom"))

        assertTrue("crash frozen before platform handler ran", chainedAfterFreeze)
        val batch = FatalCrashStore.readBatch(filesDir, gson)!!
        val event = batch.events.first()
        assertEquals("app.crash", event.eventName)
        assertEquals(crashSessionId, event.attributes.session.sessionId)
        assertEquals(crashUserId, event.attributes.user.userId)
        assertEquals(true, event.attributes.customAttributes["is_fatal"])
        assertEquals(false, event.attributes.customAttributes["handled"])
        assertTrue(event.attributes.customAttributes.containsKey("crash.thread"))
    }

    // #60: ANR freeze writes a fully-enriched app.anr to its OWN durable slot (separate from the
    // crash slot), is_fatal:false/handled:false, all-thread dump with main flagged, standard enrichment.
    @Test
    fun `anr freeze writes enriched app_anr to its own slot`() {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        com.androidtel.telemetry_library.core.crash.FatalCrashStore.delete(
            filesDir, com.androidtel.telemetry_library.core.crash.FatalCrashStore.ANR_FILE_NAME
        )
        service.initialize(buildAttributesFn = { enrich(it) }, recordCrashEventFn = {}, recordHangEventFn = {})

        val threads = listOf(
            com.androidtel.telemetry_library.core.anr.ThreadDump("main", "RUNNABLE", true, "at a.b(F:1)"),
            com.androidtel.telemetry_library.core.anr.ThreadDump("worker", "WAITING", false, "at c.d(F:2)")
        )
        service.freezeAnr(durationMs = 5000L, threads = threads)

        // Crash slot untouched; ANR rides its own file.
        assertNull(FatalCrashStore.readBatch(filesDir, gson))
        val batch = FatalCrashStore.readBatch(
            filesDir, gson, com.androidtel.telemetry_library.core.crash.FatalCrashStore.ANR_FILE_NAME
        )!!
        val event = batch.events.first()
        assertEquals("app.anr", event.eventName)
        assertEquals(crashSessionId, event.attributes.session.sessionId)
        assertEquals(crashUserId, event.attributes.user.userId)
        val custom = event.attributes.customAttributes
        assertEquals(false, custom["is_fatal"])
        assertEquals(false, custom["handled"])
        assertTrue(custom.containsKey("anr.duration_ms"))
        assertTrue(custom.containsKey("screen.name"))
        // Gson round-trips the dump into a list of maps (customAttributes is Map<String,Any>).
        @Suppress("UNCHECKED_CAST")
        val dumped = custom["anr.threads"] as List<Map<String, Any>>
        assertTrue("dump flags the main thread", dumped.any { it["main"] == true })

        FatalCrashStore.delete(filesDir, com.androidtel.telemetry_library.core.crash.FatalCrashStore.ANR_FILE_NAME)
    }

    // 2. Fatal survives process death: frozen file replays with its ORIGINAL session, and is
    //    deleted only after a 2xx (non-2xx leaves it for retry).
    @Test
    fun `frozen fatal replays with original session and deletes only on 2xx`() {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        val fatalAttrs = service.buildCrashAttributes(RuntimeException("fatal"), isFatal = true, handled = false)
        FatalCrashStore.writeBlocking(filesDir, gson.toJson(crashBatch(fatalAttrs)))
        assertTrue(FatalCrashStore.file(filesDir).exists())

        // Non-2xx: file must remain.
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        runBlocking { service.replayFatalCrashIfAny() }
        assertTrue("file must survive a failed replay", FatalCrashStore.file(filesDir).exists())

        // 2xx: file deleted, original session on the wire.
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        runBlocking { service.replayFatalCrashIfAny() }
        val request = mockWebServer.takeRequest(5, TimeUnit.SECONDS)!!
        val wire = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            .getAsJsonArray("events").get(0).asJsonObject.getAsJsonObject("attributes")
        assertEquals(crashSessionId, wire.get("session.id").asString)
        assertTrue(wire.get("is_fatal").asBoolean)
        assertFalse("file must be gone after 2xx", FatalCrashStore.file(filesDir).exists())
    }
}
