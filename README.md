# Edge Telemetry Android SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![Gradle](https://img.shields.io/badge/Gradle-8.4%2B-02303A.svg?style=flat&logo=gradle)](https://gradle.org)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0%2B-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-11%2B-ED8B00.svg?style=flat&logo=java)](https://www.oracle.com/java/)
[![JitPack](https://jitpack.io/v/NCG-Africa/edge-telemetry-sdk.svg)](https://jitpack.io/#NCG-Africa/edge-telemetry-sdk)
[![APK Size](https://img.shields.io/badge/APK%20Size-~200KB-orange.svg)]()
[![Memory](https://img.shields.io/badge/Memory-<5MB-green.svg)]()
[![Battery Impact](https://img.shields.io/badge/Battery-Negligible-brightgreen.svg)]()

A comprehensive, production-ready Android SDK for collecting and transmitting telemetry data including app performance metrics, user interactions, crash reports, and system analytics. Built with modern Android development practices and optimized for performance and reliability.

## 🚀 Features

### Core Telemetry
- **📊 Performance Monitoring**: Precise FrameMetrics-based frame drop detection, enhanced memory tracking, and app performance metrics
- **🔄 Session Management**: Automatic session tracking with detailed analytics
- **📱 Screen Analytics**: Activity and Fragment lifecycle monitoring with timing data
- **🎯 Custom Events**: Track custom business events and user interactions
- **💥 Crash Reporting**: Comprehensive crash detection and reporting with stack traces
- **🧠 Memory Intelligence**: Enhanced memory tracking with detailed insights (API 24+)

### Advanced Capabilities
- **🌐 Network Resilience**: Robust HTTP client with exponential backoff retry logic
- **💾 Offline Storage**: Persistent data storage with automatic sync when online
- **🔄 Batch Processing**: Efficient data batching to minimize network overhead
- **🎨 Jetpack Compose Support**: Native support for Compose screen tracking
- **🔒 Privacy-First**: Automatic user ID generation with persistent storage
- **⚡ Memory Efficient**: Optimized memory usage with proper lifecycle management
- **📱 Device Compatibility**: Comprehensive feature support for Android 7.0+ devices

### Technical Highlights
- **Thread-Safe**: Concurrent data collection with proper synchronization
- **Lifecycle-Aware**: Automatic cleanup and resource management
- **Configurable**: Flexible configuration options for different use cases
- **Lightweight**: Minimal impact on app performance and size
- **Modern Architecture**: Built with Kotlin coroutines and modern Android APIs
- **Enhanced Performance Insights**: Consistent, detailed frame metrics for all users
- **Simplified Codebase**: Streamlined architecture with reduced complexity

## 📋 Requirements

### 🔄 **Version Compatibility Matrix**

| Feature | **Java 11+ Version** | **Java 8 Version** |
|---------|---------------------|--------------------|
| **Latest Version** | `1.2.1` | `1.2.3-java8` |
| **Java Requirement** | Java 11+ | Java 8+ |
| **Gradle** | 8.4+ | 7.5.1+ |
| **AGP** | 8.0+ | 7.2.2+ |
| **Kotlin** | 1.9.0+ | 1.8.22+ |
| **Jetpack Compose** | ✅ Full Support | ❌ Not Supported |
| **All Core Features** | ✅ | ✅ |
| **Use Case** | Modern projects | Legacy/Enterprise |

### 🎯 **Choose Your Version**

#### **Java 11+ Version (Recommended)**
- **Latest Features**: Full Jetpack Compose support
- **Modern Toolchain**: Latest Gradle, AGP, and Kotlin
- **Future-Proof**: Receives all new features first

#### **Java 8 Version (Legacy Support)**
- **Enterprise Ready**: Perfect for legacy codebases
- **Dagger 2 Compatible**: Works with Dagger 2 + KAPT setups
- **Core Functionality**: All telemetry features except Compose

### 📱 **Runtime Requirements (Both Versions)**
- **Minimum SDK**: `24` (Android 7.0+)
- **Compile SDK**: `34` (Android 14)
- **Target SDK**: `34` (Android 14)

### 📦 **Dependencies**
- **AndroidX Core**: Compatible versions for each Java target
- **WorkManager**: For offline storage and retry logic
- **Room**: For local data persistence
- **OkHttp**: For network communication
- **Gson**: For JSON serialization

## 📦 Installation

### 🔄 **Choose Your Java Version**

#### **Java 11+ Projects (Recommended)**

Add JitPack repository to your root `build.gradle` or `settings.gradle`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle`:

```kotlin
dependencies {
    implementation 'com.github.NCG-Africa:edge_telemetry_android:1.2.1'
}
```

**Requirements:**
- **Java**: 11+
- **Gradle**: 8.4+
- **AGP**: 8.0+
- **Kotlin**: 1.9.0+ (for Kotlin projects)
- **Features**: ✅ Full Compose support

#### **Java 8 Projects (Legacy/Enterprise)**

Add JitPack repository (same as above), then:

```kotlin
dependencies {
    implementation 'com.github.NCG-Africa:edge_telemetry_android:1.2.3-java8'
}
```

**Requirements:**
- **Java**: 8+ (with desugaring enabled)
- **Gradle**: 7.5.1+
- **AGP**: 7.2.2+
- **Kotlin**: 1.8.22+ (for Kotlin projects)
- **Features**: ❌ No Compose support

### 🎯 **When to Use Each Version**

| Use Java 11+ Version | Use Java 8 Version |
|---------------------|--------------------|
| ✅ New projects | ✅ Legacy codebases |
| ✅ Compose apps | ✅ Dagger 2 + KAPT |
| ✅ Modern toolchain | ✅ Enterprise constraints |
| ✅ Latest features | ✅ Gradual migration |

## 🛠 Quick Setup

### 1. Initialize the SDK

Initialize the SDK in your `Application` class:

#### Kotlin
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize telemetry SDK
        TelemetryManager.initialize(
            application = this,
            endpoint = "https://your-telemetry-endpoint.com/api/telemetry",
            batchSize = 10
        )
    }
}
```

#### Java
```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize telemetry SDK
        TelemetryManager.initialize(
            this, // application context
            "https://your-telemetry-endpoint.com/api/telemetry", // endpoint
            10 // batchSize
        );
    }
}
```

### 2. Register Application in Manifest

```xml
<application
    android:name=".MyApplication"
    android:label="@string/app_name"
    android:theme="@style/AppTheme">
    <!-- Your activities -->
</application>
```

### 3. Start Collecting Data

The SDK automatically starts collecting telemetry data once initialized:

- ✅ App lifecycle events
- ✅ Screen transitions  
- ✅ Performance metrics
- ✅ Memory usage
- ✅ Crash reports
- ✅ User sessions

### 4. Network Request Monitoring

Track HTTP requests automatically using the TelemetryInterceptor with OkHttp:

#### Kotlin (Both Versions)
```kotlin
// Add to your OkHttpClient - Use the factory method to avoid tracking SDK's own requests
val client = OkHttpClient.Builder()
    .addInterceptor(TelemetryManager.createNetworkInterceptor())
    .build()

// Or manually track network requests
TelemetryManager.getInstance().recordNetworkRequest(
    url = "https://api.example.com/users",
    method = "GET",
    statusCode = 200,
    durationMs = 245L,
    requestBodySize = 0L,
    responseBodySize = 1024L
)
```

#### Java (Both Versions)
```java
// Add to your OkHttpClient - Use the factory method to avoid tracking SDK's own requests
OkHttpClient client = new OkHttpClient.Builder()
    .addInterceptor(TelemetryManager.createNetworkInterceptor())
    .build();

// Or manually track network requests
TelemetryManager telemetryManager = TelemetryManager.getInstance();
if (telemetryManager != null) {
    telemetryManager.recordNetworkRequest(
        "https://api.example.com/users", // url
        "GET", // method
        200, // statusCode
        245L, // durationMs
        0L, // requestBodySize
        1024L // responseBodySize
    );
}
```

### 5. Navigation Monitoring

#### For XML-based Navigation (Activities/Fragments)

Activities are automatically tracked. For manual screen tracking:

#### Kotlin
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Activities are automatically tracked by the SDK
    }
}
```

#### Java
```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Activities are automatically tracked by the SDK
    }
}
```

#### For Jetpack Compose Navigation (Java 11+ Version Only)

```kotlin
@Composable
fun MyApp() {
    val navController = rememberNavController()
    val telemetry = TelemetryManager.getInstance()
    telemetry.trackComposeScreens(navController)
    
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen() }
        composable("profile") { ProfileScreen() }
        composable("settings") { SettingsScreen() }
    }
}
```

> **Note**: Compose features are only available in the Java 11+ version (`1.2.1`). For Java 8 projects, use Activity/Fragment-based navigation.

## 📖 Usage Examples

### Custom Event Tracking

#### Kotlin
```kotlin
// Track custom events
TelemetryManager.getInstance()?.trackEvent(
    eventName = "user_login",
    customAttributes = mapOf(
        "login_method" to "google",
        "user_type" to "premium"
    )
)
```

#### Java
```java
// Track custom events
TelemetryManager telemetryManager = TelemetryManager.getInstance();
if (telemetryManager != null) {
    Map<String, Object> customAttributes = new HashMap<>();
    customAttributes.put("login_method", "google");
    customAttributes.put("user_type", "premium");
    
    telemetryManager.trackEvent("user_login", customAttributes);
}
```

### Custom Metrics

#### Kotlin
```kotlin
// Track custom metrics
TelemetryManager.getInstance()?.trackMetric(
    metricName = "api_response_time",
    value = 245.0, // milliseconds
    customAttributes = mapOf(
        "endpoint" to "/api/users",
        "method" to "GET"
    )
)
```

#### Java
```java
// Track custom metrics
TelemetryManager telemetryManager = TelemetryManager.getInstance();
if (telemetryManager != null) {
    Map<String, Object> customAttributes = new HashMap<>();
    customAttributes.put("endpoint", "/api/users");
    customAttributes.put("method", "GET");
    
    telemetryManager.trackMetric(
        "api_response_time", // metricName
        245.0, // value (milliseconds)
        customAttributes
    );
}
```

### Jetpack Compose Screen Tracking (Java 11+ Version Only)

```kotlin
@Composable
fun ProfileScreen(navController: NavController) {
    // Automatic screen tracking
    TrackComposeScreen(
        screenName = "ProfileScreen",
        navController = navController
    )
    
    // Your compose UI
    Column {
        Text("Profile Content")
    }
}
```

> **Note**: Compose screen tracking is only available in the Java 11+ version. Java 8 version users should use Activity/Fragment lifecycle tracking.

### Manual Screen Tracking

#### Kotlin
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Manual screen tracking
        TelemetryManager.getInstance()?.trackScreen("MainActivity")
    }
}
```

#### Java
```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Manual screen tracking
        TelemetryManager telemetryManager = TelemetryManager.getInstance();
        if (telemetryManager != null) {
            telemetryManager.trackScreen("MainActivity");
        }
    }
}
```

## 🔧 Configuration

### Advanced Initialization

#### Kotlin (Both Versions)
```kotlin
TelemetryManager.initialize(
    application = this,
    batchSize = 30,
    endpoint = "https://api.example.com/telemetry",
    debugMode = BuildConfig.DEBUG,
    enableCrashReporting = true,
    enableUserProfiles = true,
    enableSessionTracking = true,
    globalAttributes = mapOf(
        "app_variant" to "production",
        "feature_flags" to "v2_enabled"
    )
)
```

#### Java (Both Versions)
```java
Map<String, String> globalAttributes = new HashMap<>();
globalAttributes.put("app_variant", "production");
globalAttributes.put("feature_flags", "v2_enabled");

TelemetryManager.initialize(
    this,                           // application
    30,                            // batchSize
    "https://api.example.com/telemetry", // endpoint
    BuildConfig.DEBUG,             // debugMode
    true,                          // enableCrashReporting
    true,                          // enableUserProfiles
    true,                          // enableSessionTracking
    globalAttributes               // globalAttributes
);
```

### User Profile Management (Both Versions)

#### Kotlin
```kotlin
// Set user profile after login
TelemetryManager.getInstance().setUserProfile(
    name = "John Doe",
    email = "john.doe@example.com",
    phone = "+1234567890",
    customAttributes = mapOf(
        "subscription_tier" to "premium",
        "signup_date" to "2024-01-15"
    )
)

// Clear profile on logout
TelemetryManager.getInstance().clearUserProfile()
```

#### Java
```java
// Set user profile after login
Map<String, String> customAttributes = new HashMap<>();
customAttributes.put("subscription_tier", "premium");
customAttributes.put("signup_date", "2024-01-15");

TelemetryManager.getInstance().setUserProfile(
    "John Doe",              // name
    "john.doe@example.com",  // email
    "+1234567890",           // phone
    customAttributes         // customAttributes
);

// Clear profile on logout
TelemetryManager.getInstance().clearUserProfile();
```

### Network Configuration

The SDK includes robust network handling:
- **Automatic Retries**: Exponential backoff for server errors (5xx)
- **Offline Support**: Data persisted locally when network unavailable
- **Batch Optimization**: Configurable batch sizes for efficient transmission

## 📊 Data Structure

### Event Types

The SDK collects various event types:

| Type | Description | Auto-Collected | API Support |
|------|-------------|----------------|-------------|
| `screen_view` | Screen/Activity transitions | ✅ | API 24+ |
| `app_lifecycle` | App foreground/background | ✅ | API 24+ |
| `performance` | Frame drops, memory usage | ✅ | API 24+ |
| `frame_drop` | Precise frame performance tracking | ✅ | API 24+ |
| `memory_pressure` | Enhanced memory monitoring | ✅ | API 24+ |
| `storage_usage` | Storage usage tracking | ✅ | API 24+ |
| `crash` | Application crashes | ✅ | API 24+ |
| `custom_event` | Custom business events | Manual | API 24+ |
| `custom_metric` | Custom performance metrics | Manual | API 24+ |

### Data Schema

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "data": {
    "type": "batch",
    "events": [
      {
        "type": "screen_view",
        "eventName": "MainActivity",
        "timestamp": "2024-01-15T10:30:00Z",
        "attributes": {
          "app": {
            "appName": "MyApp",
            "appVersion": "1.0.0",
            "appPackageName": "com.example.myapp"
          },
          "device": {
            "deviceId": "device_1704067200000_a8b9c2d1_android",
            "platform": "android",
            "model": "Pixel 7",
            "manufacturer": "Google"
          },
          "user": {
            "userId": "user_1704067200000_abcd1234"
          },
          "session": {
            "sessionId": "session_1704067200000_x9y8z7w6_android",
            "startTime": "2024-01-15T10:25:00Z"
          }
        }
      }
    ]
  }
}
```

## 🔒 Privacy & Security

### Automatic ID Management
- **Device ID**: Persistent, unique device identifier
- **User ID**: Automatically generated, persistent across app sessions  
- **Session ID**: Unique per app session, regenerated on app restart

### Data Collection
- **No PII**: No personally identifiable information collected by default
- **Configurable**: All data collection can be customized
- **Secure**: HTTPS-only transmission with proper error handling

## 🏗 Architecture

### Core Components

```
TelemetryManager (Main SDK Interface)
├── TelemetryHttpClient (Network Layer)
├── OfflineBatchStorage (Persistence)
├── ScreenTimingTracker (Performance)
├── TelemetryFrameDropCollector (FrameMetrics-based Performance)
├── MemoryTracker (Enhanced Memory Tracking)
├── DeviceCapabilities (Runtime Feature Detection)
├── MemoryCapabilityTracker (Advanced Memory Analysis)
├── NetworkCapabilityDetector (Network State Management)
├── TelemetryActivityLifecycleObserver (Activity Tracking)
├── TelemetryFragmentLifecycleObserver (Fragment Tracking)
└── TelemetryMemoryUsage (Memory Monitoring)
```

### Key Features
- **Memory Leak Prevention**: Proper cleanup of listeners and observers
- **Thread Safety**: Concurrent access protection with synchronized methods
- **Performance Optimized**: <100ms crash handler execution time
- **Robust Networking**: Retry logic with exponential backoff
- **Enhanced Frame Metrics**: Precise FrameMetrics API for all users
- **Comprehensive Memory Tracking**: Detailed memory insights and pressure detection
- **Simplified Architecture**: Streamlined codebase with modern Android APIs

## 🧪 Testing

### Unit Tests
```bash
./gradlew :telemetry_library:test
```

### Integration Tests
```bash
./gradlew :telemetry_library:connectedAndroidTest
```

## 📈 Performance Impact

- **APK Size**: ~200KB
- **Memory**: <5MB runtime usage
- **Battery**: Negligible impact
- **CPU**: Minimal background processing
- **Network**: Efficient batching reduces requests

## 🔧 Troubleshooting

### Common Issues

**SDK not collecting data:**
```kotlin
// Ensure proper initialization
TelemetryManager.initialize(context, endpoint, batchSize)

// Check if instance is available
val telemetry = TelemetryManager.getInstance()
if (telemetry == null) {
    Log.e("Telemetry", "SDK not initialized")
}
```

**Network issues:**
- Verify endpoint URL is correct and accessible
- Check network permissions in manifest
- Review logs for HTTP error codes

**Memory issues:**
- SDK automatically manages memory and prevents leaks
- Ensure proper app lifecycle management

### Debug Logging

Enable debug logging to troubleshoot issues:

```kotlin
TelemetryManager.initialize(
    context = this,
    endpoint = "your-endpoint",
    batchSize = 10,
    enableDebugLogging = true  // Enable for debugging
)
```

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

### Development Setup

1. Clone the repository
2. Open in Android Studio
3. Run tests: `./gradlew test`
4. Build library: `./gradlew :telemetry_library:build`

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/NCG-Africa/edge-telemetry-android/issues)
- **Documentation**: [Wiki](https://github.com/NCG-Africa/edge-telemetry-android/wiki)
- **Email**: support@ncg-africa.com

## 🗺 Roadmap

- [ ] Real-time analytics dashboard
- [ ] Advanced filtering and sampling
- [ ] Custom data retention policies
- [ ] Enhanced privacy controls
- [ ] Performance benchmarking tools

---

**Made with ❤️ by NCG Africa**
