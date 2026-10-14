package com.androidtel.telemetry_library.core.log

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TelemetryLogTest {

    @Before
    fun setup() = ShadowLog.clear()

    @After
    fun tearDown() {
        TelemetryLog.debug = false
    }

    private fun logAll() {
        TelemetryLog.v("T", "v")
        TelemetryLog.d("T", "d")
        TelemetryLog.i("T", "i")
        TelemetryLog.w("T", "w")
        TelemetryLog.e("T", "e", RuntimeException("boom"))
    }

    @Test
    fun `debug off - only warnings and errors reach logcat`() {
        TelemetryLog.debug = false
        logAll()
        assertEquals(listOf(Log.WARN, Log.ERROR), ShadowLog.getLogsForTag("T").map { it.type })
    }

    @Test
    fun `debug on - every level reaches logcat`() {
        TelemetryLog.debug = true
        logAll()
        assertEquals(
            listOf(Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR),
            ShadowLog.getLogsForTag("T").map { it.type },
        )
    }
}
