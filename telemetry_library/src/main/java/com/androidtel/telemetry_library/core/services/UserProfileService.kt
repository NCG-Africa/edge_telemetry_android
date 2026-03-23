package com.androidtel.telemetry_library.core.services

import android.content.Context
import android.util.Log
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.ids.IdGenerator
import com.androidtel.telemetry_library.core.models.UserInfo
import com.androidtel.telemetry_library.core.user.UserProfileManager

/**
 * UserProfileService - Handles user profile management
 * Extracted from TelemetryManager as part of Phase 2 refactoring
 * 
 * Responsibilities:
 * - Set/clear user profile information
 * - Get user ID
 * - Provide user info for event attributes
 * - Handle pending profile updates before initialization
 */
internal class UserProfileService(
    private val context: Context,
    private val config: TelemetryConfig,
    private val idGenerator: IdGenerator
) {
    private lateinit var userId: String
    private var userProfileManager: UserProfileManager? = null
    
    private var pendingDisplayName: String? = null
    private var pendingEmail: String? = null
    private var pendingPhone: String? = null
    private var hasPendingProfile: Boolean = false
    
    fun initialize() {
        userId = idGenerator.getUserId()
        
        if (config.enableUserProfiles) {
            userProfileManager = UserProfileManager(context, idGenerator)
            
            if (hasPendingProfile) {
                userProfileManager!!.setUserProfile(pendingDisplayName, pendingEmail, pendingPhone)
                clearPendingProfile()
                Log.d(TAG, "Applied pending user profile")
            }
        }
        
        Log.d(TAG, "UserProfileService initialized - User ID: $userId")
    }
    
    /**
     * Set user profile information
     */
    fun setUserProfile(displayName: String?, email: String?, phone: String? = null) {
        if (userProfileManager != null) {
            userProfileManager!!.setUserProfile(displayName, email, phone)
        } else {
            pendingDisplayName = displayName
            pendingEmail = email
            pendingPhone = phone
            hasPendingProfile = true
            Log.i(TAG, "User profile stored for application after initialization")
        }
    }
    
    /**
     * Clear user profile
     */
    fun clearUserProfile() {
        if (config.enableUserProfiles && userProfileManager != null) {
            userProfileManager!!.clearUserProfile()
        } else {
            Log.w(TAG, "User profiles not enabled")
        }
    }
    
    /**
     * Get user ID
     */
    fun getUserId(): String {
        return if (config.enableUserProfiles && userProfileManager != null) {
            userProfileManager!!.getUserId()
        } else {
            if (userId.isBlank()) {
                Log.w(TAG, "User ID is blank, generating fallback")
                "user_fallback_${System.currentTimeMillis()}"
            } else {
                userId
            }
        }
    }
    
    /**
     * Get user info for event attributes
     */
    fun getUserInfo(): UserInfo {
        val userProfile = if (config.enableUserProfiles && userProfileManager != null) {
            userProfileManager!!.getUserProfile()
        } else {
            null
        }
        
        return UserInfo(
            userId = getUserId(),
            name = userProfile?.displayName,
            email = userProfile?.email,
            phone = userProfile?.phone
        )
    }
    
    /**
     * Check if user profiles are enabled
     */
    fun isUserProfilesEnabled(): Boolean = config.enableUserProfiles
    
    /**
     * Get user profile manager (for internal use)
     */
    fun getUserProfileManager(): UserProfileManager? = userProfileManager
    
    /**
     * Clear pending profile data
     */
    private fun clearPendingProfile() {
        pendingDisplayName = null
        pendingEmail = null
        pendingPhone = null
        hasPendingProfile = false
    }
    
    companion object {
        private const val TAG = "UserProfileService"
    }
}
