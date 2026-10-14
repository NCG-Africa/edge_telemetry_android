package com.androidtel.telemetry_library.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.DisplayMetrics
import com.androidtel.telemetry_library.core.log.TelemetryLog
import android.view.WindowManager
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.AppInfo
import java.util.Locale
import java.util.TimeZone

/**
 * Device Information Collector that provides comprehensive device attributes
 * matching the backend collector payload structure exactly
 */
class DeviceInfoCollector(
    private val context: Context,
    private val idGenerator: IdGenerator
) {
    
    companion object {
        private const val TAG = "DeviceInfoCollector"

        /**
         * App identity for the envelope (#135). Each field is read on its own so one failure can't
         * blank the rest; an unknown name/version/build is null — omitted on the wire, never
         * "unknown" (R15). Never null itself, so a missing versionName no longer drops events.
         */
        fun collectAppInfo(context: Context): AppInfo {
            val pm = context.packageManager
            val packageInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }
                .onFailure { TelemetryLog.e(TAG, "Failed to read package info", it) }
                .getOrNull()
            return AppInfo(
                appName = runCatching { pm.getApplicationLabel(context.applicationInfo).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() },
                appVersion = packageInfo?.versionName?.takeIf { it.isNotBlank() },
                appBuildNumber = packageInfo?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
                    else @Suppress("DEPRECATION") it.versionCode.toString()
                },
                appPackageName = context.packageName
            )
        }

        /**
         * Static device-context bundle (issue #93, spec `docs/specs/static-device-context.md`).
         *
         * Mint **once at init** — the caller stamps this verbatim on every outgoing event. 9 keys,
         * native types (`Int`/`Boolean`/`String`), permission-free, every source available at minSDK 24.
         * Values reflect app-launch state (accepted staleness; not refreshed mid-session). Screen size
         * is **physical** resolution (real metrics), `cpu_abi` is the **primary** ABI only. Orientation
         * is deliberately excluded (owned by #91).
         *
         * Context-only (no IdGenerator): none of these keys is the device id.
         */
        fun collectStaticDeviceContext(context: Context): Map<String, Any?> {
            return try {
                val metrics = DisplayMetrics().also { m ->
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    @Suppress("DEPRECATION") // getRealMetrics is the minSDK-24 physical-resolution read
                    wm.defaultDisplay.getRealMetrics(m)
                }
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val darkMode = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

                mapOf(
                    "device.cpu_abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                    "device.cpu_cores" to Runtime.getRuntime().availableProcessors(),
                    "device.low_ram" to activityManager.isLowRamDevice,
                    "device.screen_density" to metrics.densityDpi,
                    "device.screen_width_px" to metrics.widthPixels,
                    "device.screen_height_px" to metrics.heightPixels,
                    "device.dark_mode" to darkMode,
                    "device.locale" to Locale.getDefault().toLanguageTag(),
                    "device.timezone" to TimeZone.getDefault().id
                )
            } catch (e: Exception) {
                // Additive, non-critical enrichment — never let a device read break event delivery.
                TelemetryLog.e(TAG, "Failed to collect static device context", e)
                emptyMap()
            }
        }
    }
    
    /**
     * Collect all device information for telemetry events
     * Aligned with backend processor requirements
     */
    fun collectDeviceInfo(): Map<String, String> {
        return mapOf(
            "device.id" to idGenerator.getDeviceId(),
            "device.platform" to "android",
            "device.platform_version" to Build.VERSION.RELEASE,
            "device.model" to Build.MODEL,
            "device.manufacturer" to Build.MANUFACTURER,
            "device.brand" to Build.BRAND,
            "device.android_sdk" to Build.VERSION.SDK_INT.toString(),
            "device.android_release" to Build.VERSION.RELEASE,
            "device.fingerprint" to Build.FINGERPRINT,
            "device.hardware" to Build.HARDWARE,
            "device.product" to Build.PRODUCT
        )
    }
    
    /**
     * Get network type information
     */
    fun getNetworkType(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                else -> "unknown"
            }
        } catch (e: Exception) {
            TelemetryLog.e(TAG, "Failed to get network type", e)
            "unknown"
        }
    }
}
