# Telemetry Data Specification for Android SDK

## Overview
Your backend expects telemetry data in a specific JSON batch format. This document outlines the exact structure and all data points your Android SDK must send to maintain compatibility.

## Initialization

### Application Class

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Telemetry
        TelemetryManager.initialize(
            application = this,
            batchSize = 10,
            debugMode = true,
            endpoint = "https://com.example.com"

        )
    }
}
```

## Usage

### Setting User Identity

```kotlin
val telemetry = TelemetryManager.getInstance()

telemetry.setUserId("user12345")
telemetry.setUserProfile(
    name = "John Doe",
    email = "john.doe@example.com",
    phone = "+1-555-123-4567",
    profileVersion = 1
)
```

### Recording Events
```kotlin
telemetry.recordEvent(
    eventName = "ecommerce.purchase_completed",
    attributes = mapOf(
        "order_id" to "ORD-98765",
        "total_amount" to 250.50,
        "payment_method" to "credit_card",
        "item_count" to 2
    )
)
```

### Recording Metrics
```kotlin
telemetry.recordMetric(
    metricName = "performance.screen_duration",
    value = 3250.0,
    attributes = mapOf(
        "screen.name" to "HomeScreen",
        "metric.unit" to "milliseconds"
    )
)
```

## Navigation Tracking
```kotlin
### Jetpack Compose
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Track screen navigation automatically
    TelemetryManager.getInstance().trackComposeScreens(navController)

    NavHost(navController, startDestination = "home") {
        composable("home") { HomeScreen { navController.navigate("details") } }
        composable("details") { DetailsScreen { navController.popBackStack() } }
    }
}
```
## Lifecycle Tracking

### Activity & Fragment

- TelemetryActivityLifecycleObserver automatically tracks:
  - Activity created, started, resumed, paused, stopped, destroyed
  - Screen duration
  - Navigation events
  - Frame drops
- TelemetryFragmentLifecycleObserver tracks:
  - Fragment navigation
  - Screen engagement

Registration happens automatically during initialization.

## Top-Level Batch Structure

```json
{
  "type": "telemetry_batch",
  "batch_size": 5,
  "timestamp": "2024-01-15T10:30:00Z",
  "events": [
    // Array of individual events
  ]
}
```

### Batch Fields
- **type**: Always `"telemetry_batch"`
- **batch_size**: Number of events in the batch (integer)
- **timestamp**: ISO 8601 timestamp when batch was created
- **events**: Array of telemetry events

## Individual Event Structure

Each event in the `events` array has this structure:

```json
{
  "type": "metric" | "event",
  "metricName": "string (for metric events)",
  "eventName": "string (for event events)", 
  "value": 123.45,  // Only for metric events
  "timestamp": "2024-01-15T10:30:00Z",
  "attributes": {
    // All the specific attributes below
  }
}
```

## Core Attributes (Required for All Events)

### App Information
```json
{
  "app.name": "Your App Name",
  "app.version": "1.0.0", 
  "app.build_number": "123",
  "app.package_name": "com.example.yourapp"
}
```

### Device Information 
```json
{
  "device.id": "unique-persistent-device-id",        // 🔥 PRIMARY IDENTIFIER
  "device.platform": "android",                      // lowercase
  "device.platform_version": "13", 
  "device.model": "Pixel 7",
  "device.manufacturer": "Google",
  "device.brand": "google",
  "device.android_sdk": "33",
  "device.android_release": "13",
  "device.fingerprint": "google/cheetah/cheetah:13/...", 
  "device.hardware": "cheetah",
  "device.product": "cheetah"
}
```

**⚠️ Critical**: `device.id` is the primary identifier. Your Android SDK should generate a persistent UUID for each device installation.

### User Information
```json
{
  "user.id": "unique-user-identifier"
}
```

### Session Information
```json
{
  "session.id": "session-uuid",
  "session.start_time": "2024-01-15T10:00:00Z",
  "session.duration_ms": 300000,
  "session.event_count": 50,
  "session.metric_count": 25, 
  "session.screen_count": 3,
  "session.visited_screens": "HomeScreen,ProfileScreen,SettingsScreen",
  "session.is_first_session": false,
  "session.total_sessions": 15,
  "network.type": "wifi"
}
```

## Event Types Your Backend Processes

### 1. Performance Metrics (`type: "metric"`)

#### Frame Metrics
```json
{
  "type": "metric",
  "metricName": "frame_duration", // or other frame metrics
  "value": 16.7,
  "timestamp": "2024-01-15T10:30:00Z",
  "attributes": {
    // Core attributes above +
    "metric.unit": "ms",
    "frame.build_duration_ms": 8.5,
    "frame.raster_duration_ms": 6.2, 
    "frame.type": "ui",
    "frame.dropped": false
  }
}
```

#### Memory Metrics  
```json
{
  "type": "metric", 
  "metricName": "memory_usage",
  "value": 256.5,
  "timestamp": "2024-01-15T10:30:00Z",
  "attributes": {
    // Core attributes above +
    "metric.unit": "MB",
    "memory.type": "heap",
    "memory.source": "runtime"
  }
}
```

### 2. Performance Events (`type: "event"`)

#### Memory Pressure Events
```json
{
  "type": "event",
  "eventName": "memory_pressure",
  "timestamp": "2024-01-15T10:30:00Z", 
  "attributes": {
    // Core attributes above +
    "memory.usage_mb": 512.0,
    "memory.pressure_level": "moderate",
    "memory.timestamp": "2024-01-15T10:30:00Z"
  }
}
```

#### Frame Drop Events
```json
{
  "type": "event",
  "eventName": "frame_drop",
  "timestamp": "2024-01-15T10:30:00Z",
  "attributes": {
    // Core attributes above +
    "frame.build_duration_ms": 45.2,
    "frame.raster_duration_ms": 32.1,
    "frame.total_duration_ms": 77.3,
    "frame.severity": "high", 
    "frame.target_fps": 60
  }
}
```

### 3. HTTP Request Events (`type: "event"`)

```json
{
  "type": "event",
  "eventName": "http.request", 
  "timestamp": "2024-01-15T10:30:00Z",
  "attributes": {
    // Core attributes above +
    "http.url": "https://api.example.com/users",
    "http.method": "GET",
    "http.status_code": 200,
    "http.duration_ms": 245,
    "http.timestamp": "2024-01-15T10:30:00Z",
    "http.success": true
  }
}
```

### 4. User Profile Update Events (`type: "event"`)

```json
{
  "type": "event", 
  "eventName": "user.profile_updated",
  "timestamp": "2024-01-15T10:30:00Z",
  "attributes": {
    // Core attributes above +
    "user.name": "John Doe",
    "user.email": "john@example.com", 
    "user.phone": "+1234567890",
    "user.profile_version": 2,
    "user.profile_updated_at": "2024-01-15T10:30:00Z",
    // Custom attributes (optional)
    "user.age": "30",
    "user.department": "Engineering"
  }
}
```

## Data Types and Validation Rules

### Timestamps
- **Format**: ISO 8601 strings (`"2024-01-15T10:30:00Z"`)
- **Required**: `timestamp`, `session.start_time`  
- **Optional**: `memory.timestamp`, `http.timestamp`, `user.profile_updated_at`

### Numeric Values
- **Integers**: `batch_size`, `session.duration_ms`, `session.event_count`, `session.metric_count`, `session.screen_count`, `session.total_sessions`, `http.status_code`, `http.duration_ms`, `frame.target_fps`, `user.profile_version`
- **Floats**: `value` (for metrics), `frame.build_duration_ms`, `frame.raster_duration_ms`, `frame.total_duration_ms`, `memory.usage_mb`

### Booleans  
- **Format**: `true`/`false` (JSON boolean)
- **Fields**: `frame.dropped`, `session.is_first_session`, `http.success`

### Strings
- **Platform**: Must be lowercase (`"android"` not `"Android"`)
- **Package Name**: Standard reverse domain format
- **UUIDs**: For `device.id`, `session.id`, `user.id`

## Android-Specific Implementation Notes

### Device ID Generation
```kotlin
// Generate persistent device ID (store in SharedPreferences)
val deviceId = UUID.randomUUID().toString()
```

### Device Information Collection
```kotlin
// Collect Android device info
val build = Build
deviceInfo = mapOf(
    "device.platform" to "android",
    "device.platform_version" to build.VERSION.RELEASE,
    "device.model" to build.MODEL,
    "device.manufacturer" to build.MANUFACTURER,
    "device.brand" to build.BRAND,
    "device.android_sdk" to build.VERSION.SDK_INT.toString(),
    "device.android_release" to build.VERSION.RELEASE,
    "device.fingerprint" to build.FINGERPRINT,
    "device.hardware" to build.HARDWARE,
    "device.product" to build.PRODUCT
)
```

### Session Management
- Generate session UUID on app launch
- Track session duration, screen visits, event counts
- Update session info before sending batches

### Batch Processing
- Collect events in memory
- Send batches of 10-50 events  
- Include batch timestamp and event count
- Handle network failures with retry logic

## Sample Complete Batch

```json
{
  "type": "telemetry_batch",
  "batch_size": 3,
  "timestamp": "2024-01-15T10:30:00Z", 
  "events": [
    {
      "type": "metric",
      "metricName": "frame_duration",
      "value": 16.7,
      "timestamp": "2024-01-15T10:29:45Z",
      "attributes": {
        "app.name": "MyAndroidApp",
        "app.version": "1.2.0",
        "app.build_number": "42", 
        "app.package_name": "com.example.myapp",
        "device.id": "550e8400-e29b-41d4-a716-446655440000",
        "device.platform": "android",
        "device.platform_version": "13",
        "device.model": "Pixel 7",
        "device.manufacturer": "Google",
        "device.brand": "google",
        "user.id": "user123",
        "session.id": "session456",
        "session.start_time": "2024-01-15T10:00:00Z",
        "session.duration_ms": 1800000,
        "metric.unit": "ms",
        "frame.build_duration_ms": 8.5,
        "frame.raster_duration_ms": 6.2
      }
    },
    {
      "type": "event",
      "eventName": "http.request", 
      "timestamp": "2024-01-15T10:29:50Z",
      "attributes": {
        // Same core attributes...
        "http.url": "https://api.myapp.com/data",
        "http.method": "POST",
        "http.status_code": 201,
        "http.duration_ms": 320,
        "http.success": true
      }
    },
    {
      "type": "event",
      "eventName": "user.profile_updated",
      "timestamp": "2024-01-15T10:29:55Z", 
      "attributes": {
        // Same core attributes...
        "user.name": "Jane Smith",
        "user.email": "jane@example.com",
        "user.profile_version": 3
      }
    }
  ]
}
```

## Key Implementation Checklist for Android SDK

✅ **Device Identification**: Generate persistent `device.id` UUID  
✅ **Attribute Naming**: Use exact attribute names (case-sensitive)  
✅ **Data Types**: Ensure integers, floats, booleans, and strings match  
✅ **Timestamps**: Use ISO 8601 format for all timestamp fields  
✅ **Platform**: Always use lowercase `"android"`  
✅ **Session Tracking**: Implement session lifecycle management  
✅ **Batch Structure**: Follow exact JSON structure with `type`, `batch_size`, `timestamp`, `events`  
✅ **Event Types**: Support `metric` and `event` types with proper fields  
✅ **Error Handling**: Handle network failures and retry logic  

Following this specification exactly will ensure your Android SDK is fully compatible with your existing backend without requiring any changes to the EdgeTelemetryProcessor.
