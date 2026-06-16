# Phone Field Addition to User Profile

## Summary

Added `phone` field to the user profile system, ensuring it is persisted locally in SharedPreferences and included in telemetry requests when set.

## Changes Made

### 1. UserProfile Data Class
**File:** `UserProfileManager.kt`

Added `phone` field to the `UserProfile` data class:
```kotlin
data class UserProfile(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null  // NEW
)
```

### 2. SharedPreferences Persistence
**File:** `UserProfileManager.kt`

- Added `KEY_PHONE = "phone"` constant
- Updated `loadUserProfile()` to load phone from SharedPreferences
- Updated `saveUserProfile()` to persist phone:
  - Saves phone if non-null
  - Removes key if null (same behavior as name and email)

### 3. setUserProfile() Signature
**File:** `UserProfileManager.kt` & `TelemetryManager.kt`

Updated method signature to include phone parameter:
```kotlin
fun setUserProfile(name: String?, email: String?, phone: String? = null)
```

**Note:** Phone parameter has default value `null`, making it optional for backward compatibility.

### 4. TelemetryManager Integration
**File:** `TelemetryManager.kt`

- Added `pendingPhone` field for pre-init profile storage
- Updated pending profile application to include phone
- Updated `buildAttributes()` to include phone from user profile
- Phone is passed through to `UserInfo` in event attributes

### 5. Event Attribute Envelope
**File:** `TelemetryHttpClient.kt`

Updated `flattenAttributes()` to conditionally include `user.phone`:
```kotlin
attrs.user.phone?.let { flat["user.phone"] = it }
```

**Behavior:**
- `user.phone` only included in telemetry if non-null
- Key completely omitted when value is null (not sent as null)

### 6. Test Coverage
**Files:** 
- `UserProfileManagerStage2Test.kt`
- `TelemetryManagerStage2Test.kt`

Added/updated tests:
- ✅ Profile loads phone from SharedPreferences
- ✅ setUserProfile persists phone correctly
- ✅ Phone can be set independently of other fields
- ✅ Phone is cleared when set to null
- ✅ Phone field persists independently
- ✅ Phone is optional (can be omitted using default parameter)

## Usage Examples

### Set All Fields
```kotlin
TelemetryManager.getInstance().setUserProfile(
    name = "John Doe",
    email = "john@example.com",
    phone = "+1234567890"
)
```

### Set Without Phone (Backward Compatible)
```kotlin
TelemetryManager.getInstance().setUserProfile(
    name = "Jane Doe",
    email = "jane@example.com"
)
// phone defaults to null
```

### Set Only Phone
```kotlin
TelemetryManager.getInstance().setUserProfile(
    name = null,
    email = null,
    phone = "+9876543210"
)
```

### Clear Phone
```kotlin
TelemetryManager.getInstance().setUserProfile(
    name = "User",
    email = "user@example.com",
    phone = null  // Clears phone
)
```

### Clear All Fields
```kotlin
TelemetryManager.getInstance().clearUserProfile()
// All fields set to null
```

## Persistence Behavior

### SharedPreferences Keys
- `display_name` - User's display name
- `email` - User's email address
- `phone` - User's phone number

### Storage Rules
1. **Non-null values:** Saved to SharedPreferences
2. **Null values:** Key removed from SharedPreferences
3. **Full replacement:** Each `setUserProfile()` call replaces all fields (no merge)

### Example Persistence Flow
```kotlin
// Initial state: All fields empty
setUserProfile("Alice", "alice@test.com", "+1111111111")
// SharedPrefs: display_name=Alice, email=alice@test.com, phone=+1111111111

setUserProfile("Bob", null, "+2222222222")
// SharedPrefs: display_name=Bob, phone=+2222222222
// Note: email key removed

setUserProfile(null, null, null)
// SharedPrefs: All keys removed
```

## Telemetry Request Behavior

### Event Attributes
When sending telemetry events, user fields are included as follows:

**Always Included:**
- `user.id` - SDK-generated userId (never null)

**Conditionally Included (only if non-null):**
- `user.name` - name if set
- `user.email` - email if set
- `user.phone` - phone if set

### Example Payloads

**With All Fields:**
```json
{
  "user.id": "1234567890_abcd1234",
  "user.name": "John Doe",
  "user.email": "john@example.com",
  "user.phone": "+1234567890"
}
```

**With Only Phone:**
```json
{
  "user.id": "1234567890_abcd1234",
  "user.phone": "+1234567890"
}
```

**No Profile Set:**
```json
{
  "user.id": "1234567890_abcd1234"
}
```

## Files Modified

1. `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/user/UserProfileManager.kt`
2. `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt`
3. `telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryHttpClient.kt`
4. `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/user/UserProfileManagerStage2Test.kt`
5. `telemetry_library/src/test/java/com/androidtel/telemetry_library/core/TelemetryManagerStage2Test.kt`

## Backward Compatibility

✅ **Fully backward compatible** - The `phone` parameter has a default value of `null`, so existing code that calls `setUserProfile(name, email)` will continue to work without modification.

## Testing

Run the test suites to verify:
```bash
./gradlew test --tests UserProfileManagerStage2Test
./gradlew test --tests TelemetryManagerStage2Test
```

All tests should pass, including new tests for phone field functionality.
