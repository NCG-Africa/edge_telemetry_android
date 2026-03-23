# Phase 2C: Enhanced Context Collection - Completion Report

## Overview

Phase 2C has been successfully implemented, adding enhanced context tracking capabilities to the crash reporting system:
- **user_action tracking**: Captures the last user interaction before a crash
- **error_code support**: Allows app-specific error codes for better error categorization

## Implementation Summary

### 1. User Action Tracking

The SDK now tracks the last user interaction, providing valuable context for crash analysis.

#### API Methods

```kotlin
// Set last user action (max 500 chars, auto-truncated)
TelemetryManager.getInstance().setLastUserAction("Clicked login button")

// Track error with explicit user action
TelemetryManager.getInstance().trackError(
    error = exception,
    userAction = "Submitted payment form"
)
```

#### Implementation Details

**Location**: `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt:273-276`

```kotlin
fun setLastUserAction(action: String) {
    this.lastUserAction = action.take(500)
    Log.d(TAG, "User action set: $action")
}
```

**Character Limit**: 500 characters (automatically enforced)

### 2. Error Code Support

App-specific error codes enable better error categorization and tracking.

#### API Methods

```kotlin
// Track error with error code
TelemetryManager.getInstance().trackError(
    error = exception,
    errorCode = "AUTH_001"
)

// Track error with full enhanced context
TelemetryManager.getInstance().trackError(
    error = exception,
    errorCode = "PAY_TIMEOUT_001",
    productId = "payment_module",
    userAction = "Initiated payment transaction",
    attributes = mapOf(
        "amount" to "100.00",
        "gateway" to "stripe"
    )
)
```

#### Implementation Details

**Location**: `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt:97-122`

**Character Limit**: 100 characters (enforced by backend)

### 3. Product Context (Already Implemented in Phase 2C)

Product/module identification for better error attribution.

```kotlin
// Set product context (max 255 chars)
TelemetryManager.getInstance().setProductContext("authentication_module")
```

## Usage Examples

### Example 1: Authentication Error

```kotlin
class LoginActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set product context for this module
        TelemetryManager.getInstance().setProductContext("authentication_module")
        
        loginButton.setOnClickListener {
            // Track user action
            TelemetryManager.getInstance().setLastUserAction("Clicked login button")
            
            try {
                performLogin()
            } catch (e: Exception) {
                // Track error with enhanced context
                TelemetryManager.getInstance().trackError(
                    error = e,
                    errorCode = "AUTH_001",
                    attributes = mapOf(
                        "screen" to "LoginActivity",
                        "method" to "performLogin"
                    )
                )
            }
        }
    }
}
```

### Example 2: Payment Processing Error

```kotlin
class PaymentProcessor {
    
    fun processPayment(amount: Double, currency: String) {
        // Set context
        TelemetryManager.getInstance().setProductContext("payment_module")
        TelemetryManager.getInstance().setLastUserAction("Initiated payment transaction")
        
        try {
            val result = paymentGateway.charge(amount, currency)
            
            if (!result.isSuccess) {
                // Track error with specific error code
                TelemetryManager.getInstance().trackError(
                    error = PaymentException(result.errorMessage),
                    errorCode = "PAY_GATEWAY_${result.errorCode}",
                    attributes = mapOf(
                        "amount" to amount.toString(),
                        "currency" to currency,
                        "gateway" to "stripe",
                        "transaction_id" to result.transactionId
                    )
                )
            }
        } catch (e: TimeoutException) {
            TelemetryManager.getInstance().trackError(
                error = e,
                errorCode = "PAY_TIMEOUT_001",
                attributes = mapOf(
                    "amount" to amount.toString(),
                    "currency" to currency
                )
            )
        }
    }
}
```

### Example 3: Context Persistence

```kotlin
class ShoppingCartActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set context once - persists for all errors in this module
        TelemetryManager.getInstance().setProductContext("shopping_cart")
        
        addToCartButton.setOnClickListener {
            TelemetryManager.getInstance().setLastUserAction("Added item to cart")
            try {
                addItemToCart(selectedItem)
            } catch (e: Exception) {
                // Uses stored context automatically
                TelemetryManager.getInstance().trackError(
                    error = e,
                    errorCode = "CART_ADD_FAILED"
                )
            }
        }
        
        checkoutButton.setOnClickListener {
            TelemetryManager.getInstance().setLastUserAction("Clicked checkout button")
            try {
                proceedToCheckout()
            } catch (e: Exception) {
                // Uses stored context automatically
                TelemetryManager.getInstance().trackError(
                    error = e,
                    errorCode = "CART_CHECKOUT_FAILED"
                )
            }
        }
    }
}
```

### Example 4: Overriding Stored Context

```kotlin
// Set default context
TelemetryManager.getInstance().setProductContext("module_a")
TelemetryManager.getInstance().setLastUserAction("Action A")

// Track error - uses stored context
TelemetryManager.getInstance().trackError(
    error = exception1,
    errorCode = "ERR_A"
)

// Track error with explicit context - overrides stored values
TelemetryManager.getInstance().trackError(
    error = exception2,
    errorCode = "ERR_B",
    productId = "module_b",  // Overrides stored "module_a"
    userAction = "Action B"   // Overrides stored "Action A"
)
```

## Payload Structure

Crash events now include the enhanced context fields:

```json
{
  "data": {
    "tenant_id": "uuid",
    "location": "Kenya",
    "timestamp": "2024-03-18T13:30:00.000Z",
    "batch_size": 1,
    "events": [
      {
        "type": "event",
        "eventName": "app.crash",
        "timestamp": "2024-03-18T13:30:00.000Z",
        "attributes": {
          "message": "NullPointerException: User object was null",
          "stacktrace": "at com.example.LoginActivity.onButtonClick...",
          "exception_type": "NullPointerException",
          "error_context": "LoginActivity.onButtonClick",
          "product_id": "authentication_module",
          "cause": "User object was null",
          "is_fatal": true,
          "user_action": "Clicked login button",
          "error_code": "AUTH_001"
        }
      }
    ]
  }
}
```

## Character Limits

All fields enforce character limits as per backend requirements:

| Field | Max Length | Enforcement |
|-------|-----------|-------------|
| `error_code` | 100 chars | Backend truncation |
| `user_action` | 500 chars | SDK truncation (`.take(500)`) |
| `product_id` | 255 chars | SDK truncation (`.take(255)`) |
| `message` | 1000 chars | Payload factory |
| `stacktrace` | 2000 chars | Payload factory |

## Testing

Comprehensive test suite created: `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/test/java/com/androidtel/telemetry_library/core/crash/Phase2cEnhancedContextTest.kt`

### Test Coverage

- ✅ `setProductContext` with character limit enforcement
- ✅ `setLastUserAction` with character limit enforcement
- ✅ `trackError` with `error_code` parameter
- ✅ `trackError` with all enhanced context parameters
- ✅ Context persistence across multiple errors
- ✅ Explicit parameters override stored context
- ✅ Character limit enforcement for all fields
- ✅ Null parameter handling
- ✅ Empty string parameters
- ✅ Special characters in parameters
- ✅ Unicode characters in parameters
- ✅ Realistic authentication error scenario
- ✅ Realistic payment error scenario
- ✅ TelemetryManager API exposure

### Running Tests

```bash
./gradlew test --tests Phase2cEnhancedContextTest
```

## API Reference

### TelemetryManager Methods

```kotlin
/**
 * Set product context for crash reporting
 * @param productId Product/module identifier (max 255 chars)
 */
fun setProductContext(productId: String)

/**
 * Set last user action for crash context
 * @param action Description of user action (max 500 chars)
 */
fun setLastUserAction(action: String)

/**
 * Track error with enhanced context
 * @param error The throwable to track
 * @param errorCode Optional app-specific error code (max 100 chars)
 * @param productId Optional product/module identifier (max 255 chars)
 * @param userAction Optional last user action (max 500 chars)
 * @param attributes Optional additional attributes
 */
fun trackError(
    error: Throwable,
    errorCode: String? = null,
    productId: String? = null,
    userAction: String? = null,
    attributes: Map<String, String>? = null
)
```

## Best Practices

### 1. Set Context Early

```kotlin
// Set context in onCreate() or initialization
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    TelemetryManager.getInstance().setProductContext("authentication_module")
}
```

### 2. Update User Action Before Critical Operations

```kotlin
button.setOnClickListener {
    // Update action before operation
    TelemetryManager.getInstance().setLastUserAction("Clicked submit button")
    performCriticalOperation()
}
```

### 3. Use Meaningful Error Codes

```kotlin
// Good: Specific, categorized error codes
errorCode = "AUTH_LOGIN_FAILED"
errorCode = "PAY_GATEWAY_TIMEOUT"
errorCode = "DB_CONNECTION_LOST"

// Avoid: Generic error codes
errorCode = "ERROR_001"
errorCode = "FAILED"
```

### 4. Include Relevant Attributes

```kotlin
TelemetryManager.getInstance().trackError(
    error = exception,
    errorCode = "PAY_DECLINED",
    attributes = mapOf(
        "amount" to amount.toString(),
        "currency" to currency,
        "card_type" to cardType,
        "decline_reason" to declineReason
    )
)
```

## Migration from Phase 2B

No breaking changes. Phase 2C is fully backward compatible:

```kotlin
// Old API (still works)
TelemetryManager.getInstance().trackError(
    error = exception,
    attributes = mapOf("custom" to "value")
)

// New API (enhanced)
TelemetryManager.getInstance().trackError(
    error = exception,
    errorCode = "ERR_001",
    productId = "module_name",
    userAction = "User action",
    attributes = mapOf("custom" to "value")
)
```

## Implementation Files

### Core Implementation
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt:44-47` - Context storage
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt:97-122` - Enhanced trackError method
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/crash/CrashReporter.kt:265-276` - Context setters

### TelemetryManager Integration
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt:1173-1185` - Enhanced trackError
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/main/java/com/androidtel/telemetry_library/core/TelemetryManager.kt:1204-1224` - Context setters

### Tests
- `@/Users/mktowett/Development/StudioProjects/edge_telemetry_android/telemetry_library/src/test/java/com/androidtel/telemetry_library/core/crash/Phase2cEnhancedContextTest.kt` - Comprehensive test suite

## Success Criteria

- ✅ `user_action` tracking implemented with 500 char limit
- ✅ `error_code` support implemented with 100 char limit
- ✅ `product_id` tracking implemented with 255 char limit (from Phase 2C plan)
- ✅ Context persistence across errors
- ✅ Explicit parameters override stored context
- ✅ TelemetryManager API exposure
- ✅ Comprehensive test coverage
- ✅ Documentation and examples
- ✅ Backward compatibility maintained

## Next Steps

Phase 2C is complete. The SDK now supports:
1. ✅ Enhanced crash event structure (Phase 2A)
2. ✅ Field mapping & truncation (Phase 2B)
3. ✅ Enhanced context collection (Phase 2C)

Ready to proceed with:
- Phase 2D: Remove redundant fields (optional)
- Phase 3: Testing & validation
- Phase 4: Migration & rollout
