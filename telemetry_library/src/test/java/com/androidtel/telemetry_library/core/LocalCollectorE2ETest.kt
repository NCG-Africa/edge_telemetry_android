package com.androidtel.telemetry_library.core

import android.app.Application
import android.content.Context
import com.androidtel.telemetry_library.core.crash.FatalCrashStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.HttpURLConnection
import java.net.URL

/**
 * #138 end-to-end against a real collector + Kafka, not a stub. Skipped unless E2E_COLLECTOR_URL is
 * set; drives docker (stop Kafka, restart the collector) to stage the collector's "not ready" 503,
 * so never point it at a shared stack — use src/test/e2e/docker-compose.yml:
 *
 *   E2E_COLLECTOR_URL=http://127.0.0.1:18138/telemetry \
 *   E2E_KEY=edge_<lookup>_<secret> E2E_REVOKED_KEY=edge_<lookup>_<secret> \
 *   E2E_KAFKA_CONTAINER=e2e138-kafka-1 E2E_COLLECTOR_CONTAINER=e2e138-collector-1 \
 *   ./gradlew :telemetry_library:testDebugUnitTest --tests '*LocalCollectorE2ETest*'
 *
 * Exactly-once delivery is checked by hand on the telemetry-upsert topic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalCollectorE2ETest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val url = System.getenv("E2E_COLLECTOR_URL")
    private val kafka = System.getenv("E2E_KAFKA_CONTAINER") ?: "e2e138-kafka-1"
    private val collector = System.getenv("E2E_COLLECTOR_CONTAINER") ?: "e2e138-collector-1"

    @Before
    fun setUp() {
        assumeTrue("set E2E_COLLECTOR_URL to run", url != null)
        app.getSharedPreferences("telemetry_batches", Context.MODE_PRIVATE).edit().clear().commit()
        FatalCrashStore.delete(app.filesDir)
        FatalCrashStore.delete(app.filesDir, FatalCrashStore.ANR_FILE_NAME)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        TelemetryManager.resetForTesting()
        if (url != null) docker("start", kafka) // never leave the stack down
    }

    @Test
    fun `revoked key 401 is dropped, not queued`() {
        start(System.getenv("E2E_REVOKED_KEY")!!)
        TelemetryManager.getInstance().recordEvent("e2e.revoked")

        awaitLog("collector rejected the 401") { "Batch rejected (401" in it }
        Thread.sleep(1_000)
        assertEquals(0, stored())
    }

    @Test
    fun `collector not ready 503 is queued, then delivered once ready`() {
        docker("stop", kafka)
        docker("restart", collector)
        await("collector answers 503") { post() == 503 }

        start(System.getenv("E2E_KEY")!!)
        TelemetryManager.getInstance().recordEvent("e2e.not_ready")
        awaitLog("503 seen", 60_000) { "Server error 503" in it }
        await("batch queued offline", 60_000) { stored() > 0 }

        docker("start", kafka)
        awaitLog("replayed with a 2xx", 180_000) { "Successfully re-sent stored batch" in it }
        assertEquals(0, stored())
    }

    // --- helpers ---

    private fun start(key: String) {
        TelemetryManager.resetForTesting()
        TelemetryManager.initialize(
            app,
            TelemetryConfig(
                apiKey = key, endpoint = url!!, batchSize = 1_000, flushIntervalMs = 1_000L,
                enableMemoryTracking = false, enableStorageTracking = false,
                enableFrameTracking = false, enableCapabilityEvents = false
            )
        )
    }

    private fun stored() = runBlocking { OfflineBatchStorage(app).getStoredBatches().size }

    private fun post(): Int = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 2_000; readTimeout = 2_000
            outputStream.use { it.write("{}".toByteArray()) }
            responseCode
        }
    }.getOrDefault(-1)

    private fun docker(vararg args: String) {
        ProcessBuilder("docker", *args).inheritIO().start().waitFor()
    }

    private fun awaitLog(what: String, timeoutMs: Long = 30_000, match: (String) -> Boolean) =
        await(what, timeoutMs) { ShadowLog.getLogs().any { match(it.msg ?: "") } }

    private fun await(what: String, timeoutMs: Long = 30_000, probe: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out: $what")
    }
}
