package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/**
 * ID Generator that creates IDs matching the Flutter SDK format exactly
 */
class IdGenerator {
    
    companion object {
        private const val PREFS_NAME = "edge_telemetry_ids"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        
        // Character set for random strings (lowercase alphanumeric)
        private const val CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
    
    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Generate Device ID with exact format: device_<13-digit-timestamp>_<8-char-random>_android
     */
    fun getOrGenerateDeviceId(): String {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val deviceId = generateDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            deviceId
        }
    }
    
    /**
     * Get existing device ID (for external access)
     */
    fun getDeviceId(): String {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        return prefs.getString(KEY_DEVICE_ID, null) ?: getOrGenerateDeviceId()
    }
    
    /**
     * Generate User ID with exact format: user_<13-digit-timestamp>_<8-char-random>
     */
    fun generateUserId(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomString(8)
        return "user_${timestamp}_${random}"
    }
    
    /**
     * Generate Session ID with exact format: session_<13-digit-timestamp>_<6-char-random>
     */
    fun generateSessionId(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomString(6)
        return "session_${timestamp}_${random}"
    }
    
    /**
     * Store user ID in preferences
     */
    fun setUserId(userId: String) {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    /**
     * Get stored user ID
     */
    fun getUserId(): String? {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        return prefs.getString(KEY_USER_ID, null)
    }
    
    /**
     * Clear user ID
     */
    fun clearUserId() {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        prefs.edit().remove(KEY_USER_ID).apply()
    }
    
    /**
     * Generate device ID with exact format
     */
    private fun generateDeviceId(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomString(8)
        return "device_${timestamp}_${random}_android"
    }
    
    /**
     * Generate random string using lowercase alphanumeric characters
     */
    private fun generateRandomString(length: Int): String {
        return (1..length).map { CHARS.random() }.joinToString("")
    }
}
