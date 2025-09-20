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
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.TrackComposeScreen
import com.androidtel.telemetry_library.testing.EdgeTelemetryTester
import java.time.Duration

// 1. Application Setup
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize TelemetryManager with Flutter-compatible features
        TelemetryManager.initialize(
            application = this,
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
        
        // Test crash reporting
        Button(
            onClick = {
                TelemetryManager.getInstance().addBreadcrumb("User initiated crash test", "user")
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
                    TelemetryManager.getInstance().trackError(e, mapOf(
                        "error_source" to "settings_screen",
                        "user_action" to "simulate_error"
                    ))
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
