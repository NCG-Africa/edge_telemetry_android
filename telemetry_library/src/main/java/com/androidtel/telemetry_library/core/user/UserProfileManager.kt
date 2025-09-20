package com.androidtel.telemetry_library.core.user

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.androidtel.telemetry_library.core.ids.IdGenerator
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * User Profile Manager that handles user profile lifecycle and persistence
 */
class UserProfileManager(context: Context) {
    
    companion object {
        private const val TAG = "UserProfileManager"
        private const val PREFS_NAME = "edge_telemetry_user"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_PROFILE_VERSION = "profile_version"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val idGenerator = IdGenerator()
    private val lock = ReentrantReadWriteLock()
    
    // Current user profile state
    private var profileVersion = 0
    private val userProfile = mutableMapOf<String, String?>()
    
    init {
        idGenerator.initialize(context)
        loadUserProfile()
    }
    
    /**
     * Set user profile information
     */
    fun setUserProfile(
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        customAttributes: Map<String, String>? = null
    ) {
        lock.write {
            // Generate user ID if not exists
            val userId = getUserId() ?: run {
                val newUserId = idGenerator.generateUserId()
                idGenerator.setUserId(newUserId)
                newUserId
            }
            
            // Update profile
            name?.let { userProfile["name"] = it }
            email?.let { userProfile["email"] = it }
            phone?.let { userProfile["phone"] = it }
            
            // Handle custom attributes
            customAttributes?.forEach { (key, value) ->
                userProfile[key] = value
            }
            
            // Increment version
            profileVersion++
            
            // Persist to SharedPreferences
            saveUserProfile()
            
            Log.i(TAG, "👤 User profile updated: $userId (version: $profileVersion)")
            
            // Send profile updated event
            sendProfileUpdatedEvent(userId)
            sendProfileSetEvent(userId)
        }
    }
    
    /**
     * Clear user profile
     */
    fun clearUserProfile() {
        lock.write {
            val userId = getUserId()
            
            // Clear profile data but keep user ID and increment version
            userProfile.clear()
            profileVersion++
            
            // Clear from SharedPreferences
            prefs.edit()
                .remove(KEY_USER_NAME)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_USER_PHONE)
                .putInt(KEY_PROFILE_VERSION, profileVersion)
                .apply()
            
            Log.i(TAG, "🧹 User profile cleared: $userId (version: $profileVersion)")
            
            // Send profile updated event with only user.id and version
            userId?.let { sendProfileUpdatedEvent(it) }
        }
    }
    
    /**
     * Get user ID
     */
    fun getUserId(): String? {
        return idGenerator.getUserId()
    }
    
    /**
     * Get user profile attributes for telemetry events
     */
    fun getUserAttributes(): Map<String, String> {
        return lock.read {
            val attributes = mutableMapOf<String, String>()
            
            getUserId()?.let { attributes["user.id"] = it }
            userProfile["name"]?.let { attributes["user.name"] = it }
            userProfile["email"]?.let { attributes["user.email"] = it }
            userProfile["phone"]?.let { attributes["user.phone"] = it }
            
            if (profileVersion > 0) {
                attributes["user.profile_version"] = profileVersion.toString()
            }
            
            // Add custom attributes
            userProfile.forEach { (key, value) ->
                if (key !in listOf("name", "email", "phone") && value != null) {
                    attributes["user.$key"] = value
                }
            }
            
            attributes
        }
    }
    
    /**
     * Get current profile version
     */
    fun getProfileVersion(): Int {
        return lock.read { profileVersion }
    }
    
    /**
     * Load user profile from SharedPreferences
     */
    private fun loadUserProfile() {
        lock.write {
            profileVersion = prefs.getInt(KEY_PROFILE_VERSION, 0)
            
            prefs.getString(KEY_USER_NAME, null)?.let { userProfile["name"] = it }
            prefs.getString(KEY_USER_EMAIL, null)?.let { userProfile["email"] = it }
            prefs.getString(KEY_USER_PHONE, null)?.let { userProfile["phone"] = it }
        }
    }
    
    /**
     * Save user profile to SharedPreferences
     */
    private fun saveUserProfile() {
        val editor = prefs.edit()
        
        userProfile["name"]?.let { editor.putString(KEY_USER_NAME, it) }
        userProfile["email"]?.let { editor.putString(KEY_USER_EMAIL, it) }
        userProfile["phone"]?.let { editor.putString(KEY_USER_PHONE, it) }
        
        editor.putInt(KEY_PROFILE_VERSION, profileVersion)
        editor.apply()
    }
    
    /**
     * Send user.profile_updated event for backend persistence
     */
    private fun sendProfileUpdatedEvent(userId: String) {
        // This will be implemented when integrating with the event tracker
        Log.d(TAG, "📤 Sending user.profile_updated event for $userId")
    }
    
    /**
     * Send user.profile_set event for analytics
     */
    private fun sendProfileSetEvent(userId: String) {
        // This will be implemented when integrating with the event tracker
        Log.d(TAG, "📤 Sending user.profile_set event for $userId")
    }
}
