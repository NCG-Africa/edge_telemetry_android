package com.androidtel.telemetry_library.core.crash

import com.androidtel.telemetry_library.core.payload.FlutterPayloadFactory
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for crash batch envelope structure (v2.0.0)
 * Tests implementation requirements for batch envelope
 */
class CrashBatchEnvelopeTest {

    private val gson = Gson()

    @Test
    fun `crash event has correct type and eventName`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>

        assertEquals("event", firstEvent["type"])
        assertEquals("app.crash", firstEvent["eventName"])
    }

    @Test
    fun `crash wrapped in batch envelope structure`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>

        // Check top-level structure
        assertTrue(parsed.containsKey("timestamp"))
        assertTrue(parsed.containsKey("device_id"))
        assertTrue(parsed.containsKey("data"))

        // Check data structure
        val data = parsed["data"] as Map<*, *>
        assertTrue(data.containsKey("timestamp"))
        assertTrue(data.containsKey("batch_size"))
        assertTrue(data.containsKey("events"))
        assertEquals(1.0, data["batch_size"])
    }

    @Test
    fun `all required crash fields present`() {
        val throwable = RuntimeException("Test error message")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        // Required fields
        assertTrue(attributes.containsKey("message"))
        assertTrue(attributes.containsKey("stacktrace"))
        assertTrue(attributes.containsKey("exception_type"))
        assertTrue(attributes.containsKey("error_context"))
        assertTrue(attributes.containsKey("cause"))
        assertTrue(attributes.containsKey("is_fatal"))
    }

    @Test
    fun `field naming matches backend - stacktrace lowercase`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        // Should be "stacktrace" not "stackTrace"
        assertTrue(attributes.containsKey("stacktrace"))
        assertFalse(attributes.containsKey("stackTrace"))
    }

    @Test
    fun `message character limit enforced - 1000 chars`() {
        val longMessage = "x".repeat(2000)
        val throwable = RuntimeException(longMessage)
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>
        val message = attributes["message"] as String

        assertTrue(message.length <= 1000)
    }

    @Test
    fun `stacktrace character limit enforced - 2000 chars`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>
        val stacktrace = attributes["stacktrace"] as String

        assertTrue(stacktrace.length <= 2000)
    }

    @Test
    fun `exception_type extracted correctly`() {
        val throwable = NullPointerException("Null value")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertEquals("NullPointerException", attributes["exception_type"])
    }

    @Test
    fun `is_fatal correctly determined for RuntimeException`() {
        val throwable = RuntimeException("Fatal error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertEquals("true", attributes["is_fatal"])
    }

    @Test
    fun `is_fatal correctly determined for IOException`() {
        val throwable = java.io.IOException("Network error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertEquals("false", attributes["is_fatal"])
    }

    @Test
    fun `optional fields included when provided`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap(),
            productId = "auth_module",
            userAction = "Clicked login button",
            errorCode = "AUTH_001"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertEquals("auth_module", attributes["product_id"])
        assertEquals("Clicked login button", attributes["user_action"])
        assertEquals("AUTH_001", attributes["error_code"])
    }

    @Test
    fun `optional field character limits enforced`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap(),
            productId = "x".repeat(500),
            userAction = "x".repeat(1000),
            errorCode = "x".repeat(200)
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertTrue((attributes["product_id"] as String).length <= 255)
        assertTrue((attributes["user_action"] as String).length <= 500)
        assertTrue((attributes["error_code"] as String).length <= 100)
    }

    @Test
    fun `base attributes merged into crash attributes`() {
        val throwable = RuntimeException("Test error")
        val baseAttributes = mapOf(
            "device.model" to "Pixel 6",
            "app.version" to "1.0.0",
            "session.id" to "session_123"
        )
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = baseAttributes
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertEquals("Pixel 6", attributes["device.model"])
        assertEquals("1.0.0", attributes["app.version"])
        assertEquals("session_123", attributes["session.id"])
    }

    @Test
    fun `location included in batch data when provided`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap(),
            location = "Kenya"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>

        assertEquals("Kenya", data["location"])
    }

    @Test
    fun `device_id at top level for routing`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>

        assertEquals("test_device_123", parsed["device_id"])
    }

    @Test
    fun `timestamp present at multiple levels`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>

        // Top level
        assertTrue(parsed.containsKey("timestamp"))
        // Batch data level
        assertTrue(data.containsKey("timestamp"))
        // Event level
        assertTrue(firstEvent.containsKey("timestamp"))
    }

    @Test
    fun `error_context extracted from stack trace`() {
        val throwable = RuntimeException("Test error")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>
        val errorContext = attributes["error_context"] as String

        // Should extract ClassName.methodName format
        assertNotNull(errorContext)
        assertTrue(errorContext.isNotEmpty())
    }

    @Test
    fun `cause extracted from throwable`() {
        val cause = IllegalArgumentException("Invalid argument")
        val throwable = RuntimeException("Test error", cause)
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        assertEquals("Invalid argument", attributes["cause"])
    }

    @Test
    fun `message format includes exception class and message`() {
        val throwable = NullPointerException("Object was null")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "test_device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>
        val message = attributes["message"] as String

        assertTrue(message.contains("NullPointerException"))
        assertTrue(message.contains("Object was null"))
    }
}
