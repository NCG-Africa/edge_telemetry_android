# EdgeTelemetry Android SDK

A comprehensive Android telemetry SDK that produces **identical backend payloads** to the Flutter EdgeTelemetry SDK. Provides crash reporting, event tracking, user analytics, and performance monitoring with automatic data collection and Flutter-compatible payload structures.

## Features

- 🚀 **Automatic Crash Detection** - Global exception handling with detailed stack traces
- 📊 **Event Tracking** - Custom events with rich attributes and batching
- 👤 **User Profile Management** - User identification and profile tracking
- 🍞 **Breadcrumb System** - Detailed activity trail for debugging
- 📱 **Session Management** - Automatic session tracking and analytics
- 🌐 **Network Monitoring** - HTTP request tracking with OkHttp integration
- 🔄 **Offline Support** - Network-aware retry with exponential backoff
- 📐 **Compose Integration** - Automatic navigation and screen tracking
- 🎯 **Flutter SDK Compatible** - Identical payload structure for unified analytics

## Installation

### Gradle Setup

Add to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.NCG-Africa:edge_telemetry_android:1.2.1")
}
```

Add to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Quick Start

### 1. Initialize in Application

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        TelemetryManager.initialize(
            application = this,
            endpoint = "https://your-telemetry-endpoint.com/collector/telemetry",
            debugMode = BuildConfig.DEBUG,
            batchSize = 30,
            enableCrashReporting = true, // enabled by default
            enableUserProfiles = true,   // enabled by default
            enableSessionTracking = true, // enabled by default
            globalAttributes = mapOf(
                "app.environment" to if (BuildConfig.DEBUG) "development" else "production",
                "app.flavor" to BuildConfig.FLAVOR
            )
        )
    }
}
```

### 2. Basic Usage

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val telemetryManager = TelemetryManager.getInstance()
        
        // Track custom events
        telemetryManager.trackEvent("app.launched", mapOf(
            "launch_time" to System.currentTimeMillis().toString(),
            "first_launch" to "false"
        ))
        
        // Set user profile
        telemetryManager.setUserProfile(
            name = "John Doe",
            email = "john@example.com",
            customAttributes = mapOf("plan" to "premium")
        )
        
        // Add breadcrumbs
        telemetryManager.addBreadcrumb("MainActivity created", "lifecycle")
    }
}
```

## Advanced Usage

### Crash Reporting

```kotlin
// Automatic crash detection is enabled by default
// Manual error tracking:

try {
    riskyOperation()
} catch (e: Exception) {
    TelemetryManager.getInstance().trackError(e, mapOf(
        "operation" to "risky_operation",
        "user_action" to "button_click"
    ))
}

// Track error with custom message
TelemetryManager.getInstance().trackError(
    message = "Custom error occurred",
    stackTrace = "Custom stack trace...",
    attributes = mapOf("custom" to "true")
)
```

### User Profile Management

```kotlin
val telemetryManager = TelemetryManager.getInstance()

// Set complete user profile
telemetryManager.setUserProfile(
    name = "Jane Smith",
    email = "jane@example.com",
    phone = "+1234567890",
    customAttributes = mapOf(
        "role" to "admin",
        "plan" to "enterprise",
        "signup_date" to "2024-01-15"
    )
)

// Update specific attributes
telemetryManager.setUserProfile(
    customAttributes = mapOf("last_login" to "2024-01-20")
)

// Clear user profile (logout)
telemetryManager.clearUserProfile()
```

### Session Management

```kotlin
val telemetryManager = TelemetryManager.getInstance()

// Sessions are managed automatically, but you can control them:

// Start new session (e.g., after login)
telemetryManager.startNewSession()

// End current session (e.g., before logout)
telemetryManager.endCurrentSession()

// Get current session info
val sessionId = telemetryManager.getSessionId()
val userId = telemetryManager.getUserId()
val deviceId = telemetryManager.getDeviceId()
```

### Breadcrumb System

```kotlin
val telemetryManager = TelemetryManager.getInstance()

// Add different types of breadcrumbs
telemetryManager.addBreadcrumb("User clicked login button", "user", "info", mapOf(
    "button_id" to "login_btn",
    "screen" to "login"
))

telemetryManager.addBreadcrumb("API call started", "network", "debug", mapOf(
    "endpoint" to "/api/users",
    "method" to "GET"
))

telemetryManager.addBreadcrumb("Low memory warning", "system", "warning", mapOf(
    "available_memory" to "50MB"
))
```

### HTTP Monitoring

```kotlin
// Add EdgeTelemetry interceptor to your OkHttpClient
val httpClient = OkHttpClient.Builder()
    .addInterceptor(EdgeTelemetryInterceptor(
        eventTracker = /* get from EdgeTelemetry */,
        breadcrumbManager = /* get from EdgeTelemetry */,
        telemetryEndpoint = "your-endpoint"
    ))
    .build()

// All HTTP requests will now be automatically tracked
```

### Compose Integration

```kotlin
@Composable
fun MyScreen(navController: NavController) {
    // Automatic navigation tracking
    TrackComposeScreen(
        navController = navController,
        screenName = "MyScreen",
        additionalData = mapOf("feature" to "main")
    )
    
    // Manual screen tracking
    TrackScreen(
        screenName = "CustomScreen",
        category = "onboarding",
        attributes = mapOf("step" to "1")
    )
    
    Column {
        Button(
            onClick = {
                // Track user interactions
                trackUserInteraction(
                    action = "button_click",
                    target = "submit_button",
                    attributes = mapOf("form" to "registration")
                )
                
                // Your button logic
                handleSubmit()
            }
        ) {
            Text("Submit")
        }
    }
}

// Track performance metrics
@Composable
fun PerformanceAwareComponent() {
    val startTime = remember { System.currentTimeMillis() }
    
    LaunchedEffect(Unit) {
        // Simulate some work
        delay(100)
        
        val renderTime = System.currentTimeMillis() - startTime
        trackComposePerformance(
            metricName = "component_render_time",
            value = renderTime.toDouble(),
            unit = "ms",
            attributes = mapOf("component" to "PerformanceAwareComponent")
        )
    }
    
    // Your component content
}
```

## Testing

### Built-in Testing Utilities

```kotlin
import com.androidtel.telemetry_library.testing.EdgeTelemetryTester

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Test individual features
        EdgeTelemetryTester.testCrashReporting("Test crash message")
        EdgeTelemetryTester.testConnectivity()
        EdgeTelemetryTester.testBreadcrumbs()
        EdgeTelemetryTester.testUserProfile()
        EdgeTelemetryTester.testEventTracking()
        
        // Run comprehensive test suite
        EdgeTelemetryTester.runComprehensiveTest()
        
        // Validate payload structure
        EdgeTelemetryTester.validatePayloadStructure()
    }
}
```

### Manual Testing

```kotlin
// Test crash reporting
EdgeTelemetry.getInstance().testCrashReporting("Manual test crash")

// Test connectivity
EdgeTelemetry.getInstance().testConnectivity()

// Check network status
val networkType = EdgeTelemetryTester.checkNetworkConnectivity(this)
Log.d("Network", "Current connection: $networkType")
```

## Payload Structure

The SDK generates payloads that match the Flutter SDK exactly:

### Crash Payload

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "data": {
    "type": "error",
    "error": "java.lang.RuntimeException: Something went wrong",
    "timestamp": "2025-01-15T10:30:45.123Z",
    "stackTrace": "Full stack trace string...",
    "fingerprint": "RuntimeException_-1234567890_987654321",
    "attributes": {
      "device.id": "device_1704067200000_a8b9c2d1_android",
      "device.platform": "android",
      "device.model": "Pixel 7",
      "device.manufacturer": "Google",
      "device.os_version": "13",
      "app.name": "My App",
      "app.version": "1.0.0",
      "app.build_number": "123",
      "user.id": "user_1704067200123_abcd1234",
      "session.id": "session_1704067200456_xyz789",
      "network.type": "wifi",
      "crash.fingerprint": "RuntimeException_-1234567890_987654321",
      "crash.breadcrumb_count": "5",
      "error.timestamp": "2025-01-15T10:30:45.123Z",
      "error.has_stack_trace": "true",
      "breadcrumbs": "[{\"message\":\"Navigated to MainActivity\",\"category\":\"navigation\",\"level\":\"info\",\"timestamp\":\"2025-01-15T10:29:30.000Z\",\"data\":{\"activity\":\"MainActivity\",\"from\":\"SplashActivity\"}}]"
    }
  }
}
```

### Event Batch Payload

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "data": {
    "type": "batch",
    "events": [
      {
        "type": "event",
        "eventName": "button_click",
        "timestamp": "2025-01-15T10:30:45.123Z",
        "attributes": {
          "device.id": "device_1704067200000_a8b9c2d1_android",
          "user.id": "user_1704067200123_abcd1234",
          "session.id": "session_1704067200456_xyz789",
          "button_id": "login_btn"
        }
      }
    ],
    "batch_size": 1,
    "timestamp": "2025-01-15T10:30:45.123Z"
  }
}
```

## ID Generation

The SDK generates IDs that match the Flutter SDK format exactly:

- **Device ID**: `device_<13-digit-timestamp>_<8-char-random>_android`
- **User ID**: `user_<13-digit-timestamp>_<8-char-random>`
- **Session ID**: `session_<13-digit-timestamp>_<6-char-random>`

## Configuration Options

```kotlin
TelemetryManager.initialize(
    application = applicationContext,
    endpoint = "https://your-endpoint.com/collector/telemetry",
    debugMode = false,                    // Enable debug logging
    batchSize = 30,                       // Events per batch
    enableCrashReporting = true,          // Automatic crash detection (default: true)
    enableUserProfiles = true,            // User profile management (default: true)
    enableSessionTracking = true,         // Enhanced session tracking (default: true)
    globalAttributes = emptyMap()         // Global attributes for all events
)
```

## Requirements

- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34
- **Kotlin**: 2.1.0+
- **Compose**: BOM 2024.04.01+

## Dependencies

- AndroidX Core KTX
- AndroidX Lifecycle
- AndroidX Navigation (for Compose integration)
- AndroidX Work (for retry scheduling)
- AndroidX Room (for offline storage)
- OkHttp (for networking)
- Gson (for JSON serialization)

## Proguard/R8

The SDK is fully compatible with code obfuscation. No additional ProGuard rules are required.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues, feature requests, or questions:

1. Check the [GitHub Issues](https://github.com/NCG-Africa/edge_telemetry_android/issues)
2. Create a new issue with detailed information
3. Include SDK version, Android version, and reproduction steps

## Changelog

### Version 1.2.1
- 🔧 **IMPROVED**: Unified API - single TelemetryManager initialization
- 🔧 **IMPROVED**: Enhanced backward compatibility
- 🔧 **IMPROVED**: Simplified configuration options
- 📚 **UPDATED**: Documentation and usage examples
- 🧹 **REMOVED**: EdgeTelemetry class (functionality merged into TelemetryManager)

### Version 1.2.0
- ✨ **NEW**: Complete Flutter SDK compatibility
- ✨ **NEW**: Enhanced crash reporting with fingerprinting
- ✨ **NEW**: Comprehensive breadcrumb system
- ✨ **NEW**: User profile management
- ✨ **NEW**: Session tracking and analytics
- ✨ **NEW**: Network-aware retry system
- ✨ **NEW**: Compose navigation integration
- ✨ **NEW**: Built-in testing utilities
- 🔧 **IMPROVED**: Payload structure matches Flutter SDK exactly
- 🔧 **IMPROVED**: Enhanced device information collection
- 🔧 **IMPROVED**: Better error handling and logging
- 📱 **CHANGED**: Minimum SDK raised to 24 (Android 7.0)
