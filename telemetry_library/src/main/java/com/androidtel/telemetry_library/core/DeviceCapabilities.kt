package com.androidtel.telemetry_library.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Central capability detection system for runtime feature availability checking.
 * Determines which telemetry features are supported on the current device/API level.
 */
class DeviceCapabilities private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceCapabilities"
        
        @Volatile
        private var INSTANCE: DeviceCapabilities? = null
        
        fun getInstance(context: Context): DeviceCapabilities {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceCapabilities(context.applicationContext).also { 
                    INSTANCE = it
                    it.logCapabilities()
                }
            }
        }
        
        fun getInstanceOrNull(): DeviceCapabilities? = INSTANCE
    }
    
    // Core API level information
    val apiLevel: Int = Build.VERSION.SDK_INT
    val androidVersion: String = Build.VERSION.RELEASE
    
    // Frame metrics capabilities (API 24+ - always available with minSdk 24)
    val supportsFrameMetrics: Boolean = true
    
    // Network capabilities
    val supportsModernNetworking: Boolean = apiLevel >= Build.VERSION_CODES.M // API 23
    val supportsNetworkCallback: Boolean = apiLevel >= Build.VERSION_CODES.LOLLIPOP // API 21
    val supportsConnectivityManagerCompat: Boolean = apiLevel >= Build.VERSION_CODES.M // API 23
    
    // Memory and performance capabilities
    val supportsAdvancedMemoryInfo: Boolean = apiLevel >= Build.VERSION_CODES.KITKAT // API 19
    val supportsMemoryManager: Boolean = apiLevel >= Build.VERSION_CODES.M // API 23
    val supportsProcessImportance: Boolean = apiLevel >= Build.VERSION_CODES.JELLY_BEAN // API 16
    
    // Storage and file system capabilities
    val supportsScopedStorage: Boolean = apiLevel >= Build.VERSION_CODES.Q // API 29
    val supportsExternalFilesDir: Boolean = apiLevel >= Build.VERSION_CODES.FROYO // API 8
    val supportsMediaStore: Boolean = apiLevel >= Build.VERSION_CODES.Q // API 29
    
    // Notification capabilities
    val supportsNotificationChannels: Boolean = apiLevel >= Build.VERSION_CODES.O // API 26
    val supportsNotificationImportance: Boolean = apiLevel >= Build.VERSION_CODES.N // API 24
    
    // Permission capabilities
    val supportsRuntimePermissions: Boolean = apiLevel >= Build.VERSION_CODES.M // API 23
    val supportsPermissionRationale: Boolean = apiLevel >= Build.VERSION_CODES.M // API 23
    
    // Package and version capabilities
    val supportsLongVersionCode: Boolean = apiLevel >= Build.VERSION_CODES.P // API 28
    val supportsPackageInfoFlags: Boolean = apiLevel >= Build.VERSION_CODES.TIRAMISU // API 33
    
    // Lifecycle and component capabilities
    val supportsLifecycleObserver: Boolean = true // Available through support library
    val supportsProcessLifecycleOwner: Boolean = true // Available through support library
    
    // Hardware feature detection
    val hasCamera: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
    }
    
    val hasTelephony: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
    
    val hasWifi: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    }
    
    val hasBluetooth: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    }
    
    val hasGps: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    }
    
    // Composite capability checks
    val canCollectFrameMetrics: Boolean
        get() = true // Always true with minSdk 24
    
    val canUseModernNetworkAPIs: Boolean
        get() = supportsModernNetworking
    
    val canCollectAdvancedMemoryMetrics: Boolean
        get() = supportsAdvancedMemoryInfo && supportsMemoryManager
    
    val canHandleRuntimePermissions: Boolean
        get() = supportsRuntimePermissions
    
    val canUseScopedStorage: Boolean
        get() = supportsScopedStorage
    
    /**
     * Get a summary of all capabilities for debugging/logging
     */
    fun getCapabilitiesSummary(): Map<String, Any> {
        return mapOf(
            "api_level" to apiLevel,
            "android_version" to androidVersion,
            "frame_metrics" to supportsFrameMetrics,
            "modern_networking" to supportsModernNetworking,
            "advanced_memory" to supportsAdvancedMemoryInfo,
            "scoped_storage" to supportsScopedStorage,
            "runtime_permissions" to supportsRuntimePermissions,
            "notification_channels" to supportsNotificationChannels,
            "long_version_code" to supportsLongVersionCode,
            "hardware_camera" to hasCamera,
            "hardware_telephony" to hasTelephony,
            "hardware_wifi" to hasWifi,
            "hardware_bluetooth" to hasBluetooth,
            "hardware_gps" to hasGps
        )
    }
    
    /**
     * Check if a specific API level is supported
     */
    fun isApiLevelSupported(requiredApiLevel: Int): Boolean {
        return apiLevel >= requiredApiLevel
    }
    
    /**
     * Get fallback strategy for unsupported features
     */
    fun getFallbackStrategy(feature: String): String {
        return when (feature) {
            "frame_metrics" -> "native" // Always native with minSdk 24
            "networking" -> if (supportsModernNetworking) "modern" else "legacy"
            "memory_tracking" -> if (supportsAdvancedMemoryInfo) "advanced" else "basic"
            "storage" -> if (supportsScopedStorage) "scoped" else "legacy"
            "permissions" -> if (supportsRuntimePermissions) "runtime" else "manifest"
            else -> "unknown"
        }
    }
    
    /**
     * Log all capabilities for debugging
     */
    private fun logCapabilities() {
        Log.i(TAG, "Device Capabilities Initialized:")
        Log.i(TAG, "  API Level: $apiLevel (Android $androidVersion)")
        Log.i(TAG, "  Frame Metrics: Always Available (minSdk 24+)")
        Log.i(TAG, "  Modern Networking: ${if (supportsModernNetworking) "✓" else "✗"}")
        Log.i(TAG, "  Advanced Memory: ${if (supportsAdvancedMemoryInfo) "✓" else "✗"}")
        Log.i(TAG, "  Scoped Storage: ${if (supportsScopedStorage) "✓" else "✗"}")
        Log.i(TAG, "  Runtime Permissions: ${if (supportsRuntimePermissions) "✓" else "✗"}")
        Log.i(TAG, "  Notification Channels: ${if (supportsNotificationChannels) "✓" else "✗"}")
        Log.i(TAG, "  Long Version Code: ${if (supportsLongVersionCode) "✓" else "✗"}")
        
        // Log hardware capabilities
        Log.d(TAG, "Hardware Features:")
        Log.d(TAG, "  Camera: ${if (hasCamera) "✓" else "✗"}")
        Log.d(TAG, "  Telephony: ${if (hasTelephony) "✓" else "✗"}")
        Log.d(TAG, "  WiFi: ${if (hasWifi) "✓" else "✗"}")
        Log.d(TAG, "  Bluetooth: ${if (hasBluetooth) "✓" else "✗"}")
        Log.d(TAG, "  GPS: ${if (hasGps) "✓" else "✗"}")
    }
}
