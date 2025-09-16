package com.androidtel.telemetry_library.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Network capability detection with API level-aware implementations.
 * Provides modern network monitoring on supported devices and legacy fallbacks.
 */
class NetworkCapabilityDetector(
    private val context: Context,
    private val deviceCapabilities: DeviceCapabilities
) {
    companion object {
        private const val TAG = "NetworkCapabilityDetector"
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false
    
    /**
     * Get current network connection type using appropriate API level
     */
    fun getCurrentNetworkType(): String {
        return try {
            if (deviceCapabilities.supportsModernNetworking) {
                getNetworkTypeModern()
            } else {
                getNetworkTypeLegacy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get network type: ${e.localizedMessage}")
            "unknown"
        }
    }
    
    /**
     * Check if device is currently connected to internet
     */
    fun isConnected(): Boolean {
        return try {
            if (deviceCapabilities.supportsModernNetworking) {
                isConnectedModern()
            } else {
                isConnectedLegacy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check connectivity: ${e.localizedMessage}")
            false
        }
    }
    
    /**
     * Get network capabilities summary for telemetry
     */
    fun getNetworkCapabilitiesSummary(): Map<String, Any> {
        val summary = mutableMapOf<String, Any>()
        
        summary["connection_type"] = getCurrentNetworkType()
        summary["is_connected"] = isConnected()
        summary["api_level"] = deviceCapabilities.apiLevel
        summary["uses_modern_api"] = deviceCapabilities.supportsModernNetworking
        
        if (deviceCapabilities.supportsModernNetworking) {
            try {
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                
                networkCapabilities?.let { caps ->
                    summary["has_internet"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    summary["has_validated"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    summary["is_metered"] = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    
                    // Transport types
                    summary["has_wifi"] = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    summary["has_cellular"] = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    summary["has_ethernet"] = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        summary["has_vpn"] = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get detailed network capabilities: ${e.localizedMessage}")
            }
        }
        
        return summary
    }
    
    /**
     * Start monitoring network changes (if supported)
     */
    fun startNetworkMonitoring(onNetworkChanged: (String) -> Unit) {
        if (!deviceCapabilities.supportsNetworkCallback) {
            Log.d(TAG, "Network callback not supported on API ${deviceCapabilities.apiLevel}")
            return
        }
        
        if (isMonitoring) {
            Log.d(TAG, "Network monitoring already active")
            return
        }
        
        try {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val networkType = getCurrentNetworkType()
                    Log.d(TAG, "Network available: $networkType")
                    onNetworkChanged(networkType)
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost")
                    onNetworkChanged("disconnected")
                }
                
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val networkType = getCurrentNetworkType()
                    Log.d(TAG, "Network capabilities changed: $networkType")
                    onNetworkChanged(networkType)
                }
            }
            
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
                
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            isMonitoring = true
            Log.d(TAG, "Network monitoring started")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start network monitoring: ${e.localizedMessage}")
        }
    }
    
    /**
     * Stop network monitoring
     */
    fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop network monitoring: ${e.localizedMessage}")
            }
        }
        networkCallback = null
        isMonitoring = false
    }
    
    // Modern API implementation (API 23+)
    @RequiresApi(Build.VERSION_CODES.M)
    private fun getNetworkTypeModern(): String {
        val activeNetwork = connectivityManager.activeNetwork ?: return "none"
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "unknown"
        
        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    private fun isConnectedModern(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    // Legacy API implementation (API 21-22)
    @Suppress("DEPRECATION")
    private fun getNetworkTypeLegacy(): String {
        val activeNetworkInfo = connectivityManager.activeNetworkInfo ?: return "none"
        
        return when (activeNetworkInfo.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            ConnectivityManager.TYPE_ETHERNET -> "ethernet"
            ConnectivityManager.TYPE_VPN -> "vpn"
            else -> "other"
        }
    }
    
    @Suppress("DEPRECATION")
    private fun isConnectedLegacy(): Boolean {
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo?.isConnectedOrConnecting == true
    }
}
