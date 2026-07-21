package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class IdGenerator {
    
    companion object {
        private const val PREFS_NAME = "edge_telemetry_ids"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "edge_rum_user_id"

        private val secureRandom = SecureRandom()

        /** W3C Trace Context trace-id: 32 lowercase hex (128-bit). Raw hex, not a join key (#43). */
        fun traceId(): String = randomHex(16)

        /** W3C Trace Context span-id: 16 lowercase hex (64-bit). */
        fun spanId(): String = randomHex(8)

        /** `2 * byteCount` lowercase hex chars from the shared crypto RNG. */
        private fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            synchronized(secureRandom) {
                secureRandom.nextBytes(bytes)
            }
            val sb = StringBuilder(byteCount * 2)
            for (b in bytes) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString()
        }
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
                val deviceId = generateId("device")
                sharedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
                deviceId
            }
        }
    }
    
    fun getDeviceId(): String {
        return getOrGenerateDeviceId()
    }
    
    fun generateSessionId(): String {
        return generateId("session")
    }
    
    fun getUserId(): String {
        val sharedPrefs = prefs ?: throw IllegalStateException("IdGenerator not initialized")
        
        return synchronized(lock) {
            sharedPrefs.getString(KEY_USER_ID, null) ?: run {
                val userId = generateId("user")
                sharedPrefs.edit().putString(KEY_USER_ID, userId).apply()
                userId
            }
        }
    }
    
    private fun generateId(kind: String): String {
        return "${kind}_${System.currentTimeMillis()}_${hex16()}_android"
    }

    // 16 lowercase hex chars (64 bits) from crypto RNG.
    private fun hex16(): String = randomHex(8)
}
