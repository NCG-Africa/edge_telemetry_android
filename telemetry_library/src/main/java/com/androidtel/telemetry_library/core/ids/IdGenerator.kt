package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
     * CRITICAL: Validates that generated ID is not empty
     */
    fun generateUserId(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomString(8)
        val userId = "user_${timestamp}_${random}"
        
        // Validation: Ensure ID is not empty
        if (userId.isBlank()) {
            throw IllegalStateException("Generated user ID is blank - this should never happen")
        }
        
        return userId
    }
    
    /**
     * Generate Session ID with exact format: session_<13-digit-timestamp>_<6-char-random>
     * CRITICAL: Validates that generated ID is not empty
     */
    fun generateSessionId(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomString(6)
        val sessionId = "session_${timestamp}_${random}"
        
        // Validation: Ensure ID is not empty
        if (sessionId.isBlank()) {
            throw IllegalStateException("Generated session ID is blank - this should never happen")
        }
        
        return sessionId
    }
    
    /**
     * Store user ID in preferences
     */
    fun setUserId(userId: String) {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    /**
     * Get stored user ID, auto-generating if not exists
     * CRITICAL: This method NEVER returns null - always returns a valid user ID
     */
    fun getUserId(): String {
        val prefs = this.prefs ?: throw IllegalStateException("IdGenerator not initialized")
        return prefs.getString(KEY_USER_ID, null) ?: run {
            // Auto-generate user ID if not exists
            val newUserId = generateUserId()
            prefs.edit().putString(KEY_USER_ID, newUserId).apply()
            Log.i("IdGenerator", "Auto-generated user ID: $newUserId")
            newUserId
        }
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
     * CRITICAL: Validates that generated ID is not empty
     */
    private fun generateDeviceId(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomString(8)
        val deviceId = "device_${timestamp}_${random}_android"
        
        // Validation: Ensure ID is not empty
        if (deviceId.isBlank()) {
            throw IllegalStateException("Generated device ID is blank - this should never happen")
        }
        
        return deviceId
    }
    
    /**
     * Generate random string using lowercase alphanumeric characters
     */
    private fun generateRandomString(length: Int): String {
        return (1..length).map { CHARS.random() }.joinToString("")
    }
}
