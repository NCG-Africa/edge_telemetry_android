package com.androidtel.telemetry_library.core.services

import android.content.Context
import com.androidtel.telemetry_library.core.log.TelemetryLog
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
    companion object {
        private const val TAG = "UserProfileService"
    }
    
    private lateinit var userId: String
    private lateinit var userProfileManager: UserProfileManager
    
    private var pendingName: String? = null
    private var pendingEmail: String? = null
    private var pendingPhone: String? = null
    private var hasPendingProfile: Boolean = false
    
    fun initialize() {
        userId = idGenerator.getUserId()
        
        userProfileManager = UserProfileManager(context, idGenerator)
        
        if (hasPendingProfile) {
            userProfileManager.setUserProfile(pendingName, pendingEmail, pendingPhone)
            clearPendingProfile()
            TelemetryLog.d(TAG, "Applied pending user profile")
        }
        
        TelemetryLog.d(TAG, "UserProfileService initialized - User ID: $userId")
    }
    
    /**
     * Set user profile information
     */
    fun setUserProfile(name: String?, email: String?, phone: String? = null) {
        TelemetryLog.d(TAG, "setUserProfile() called")
        if (::userProfileManager.isInitialized) {
            userProfileManager.setUserProfile(name, email, phone)
            TelemetryLog.d(TAG, "User profile set successfully via userProfileManager")
        } else {
            pendingName = name
            pendingEmail = email
            pendingPhone = phone
            hasPendingProfile = true
            TelemetryLog.i(TAG, "User profile stored as pending (will be applied after initialization)")
        }
    }
    
    /**
     * Clear user profile
     */
    fun clearUserProfile() {
        if (::userProfileManager.isInitialized) {
            userProfileManager.clearUserProfile()
            TelemetryLog.d(TAG, "User profile cleared")
        } else {
            TelemetryLog.w(TAG, "UserProfileManager not initialized yet")
        }
    }
    
    /**
     * Get user ID
     */
    fun getUserId(): String {
        val resolvedId = if (::userProfileManager.isInitialized) {
            userProfileManager.getUserId()
        } else if (::userId.isInitialized) {
            userId
        } else {
            ""
        }

        return if (resolvedId.isNotBlank()) {
            resolvedId
        } else {
            TelemetryLog.w(TAG, "User ID not initialized, generating fallback")
            "user_fallback_${System.currentTimeMillis()}"
        }
    }
    
    /**
     * Get user info for event attributes
     */
    fun getUserInfo(): UserInfo {
        val userProfile = if (::userProfileManager.isInitialized) {
            userProfileManager.getUserProfile()
        } else {
            null
        }
        
        val userInfo = UserInfo(
            userId = getUserId(),
            name = userProfile?.name,
            email = userProfile?.email,
            phone = userProfile?.phone
        )
        
        TelemetryLog.d(TAG, "getUserInfo() - userId: ${userInfo.userId}")
        
        return userInfo
    }
    
    /**
     * Get user profile manager (for internal use)
     */
    fun getUserProfileManager(): UserProfileManager? = if (::userProfileManager.isInitialized) userProfileManager else null
    
    /**
     * Clear pending profile data
     */
    private fun clearPendingProfile() {
        pendingName = null
        pendingEmail = null
        pendingPhone = null
        hasPendingProfile = false
    }
}
