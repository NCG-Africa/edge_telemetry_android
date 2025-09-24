package com.androidtel.telemetry_library.core.breadcrumbs

import android.util.Log
import com.google.gson.Gson
import com.androidtel.telemetry_library.utils.DateTimeUtils
import java.util.LinkedList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Breadcrumb Manager that maintains a circular buffer of breadcrumbs
 * with thread-safe operations and JSON serialization matching Flutter SDK
 */
class BreadcrumbManager {
    
    companion object {
        private const val TAG = "BreadcrumbManager"
        private const val MAX_BREADCRUMBS = 50
    }
    
    private val breadcrumbs = LinkedList<Breadcrumb>()
    private val lock = ReentrantReadWriteLock()
    private val gson = Gson()
    
    /**
     * Add a breadcrumb with automatic timestamp
     */
    fun addBreadcrumb(
        message: String, 
        category: String, 
        level: String = BreadcrumbLevel.INFO, 
        data: Map<String, String>? = null
    ) {
        val breadcrumb = Breadcrumb.create(message, category, level, data)
        
        lock.write {
            breadcrumbs.addLast(breadcrumb)
            
            // Maintain max size
            while (breadcrumbs.size > MAX_BREADCRUMBS) {
                breadcrumbs.removeFirst()
            }
        }
        
        Log.d(TAG, "🍞 Breadcrumb added: [$category] $message")
    }
    
    /**
     * Add navigation breadcrumb
     */
    fun addNavigation(activity: String, data: Map<String, String>? = null) {
        val navigationData = mutableMapOf<String, String>()
        navigationData["activity"] = activity
        data?.let { navigationData.putAll(it) }
        
        addBreadcrumb(
            message = "Navigated to $activity",
            category = BreadcrumbCategory.NAVIGATION,
            level = BreadcrumbLevel.INFO,
            data = navigationData
        )
    }
    
    /**
     * Add user action breadcrumb
     */
    fun addUserAction(action: String, data: Map<String, String>? = null) {
        addBreadcrumb(
            message = "User action: $action",
            category = BreadcrumbCategory.USER,
            level = BreadcrumbLevel.INFO,
            data = data
        )
    }
    
    /**
     * Add system event breadcrumb
     */
    fun addSystem(event: String, data: Map<String, String>? = null) {
        addBreadcrumb(
            message = "System event: $event",
            category = BreadcrumbCategory.SYSTEM,
            level = BreadcrumbLevel.INFO,
            data = data
        )
    }
    
    /**
     * Add network event breadcrumb
     */
    fun addNetwork(event: String, data: Map<String, String>? = null) {
        addBreadcrumb(
            message = "Network event: $event",
            category = BreadcrumbCategory.NETWORK,
            level = BreadcrumbLevel.INFO,
            data = data
        )
    }
    
    /**
     * Add UI event breadcrumb
     */
    fun addUI(event: String, data: Map<String, String>? = null) {
        addBreadcrumb(
            message = "UI event: $event",
            category = BreadcrumbCategory.UI,
            level = BreadcrumbLevel.INFO,
            data = data
        )
    }
    
    /**
     * Add custom breadcrumb
     */
    fun addCustom(message: String, data: Map<String, String>? = null) {
        addBreadcrumb(
            message = message,
            category = BreadcrumbCategory.CUSTOM,
            level = BreadcrumbLevel.INFO,
            data = data
        )
    }
    
    /**
     * Add error breadcrumb
     */
    fun addError(message: String, data: Map<String, String>? = null) {
        addBreadcrumb(
            message = message,
            category = BreadcrumbCategory.SYSTEM,
            level = BreadcrumbLevel.ERROR,
            data = data
        )
    }
    
    /**
     * Get breadcrumbs as JSON string for crash payload
     */
    fun getBreadcrumbsAsJson(): String {
        return lock.read {
            try {
                gson.toJson(breadcrumbs.toList())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize breadcrumbs", e)
                "[]"
            }
        }
    }
    
    /**
     * Get breadcrumb count
     */
    fun getBreadcrumbCount(): Int {
        return lock.read { breadcrumbs.size }
    }
    
    /**
     * Get all breadcrumbs (copy)
     */
    fun getAllBreadcrumbs(): List<Breadcrumb> {
        return lock.read { breadcrumbs.toList() }
    }
    
    /**
     * Clear all breadcrumbs
     */
    fun clear() {
        lock.write {
            breadcrumbs.clear()
        }
        Log.d(TAG, "🧹 Breadcrumbs cleared")
    }
    
    /**
     * Get recent breadcrumbs (last N)
     */
    fun getRecentBreadcrumbs(count: Int): List<Breadcrumb> {
        return lock.read {
            breadcrumbs.takeLast(count)
        }
    }
}
