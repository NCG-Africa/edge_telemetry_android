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

---

## 🔴 v2.0.0 Breaking Changes

**Version 2.0.0** introduces breaking changes to crash reporting payload structure to align with backend Kafka processor requirements.

### What Changed
- Crash events now sent as `type: "event"` with `eventName: "app.crash"` (batch envelope structure)
- New required crash attributes: `message`, `stacktrace`, `exception_type`, `error_context`, `is_fatal`, `cause`
- New optional context APIs: `setProductContext()`, `setLastUserAction()`, error code support
- Removed SDK-generated fields: `crash.fingerprint`, `crash.breadcrumb_count` (backend auto-generates)

### Migration Required
- **Public API**: No changes required - existing `trackError()` calls work unchanged
- **Backend**: Kafka processor must support new crash event structure
- **Recommended**: Use new context APIs for enhanced crash analytics

📖 **[Read Full Migration Guide](docs/MIGRATION_GUIDE_V2.md)** | 📋 **[View Changelog](CHANGELOG.md#200---2025-03-18)**

---

## ✅ Backend Alignment (v2.1.0)

**EdgeRum SDK is now fully aligned with OpenTelemetry backend requirements.**

The SDK has undergone comprehensive alignment to ensure all telemetry events match backend processor expectations. This alignment includes event name standardization, attribute structure updates, and performance optimizations.

### What's Aligned

#### ✅ Phase 1: Event Name Alignment
All event names now match backend processor requirements:

| Old Event Name | New Event Name | Status |
|----------------|----------------|--------|
| `network.request` | `http.request` | ✅ Updated |
| `session_end` | `session.finalized` | ✅ Updated |
| `navigation.route_change` | `navigation` | ✅ Standardized |
| `performance.screen_duration` | `performance.screen_duration` | ✅ Verified |
| `app.crash` | `app.crash` | ✅ Enhanced |

**Impact:** Automatic - SDK handles all event name changes internally.

#### ✅ Phase 2: Standard Attributes
All events automatically include comprehensive standard attributes:

**App Information (4 attributes):**
- `app.name`, `app.version`, `app.build_number`, `app.package_name`

**Device Information (11 attributes):**
- `device.id`, `device.platform`, `device.platform_version`, `device.model`, `device.manufacturer`, `device.brand`, `device.android_sdk`, `device.android_release`, `device.fingerprint`, `device.hardware`, `device.product`

**User & Session (3+ attributes):**
- `user.id`, `session.id`, `session.start_time`, plus comprehensive session analytics

**Impact:** Automatic - all attributes attached to every event.

#### ✅ Phase 3: Event Cleanup
Unsupported events are now disabled by default to optimize performance:

**Disabled Events (60-70% reduction in traffic):**
- Performance events: `frame_drop`, `performance.frame_summary`, `performance.compose`
- System events: `memory_pressure`, `storage_usage`
- Legacy screen events: `screen.entry`, `screen.exit`, `screen.resume`, `screen.pause`, `screen_view`
- User interaction events: `user.interaction`
- Capability events: `telemetry.capabilities_initialized`

**Opt-In Available:**
```kotlin
val config = TelemetryConfig.builder(application, apiKey)
    .enableMemoryTracking(true)      // Enable if needed
    .enableFrameTracking(true)       // Enable if needed
    .enableLegacyScreenEvents(true)  // Enable if needed
    .build()
```

**Impact:** Improved performance, reduced bandwidth, lower battery usage.

#### ✅ Phase 4: Testing & Validation
Comprehensive validation ensures all events meet backend requirements:

- **EventPayloadValidator** - Validates all 5 event types
- **RuntimeEventValidator** - Optional runtime validation with debug/strict modes
- **75+ Test Cases** - Unit and integration tests
- **Zero Production Overhead** - Validation disabled by default

**Usage:**
```kotlin
import com.androidtel.telemetry_library.core.validation.EventPayloadValidator

val result = EventPayloadValidator.validateHttpRequestEvent(
    eventName = "http.request",
    attributes = attributes,
    timestamp = timestamp
)
```

### Feature Flags

Control which events are tracked via configuration:

```kotlin
val config = TelemetryConfig.builder(application, apiKey)
    // Core features (enabled by default)
    .enableCrashReporting(true)
    .enableUserProfiles(true)
    .enableSessionTracking(true)
    
    // Optional features (disabled by default)
    .enableMemoryTracking(false)        // Memory pressure events
    .enableStorageTracking(false)       // Storage usage events
    .enableFrameTracking(false)         // Frame drop events
    .enableLegacyScreenEvents(false)    // Screen lifecycle events
    .enableUserInteractionEvents(false) // User interaction events
    .enableCapabilityEvents(false)      // Capability initialization events
    .build()

TelemetryManager.initialize(config)
```

**Benefits:**
- 60-70% reduction in event traffic
- Lower battery consumption
- Reduced memory usage
- Improved app performance
- Backend-compatible events only

### Event Schema Reference

For complete event schemas, validation rules, and JSON examples, see:

📖 **[Event Schema Reference](docs/EVENT_SCHEMA_REFERENCE.md)**

**Includes:**
- All 5 supported event types with examples
- Required and optional attributes
- Field length limits and validation rules
- Standard attributes documentation
- Backend compatibility notes
- Migration guidance

### Documentation

- **[Event Schema Reference](docs/EVENT_SCHEMA_REFERENCE.md)** - Complete event schemas and examples
- **[Phase 1 Summary](docs/PHASE_1_SUMMARY.md)** - Event name alignment details
- **[Phase 2 Summary](docs/PHASE_2_SUMMARY.md)** - Standard attributes implementation
- **[Phase 3 Summary](docs/PHASE_3_SUMMARY.md)** - Event cleanup and feature flags
- **[Phase 4 Summary](docs/PHASE_4_SUMMARY.md)** - Testing and validation
- **[Phase 4 Testing Guide](docs/PHASE_4_TESTING_GUIDE.md)** - Comprehensive testing guide

---

## 🚀 Features

### Core Telemetry
- **📊 Performance Monitoring**: Precise FrameMetrics-based frame drop detection, enhanced memory tracking, and app performance metrics
- **🔄 Session Management**: Automatic session tracking with detailed analytics
- **📱 Screen Analytics**: Activity and Fragment lifecycle monitoring with timing data
- **🎯 Custom Events**: Track custom business events and user interactions
- **💥 Crash Reporting**: Comprehensive crash detection with automatic classification, context tracking, and backend-compatible event structure (v2.0.0)
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
| **Latest Version** | `2.1.13` | `2.1.13-java8` |
| **Java Requirement** | Java 11+ | Java 8+ (core-library desugaring) |
| **Gradle** | 8.4+ | 8.4+ |
| **AGP** | 8.7.1+ | 8.7.1+ |
| **Kotlin** | 2.1.0+ | 2.1.0+ |
| **Jetpack Compose** | ✅ Full Support | ✅ Full Support |
| **All Core Features** | ✅ | ✅ |
| **Use Case** | Modern projects | Projects pinned to Java 8 bytecode |

> The `-java8` build is byte-identical to the standard release — same source, same features (Compose included) — but compiled to Java 8 bytecode (`sourceCompatibility`/`targetCompatibility = 1.8`) with core-library desugaring backfilling `java.time.*`. Use it when your app's `compileOptions` are pinned to Java 8 and the standard AAR fails with "compiled by a more recent version of Java." It needs the same modern Gradle/AGP/Kotlin toolchain as the standard release. Truly legacy toolchains (AGP 7.x, Kotlin 1.8) should stay on `1.2.3-java8`.

### 🎯 **Choose Your Version**

#### **Java 11+ Version (Recommended)**
- **Latest Features**: Full Jetpack Compose support
- **Modern Toolchain**: Latest Gradle, AGP, and Kotlin
- **Future-Proof**: Receives all new features first

#### **Java 8 Version (Java 8 bytecode)**
- **Full Parity**: Identical to the standard release, Compose included
- **Java 8 Bytecode**: For apps whose `compileOptions` are pinned to Java 8
- **Desugaring**: `java.time.*` backfilled onto minSdk 24 via core-library desugaring

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
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.1.13'
}
```

**Requirements:**
- **Java**: 11+
- **Gradle**: 8.4+
- **AGP**: 8.7.1+
- **Kotlin**: 2.1.0+ (for Kotlin projects)
- **Features**: ✅ Full Compose support

#### **Java 8 Projects (Legacy/Enterprise)**

Add JitPack repository (same as above), then:

```kotlin
dependencies {
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.1.13-java8'
}
```

**Requirements:**
- **Java**: 8+ (core-library desugaring enabled in the SDK)
- **Gradle**: 8.4+
- **AGP**: 8.7.1+
- **Kotlin**: 2.1.0+ (for Kotlin projects)
- **Features**: ✅ Full Compose support (identical to the standard release)

### 🎯 **When to Use Each Version**

| Use Java 11+ Version | Use Java 8 Version |
|---------------------|--------------------|
| ✅ Java 11+ bytecode OK | ✅ `compileOptions` pinned to Java 8 |
| ✅ Standard setup | ✅ "compiled by a more recent Java" errors |
| ✅ Same features | ✅ Same features (Compose included) |

## 🛠 Quick Setup

### 1. Initialize the SDK

> **🔐 SECURITY FIRST**: Never hardcode API keys! Use BuildConfig, local.properties, or environment variables. See [API Key Security](#-api-key-security-best-practices) section below.

Initialize the SDK in your `Application` class:

#### Option A: TelemetryConfig (Recommended)

**Kotlin**
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Create configuration
        val config = TelemetryConfig.builder(this, BuildConfig.TELEMETRY_API_KEY)
            .batchSize(30)
            .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
            .debugMode(BuildConfig.DEBUG)
            .enableCrashReporting(true)
            .enableUserProfiles(true)
            .enableSessionTracking(true)
            .globalAttributes(mapOf(
                "app_environment" to if (BuildConfig.DEBUG) "dev" else "prod"
            ))
            .build()
        
        // Initialize SDK
        TelemetryManager.initialize(config)
    }
}
```

**Java**
```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Create configuration
        Map<String, String> globalAttrs = new HashMap<>();
        globalAttrs.put("app_environment", BuildConfig.DEBUG ? "dev" : "prod");
        
        TelemetryConfig config = TelemetryConfig.builder(this, BuildConfig.TELEMETRY_API_KEY)
            .batchSize(30)
            .endpoint("https://edgetelemetry.ncgafrica.com/collector/telemetry")
            .debugMode(BuildConfig.DEBUG)
            .enableCrashReporting(true)
            .enableUserProfiles(true)
            .enableSessionTracking(true)
            .globalAttributes(globalAttrs)
            .build();
        
        // Initialize SDK
        TelemetryManager.initialize(config);
    }
}
```

#### Option B: Direct Parameters (Legacy)

**Kotlin**
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        TelemetryManager.initialize(
            application = this,
            apiKey = BuildConfig.TELEMETRY_API_KEY,  // ✅ From BuildConfig
            batchSize = 30,
            endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
            debugMode = BuildConfig.DEBUG,
            enableCrashReporting = true,
            enableUserProfiles = true,
            enableSessionTracking = true
        )
    }
}
```

**Java**
```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        TelemetryManager.initialize(
            this,                                                      // application
            BuildConfig.TELEMETRY_API_KEY,                            // apiKey ✅ From BuildConfig
            30,                                                        // batchSize
            "https://edgetelemetry.ncgafrica.com/collector/telemetry", // endpoint
            BuildConfig.DEBUG,                                         // debugMode
            true,                                                      // enableCrashReporting
            true,                                                      // enableUserProfiles
            true                                                       // enableSessionTracking
        );
    }
}
```

> **⚠️ Breaking Change (v1.2.6+)**: The `apiKey` parameter is **required**. Get your API key from your backend administrator and store it securely using BuildConfig.

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

### 5. Navigation Tracking

The SDK automatically tracks all navigation events with comprehensive data structure aligned with backend requirements.

#### Navigation Event Structure

All navigation events include the following fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `navigation.from_screen` | String | Optional | Source screen (null on app launch) |
| `navigation.to_screen` | String | Required | Destination screen name |
| `navigation.method` | String | Required | Navigation action: `push`, `pop`, or `replace` |
| `navigation.route_type` | String | Optional | Route classification (e.g., `main_flow`, `modal`, `deeplink`) |
| `navigation.has_arguments` | Boolean | Optional | Whether navigation includes data/arguments |
| `navigation.timestamp` | String | Required | ISO 8601 formatted datetime |

#### Navigation Methods

The SDK automatically detects and reports the correct navigation method:

- **`push`** - Forward navigation to a new screen (Activity/Fragment resumed, Compose navigation forward)
- **`pop`** - Back navigation to previous screen (back button, up navigation)
- **`replace`** - Screen replacement (Activity finish + start, Fragment replace)

#### Route Types

The SDK automatically classifies navigation routes:

- **`main_flow`** - Primary app navigation (default for Activities)
- **`fragment_flow`** - Fragment-based navigation
- **`deeplink`** - Deep link navigation (detected from Intent data)
- **`modal`** - Modal/dialog screens
- **`onboarding`** - Onboarding flow screens
- **`settings`** - Settings screens

#### For XML-based Navigation (Activities/Fragments)

Activities and Fragments are automatically tracked with full navigation context:

**Kotlin**
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Activities are automatically tracked by the SDK
        // Navigation events include:
        // - from_screen: Previous activity (null on first launch)
        // - to_screen: "MainActivity"
        // - method: "push"
        // - route_type: "main_flow" or "deeplink" (if launched via deep link)
        // - has_arguments: true if intent has extras
    }
}
```

**Java**
```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Activities are automatically tracked by the SDK
        // Navigation events include full context
    }
}
```

**Fragment Navigation**
```kotlin
class ProfileFragment : Fragment() {
    override fun onResume() {
        super.onResume()
        
        // Fragments are automatically tracked
        // - from_screen: Previous fragment
        // - to_screen: "ProfileFragment"
        // - method: "push"
        // - route_type: "fragment_flow"
        // - has_arguments: true if fragment has arguments
    }
}
```

#### For Jetpack Compose Navigation

**Automatic Tracking**
```kotlin
@Composable
fun MyApp() {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { 
            TrackComposeScreen(navController = navController, screenName = "HomeScreen")
            HomeScreen() 
        }
        composable("profile") { 
            TrackComposeScreen(navController = navController, screenName = "ProfileScreen")
            ProfileScreen() 
        }
        composable("settings") { 
            TrackComposeScreen(navController = navController, screenName = "SettingsScreen")
            SettingsScreen() 
        }
    }
}
```

**Custom Route Types**
```kotlin
@Composable
fun ModalScreen() {
    TrackComposeScreen(
        navController = navController,
        screenName = "ModalScreen",
        additionalData = mapOf("route_type" to "modal")
    )
    
    // Your modal UI
}
```

**Deep Link Navigation**
```kotlin
composable(
    route = "product/{productId}",
    deepLinks = listOf(navDeepLink { uriPattern = "myapp://product/{productId}" })
) { backStackEntry ->
    val productId = backStackEntry.arguments?.getString("productId")
    
    TrackComposeScreen(
        navController = navController,
        screenName = "ProductDetailScreen",
        additionalData = mapOf("route_type" to "deeplink")
    )
    
    ProductDetailScreen(productId)
}
```

#### Sample Navigation Event

```json
{
  "type": "event",
  "eventName": "navigation",
  "timestamp": "2024-03-18T14:50:23.456Z",
  "attributes": {
    "navigation.from_screen": "HomeScreen",
    "navigation.to_screen": "ProfileScreen",
    "navigation.method": "push",
    "navigation.route_type": "main_flow",
    "navigation.has_arguments": true,
    "navigation.timestamp": "2024-03-18T14:50:23.456Z",
    "app.name": "MyApp",
    "app.version": "1.0.0",
    "session.id": "session_1234567890",
    "user.id": "user_abcd1234"
  }
}
```

> **Note**: Compose is fully supported in both the standard and `-java8` builds.

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

> **Note**: Compose screen tracking is available in both the standard and `-java8` builds.

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
    apiKey = "your-api-key-here",  // ⚠️ REQUIRED
    batchSize = 30,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry",
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
    this,                                                      // application
    "your-api-key-here",                                      // apiKey (REQUIRED)
    30,                                                        // batchSize
    "https://edgetelemetry.ncgafrica.com/collector/telemetry", // endpoint
    BuildConfig.DEBUG,                                         // debugMode
    true,                                                      // enableCrashReporting
    true,                          // enableUserProfiles
    true,                          // enableSessionTracking
    globalAttributes               // globalAttributes
);
```

### Configuration Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `application` | `Application` | ✅ Yes | - | Application context |
| `apiKey` | `String` | ✅ Yes | - | **API key for backend authentication** (v1.2.6+) - Must start with `edge_` |
| `batchSize` | `Int` | No | `30` | Number of events to batch before sending |
| `endpoint` | `String` | No | `https://edgetelemetry.ncgafrica.com/collector/telemetry` | Telemetry backend endpoint |
| `debugMode` | `Boolean` | No | `false` | Enable verbose logging (API keys auto-redacted) |
| `enableCrashReporting` | `Boolean` | No | `true` | Enable automatic crash reporting |
| `enableUserProfiles` | `Boolean` | No | `true` | Enable user profile tracking |
| `enableSessionTracking` | `Boolean` | No | `true` | Enable session analytics |
| `globalAttributes` | `Map<String, String>` | No | `emptyMap()` | Custom attributes added to all events |

> **⚠️ Breaking Change (v1.2.6+)**: The `apiKey` parameter is now **required**. All HTTP requests include the API key in the `X-API-Key` header for backend authentication.

### TelemetryConfig Builder

For cleaner, more maintainable code, use the `TelemetryConfig` builder:

```kotlin
val config = TelemetryConfig.builder(application, apiKey)
    .batchSize(30)
    .endpoint("your-endpoint")
    .debugMode(true)
    .enableCrashReporting(true)
    .enableUserProfiles(true)
    .enableSessionTracking(true)
    .globalAttributes(mapOf("key" to "value"))
    .build()

TelemetryManager.initialize(config)
```

**Benefits:**
- Type-safe configuration
- Immutable config object
- Validation at build time
- Cleaner initialization code

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

### 🔐 API Key Security Best Practices

**⚠️ CRITICAL: Never hardcode API keys in your source code or commit them to version control.**

#### Recommended Approach 1: BuildConfig (Recommended)

Store your API key in `local.properties` (which is gitignored by default):

```properties
# local.properties
TELEMETRY_API_KEY=your-api-key-here
```

Access it in your `build.gradle.kts`:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        // Read API key from local.properties
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }
        
        buildConfigField(
            "String",
            "TELEMETRY_API_KEY",
            "\"${properties.getProperty("TELEMETRY_API_KEY", "")}\""
        )
    }
}
```

Use it in your code:

```kotlin
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,  // ✅ Secure
    // ... other parameters
)
```

#### Recommended Approach 2: Environment Variables

For CI/CD pipelines, use environment variables:

```kotlin
// build.gradle.kts
android {
    defaultConfig {
        buildConfigField(
            "String",
            "TELEMETRY_API_KEY",
            "\"${System.getenv("TELEMETRY_API_KEY") ?: ""}\""
        )
    }
}
```

#### Recommended Approach 3: Android Keystore (Advanced)

For maximum security in production apps:

```kotlin
// Use Android Keystore System to encrypt/decrypt API key
// See: https://developer.android.com/training/articles/keystore
```

#### What NOT to Do ❌

```kotlin
// ❌ NEVER hardcode API keys
TelemetryManager.initialize(
    application = this,
    apiKey = "edge_1234567890abcdef",  // ❌ INSECURE!
    // ...
)

// ❌ NEVER commit API keys to git
// ❌ NEVER include API keys in strings.xml
// ❌ NEVER log API keys in plain text
```

#### .gitignore Configuration

Ensure your `.gitignore` includes:

```gitignore
# API Keys and Secrets
local.properties
*.keystore
*.jks
secrets.properties
```

#### API Key Redaction in Logs

The SDK automatically redacts API keys in debug logs:
- **Full key**: `edge_1234567890abcdef_xyz123`
- **Logged as**: `edge_**************_xyz1`

This applies to:
- HTTP request logs (when `debugMode = true`)
- Crash report logs
- WorkManager retry logs

#### ProGuard/R8 Protection

The SDK includes ProGuard/R8 rules in `consumer-rules.pro` to:
- Obfuscate string constants in release builds
- Protect API keys from reverse engineering
- Maintain SDK public API

**Note**: ProGuard provides basic obfuscation but is not a substitute for proper API key management.

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

#### API Key Issues

**Error: "API key cannot be blank"**
```kotlin
// ❌ Wrong - API key is blank or missing
TelemetryManager.initialize(
    application = this,
    apiKey = "",  // Blank!
    // ...
)

// ✅ Correct - Use BuildConfig
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,
    // ...
)
```

**Error: "API key is invalid"**
```kotlin
// API key must start with "edge_"
// Get the correct format from your backend administrator
```

**401 Unauthorized from backend:**
- Verify API key is correct
- Check API key is active in backend
- Ensure API key hasn't expired
- Review backend logs for authentication errors

#### SDK Initialization Issues

**SDK not collecting data:**
```kotlin
// Ensure proper initialization with API key
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,  // Required!
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
)

// Check if instance is available
val telemetry = TelemetryManager.getInstance()
if (telemetry == null) {
    Log.e("Telemetry", "SDK not initialized")
}
```

**IllegalArgumentException during initialization:**
- Check API key is not blank
- Verify API key starts with "edge_"
- Ensure batch size > 0
- Verify endpoint is not blank

#### Network Issues

**Data not reaching backend:**
- Verify endpoint URL is correct and accessible
- Check network permissions in `AndroidManifest.xml`
- Review logs for HTTP error codes (401, 403, 500, etc.)
- Enable debug mode to see detailed HTTP logs
- Verify API key is included in `X-API-Key` header

**Offline data not syncing:**
- SDK automatically retries failed requests
- Check device has network connectivity
- Review WorkManager logs for retry attempts
- Verify crash retry mechanism is working

#### Memory Issues

**Out of memory errors:**
- SDK automatically manages memory and prevents leaks
- Ensure proper app lifecycle management
- Reduce batch size if processing large volumes
- Check for memory leaks in your app code

### Debug Logging

Enable debug logging to troubleshoot issues:

```kotlin
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,
    endpoint = "your-endpoint",
    debugMode = true  // ✅ Enable for debugging
)
```

**What debug mode shows:**
- API key validation (redacted: `edge_****_xyz1`)
- HTTP request/response details
- Batch processing logs
- Crash report generation
- Retry mechanism activity
- WorkManager job status

### Getting Help

If issues persist:

1. **Check logs** with `debugMode = true`
2. **Review documentation** at [API Key Guide](docs/API_KEY_GUIDE.md)
3. **Verify backend** accepts your API key
4. **Test connectivity** using `TelemetryManager.getInstance().testConnectivity()`
5. **Report issues** at [GitHub Issues](https://github.com/NCG-Africa/edge-telemetry-android/issues)

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
