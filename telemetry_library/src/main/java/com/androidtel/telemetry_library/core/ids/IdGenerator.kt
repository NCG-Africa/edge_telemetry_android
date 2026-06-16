package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class IdGenerator {
    
    companion object {
        private const val PREFS_NAME = "edge_telemetry_ids"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "edge_rum_user_id"
        private const val CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val RANDOM_LENGTH = 8
        
        private val secureRandom = SecureRandom()
    }
    
    @Volatile
    private var prefs: SharedPreferences? = null
    
    private val lock = Any()
    
    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
    
    fun getOrGenerateDeviceId(): String {
        val sharedPrefs = prefs ?: throw IllegalStateException("IdGenerator not initialized")
        
        return synchronized(lock) {
            sharedPrefs.getString(KEY_DEVICE_ID, null) ?: run {
                val deviceId = generateId()
                sharedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
                deviceId
            }
        }
    }
    
    fun getDeviceId(): String {
        return getOrGenerateDeviceId()
    }
    
    fun generateSessionId(): String {
        return generateId()
    }
    
    fun getUserId(): String {
        val sharedPrefs = prefs ?: throw IllegalStateException("IdGenerator not initialized")
        
        return synchronized(lock) {
            sharedPrefs.getString(KEY_USER_ID, null) ?: run {
                val userId = generateId()
                sharedPrefs.edit().putString(KEY_USER_ID, userId).apply()
                userId
            }
        }
    }
    
    private fun generateId(): String {
        val timestamp = System.currentTimeMillis()
        val randomString = generateRandomString(RANDOM_LENGTH)
        return "${timestamp}_${randomString}"
    }
    
    private fun generateRandomString(length: Int): String {
        val chars = CharArray(length)
        synchronized(secureRandom) {
            for (i in 0 until length) {
                chars[i] = CHARS[secureRandom.nextInt(CHARS.length)]
            }
        }
        return String(chars)
    }
}
