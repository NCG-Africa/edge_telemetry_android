package com.androidtel.telemetry_library.core.device

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.androidtel.telemetry_library.core.ids.IdGenerator

/**
 * Device Information Collector that provides comprehensive device attributes
 * matching the Flutter SDK payload structure exactly
 */
class DeviceInfoCollector(
    private val context: Context,
    private val idGenerator: IdGenerator
) {
    
    companion object {
        private const val TAG = "DeviceInfoCollector"
    }
    
    /**
     * Collect all device information for telemetry events
     */
    fun collectDeviceInfo(): Map<String, String> {
        return mapOf(
            "device.id" to idGenerator.getDeviceId(),
            "device.platform" to "android",
            "device.model" to Build.MODEL,
            "device.manufacturer" to Build.MANUFACTURER,
            "device.os_version" to Build.VERSION.RELEASE,
            "device.api_level" to Build.VERSION.SDK_INT.toString(),
            "device.brand" to Build.BRAND,
            "device.hardware" to Build.HARDWARE,
            "device.product" to Build.PRODUCT,
            "device.fingerprint" to Build.FINGERPRINT
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
                "app.version" to packageInfo.versionName ?: "unknown",
                "app.build_number" to getBuildNumber(packageInfo),
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
     * Get comprehensive attributes for crash payloads
     */
    fun getCrashAttributes(): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        
        // Add device info
        attributes.putAll(collectDeviceInfo())
        
        // Add app info
        attributes.putAll(collectAppInfo())
        
        // Add network info
        attributes["network.type"] = getNetworkType()
        
        return attributes
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
