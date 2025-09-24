package com.androidtel.telemetry_library.utils

import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for handling date/time operations in Java 8 compatible way
 * Replaces java.time APIs with java.util.Date and SimpleDateFormat
 */
object DateTimeUtils {
    
    // Thread-safe date formatters
    private val iso8601Formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    
    private val timestampFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
    
    /**
     * Get current timestamp in milliseconds
     */
    fun currentTimeMillis(): Long = System.currentTimeMillis()
    
    /**
     * Get current date
     */
    fun now(): Date = Date()
    
    /**
     * Format date to ISO 8601 string (UTC)
     */
    fun formatToIso8601(date: Date): String {
        return iso8601Formatter.get().format(date)
    }
    
    /**
     * Format current time to ISO 8601 string (UTC)
     */
    fun nowAsIso8601(): String {
        return formatToIso8601(now())
    }
    
    /**
     * Format date to readable timestamp
     */
    fun formatTimestamp(date: Date): String {
        return timestampFormatter.get().format(date)
    }
    
    /**
     * Format current time to readable timestamp
     */
    fun nowAsTimestamp(): String {
        return formatTimestamp(now())
    }
    
    /**
     * Parse ISO 8601 string to Date
     */
    fun parseIso8601(dateString: String): Date? {
        return try {
            iso8601Formatter.get().parse(dateString)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Calculate duration between two dates in milliseconds
     */
    fun durationMillis(start: Date, end: Date): Long {
        return end.time - start.time
    }
    
    /**
     * Calculate duration from timestamp to now in milliseconds
     */
    fun durationFromNow(startTimeMillis: Long): Long {
        return currentTimeMillis() - startTimeMillis
    }
    
    /**
     * Add milliseconds to a date
     */
    fun addMilliseconds(date: Date, milliseconds: Long): Date {
        return Date(date.time + milliseconds)
    }
    
    /**
     * Check if date is before another date
     */
    fun isBefore(date1: Date, date2: Date): Boolean {
        return date1.before(date2)
    }
    
    /**
     * Check if date is after another date
     */
    fun isAfter(date1: Date, date2: Date): Boolean {
        return date1.after(date2)
    }
}
