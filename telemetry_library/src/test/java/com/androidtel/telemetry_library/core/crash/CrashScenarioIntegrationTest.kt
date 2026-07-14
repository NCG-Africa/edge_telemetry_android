package com.androidtel.telemetry_library.core.crash

import com.androidtel.telemetry_library.core.payload.FlutterPayloadFactory
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Integration Tests for Crash Scenarios
 * Tests real-world crash scenarios and edge cases
 */
class CrashScenarioIntegrationTest {

    private val gson = Gson()

    @Test
    fun `uncaught exception scenario - NullPointerException`() {
        val throwable = NullPointerException("Attempted to access null object")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = mapOf(
                "device.model" to "Samsung Galaxy S21",
                "app.version" to "2.1.0",
                "session.id" to "session_456"
            ),
            productId = "user_profile",
            userAction = "Viewing profile screen"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Verify crash captured correctly
        assertEquals("event", event["type"])
        assertEquals("app.crash", event["eventName"])
        assertEquals("NullPointerException", attributes["exception_type"])
        assertTrue((attributes["message"] as String).contains("Attempted to access null object"))
        assertEquals("user_profile", attributes["product_id"])
        assertEquals("Viewing profile screen", attributes["user_action"])
        
        // NullPointerException should be high severity (backend will classify)
        // is_fatal should be false for NPE
        assertEquals("false", attributes["is_fatal"])
    }

    @Test
    fun `manual error tracking scenario - IOException`() {
        val throwable = IOException("Network connection failed")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_789",
            baseAttributes = mapOf(
                "network.type" to "WiFi",
                "network.connected" to "false"
            ),
            errorCode = "NET_001",
            userAction = "Syncing data"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        assertEquals("IOException", attributes["exception_type"])
        assertEquals("NET_001", attributes["error_code"])
        assertEquals("Syncing data", attributes["user_action"])
        assertEquals("false", attributes["is_fatal"]) // IOException is not fatal
    }

    @Test
    fun `fatal crash scenario - OutOfMemoryError`() {
        val throwable = OutOfMemoryError("Java heap space")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_999",
            baseAttributes = mapOf(
                "device.memory.available" to "50MB",
                "app.memory.used" to "512MB"
            ),
            productId = "image_processing"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        assertEquals("OutOfMemoryError", attributes["exception_type"])
        assertEquals("true", attributes["is_fatal"]) // OOM is fatal
        assertEquals("image_processing", attributes["product_id"])
    }

    @Test
    fun `crash with nested cause chain`() {
        val rootCause = IllegalArgumentException("Invalid user ID")
        val middleCause = IllegalStateException("User not found", rootCause)
        val topException = RuntimeException("Failed to load user profile", middleCause)

        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = topException,
            deviceId = "device_111",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Should capture immediate cause
        assertEquals("User not found", attributes["cause"])
        assertEquals("RuntimeException", attributes["exception_type"])
        assertTrue((attributes["message"] as String).contains("Failed to load user profile"))
    }

    @Test
    fun `crash with very long stack trace`() {
        val throwable = generateDeepStackTrace(100) // 100 levels deep
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_222",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Stack trace should be truncated to 2000 chars
        val stacktrace = attributes["stacktrace"] as String
        assertTrue(stacktrace.length <= 2000)
        assertTrue(stacktrace.isNotEmpty())
    }

    @Test
    fun `crash with special characters in message`() {
        val specialMessage = "Error: \"User's data\" couldn't be loaded\n\tReason: null\r\nCode: 404"
        val throwable = RuntimeException(specialMessage)
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_333",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Message should be properly escaped in JSON
        val message = attributes["message"] as String
        assertTrue(message.contains("User's data"))
        
        // JSON should be valid
        assertNotNull(json)
        assertTrue(json.contains("RuntimeException"))
    }

    @Test
    fun `crash with unicode characters`() {
        val unicodeMessage = "Error: 用户数据加载失败 🚨 Échec du chargement"
        val throwable = RuntimeException(unicodeMessage)
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_444",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        val message = attributes["message"] as String
        assertTrue(message.contains("用户数据加载失败"))
        assertTrue(message.contains("🚨"))
        assertTrue(message.contains("Échec"))
    }

    @Test
    fun `crash with empty message`() {
        val throwable = RuntimeException("")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_555",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Message should still contain exception type
        val message = attributes["message"] as String
        assertTrue(message.contains("RuntimeException"))
        assertTrue(message.isNotEmpty())
    }

    @Test
    fun `crash with null message`() {
        val throwable = RuntimeException(null as String?)
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_666",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Message should still contain exception type
        val message = attributes["message"] as String
        assertTrue(message.contains("RuntimeException"))
        assertTrue(message.isNotEmpty())
    }

    @Test
    fun `crash with all optional fields provided`() {
        val throwable = RuntimeException("Complete crash data")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_777",
            baseAttributes = mapOf(
                "device.model" to "iPhone 13",
                "app.version" to "3.0.0",
                "session.id" to "session_999",
                "user.id" to "user_123"
            ),
            location = "Kenya",
            productId = "checkout_flow",
            userAction = "Completing payment",
            errorCode = "PAY_500"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Verify all fields present
        assertEquals("Kenya", data["location"])
        assertEquals("checkout_flow", attributes["product_id"])
        assertEquals("Completing payment", attributes["user_action"])
        assertEquals("PAY_500", attributes["error_code"])
        assertEquals("iPhone 13", attributes["device.model"])
        assertEquals("3.0.0", attributes["app.version"])
        assertEquals("session_999", attributes["session.id"])
        assertEquals("user_123", attributes["user.id"])
    }

    @Test
    fun `crash with minimal data - only required fields`() {
        val throwable = RuntimeException("Minimal crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_888",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Only required fields should be present
        assertTrue(attributes.containsKey("message"))
        assertTrue(attributes.containsKey("stacktrace"))
        assertTrue(attributes.containsKey("exception_type"))
        assertTrue(attributes.containsKey("error_context"))
        assertTrue(attributes.containsKey("cause"))
        assertTrue(attributes.containsKey("is_fatal"))
    }

    @Test
    fun `multiple crashes maintain separate contexts`() {
        val crash1 = NullPointerException("First crash")
        val envelope1 = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = crash1,
            deviceId = "device_multi",
            baseAttributes = emptyMap(),
            productId = "module_a",
            errorCode = "ERR_A"
        )

        val crash2 = IllegalStateException("Second crash")
        val envelope2 = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = crash2,
            deviceId = "device_multi",
            baseAttributes = emptyMap(),
            productId = "module_b",
            errorCode = "ERR_B"
        )

        val json1 = envelope1.toJson()
        val json2 = envelope2.toJson()
        
        val parsed1 = gson.fromJson(json1, Map::class.java) as Map<*, *>
        val parsed2 = gson.fromJson(json2, Map::class.java) as Map<*, *>
        
        val attrs1 = ((parsed1["data"] as Map<*, *>)["events"] as List<*>)[0] as Map<*, *>
        val attrs2 = ((parsed2["data"] as Map<*, *>)["events"] as List<*>)[0] as Map<*, *>
        
        val attributes1 = attrs1["attributes"] as Map<*, *>
        val attributes2 = attrs2["attributes"] as Map<*, *>

        // Each crash should maintain its own context
        assertEquals("module_a", attributes1["product_id"])
        assertEquals("ERR_A", attributes1["error_code"])
        assertEquals("NullPointerException", attributes1["exception_type"])

        assertEquals("module_b", attributes2["product_id"])
        assertEquals("ERR_B", attributes2["error_code"])
        assertEquals("IllegalStateException", attributes2["exception_type"])
    }

    @Test
    fun `crash from message and stack trace string`() {
        val message = "Custom error message"
        val stackTrace = """
            at com.example.MyClass.myMethod(MyClass.kt:42)
            at com.example.MainActivity.onCreate(MainActivity.kt:123)
            at android.app.Activity.performCreate(Activity.java:7802)
        """.trimIndent()

        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            message = message,
            stackTrace = stackTrace,
            deviceId = "device_custom",
            baseAttributes = emptyMap(),
            errorCode = "CUSTOM_001"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        assertTrue((attributes["message"] as String).contains("Custom error message"))
        assertTrue((attributes["stacktrace"] as String).contains("MyClass.myMethod"))
        assertEquals("CUSTOM_001", attributes["error_code"])
        assertEquals("false", attributes["is_fatal"]) // Manual errors default to non-fatal
    }

    @Test
    fun `crash preserves breadcrumbs in attributes`() {
        val throwable = RuntimeException("Test crash")
        val breadcrumbs = """[{"type":"navigation","screen":"Home"},{"type":"click","button":"Login"}]"""
        
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_breadcrumb",
            baseAttributes = mapOf(
                "breadcrumbs" to breadcrumbs
            )
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        assertEquals(breadcrumbs, attributes["breadcrumbs"])
    }

    // Helper function to generate deep stack trace
    private fun generateDeepStackTrace(depth: Int): RuntimeException {
        return if (depth <= 0) {
            RuntimeException("Deep stack trace test")
        } else {
            try {
                throw generateDeepStackTrace(depth - 1)
            } catch (e: RuntimeException) {
                throw RuntimeException("Level $depth", e)
            }
        }
    }
}
