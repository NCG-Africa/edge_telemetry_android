package com.androidtel.telemetry_library.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.androidtel.telemetry_library.core.ids.IdGenerator
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
                Log.e(TAG, "Failed to collect static device context", e)
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
     * Collect app information
     */
    fun collectAppInfo(): Map<String, String> {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appName = context.packageManager.getApplicationLabel(
                context.applicationInfo
            ).toString()
            
            mapOf(
                "app.name" to appName,
                "app.version" to (packageInfo.versionName ?: "unknown"),
                "app.build_number" to getBuildNumber(packageInfo).toString(),
                "app.package_name" to context.packageName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect app info", e)
            mapOf(
                "app.name" to "unknown",
                "app.version" to "unknown",
                "app.build_number" to "unknown",
                "app.package_name" to context.packageName
            )
        }
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
            Log.e(TAG, "Failed to get network type", e)
            "unknown"
        }
    }
    
    /**
     * Get build number with proper handling for different API levels
     */
    private fun getBuildNumber(packageInfo: android.content.pm.PackageInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
    }
}
