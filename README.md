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

### Build Requirements
- **Gradle**: `8.4+` (recommended: `8.9+`)
- **Android Gradle Plugin (AGP)**: `8.0+`
- **Kotlin**: `1.9.0+` (for Kotlin projects)
- **Java**: `11+` (for Java projects)

### Runtime Requirements
- **Minimum SDK**: `24` (Android 7.0+)
- **Compile SDK**: `35` (Android 15)
- **Target SDK**: `34` (Android 14)

### Dependencies
- **AndroidX Core**: `1.16.0+`
- **Jetpack Compose**: `2024.04.01+` (if using Compose features)
- **WorkManager**: `2.9.0+` (for offline storage)
- **Room**: `2.6.1+` (for local persistence)

## 📦 Installation

### Gradle (Recommended)

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

### Requirements

- **Minimum SDK**: Android API 24 (Android 7.0)
- **Target SDK**: Android API 35
- **Kotlin**: 1.9.0+
- **Java**: 11+

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

#### Kotlin
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

#### Java
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

#### For Jetpack Compose Navigation

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

### Jetpack Compose Screen Tracking

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

#### Kotlin
```kotlin
TelemetryManager.initialize(
    context = this,
    endpoint = "https://api.example.com/telemetry",
    batchSize = 20,                    // Events per batch
    enableDebugLogging = BuildConfig.DEBUG
)
```

#### Java
```java
TelemetryManager.initialize(
    this, // context
    "https://api.example.com/telemetry", // endpoint
    20, // batchSize - Events per batch
    BuildConfig.DEBUG // enableDebugLogging
);
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
