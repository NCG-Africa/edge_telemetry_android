package com.androidtel.telemetry_library.core.breadcrumbs

import java.time.Instant

/**
 * Breadcrumb data structure matching Flutter SDK format
 */
data class Breadcrumb(
    val message: String,
    val category: String,
    val level: String, // "debug", "info", "warning", "error", "critical"
    val timestamp: String, // ISO8601 format
    val data: Map<String, String>? = null
) {
    companion object {
        fun create(
            message: String,
            category: String,
            level: String = "info",
            data: Map<String, String>? = null
        ): Breadcrumb {
            return Breadcrumb(
                message = message,
                category = category,
                level = level,
                timestamp = Instant.now().toString(),
                data = data
            )
        }
    }
}

/**
 * Breadcrumb categories matching Flutter SDK
 */
object BreadcrumbCategory {
    const val NAVIGATION = "navigation"
    const val USER = "user"
    const val SYSTEM = "system"
    const val NETWORK = "network"
    const val UI = "ui"
    const val CUSTOM = "custom"
}

/**
 * Breadcrumb levels matching Flutter SDK
 */
object BreadcrumbLevel {
    const val DEBUG = "debug"
    const val INFO = "info"
    const val WARNING = "warning"
    const val ERROR = "error"
    const val CRITICAL = "critical"
}
