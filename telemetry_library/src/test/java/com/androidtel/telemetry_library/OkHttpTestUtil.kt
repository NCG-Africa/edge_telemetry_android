package com.androidtel.telemetry_library

import okhttp3.OkHttpClient

/**
 * Releases OkHttp's non-daemon dispatcher threads and pooled connections so a forked unit-test JVM
 * can exit immediately after the test. Without this, the dispatcher's thread pool lingers on its
 * keep-alive and Gradle blocks waiting for the test worker to die — the "MockWebServer suite hangs"
 * symptom. Call from @After in any test that builds an OkHttpClient (directly or via TelemetryHttpClient).
 */
internal fun OkHttpClient.release() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
}
