package com.androidtel.telemetry_library.core.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R15 (#135): an unknown version/build is null (omitted on the wire), never "unknown" or "". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CollectAppInfoTest {

    private fun context(pm: PackageManager): Context = mockk(relaxed = true) {
        every { packageName } returns "com.test.app"
        every { packageManager } returns pm
        every { applicationInfo } returns ApplicationInfo()
    }

    @Test
    fun `null versionName leaves version unknown but keeps the build and events flowing`() {
        val pm = mockk<PackageManager> {
            every { getPackageInfo("com.test.app", 0) } returns PackageInfo().apply {
                versionName = null
                @Suppress("DEPRECATION")
                versionCode = 42
                longVersionCode = 42L
            }
            every { getApplicationLabel(any()) } returns "Test App"
        }

        val info = DeviceInfoCollector.collectAppInfo(context(pm))

        assertNull(info.appVersion)
        assertEquals("42", info.appBuildNumber)
        assertEquals("Test App", info.appName)
        assertEquals("com.test.app", info.appPackageName)
    }

    @Test
    fun `getPackageInfo failure leaves name version and build unknown`() {
        val pm = mockk<PackageManager> {
            every { getPackageInfo("com.test.app", 0) } throws PackageManager.NameNotFoundException()
            every { getApplicationLabel(any()) } throws RuntimeException("boom")
        }

        val info = DeviceInfoCollector.collectAppInfo(context(pm))

        assertNull(info.appName)
        assertNull(info.appVersion)
        assertNull(info.appBuildNumber)
        assertEquals("com.test.app", info.appPackageName)
    }
}
