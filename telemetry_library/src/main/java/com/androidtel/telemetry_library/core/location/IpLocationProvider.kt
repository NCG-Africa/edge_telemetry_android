package com.androidtel.telemetry_library.core.location

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class IpLocationProvider(
    private val httpClient: OkHttpClient,
    private val apiEndpoint: String = "https://ipinfo.io/json",
    private val cacheDuration: Long = 3600000,
    private val fallbackToIp: Boolean = true
) : LocationProvider {
    
    companion object {
        private const val TAG = "IpLocationProvider"
        private const val IP_ECHO_SERVICE = "https://api.ipify.org?format=text"
    }
    
    @Volatile
    private var cachedLocation: String? = null
    
    @Volatile
    private var cacheTimestamp: Long = 0
    
    override suspend fun getLocation(): String = withContext(Dispatchers.IO) {
        if (isCacheValid()) {
            Log.d(TAG, "Returning cached location: $cachedLocation")
            return@withContext cachedLocation ?: "Unknown/Unknown"
        }
        
        try {
            val request = Request.Builder()
                .url(apiEndpoint)
                .get()
                .build()
            
            val response = withTimeout(3000) {
                httpClient.newCall(request).execute()
            }
            
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val city = json.optString("city", "")
                val country = json.optString("country", "")
                val ip = json.optString("ip", "")
                
                val location = when {
                    city.isNotEmpty() && country.isNotEmpty() -> {
                        Log.d(TAG, "Successfully fetched location: $city/$country")
                        "$city/$country"
                    }
                    fallbackToIp && ip.isNotEmpty() -> {
                        Log.d(TAG, "Using IP from response: $ip")
                        ip
                    }
                    else -> {
                        Log.w(TAG, "No location data in response")
                        "Unknown/Unknown"
                    }
                }
                
                cachedLocation = location
                cacheTimestamp = System.currentTimeMillis()
                
                location
            } else if (response.code == 429 && fallbackToIp) {
                Log.w(TAG, "ipinfo.io rate limit exceeded (429), attempting IP fallback")
                getIpAddressFallback()
            } else {
                Log.w(TAG, "API request failed with code: ${response.code}")
                "Unknown/Unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch location: ${e.message}")
            if (fallbackToIp) {
                getIpAddressFallback()
            } else {
                "Unknown/Unknown"
            }
        }
    }
    
    override fun getCachedLocation(): String? = cachedLocation
    
    override fun clearCache() {
        Log.d(TAG, "Clearing location cache")
        cachedLocation = null
        cacheTimestamp = 0
    }
    
    private suspend fun getIpAddressFallback(): String {
        return try {
            val request = Request.Builder()
                .url(IP_ECHO_SERVICE)
                .get()
                .build()
            
            val response = withTimeout(2000) {
                httpClient.newCall(request).execute()
            }
            
            if (response.isSuccessful) {
                val ip = response.body?.string()?.trim() ?: ""
                if (ip.isNotEmpty()) {
                    Log.d(TAG, "IP fallback successful: $ip")
                    cachedLocation = ip
                    cacheTimestamp = System.currentTimeMillis()
                    ip
                } else {
                    Log.w(TAG, "IP fallback returned empty response")
                    "Unknown/Unknown"
                }
            } else {
                Log.w(TAG, "IP fallback failed with code: ${response.code}")
                "Unknown/Unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "IP fallback exception: ${e.message}")
            "Unknown/Unknown"
        }
    }
    
    private fun isCacheValid(): Boolean {
        return cachedLocation != null && 
               (System.currentTimeMillis() - cacheTimestamp) < cacheDuration
    }
}
