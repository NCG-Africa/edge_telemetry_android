package com.androidtel.telemetry_library.core.user

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.androidtel.telemetry_library.core.ids.IdGenerator
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * User Profile data class
 */
data class UserProfile(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null
)

/**
 * User Profile Manager that handles user profile lifecycle and persistence
 */
class UserProfileManager(
    context: Context,
    private val idGenerator: IdGenerator
) {
    
    companion object {
        private const val TAG = "UserProfileManager"
        private const val PREFS_NAME = "edge_telemetry_user"
        private const val KEY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = ReentrantReadWriteLock()
    
    // Current user profile state - starts empty
    private var currentProfile = UserProfile()
    
    init {
        // Load persisted profile if exists
        loadUserProfile()
    }
    
    /**
     * Set user profile information
     * Can be called before or after SDK.init()
     * Fully replaces previous values (no merge)
     * Passing null for a field clears it
     */
    fun setUserProfile(name: String?, email: String?, phone: String? = null) {
        lock.write {
            // Update profile - full replacement
            currentProfile = UserProfile(
                name = name,
                email = email,
                phone = phone
            )
            
            // Persist to SharedPreferences
            saveUserProfile()
            
            Log.i(TAG, "User profile updated: name=$name, email=$email, phone=$phone")
        }
    }
    
    /**
     * Clear user profile (sets all fields to null)
     */
    fun clearUserProfile() {
        setUserProfile(null, null, null)
    }
    
    /**
     * Get user ID - NEVER returns null, auto-generates if needed
     */
    fun getUserId(): String {
        return idGenerator.getUserId()
    }
    
    /**
     * Get user profile for telemetry events
     * Returns current profile with name and email (may be null)
     */
    fun getUserProfile(): UserProfile {
        return lock.read {
            currentProfile
        }
    }
    
    /**
     * Get user attributes as a map for event enrichment
     */
    fun getUserAttributes(): Map<String, String> {
        return lock.read {
            buildMap {
                put("user.id", idGenerator.getUserId())
                currentProfile.name?.let { put("user_display_name", it) }
                currentProfile.email?.let { put("user_email", it) }
                currentProfile.phone?.let { put("user_phone", it) }
            }
        }
    }
    
    
    /**
     * Load user profile from SharedPreferences
     */
    private fun loadUserProfile() {
        lock.write {
            val name = prefs.getString(KEY_NAME, null)
            val email = prefs.getString(KEY_EMAIL, null)
            val phone = prefs.getString(KEY_PHONE, null)
            
            currentProfile = UserProfile(
                name = name,
                email = email,
                phone = phone
            )
        }
    }
    
    /**
     * Save user profile to SharedPreferences
     */
    private fun saveUserProfile() {
        val editor = prefs.edit()
        
        // Save or remove name
        if (currentProfile.name != null) {
            editor.putString(KEY_NAME, currentProfile.name)
        } else {
            editor.remove(KEY_NAME)
        }
        
        // Save or remove email
        if (currentProfile.email != null) {
            editor.putString(KEY_EMAIL, currentProfile.email)
        } else {
            editor.remove(KEY_EMAIL)
        }
        
        // Save or remove phone
        if (currentProfile.phone != null) {
            editor.putString(KEY_PHONE, currentProfile.phone)
        } else {
            editor.remove(KEY_PHONE)
        }
        
        editor.apply()
    }
    
}
