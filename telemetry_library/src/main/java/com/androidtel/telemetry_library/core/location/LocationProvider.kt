package com.androidtel.telemetry_library.core.location

interface LocationProvider {
    suspend fun getLocation(): String
    
    fun getCachedLocation(): String?
    
    fun clearCache()
}
