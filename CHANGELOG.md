# Changelog

All notable changes to the Edge Telemetry Android SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.5] - 2025-01-27

### 💥 Breaking Changes

#### API Key Authentication Required
- **API Key Parameter**: `apiKey` is now a **required parameter** in `TelemetryManager.initialize()`
- **Security Enhancement**: All telemetry requests now include `X-API-Key` header for backend authentication
- **Migration Required**: Existing integrations must add `apiKey` parameter to initialization

### 🔒 Security Enhancements

#### API Key Authentication
- **Required Parameter**: `apiKey` must be provided during SDK initialization
- **Header-Based Auth**: API key sent via `X-API-Key` HTTP header on all requests
- **Secure Transmission**: API key never exposed in URLs or request bodies
- **Consistent Auth**: API key included in all retry attempts and offline batch sends

### 🛡️ Reliability Improvements

#### Critical ID Validation with Safe Error Handling
- **Device ID Protection**: Multiple validation layers ensure `device_id` is never null or empty
- **User ID Protection**: Comprehensive validation ensures `user_id` is always present
- **Graceful Degradation**: SDK uses fallback IDs instead of crashing the instrumented app
- **Clear Error Logging**: All validation failures logged with "CRITICAL ERROR" prefix for easy debugging

#### Fallback ID System
- **Emergency Fallbacks**: Automatic fallback IDs generated if primary generation fails
- **Identifiable Patterns**: Fallback IDs clearly marked (e.g., `device_fallback_`, `user_emergency_`)
- **Telemetry Continuity**: Data collection continues even with fallback IDs
- **Zero App Crashes**: SDK errors never crash the host application

### 📊 Payload Structure Updates

#### Enhanced JSON Response
- **Top-Level Device ID**: `device_id` now included at the root level of the `data` object
- **Consistent Structure**: Device ID present in both payload root and event attributes
- **Backend Optimization**: Easier device identification without parsing individual events

### 🔧 Technical Improvements

#### Safe Error Handling
- **No Exception Throwing**: Replaced `require()` and `check()` calls with safe logging
- **Fallback Values**: Uses `unknown_device`, `unknown_user`, or timestamped fallbacks
- **Production Ready**: SDK never crashes the instrumented application
- **Debug Friendly**: Clear error logs help identify and fix integration issues

#### Validation Layers
1. **Initialization Layer**: IDs validated when created/loaded from SharedPreferences
2. **Data Model Layer**: `userId` is non-nullable in `UserInfo` data class
3. **Payload Builder Layer**: Validation before creating `TelemetryDataOut`
4. **Attribute Flattening Layer**: Final validation when converting to JSON

### 📝 Migration Guide

#### Update Initialization Code

**Before (v1.2.1 and earlier):**
```kotlin
TelemetryManager.initialize(
    application = this,
    batchSize = 30,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
)
```

**After (v1.2.5+):**
```kotlin
TelemetryManager.initialize(
    application = this,
    apiKey = "your-api-key-here",  // ⚠️ REQUIRED
    batchSize = 30,
    endpoint = "https://edgetelemetry.ncgafrica.com/collector/telemetry"
)
```

### 🔍 What Changed

#### New Required Parameter
- `apiKey: String` - Required parameter for backend authentication

#### HTTP Request Headers
All telemetry requests now include:
```http
X-API-Key: your-api-key-here
Content-Type: application/json
User-Agent: EdgeTelemetryAndroid/1.0.0
```

#### JSON Payload Structure
```json
{
  "timestamp": "2025-01-27T13:42:10.123Z",
  "data": {
    "type": "batch",
    "device_id": "device_1727012247_ghi789_android",
    "events": [...],
    "batch_size": 7,
    "timestamp": "2025-01-27T13:42:10.123Z"
  }
}
```

### ⚠️ Important Notes

- **Backend Integration**: Your backend must validate the `X-API-Key` header
- **API Key Storage**: Store API keys securely (e.g., in BuildConfig or secure storage)
- **No Default Value**: `apiKey` has no default - must be explicitly provided
- **Backward Incompatible**: Apps using v1.2.1 or earlier must update initialization code

## [1.2.1] - 2025-01-20

### 🔧 Improved
- **Unified API**: Merged all Flutter-compatible functionality into single TelemetryManager
- **Single Initialization**: Eliminated dual initialization confusion - only `TelemetryManager.initialize()` needed
- **Enhanced Backward Compatibility**: All existing code works unchanged with new features available
- **Simplified Configuration**: Streamlined initialization parameters with sensible defaults
- **Better Documentation**: Updated README and usage examples for unified API

### 🧹 Removed
- **EdgeTelemetry Class**: Functionality merged into TelemetryManager to eliminate API duplication

### 📚 Updated
- **Documentation**: Complete rewrite of README with unified API examples
- **Usage Examples**: Updated all examples to use single TelemetryManager API
- **Integration Guide**: Added comprehensive integration summary

## [1.2.0] - 2025-01-16

### 💥 Breaking Changes

#### Minimum SDK Requirement Updated
- **Minimum SDK**: Raised from Android 5.0 (API 21) to Android 7.0 (API 24)
- **Target Audience**: Now supports Android 7.0+ through Android 14+ (API 24-35)
- **Compatibility Impact**: Drops support for Android 5.0-6.x devices (API 21-23)

### 🚀 Enhanced Features

#### Frame Metrics Collection Now Standard
- **Universal Frame Metrics**: All users now have access to precise FrameMetrics API-based performance tracking
- **Simplified Architecture**: Removed legacy Choreographer-based fallback implementation
- **Enhanced Performance Insights**: Consistent, detailed frame drop detection across all supported devices
- **Notification Features**: Notification importance levels now available for all users

### 🔧 Technical Improvements

#### Simplified Codebase
- **Reduced Complexity**: Eliminated conditional API 21-23 compatibility code paths
- **Performance Optimization**: Removed overhead from legacy device support
- **Enhanced Reliability**: Standardized on modern Android APIs for better stability

### 📊 Impact Summary

#### Positive Changes
- **Better Performance Data**: All users get detailed frame metrics instead of estimated values
- **Simplified Maintenance**: Reduced conditional code complexity
- **Enhanced User Experience**: More accurate performance insights for app optimization

#### Migration Notes
- **App Integration**: Apps using this SDK must also update their minSdk to 24 or higher
- **Device Coverage**: Verify your user base compatibility before upgrading
- **Feature Parity**: All existing telemetry features remain unchanged, just with enhanced capabilities

## [1.1.15] - 2025-01-11

### 🚀 Major Features

#### Enhanced Memory Tracking with API-Level Compatibility
- **Progressive Memory Monitoring**: Comprehensive memory tracking that adapts to device capabilities across Android API levels 21-35
- **Tiered Memory Collection**: 
  - **Tier 1 (API 21+)**: Basic heap memory tracking via Runtime
  - **Tier 2 (API 23+)**: System memory info via ActivityManager  
  - **Tier 3 (API 26+)**: Detailed memory breakdown via Debug.MemoryInfo
  - **Tier 4 (API 28+)**: Advanced memory classification and profiling
- **Enhanced Memory Pressure Detection**: Intelligent pressure calculation using system memory state, heap ratios, and memory class
- **Storage Usage Tracking**: Comprehensive storage monitoring with internal/external breakdown
- **Memory Event Standardization**: Consistent telemetry structure across all API levels with progressive enhancement

#### Unified Memory Tracking Architecture
- **MemoryTracker Interface**: Factory pattern for automatic implementation selection based on device capabilities
- **EnhancedMemoryTracker**: Uses MemoryCapabilityTracker for detailed insights on capable devices
- **BasicMemoryTracker**: Runtime-based fallback ensuring compatibility on all API levels
- **Graceful Degradation**: No crashes or missing functionality regardless of Android version

### 🔧 Enhanced Components

#### TelemetryMemoryUsage Class Overhaul
- **API-Aware Implementation**: Automatically leverages enhanced tracking when available
- **Consistent Event Structure**: Same core attributes across all implementations with API-specific enhancements
- **Enhanced Pressure Calculation**: Multi-factor analysis including heap usage ratio and system pressure
- **Storage Integration**: Added comprehensive storage usage monitoring
- **Error Resilience**: Robust fallback mechanisms prevent memory tracking failures

#### MemoryCapabilityTracker Integration
- **Comprehensive Memory Analysis**: Detailed memory breakdown with native heap, PSS, and system totals
- **Storage Information**: Internal/external storage tracking with capability awareness
- **Memory Pressure Detection**: Advanced pressure detection using system thresholds
- **Safe API Access**: Reflection-based access to deprecated fields with proper error handling

### 📊 New Telemetry Events

#### Enhanced Memory Events
```kotlin
eventName = "memory_pressure"
attributes = {
    "memory.pressure_level": String,        // "low", "moderate", "high"
    "memory.heap_used_mb": Long,           // Always present
    "memory.heap_max_mb": Long,            // Always present  
    "memory.system_available_mb": Long?,   // API 16+ when available
    "memory.native_heap_kb": Int?,         // API 26+ detailed breakdown
    "memory.total_pss_kb": Int?,           // Process memory usage
    "memory.tracking_method": String,      // "enhanced" or "basic"
    "memory.api_level": Int,               // Device API level
    "memory.under_system_pressure": Boolean // System-level pressure detection
}
```

#### Storage Usage Events
```kotlin
eventName = "storage_usage"
attributes = {
    "storage.internal_total_mb": Long,
    "storage.internal_free_mb": Long,
    "storage.internal_usable_mb": Long,
    "storage.external_total_mb": Long?,    // When available
    "storage.api_level": Int
}
```

### 🏗️ Architecture Improvements

#### Factory Pattern Implementation
- **MemoryTrackerFactory**: Automatic selection of appropriate memory tracker implementation
- **Runtime Capability Detection**: Uses existing DeviceCapabilities system for intelligent selection
- **Consistent Interface**: Unified API regardless of underlying implementation complexity
- **Performance Optimized**: Lazy initialization and efficient capability detection

#### TelemetryManager Integration
- **getMemoryCapabilityTracker()**: Safe accessor method for enhanced memory tracking
- **Capability Awareness**: Leverages existing runtime feature detection system
- **Memory Safety**: Proper initialization checks and null handling throughout

### 🔄 Performance Enhancements

#### Memory Tracking Optimizations
- **Sampling Control**: Configurable sampling rates to minimize overhead on legacy devices
- **Efficient Collection**: Optimized memory info gathering with minimal performance impact
- **Lazy Initialization**: Memory trackers created only when needed
- **Error Handling**: Comprehensive error handling prevents tracking failures

#### Enhanced Pressure Detection
- **Multi-Factor Analysis**: Considers heap usage ratio, system memory state, and memory class
- **System Integration**: Uses ActivityManager.MemoryInfo for accurate system-level pressure detection
- **Threshold Intelligence**: Dynamic thresholds based on device memory class and available memory

### 🛡️ Reliability & Compatibility

#### Graceful Degradation
- **No Breaking Changes**: Existing memory events maintain same structure with progressive enhancement
- **API Compatibility**: Works reliably on all Android versions from 5.0 (API 21) to latest
- **Fallback Mechanisms**: Automatic fallback to basic tracking when enhanced features unavailable
- **Error Resilience**: Robust error handling ensures memory tracking never fails completely

#### Memory Safety
- **Leak Prevention**: Proper cleanup and lifecycle management for all memory tracking components
- **Thread Safety**: Synchronized access to prevent concurrent modification issues
- **Resource Management**: Efficient resource usage with proper cleanup on component destruction

### 📈 Expected Behavior by API Level

#### Modern Devices (API 26+)
- Full memory breakdown with native heap, PSS, and system totals
- Advanced pressure detection using system thresholds  
- Storage tracking with internal/external breakdown
- Enhanced memory classification and profiling data

#### Mid-Range Devices (API 21-25)
- System memory info via ActivityManager
- Enhanced pressure detection with memory class consideration
- Basic storage tracking with capability awareness
- Graceful handling of unavailable advanced features

#### Legacy Fallback (All APIs)
- Runtime heap memory tracking guaranteed on all devices
- Basic pressure calculation using heap usage ratios
- Consistent event structure with core memory metrics
- No feature loss, only progressive enhancement

### 🔧 Developer Experience

#### Automatic Implementation Selection
- **Zero Configuration**: Memory tracking automatically adapts to device capabilities
- **Transparent Operation**: Developers don't need to handle API level differences
- **Consistent Events**: Same event names and core structure across all implementations
- **Clear Attribution**: Tracking method clearly indicated in telemetry events

#### Enhanced Logging
- **Capability Detection**: Clear logging of memory tracking capabilities at initialization
- **Implementation Selection**: Logs which memory tracker implementation is selected
- **Performance Metrics**: Detailed logging of memory usage and pressure levels
- **Error Reporting**: Comprehensive error logging with graceful degradation messages

### 📋 Technical Details

#### Memory Tracking Methods
- **Enhanced Tracking**: Uses MemoryCapabilityTracker for comprehensive analysis
- **Basic Tracking**: Runtime-based fallback for maximum compatibility
- **Progressive Enhancement**: Additional data collected where APIs support it
- **Consistent Structure**: Same core event format regardless of collection method

#### Storage Monitoring
- **Internal Storage**: Total, free, and usable space tracking
- **External Storage**: Optional external storage monitoring when available
- **Capability Aware**: Respects device storage capabilities and permissions
- **Error Handling**: Graceful handling of storage access issues

## [1.1.0] - 2024-12-15

### 🚀 Major Features

#### Extended Android Compatibility
- **Minimum SDK Lowered**: Now supports Android 5.0 (API 21) through Android 14 (API 35)
- **Runtime Feature Detection**: Comprehensive capability detection system for graceful degradation
- **Progressive Enhancement**: Enhanced features on newer devices, reliable basics on older devices

#### Conditional Frame Drop Collection with API-Level Fallbacks
- **Modern Implementation (API 24+)**: Native FrameMetrics API for precise frame drop detection
- **Legacy Implementation (API 21-23)**: Choreographer-based performance tracking with estimated metrics
- **Unified Interface**: Same telemetry events regardless of tracking method
- **Performance Optimized**: Sampling-based collection on older devices to minimize overhead

#### Device Capabilities System
- **DeviceCapabilities**: Central runtime detection of API levels, hardware features, and permissions
- **NetworkCapabilityDetector**: API-aware network state detection with modern and legacy fallbacks
- **MemoryCapabilityTracker**: Capability-aware memory tracking using appropriate APIs per Android version

### 🔧 Enhanced Components

#### Performance Tracking Architecture
- **PerformanceTracker Interface**: Unified performance tracking across all API levels
- **PerformanceTrackerFactory**: Automatic selection of appropriate implementation
- **ModernPerformanceTracker**: Wraps TelemetryFrameDropCollector for API 24+
- **LegacyPerformanceTracker**: Choreographer-based tracking for API 21-23

#### Frame Drop Collection Improvements
- **Runtime Capability Checks**: No more compile-time API restrictions
- **Error Handling**: Comprehensive error handling and logging
- **Memory Leak Prevention**: Proper cleanup with WeakReferences and synchronized methods
- **Graceful Degradation**: No crashes on unsupported API levels

### 🔄 Breaking Changes Avoided
- **No API Changes**: All existing telemetry functionality preserved
- **Same Event Structure**: Consistent telemetry event format across implementations
- **Backward Compatible**: Existing integrations continue to work without modification

### 🛡️ Reliability Improvements
- **Java 8 Time API Removal**: Replaced with System.currentTimeMillis() for API 21+ compatibility
- **Annotation Cleanup**: Removed @RequiresApi annotations that blocked older Android versions
- **Build Compatibility**: Library builds successfully with minSdk 21

### 📈 Performance Enhancements
- **Sampling Control**: 10% sampling rate on legacy devices to reduce overhead
- **Efficient Choreographer Usage**: Proper cleanup and memory management
- **Configurable Thresholds**: Performance classification with adjustable frame timing thresholds

## [1.0.15] - 2024-11-20

### 🐛 Bug Fixes
- **HTTP Retry Logic**: Fixed infinite loop in server error (5xx) retry handling
- **Crash Handler Performance**: Eliminated ANR-causing blocking operations during crash handling
- **Memory Leak Prevention**: Fixed frame metrics listener accumulation in TelemetryFrameDropCollector
- **ID Format Consistency**: Standardized device and session ID formats

### 🔧 Improvements
- **Network Resilience**: Proper exponential backoff for server error retries
- **Performance Optimization**: Crash handler execution time reduced to <100ms
- **Memory Management**: Proper cleanup of listeners and observers
- **User ID Management**: Automatic user ID generation with persistent storage

### 📊 Enhanced Telemetry
- **Consistent Event Structure**: Standardized telemetry event formats
- **Improved Error Handling**: Better error reporting and graceful degradation
- **Session Management**: Enhanced session tracking with proper ID generation

---

## Version History Summary

- **1.2.0**: Raised minimum SDK requirement from 21 to 24, simplified architecture, and enhanced performance insights
- **1.1.15**: Enhanced memory tracking with API-level compatibility and progressive enhancement
- **1.1.0**: Extended Android compatibility (API 21+) with runtime feature detection
- **1.0.15**: Bug fixes for HTTP retry logic, crash handler performance, and memory leaks

---

**For detailed technical documentation, see [README.md](README.md)**
