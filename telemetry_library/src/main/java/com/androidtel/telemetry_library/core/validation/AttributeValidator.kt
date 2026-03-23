package com.androidtel.telemetry_library.core.validation

import android.util.Log

/**
 * Validator for Phase 2 Standard Attributes
 * Ensures all events have required app, device, user, and session attributes
 */
object AttributeValidator {
    
    private const val TAG = "AttributeValidator"
    
    /**
     * Phase 2 Required App Attributes (must be present on ALL events)
     */
    private val REQUIRED_APP_ATTRIBUTES = setOf(
        "app.name",
        "app.version",
        "app.build_number",
        "app.package_name"
    )
    
    /**
     * Phase 2 Required Device Attributes (must be present on ALL events)
     */
    private val REQUIRED_DEVICE_ATTRIBUTES = setOf(
        "device.id",
        "device.platform",
        "device.platform_version",
        "device.model",
        "device.manufacturer",
        "device.brand",
        "device.android_sdk",
        "device.android_release",
        "device.fingerprint",
        "device.hardware",
        "device.product"
    )
    
    /**
     * Phase 2 Required User & Session Attributes (must be present on ALL events)
     */
    private val REQUIRED_USER_SESSION_ATTRIBUTES = setOf(
        "user.id",
        "session.id",
        "session.start_time"
    )
    
    /**
     * All required Phase 2 attributes combined
     */
    private val ALL_REQUIRED_ATTRIBUTES = REQUIRED_APP_ATTRIBUTES + 
        REQUIRED_DEVICE_ATTRIBUTES + 
        REQUIRED_USER_SESSION_ATTRIBUTES
    
    /**
     * Validate that all Phase 2 required attributes are present
     * 
     * @param attributes The flattened attributes map from an event
     * @param eventName The name of the event being validated (for logging)
     * @return ValidationResult indicating success or failure with missing attributes
     */
    fun validatePhase2Attributes(
        attributes: Map<String, Any?>,
        eventName: String? = null
    ): ValidationResult {
        val missingAttributes = mutableListOf<String>()
        val emptyAttributes = mutableListOf<String>()
        
        // Check each required attribute
        ALL_REQUIRED_ATTRIBUTES.forEach { requiredAttr ->
            val value = attributes[requiredAttr]
            
            when {
                value == null -> missingAttributes.add(requiredAttr)
                value.toString().isBlank() -> emptyAttributes.add(requiredAttr)
            }
        }
        
        val allIssues = missingAttributes + emptyAttributes
        
        return if (allIssues.isEmpty()) {
            ValidationResult.Success
        } else {
            val eventInfo = eventName?.let { " for event '$it'" } ?: ""
            Log.w(TAG, "Phase 2 validation failed$eventInfo. Missing: $missingAttributes, Empty: $emptyAttributes")
            ValidationResult.Failure(missingAttributes, emptyAttributes)
        }
    }
    
    /**
     * Validate app attributes specifically
     */
    fun validateAppAttributes(attributes: Map<String, Any?>): ValidationResult {
        return validateAttributeSet(attributes, REQUIRED_APP_ATTRIBUTES, "App")
    }
    
    /**
     * Validate device attributes specifically
     */
    fun validateDeviceAttributes(attributes: Map<String, Any?>): ValidationResult {
        return validateAttributeSet(attributes, REQUIRED_DEVICE_ATTRIBUTES, "Device")
    }
    
    /**
     * Validate user and session attributes specifically
     */
    fun validateUserSessionAttributes(attributes: Map<String, Any?>): ValidationResult {
        return validateAttributeSet(attributes, REQUIRED_USER_SESSION_ATTRIBUTES, "User/Session")
    }
    
    /**
     * Helper to validate a specific set of attributes
     */
    private fun validateAttributeSet(
        attributes: Map<String, Any?>,
        requiredSet: Set<String>,
        category: String
    ): ValidationResult {
        val missingAttributes = mutableListOf<String>()
        val emptyAttributes = mutableListOf<String>()
        
        requiredSet.forEach { requiredAttr ->
            val value = attributes[requiredAttr]
            
            when {
                value == null -> missingAttributes.add(requiredAttr)
                value.toString().isBlank() -> emptyAttributes.add(requiredAttr)
            }
        }
        
        val allIssues = missingAttributes + emptyAttributes
        
        return if (allIssues.isEmpty()) {
            ValidationResult.Success
        } else {
            Log.w(TAG, "$category validation failed. Missing: $missingAttributes, Empty: $emptyAttributes")
            ValidationResult.Failure(missingAttributes, emptyAttributes)
        }
    }
    
    /**
     * Get a human-readable report of validation issues
     */
    fun getValidationReport(result: ValidationResult): String {
        return when (result) {
            is ValidationResult.Success -> "✓ All Phase 2 required attributes present"
            is ValidationResult.Failure -> {
                buildString {
                    appendLine("✗ Phase 2 validation failed:")
                    if (result.missingAttributes.isNotEmpty()) {
                        appendLine("  Missing attributes: ${result.missingAttributes.joinToString(", ")}")
                    }
                    if (result.emptyAttributes.isNotEmpty()) {
                        appendLine("  Empty attributes: ${result.emptyAttributes.joinToString(", ")}")
                    }
                }
            }
        }
    }
}

/**
 * Result of attribute validation
 */
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Failure(
        val missingAttributes: List<String>,
        val emptyAttributes: List<String>
    ) : ValidationResult()
    
    val isValid: Boolean
        get() = this is Success
}
