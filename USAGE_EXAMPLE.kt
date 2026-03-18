// Example usage of the EdgeTelemetry Android SDK
// This file demonstrates how to integrate and use the SDK in your Android application

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.androidtel.telemetry_library.core.TelemetryConfig
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.TrackComposeScreen
import com.androidtel.telemetry_library.testing.EdgeTelemetryTester
import java.time.Duration

// 1. Application Setup
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // ⚠️ SECURITY: Never hardcode API keys in source code!
        // Use BuildConfig, local.properties, or environment variables instead.
        // See README.md "API Key Security Best Practices" section for details.
        // For comprehensive guide, see: API_KEY_GUIDE.md
        
        // Option 1: Initialize with TelemetryConfig (Recommended)
        // Cleaner, more maintainable approach for complex configurations
        val config = TelemetryConfig.builder(this, BuildConfig.TELEMETRY_API_KEY)
            .debugMode(true) // Set to false in production
            .batchSize(30)
            .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
            .enableCrashReporting(true)
            .enableUserProfiles(true)
            .enableSessionTracking(true)
            .globalAttributes(mapOf(
                "app.environment" to "development",
                "app.version" to "1.0.0"
            ))
            .build()
        
        TelemetryManager.initialize(config)
        
        // Option 2: Initialize with individual parameters (Legacy)
        // Still fully supported for backward compatibility
        /*
        TelemetryManager.initialize(
            application = this,
            apiKey = BuildConfig.TELEMETRY_API_KEY,  // ✅ Secure: API key from BuildConfig
            // apiKey = "your-api-key",  // ❌ NEVER hardcode API keys!
            endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
            debugMode = true, // Set to false in production
            batchSize = 30,
            enableCrashReporting = true,
            enableUserProfiles = true,
            enableSessionTracking = true,
            globalAttributes = mapOf(
                "app.environment" to "development",
                "app.version" to "1.0.0"
            )
        )
        */
        
        // Set initial user profile (if user is logged in)
        TelemetryManager.getInstance().setUserProfile(
            name = "Demo User",
            email = "demo@example.com",
            customAttributes = mapOf(
                "user_type" to "demo",
                "signup_date" to "2024-01-15"
            )
        )
    }
}

// 2. Main Activity with Compose Integration
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val telemetryManager = TelemetryManager.getInstance()
        
        // Track app launch
        telemetryManager.trackEvent("app.launched", mapOf(
            "launch_time" to System.currentTimeMillis().toString(),
            "activity" to "MainActivity"
        ))
        
        // Add lifecycle breadcrumb
        telemetryManager.addBreadcrumb("MainActivity created", "lifecycle", "info")
        
        setContent {
            MyAppTheme {
                AppNavigation()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        TelemetryManager.getInstance().addBreadcrumb("MainActivity resumed", "lifecycle")
    }
    
    override fun onPause() {
        super.onPause()
        TelemetryManager.getInstance().addBreadcrumb("MainActivity paused", "lifecycle")
    }
}

// 3. Navigation with Automatic Tracking
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(navController)
        }
        composable("profile") {
            ProfileScreen(navController)
        }
        composable("settings") {
            SettingsScreen(navController)
        }
    }
}

// 4. Home Screen with Tracking
@Composable
fun HomeScreen(navController: NavController) {
    // Automatic navigation tracking
    TrackComposeScreen(
        navController = navController,
        screenName = "HomeScreen",
        additionalData = mapOf("feature" to "main_dashboard")
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "EdgeTelemetry Demo",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Button with user interaction tracking
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb(
                    "User clicked navigate to profile",
                    "user",
                    "info",
                    mapOf("screen" to "home")
                )
                TelemetryManager.getInstance().trackEvent("user.navigation", mapOf(
                    "action" to "button_click",
                    "target" to "profile",
                    "source" to "home"
                ))
                navController.navigate("profile")
            }
        ) {
            Text("Go to Profile")
        }
        
        // Test crash reporting (v2.0.0 with enhanced context)
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User initiated crash test", "user")
                
                // Set product context for crash reporting
                TelemetryManager.getInstance().setProductContext("home_module")
                TelemetryManager.getInstance().setLastUserAction("Clicked test crash button")
                
                TelemetryManager.getInstance().testCrashReporting("User initiated test crash")
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Test Crash Reporting")
        }
        
        // Test connectivity
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User tested connectivity", "user")
                TelemetryManager.getInstance().testConnectivity()
            }
        ) {
            Text("Test Connectivity")
        }
        
        // Run comprehensive test
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User ran comprehensive test", "user")
                EdgeTelemetryTester.runComprehensiveTest()
            }
        ) {
            Text("Run All Tests")
        }
    }
}

// 5. Profile Screen with User Management
@Composable
fun ProfileScreen(navController: NavController) {
    TrackComposeScreen(
        navController = navController,
        screenName = "ProfileScreen",
        additionalData = mapOf("feature" to "user_profile")
    )
    
    var userName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "User Profile",
            style = MaterialTheme.typography.headlineMedium
        )
        
        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = userEmail,
            onValueChange = { userEmail = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb(
                    "User updated profile",
                    "user",
                    "info",
                    mapOf("has_name" to userName.isNotEmpty().toString())
                )
                
                TelemetryManager.getInstance().setUserProfile(
                    name = userName.takeIf { it.isNotEmpty() },
                    email = userEmail.takeIf { it.isNotEmpty() },
                    customAttributes = mapOf(
                        "profile_updated_at" to System.currentTimeMillis().toString(),
                        "update_method" to "manual"
                    )
                )
                
                TelemetryManager.getInstance().trackEvent("user.profile_updated", mapOf(
                    "fields_updated" to listOf(
                        if (userName.isNotEmpty()) "name" else null,
                        if (userEmail.isNotEmpty()) "email" else null
                    ).filterNotNull().joinToString(",")
                ))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Update Profile")
        }
        
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User cleared profile", "user")
                TelemetryManager.getInstance().clearUserProfile()
                userName = ""
                userEmail = ""
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Clear Profile")
        }
        
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User navigated back", "user")
                navController.popBackStack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Home")
        }
    }
}

// 6. Settings Screen with Error Handling
@Composable
fun SettingsScreen(navController: NavController) {
    TrackComposeScreen(
        navController = navController,
        screenName = "SettingsScreen"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User simulated error", "user")
                
                try {
                    // Simulate an error
                    throw RuntimeException("Simulated error for testing")
                } catch (e: Exception) {
                    // v2.0.0: Enhanced error tracking with context
                    TelemetryManager.getInstance().trackError(
                        error = e,
                        errorCode = "SETTINGS_001",
                        productId = "settings_module",
                        userAction = "Clicked simulate error button",
                        attributes = mapOf(
                            "error_source" to "settings_screen",
                            "screen" to "settings"
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simulate Error")
        }
        
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User started new session", "user")
                TelemetryManager.getInstance().startNewSession()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start New Session")
        }
        
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User navigated back", "user")
                navController.popBackStack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Home")
        }
    }
}

// 7. Theme (placeholder)
@Composable
fun MyAppTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}

// 8. Network Request Example (if using OkHttp)
/*
class ApiService {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(EdgeTelemetryInterceptor(
            eventTracker = EdgeTelemetry.getInstance().eventTracker,
            breadcrumbManager = EdgeTelemetry.getInstance().breadcrumbManager,
            telemetryEndpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
        ))
        .build()
    
    suspend fun fetchUserData(): Result<UserData> {
        return try {
            val request = Request.Builder()
                .url("https://api.example.com/user")
                .build()
            
            val response = httpClient.newCall(request).execute()
            // Handle response...
            Result.success(userData)
        } catch (e: Exception) {
            EdgeTelemetry.getInstance().trackError(e, mapOf(
                "api_call" to "fetch_user_data",
                "error_type" to "network"
            ))
            Result.failure(e)
        }
    }
}
*/

// 9. Custom Event Tracking Examples
object AnalyticsHelper {
    
    fun trackFeatureUsage(featureName: String, attributes: Map<String, String> = emptyMap()) {
        TelemetryManager.getInstance().trackEvent("feature.used", attributes + mapOf(
            "feature_name" to featureName,
            "timestamp" to System.currentTimeMillis().toString()
        ))
    }
    
    fun trackUserJourney(step: String, journey: String) {
        TelemetryManager.getInstance().trackEvent("user.journey", mapOf(
            "journey" to journey,
            "step" to step,
            "timestamp" to System.currentTimeMillis().toString()
        ))
        
        TelemetryManager.getInstance().addBreadcrumb(
            "User journey: $journey - $step",
            "user",
            "info",
            mapOf("journey" to journey, "step" to step)
        )
    }
    
    fun trackPerformanceMetric(metricName: String, value: Double, unit: String) {
        TelemetryManager.getInstance().trackEvent("performance.metric", mapOf(
            "metric_name" to metricName,
            "value" to value.toString(),
            "unit" to unit,
            "timestamp" to System.currentTimeMillis().toString()
        ))
    }
}

// 10. Phase 2C: Enhanced Context Tracking Examples (v2.0.0)
object Phase2cExamples {
    
    /**
     * Example 1: Authentication Module Error Tracking
     * Demonstrates user_action and error_code in authentication context
     */
    fun trackAuthenticationError() {
        val telemetry = TelemetryManager.getInstance()
        
        // Set product context for the authentication module
        telemetry.setProductContext("authentication_module")
        
        // Track user action before critical operation
        telemetry.setLastUserAction("Clicked login button")
        
        try {
            // Simulate authentication logic
            performLogin()
        } catch (e: NullPointerException) {
            // Track error with enhanced context
            telemetry.trackError(
                error = e,
                errorCode = "AUTH_001",
                attributes = mapOf(
                    "screen" to "LoginActivity",
                    "method" to "performLogin",
                    "user_input_valid" to "true"
                )
            )
        }
    }
    
    /**
     * Example 2: Payment Processing Error Tracking
     * Demonstrates error_code with payment-specific context
     */
    fun trackPaymentError(amount: Double, currency: String) {
        val telemetry = TelemetryManager.getInstance()
        
        // Set product context
        telemetry.setProductContext("payment_module")
        telemetry.setLastUserAction("Initiated payment transaction")
        
        try {
            processPayment(amount, currency)
        } catch (e: TimeoutException) {
            telemetry.trackError(
                error = e,
                errorCode = "PAY_TIMEOUT_001",
                attributes = mapOf(
                    "amount" to amount.toString(),
                    "currency" to currency,
                    "gateway" to "stripe",
                    "retry_count" to "0"
                )
            )
        } catch (e: IllegalStateException) {
            telemetry.trackError(
                error = e,
                errorCode = "PAY_INVALID_STATE",
                attributes = mapOf(
                    "amount" to amount.toString(),
                    "currency" to currency,
                    "payment_state" to "pending"
                )
            )
        }
    }
    
    /**
     * Example 3: Shopping Cart Error Tracking
     * Demonstrates context persistence across multiple operations
     */
    fun trackShoppingCartErrors() {
        val telemetry = TelemetryManager.getInstance()
        
        // Set context once - persists for all errors in this module
        telemetry.setProductContext("shopping_cart")
        
        // Add item to cart
        telemetry.setLastUserAction("Added item to cart")
        try {
            addItemToCart(itemId = "12345")
        } catch (e: Exception) {
            telemetry.trackError(
                error = e,
                errorCode = "CART_ADD_FAILED"
            )
        }
        
        // Update quantity
        telemetry.setLastUserAction("Updated item quantity")
        try {
            updateCartQuantity(itemId = "12345", quantity = 2)
        } catch (e: Exception) {
            telemetry.trackError(
                error = e,
                errorCode = "CART_UPDATE_FAILED"
            )
        }
        
        // Checkout
        telemetry.setLastUserAction("Clicked checkout button")
        try {
            proceedToCheckout()
        } catch (e: Exception) {
            telemetry.trackError(
                error = e,
                errorCode = "CART_CHECKOUT_FAILED"
            )
        }
    }
    
    /**
     * Example 4: Explicit Context Override
     * Demonstrates overriding stored context with explicit parameters
     */
    fun trackWithContextOverride() {
        val telemetry = TelemetryManager.getInstance()
        
        // Set default context
        telemetry.setProductContext("module_a")
        telemetry.setLastUserAction("Action A")
        
        // Track error - uses stored context
        try {
            performOperationA()
        } catch (e: Exception) {
            telemetry.trackError(
                error = e,
                errorCode = "ERR_A"
            )
        }
        
        // Track error with explicit override
        try {
            performOperationB()
        } catch (e: Exception) {
            telemetry.trackError(
                error = e,
                errorCode = "ERR_B",
                productId = "module_b",  // Overrides stored "module_a"
                userAction = "Action B"   // Overrides stored "Action A"
            )
        }
    }
    
    /**
     * Example 5: Database Error Tracking
     * Demonstrates error tracking in data layer
     */
    fun trackDatabaseError(operation: String) {
        val telemetry = TelemetryManager.getInstance()
        
        telemetry.setProductContext("database_module")
        telemetry.setLastUserAction("Executing database $operation")
        
        try {
            executeDatabaseOperation(operation)
        } catch (e: SQLException) {
            telemetry.trackError(
                error = e,
                errorCode = "DB_${operation.uppercase()}_FAILED",
                attributes = mapOf(
                    "operation" to operation,
                    "table" to "users",
                    "sql_state" to (e.sqlState ?: "unknown")
                )
            )
        }
    }
    
    /**
     * Example 6: Network Error Tracking
     * Demonstrates error tracking for API calls
     */
    fun trackNetworkError(endpoint: String, method: String) {
        val telemetry = TelemetryManager.getInstance()
        
        telemetry.setProductContext("network_module")
        telemetry.setLastUserAction("API call to $endpoint")
        
        try {
            makeApiCall(endpoint, method)
        } catch (e: IOException) {
            telemetry.trackError(
                error = e,
                errorCode = "NET_IO_ERROR",
                attributes = mapOf(
                    "endpoint" to endpoint,
                    "method" to method,
                    "network_type" to "wifi"
                )
            )
        } catch (e: TimeoutException) {
            telemetry.trackError(
                error = e,
                errorCode = "NET_TIMEOUT",
                attributes = mapOf(
                    "endpoint" to endpoint,
                    "method" to method,
                    "timeout_ms" to "30000"
                )
            )
        }
    }
    
    /**
     * Example 7: UI Interaction Error Tracking
     * Demonstrates tracking errors from user interactions
     */
    fun trackUIInteractionError(buttonId: String, action: String) {
        val telemetry = TelemetryManager.getInstance()
        
        telemetry.setProductContext("ui_module")
        telemetry.setLastUserAction("Clicked $buttonId button")
        
        try {
            performUIAction(action)
        } catch (e: Exception) {
            telemetry.trackError(
                error = e,
                errorCode = "UI_ACTION_FAILED",
                attributes = mapOf(
                    "button_id" to buttonId,
                    "action" to action,
                    "screen" to "MainActivity"
                )
            )
        }
    }
    
    // Placeholder functions for examples
    private fun performLogin() { throw NullPointerException("User object was null") }
    private fun processPayment(amount: Double, currency: String) { }
    private fun addItemToCart(itemId: String) { }
    private fun updateCartQuantity(itemId: String, quantity: Int) { }
    private fun proceedToCheckout() { }
    private fun performOperationA() { }
    private fun performOperationB() { }
    private fun executeDatabaseOperation(operation: String) { }
    private fun makeApiCall(endpoint: String, method: String) { }
    private fun performUIAction(action: String) { }
}

// 11. Best Practices for Phase 2C Features
object Phase2cBestPractices {
    
    /**
     * Best Practice 1: Set context early in lifecycle
     */
    fun setContextInLifecycle() {
        // In Activity onCreate() or Fragment onViewCreated()
        TelemetryManager.getInstance().setProductContext("feature_module")
    }
    
    /**
     * Best Practice 2: Update user action before critical operations
     */
    fun updateActionBeforeOperation() {
        // Before any operation that might fail
        TelemetryManager.getInstance().setLastUserAction("User submitted form")
        submitForm()
    }
    
    /**
     * Best Practice 3: Use meaningful error codes
     */
    fun useMeaningfulErrorCodes() {
        // Good: Specific, categorized error codes
        val goodErrorCodes = listOf(
            "AUTH_LOGIN_FAILED",
            "PAY_GATEWAY_TIMEOUT",
            "DB_CONNECTION_LOST",
            "NET_SSL_ERROR",
            "UI_VALIDATION_FAILED"
        )
        
        // Avoid: Generic error codes
        val badErrorCodes = listOf(
            "ERROR_001",
            "FAILED",
            "ERR"
        )
    }
    
    /**
     * Best Practice 4: Include relevant attributes
     */
    fun includeRelevantAttributes(amount: Double, currency: String) {
        try {
            processPayment(amount, currency)
        } catch (e: Exception) {
            TelemetryManager.getInstance().trackError(
                error = e,
                errorCode = "PAY_DECLINED",
                attributes = mapOf(
                    "amount" to amount.toString(),
                    "currency" to currency,
                    "card_type" to "visa",
                    "decline_reason" to "insufficient_funds",
                    "retry_count" to "0"
                )
            )
        }
    }
    
    private fun submitForm() { }
    private fun processPayment(amount: Double, currency: String) { }
}
