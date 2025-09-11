# Edge Telemetry Android SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![JitPack](https://jitpack.io/v/NCG-Africa/edge-telemetry-sdk.svg)](https://jitpack.io/#NCG-Africa/edge-telemetry-sdk)

A comprehensive, production-ready Android SDK for collecting and transmitting telemetry data including app performance metrics, user interactions, crash reports, and system analytics. Built with modern Android development practices and optimized for performance and reliability.

## 🚀 Features

### Core Telemetry
- **📊 Performance Monitoring**: API-aware frame drop detection, enhanced memory tracking, and app performance metrics
- **🔄 Session Management**: Automatic session tracking with detailed analytics
- **📱 Screen Analytics**: Activity and Fragment lifecycle monitoring with timing data
- **🎯 Custom Events**: Track custom business events and user interactions
- **💥 Crash Reporting**: Comprehensive crash detection and reporting with stack traces
- **🧠 Memory Intelligence**: Progressive memory tracking with API-level appropriate methods (API 21-35)

### Advanced Capabilities
- **🌐 Network Resilience**: Robust HTTP client with exponential backoff retry logic
- **💾 Offline Storage**: Persistent data storage with automatic sync when online
- **🔄 Batch Processing**: Efficient data batching to minimize network overhead
- **🎨 Jetpack Compose Support**: Native support for Compose screen tracking
- **🔒 Privacy-First**: Automatic user ID generation with persistent storage
- **⚡ Memory Efficient**: Optimized memory usage with proper lifecycle management
- **📱 Device Compatibility**: Runtime feature detection with graceful degradation (Android 5.0+)

### Technical Highlights
- **Thread-Safe**: Concurrent data collection with proper synchronization
- **Lifecycle-Aware**: Automatic cleanup and resource management
- **Configurable**: Flexible configuration options for different use cases
- **Lightweight**: Minimal impact on app performance and size
- **Modern Architecture**: Built with Kotlin coroutines and modern Android APIs
- **API-Level Adaptive**: Automatic selection of appropriate tracking methods based on device capabilities
- **Progressive Enhancement**: Enhanced features on newer devices, reliable basics on older devices

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
    implementation 'com.github.NCG-Africa:edge-telemetry-sdk:1.1.15'
}
```

### Requirements

- **Minimum SDK**: Android API 21 (Android 5.0)
- **Target SDK**: Android API 35
- **Kotlin**: 1.9.0+
- **Java**: 11+

## 🛠 Quick Setup

### 1. Initialize the SDK

Initialize the SDK in your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize telemetry SDK
        TelemetryManager.initialize(
            context = this,
            endpoint = "https://your-telemetry-endpoint.com/api/telemetry",
            batchSize = 10
        )
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

## 📖 Usage Examples

### Custom Event Tracking

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

### Custom Metrics

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

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Manual screen tracking
        TelemetryManager.getInstance()?.trackScreen("MainActivity")
    }
}
```

## 🔧 Configuration

### Advanced Initialization

```kotlin
TelemetryManager.initialize(
    context = this,
    endpoint = "https://api.example.com/telemetry",
    batchSize = 20,                    // Events per batch
    enableDebugLogging = BuildConfig.DEBUG
)
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
| `screen_view` | Screen/Activity transitions | ✅ | API 21+ |
| `app_lifecycle` | App foreground/background | ✅ | API 21+ |
| `performance` | Frame drops, memory usage | ✅ | API 21+ |
| `frame_drop` | Frame performance tracking | ✅ | API 24+ (legacy fallback 21+) |
| `memory_pressure` | Enhanced memory monitoring | ✅ | API 21+ (progressive) |
| `storage_usage` | Storage usage tracking | ✅ | API 21+ |
| `crash` | Application crashes | ✅ | API 21+ |
| `custom_event` | Custom business events | Manual | API 21+ |
| `custom_metric` | Custom performance metrics | Manual | API 21+ |

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
├── PerformanceTracker (Unified Performance - API Aware)
│   ├── ModernPerformanceTracker (API 24+)
│   └── LegacyPerformanceTracker (API 21-23)
├── MemoryTracker (Unified Memory - API Aware)
│   ├── EnhancedMemoryTracker (Enhanced Capabilities)
│   └── BasicMemoryTracker (Fallback)
├── DeviceCapabilities (Runtime Feature Detection)
├── MemoryCapabilityTracker (Advanced Memory Analysis)
├── NetworkCapabilityDetector (Network State Management)
├── TelemetryActivityLifecycleObserver (Activity Tracking)
├── TelemetryFragmentLifecycleObserver (Fragment Tracking)
└── TelemetryMemoryUsage (Memory Monitoring - Enhanced)
```

### Key Features
- **Memory Leak Prevention**: Proper cleanup of listeners and observers
- **Thread Safety**: Concurrent access protection with synchronized methods
- **Performance Optimized**: <100ms crash handler execution time
- **Robust Networking**: Retry logic with exponential backoff
- **Runtime Capability Detection**: Automatic feature detection and graceful degradation
- **API-Level Awareness**: Progressive enhancement based on Android version
- **Unified Tracking Interfaces**: Consistent APIs across different implementation tiers

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

- **APK Size**: ~200KB additional size
- **Memory**: <5MB runtime memory usage
- **CPU**: Minimal background processing
- **Battery**: Negligible battery impact
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