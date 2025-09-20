package com.androidtel.telemetry_library.testing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.androidtel.telemetry_library.EdgeTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Testing utility for EdgeTelemetry SDK functionality
 */
object EdgeTelemetryTester {
    
    private const val TAG = "EdgeTelemetryTester"
    
    /**
     * Test crash reporting with custom message
     */
    fun testCrashReporting(customMessage: String? = null) {
        try {
            EdgeTelemetry.getInstance().testCrashReporting(customMessage)
            Log.i(TAG, "🧪 Crash reporting test initiated")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test crash reporting", e)
        }
    }
    
    /**
     * Test connectivity by sending test events
     */
    fun testConnectivity() {
        try {
            EdgeTelemetry.getInstance().testConnectivity()
            Log.i(TAG, "🌐 Connectivity test initiated")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test connectivity", e)
        }
    }
    
    /**
     * Test breadcrumb functionality
     */
    fun testBreadcrumbs() {
        try {
            val edgeTelemetry = EdgeTelemetry.getInstance()
            
            // Add various types of breadcrumbs
            edgeTelemetry.addBreadcrumb("Test navigation", "navigation", "info", mapOf("screen" to "TestScreen"))
            edgeTelemetry.addBreadcrumb("Test user action", "user", "info", mapOf("action" to "button_click"))
            edgeTelemetry.addBreadcrumb("Test system event", "system", "warning", mapOf("event" to "low_memory"))
            edgeTelemetry.addBreadcrumb("Test custom event", "custom", "debug", mapOf("test" to "true"))
            
            Log.i(TAG, "🍞 Breadcrumb test completed - 4 breadcrumbs added")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test breadcrumbs", e)
        }
    }
    
    /**
     * Test user profile functionality
     */
    fun testUserProfile() {
        try {
            val edgeTelemetry = EdgeTelemetry.getInstance()
            
            // Set user profile
            edgeTelemetry.setUserProfile(
                name = "Test User",
                email = "test@example.com",
                phone = "+1234567890",
                customAttributes = mapOf(
                    "role" to "tester",
                    "plan" to "premium"
                )
            )
            
            Log.i(TAG, "👤 User profile test completed")
            
            // Clear user profile after a delay
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(2000)
                edgeTelemetry.clearUserProfile()
                Log.i(TAG, "🧹 User profile cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test user profile", e)
        }
    }
    
    /**
     * Test session management
     */
    fun testSessionManagement() {
        try {
            val edgeTelemetry = EdgeTelemetry.getInstance()
            
            Log.i(TAG, "📊 Current session: ${edgeTelemetry.getSessionId()}")
            
            // Start new session
            edgeTelemetry.startSession()
            Log.i(TAG, "🚀 New session started: ${edgeTelemetry.getSessionId()}")
            
            // End session after delay
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(3000)
                edgeTelemetry.endSession()
                Log.i(TAG, "🏁 Session ended")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test session management", e)
        }
    }
    
    /**
     * Test event tracking
     */
    fun testEventTracking() {
        try {
            val edgeTelemetry = EdgeTelemetry.getInstance()
            
            // Track various events
            edgeTelemetry.trackEvent("test.button_click", mapOf(
                "button_id" to "test_button",
                "screen" to "test_screen"
            ))
            
            edgeTelemetry.trackEvent("test.feature_used", mapOf(
                "feature" to "telemetry_testing",
                "version" to "1.0"
            ))
            
            edgeTelemetry.trackEvent("test.performance", mapOf(
                "load_time" to "1250ms",
                "success" to "true"
            ))
            
            Log.i(TAG, "📊 Event tracking test completed - 3 events tracked")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test event tracking", e)
        }
    }
    
    /**
     * Test error tracking
     */
    fun testErrorTracking() {
        try {
            val edgeTelemetry = EdgeTelemetry.getInstance()
            
            // Track manual error
            val testException = RuntimeException("Test error for telemetry validation")
            edgeTelemetry.trackError(testException, mapOf(
                "test" to "true",
                "error_type" to "manual"
            ))
            
            // Track error with message
            edgeTelemetry.trackError(
                "Manual error message test",
                "Test stack trace\nat com.test.TestClass.testMethod(TestClass.kt:123)",
                mapOf("manual" to "true")
            )
            
            Log.i(TAG, "💥 Error tracking test completed - 2 errors tracked")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test error tracking", e)
        }
    }
    
    /**
     * Run comprehensive test suite
     */
    fun runComprehensiveTest() {
        Log.i(TAG, "🧪 Starting comprehensive EdgeTelemetry test suite...")
        
        try {
            // Test in sequence with delays
            testBreadcrumbs()
            
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(500)
                testEventTracking()
                
                kotlinx.coroutines.delay(500)
                testUserProfile()
                
                kotlinx.coroutines.delay(500)
                testSessionManagement()
                
                kotlinx.coroutines.delay(500)
                testErrorTracking()
                
                kotlinx.coroutines.delay(500)
                testConnectivity()
                
                kotlinx.coroutines.delay(1000)
                testCrashReporting("Comprehensive test crash")
                
                Log.i(TAG, "✅ Comprehensive test suite completed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Comprehensive test suite failed", e)
        }
    }
    
    /**
     * Validate payload structure (for debugging)
     */
    fun validatePayloadStructure() {
        Log.i(TAG, "🔍 Validating payload structure...")
        
        try {
            val edgeTelemetry = EdgeTelemetry.getInstance()
            
            Log.i(TAG, "Device ID: ${edgeTelemetry.getDeviceId()}")
            Log.i(TAG, "User ID: ${edgeTelemetry.getUserId() ?: "Not set"}")
            Log.i(TAG, "Session ID: ${edgeTelemetry.getSessionId()}")
            
            // Add test breadcrumb and track test event to validate structure
            edgeTelemetry.addBreadcrumb("Payload validation test", "custom", "info")
            edgeTelemetry.trackEvent("payload.validation", mapOf("test" to "structure"))
            
            Log.i(TAG, "✅ Payload structure validation completed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Payload structure validation failed", e)
        }
    }
    
    /**
     * Check network connectivity
     */
    fun checkNetworkConnectivity(context: Context): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "No Connection"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check network connectivity", e)
            "Unknown"
        }
    }
}
