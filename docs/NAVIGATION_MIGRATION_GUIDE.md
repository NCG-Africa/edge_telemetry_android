# Navigation Event Migration Guide

**Version**: v2.1.0  
**Date**: March 18, 2026  
**Impact**: Breaking Changes to Navigation Event Structure

---

## Overview

Version 2.1.0 introduces **breaking changes** to navigation event tracking to align with backend Kafka processor requirements and the `rum_navigation_events` database table schema.

### What Changed

Navigation events now use a standardized structure with proper field names, navigation methods, and comprehensive tracking:

| Aspect | v2.0.x (Old) | v2.1.0 (New) |
|--------|--------------|--------------|
| **Event Name** | `navigation.route_change` | `navigation` |
| **Destination Field** | `navigation.to` | `navigation.to_screen` |
| **Source Field** | ❌ Not tracked | ✅ `navigation.from_screen` |
| **Method Values** | `resumed`, `paused`, `navigation` | `push`, `pop`, `replace` |
| **Route Classification** | `navigation.type` | `navigation.route_type` |
| **Argument Tracking** | ❌ Not tracked | ✅ `navigation.has_arguments` |
| **Timestamp Format** | `currentTimeMillis()` string | ISO 8601 format |

---

## Breaking Changes

### 1. Field Name Changes

#### ❌ Old Structure (v2.0.x)
```json
{
  "eventName": "navigation.route_change",
  "attributes": {
    "navigation.to": "ProfileScreen",
    "navigation.method": "resumed",
    "navigation.type": "activity_change",
    "navigation.timestamp": "1710770400000"
  }
}
```

#### ✅ New Structure (v2.1.0)
```json
{
  "eventName": "navigation",
  "attributes": {
    "navigation.from_screen": "HomeScreen",
    "navigation.to_screen": "ProfileScreen",
    "navigation.method": "push",
    "navigation.route_type": "main_flow",
    "navigation.has_arguments": true,
    "navigation.timestamp": "2024-03-18T14:50:23.456Z"
  }
}
```

### 2. Navigation Method Values

#### ❌ Old Values (v2.0.x)
- `"resumed"` - Activity/Fragment lifecycle state
- `"paused"` - Activity/Fragment lifecycle state
- `"navigation"` - Generic navigation
- `"closed"`, `"destroyed"` - Lifecycle states

#### ✅ New Values (v2.1.0)
- `"push"` - Forward navigation to new screen
- `"pop"` - Back navigation to previous screen
- `"replace"` - Screen replacement

**Impact**: Backend database expects only these 3 values. Old values will cause constraint violations.

### 3. New Required Fields

#### `navigation.from_screen`
- **Type**: String (nullable)
- **Purpose**: Track user navigation flow and journey
- **Value**: Previous screen name, `null` on app launch
- **Example**: `"HomeScreen"` → `"ProfileScreen"` (from_screen = "HomeScreen")

#### `navigation.has_arguments`
- **Type**: Boolean
- **Purpose**: Track data passing between screens
- **Default**: `false`
- **Examples**:
  - Deep link parameters → `true`
  - Intent extras → `true`
  - Navigation arguments → `true`
  - No data → `false`

### 4. Timestamp Format Change

#### ❌ Old Format (v2.0.x)
```kotlin
"navigation.timestamp" to System.currentTimeMillis().toString()
// Result: "1710770400000"
```

#### ✅ New Format (v2.1.0)
```kotlin
"navigation.timestamp" to Instant.now().toString()
// Result: "2024-03-18T14:50:23.456Z"
```

**Impact**: Backend expects ISO 8601 format. Old format causes parsing errors.

---

## Migration Steps

### For SDK Users (App Developers)

**Good News**: No code changes required! The SDK handles everything automatically.

#### Step 1: Update SDK Version

```kotlin
// build.gradle.kts
dependencies {
    // Old version
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.0.0'
    
    // New version
    implementation 'com.github.NCG-Africa:edge_telemetry_android:2.1.0'
}
```

#### Step 2: Sync and Rebuild

```bash
./gradlew clean build
```

#### Step 3: Verify Navigation Tracking

Enable debug mode to verify new event structure:

```kotlin
TelemetryManager.initialize(
    application = this,
    apiKey = BuildConfig.TELEMETRY_API_KEY,
    debugMode = true  // See navigation events in logs
)
```

Look for logs like:
```
[EdgeTelemetry] Navigation Event: {
  "navigation.from_screen": "HomeScreen",
  "navigation.to_screen": "ProfileScreen",
  "navigation.method": "push",
  "navigation.route_type": "main_flow",
  "navigation.has_arguments": false
}
```

### For Backend Teams

#### Step 1: Verify Kafka Processor Compatibility

Ensure your Kafka processor supports the new event structure:

```python
# Expected processor behavior
def process_navigation_event(event):
    attributes = event.get('attributes', {})
    
    # Required fields
    to_screen = attributes.get('navigation.to_screen')  # Required
    method = attributes.get('navigation.method', 'push')  # Default: push
    
    # Optional fields
    from_screen = attributes.get('navigation.from_screen')  # Can be null
    route_type = attributes.get('navigation.route_type')
    has_arguments = attributes.get('navigation.has_arguments', False)
    timestamp = attributes.get('navigation.timestamp', event['timestamp'])
```

#### Step 2: Update Database Schema (if needed)

Verify `rum_navigation_events` table schema:

```sql
CREATE TABLE rum_navigation_events (
    id SERIAL PRIMARY KEY,
    from_screen VARCHAR(255),  -- Nullable
    to_screen VARCHAR(255) NOT NULL,
    navigation_method VARCHAR(20) NOT NULL CHECK (navigation_method IN ('push', 'pop', 'replace')),
    route_type VARCHAR(100),
    has_arguments BOOLEAN DEFAULT FALSE,
    timestamp TIMESTAMP NOT NULL,
    -- ... other fields
);
```

#### Step 3: Test Integration

1. Deploy SDK v2.1.0 to staging environment
2. Trigger navigation events in test app
3. Verify events in Kafka consumer logs
4. Verify data in `rum_navigation_events` table
5. Check for constraint violations or parsing errors

---

## Code Examples

### Activity Navigation (Automatic)

No code changes needed. The SDK automatically tracks:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // SDK automatically generates:
        // {
        //   "navigation.from_screen": null,  // First launch
        //   "navigation.to_screen": "MainActivity",
        //   "navigation.method": "push",
        //   "navigation.route_type": "main_flow",
        //   "navigation.has_arguments": false
        // }
    }
}
```

### Fragment Navigation (Automatic)

```kotlin
class ProfileFragment : Fragment() {
    override fun onResume() {
        super.onResume()
        
        // SDK automatically generates:
        // {
        //   "navigation.from_screen": "HomeFragment",
        //   "navigation.to_screen": "ProfileFragment",
        //   "navigation.method": "push",
        //   "navigation.route_type": "fragment_flow",
        //   "navigation.has_arguments": true  // If fragment has arguments
        // }
    }
}
```

### Compose Navigation (Automatic)

```kotlin
@Composable
fun ProfileScreen(navController: NavController) {
    TrackComposeScreen(
        navController = navController,
        screenName = "ProfileScreen",
        additionalData = mapOf("route_type" to "main_flow")
    )
    
    // SDK automatically generates:
    // {
    //   "navigation.from_screen": "HomeScreen",
    //   "navigation.to_screen": "ProfileScreen",
    //   "navigation.method": "push",
    //   "navigation.route_type": "main_flow",
    //   "navigation.has_arguments": false
    // }
}
```

### Deep Link Navigation

```kotlin
// Deep link intent: myapp://product/123
class ProductActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // SDK automatically detects deep link and generates:
        // {
        //   "navigation.from_screen": null,
        //   "navigation.to_screen": "ProductActivity",
        //   "navigation.method": "push",
        //   "navigation.route_type": "deeplink",  // Auto-detected!
        //   "navigation.has_arguments": true  // Intent has data
        // }
    }
}
```

---

## Troubleshooting

### Issue: Events Not Appearing in Database

**Symptoms**: Navigation events sent but not stored in `rum_navigation_events` table

**Possible Causes**:
1. Backend processor not updated to handle new field names
2. Database schema doesn't support new fields
3. Method validation constraint rejecting old values

**Solution**:
```bash
# Check Kafka consumer logs for errors
tail -f /var/log/kafka-consumer.log | grep navigation

# Verify database constraints
SELECT constraint_name, constraint_type 
FROM information_schema.table_constraints 
WHERE table_name = 'rum_navigation_events';
```

### Issue: Method Constraint Violations

**Symptoms**: Database errors like `CHECK constraint failed: navigation_method`

**Cause**: Backend receiving old method values (`resumed`, `paused`, etc.)

**Solution**: Verify SDK version is 2.1.0+:
```kotlin
// Check in app logs
Log.d("SDK", "Telemetry SDK Version: ${BuildConfig.SDK_VERSION}")
```

### Issue: Timestamp Parsing Errors

**Symptoms**: Backend logs show timestamp parsing failures

**Cause**: Expecting ISO 8601 but receiving milliseconds

**Solution**: Ensure SDK v2.1.0+ is deployed:
```bash
# Verify SDK dependency
./gradlew :app:dependencies | grep edge_telemetry_android
```

### Issue: Missing from_screen Data

**Symptoms**: `from_screen` always null

**Cause**: Navigation stack not properly initialized

**Solution**: This is expected on first app launch. Subsequent navigations should have `from_screen` populated.

---

## Backward Compatibility

### Dual Event Support (Temporary)

If you need to support both old and new backends temporarily:

**Not Recommended**: The SDK only sends new format events. Update your backend instead.

### Gradual Rollout Strategy

1. **Week 1**: Deploy backend changes to staging
2. **Week 2**: Test SDK v2.1.0 in staging with new backend
3. **Week 3**: Deploy backend to production
4. **Week 4**: Release app with SDK v2.1.0

---

## FAQ

### Q: Do I need to change my app code?

**A**: No. The SDK handles all changes automatically. Just update the dependency version.

### Q: Will old events still work?

**A**: No. The backend expects the new structure. Old events may be rejected or cause errors.

### Q: Can I customize route_type?

**A**: Yes, for Compose navigation:
```kotlin
TrackComposeScreen(
    navController = navController,
    screenName = "CustomScreen",
    additionalData = mapOf("route_type" to "custom_flow")
)
```

For Activities/Fragments, route_type is auto-detected.

### Q: What happens to screen duration tracking?

**A**: Screen duration is tracked separately in `performance.screen_duration` events. Navigation events only track the navigation action itself.

### Q: How do I track back navigation?

**A**: The SDK automatically detects back navigation and sets `method` to `"pop"`. No manual tracking needed.

### Q: Can I disable navigation tracking?

**A**: Not currently. Navigation tracking is core to the SDK. If you need this feature, please file a GitHub issue.

---

## Support

If you encounter issues during migration:

1. **Check Logs**: Enable `debugMode = true` to see detailed event structure
2. **Verify Version**: Ensure SDK version is 2.1.0+
3. **Test Backend**: Verify Kafka processor handles new event structure
4. **Review Schema**: Check database schema matches requirements
5. **Contact Support**: support@ncg-africa.com or [GitHub Issues](https://github.com/NCG-Africa/edge-telemetry-android/issues)

---

## Related Documentation

- [README.md](../README.md) - Full SDK documentation
- [CHANGELOG.md](../CHANGELOG.md) - Version history
- [Navigation Event Examples](navigation_event_examples.md) - Detailed examples
- [Backend Integration Guide](BACKEND_INTEGRATION.md) - Kafka processor setup

---

**Last Updated**: March 18, 2026  
**SDK Version**: v2.1.0  
**Status**: Production Ready
