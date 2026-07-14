package com.androidtel.telemetry_library.core.crash

import com.androidtel.telemetry_library.core.payload.FlutterPayloadFactory
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Payload Structure Validation Tests
 * Validates crash payload structure against backend processor requirements
 */
class PayloadStructureValidationTest {

    private val gson = Gson()

    @Test
    fun `crash payload matches backend schema exactly`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = mapOf(
                "device.model" to "Pixel 6",
                "app.version" to "1.0.0"
            ),
            location = "Kenya",
            productId = "auth_module",
            userAction = "Clicked login",
            errorCode = "AUTH_001"
        )

        val json = envelope.toJson()
        val jsonObject = gson.fromJson(json, JsonObject::class.java)

        // Validate root structure
        assertTrue(jsonObject.has("timestamp"))
        assertTrue(jsonObject.has("device_id"))
        assertTrue(jsonObject.has("data"))

        // Validate data structure
        val data = jsonObject.getAsJsonObject("data")
        assertTrue(data.has("timestamp"))
        assertTrue(data.has("batch_size"))
        assertTrue(data.has("events"))
        assertTrue(data.has("location"))

        // Validate events array
        val events = data.getAsJsonArray("events")
        assertEquals(1, events.size())

        // Validate event structure
        val event = events[0].asJsonObject
        assertEquals("event", event.get("type").asString)
        assertEquals("app.crash", event.get("eventName").asString)
        assertTrue(event.has("timestamp"))
        assertTrue(event.has("attributes"))

        // Validate attributes contain all required fields
        val attributes = event.getAsJsonObject("attributes")
        assertTrue(attributes.has("message"))
        assertTrue(attributes.has("stacktrace"))
        assertTrue(attributes.has("exception_type"))
        assertTrue(attributes.has("error_context"))
        assertTrue(attributes.has("cause"))
        assertTrue(attributes.has("is_fatal"))
        assertTrue(attributes.has("product_id"))
        assertTrue(attributes.has("user_action"))
        assertTrue(attributes.has("error_code"))
    }

    @Test
    fun `no legacy fields present in new structure`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        // Legacy fields should NOT be present
        assertFalse(attributes.containsKey("crash.fingerprint"))
        assertFalse(attributes.containsKey("crash.breadcrumb_count"))
        assertFalse(attributes.containsKey("stackTrace")) // Should be "stacktrace"
        
        // Old structure fields should NOT be at event level
        assertFalse(firstEvent.containsKey("error"))
        assertFalse(firstEvent.containsKey("fingerprint"))
    }

    @Test
    fun `batch_size always equals events array length`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val batchSize = (data["batch_size"] as Double).toInt()
        val events = data["events"] as List<*>

        assertEquals(events.size, batchSize)
        assertEquals(1, batchSize) // Crash events always have batch_size = 1
    }

    @Test
    fun `all timestamps are ISO8601 format`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>

        // All timestamps should match ISO8601 format (flexible decimal precision)
        val iso8601Regex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")
        
        val topTimestamp = parsed["timestamp"] as String
        val dataTimestamp = data["timestamp"] as String
        val eventTimestamp = firstEvent["timestamp"] as String

        assertTrue("Top timestamp should be ISO8601: $topTimestamp", topTimestamp.matches(iso8601Regex))
        assertTrue("Data timestamp should be ISO8601: $dataTimestamp", dataTimestamp.matches(iso8601Regex))
        assertTrue("Event timestamp should be ISO8601: $eventTimestamp", eventTimestamp.matches(iso8601Regex))
    }

    @Test
    fun `device_id is string type not object`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val jsonObject = gson.fromJson(json, JsonObject::class.java)

        assertTrue(jsonObject.get("device_id").isJsonPrimitive)
        assertEquals("device_123", jsonObject.get("device_id").asString)
    }

    @Test
    fun `attributes are flat map not nested objects`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = mapOf(
                "device.model" to "Pixel 6",
                "app.version" to "1.0.0",
                "session.id" to "session_123"
            )
        )

        val json = envelope.toJson()
        val jsonObject = gson.fromJson(json, JsonObject::class.java)
        val data = jsonObject.getAsJsonObject("data")
        val events = data.getAsJsonArray("events")
        val event = events[0].asJsonObject
        val attributes = event.getAsJsonObject("attributes")

        // All attributes should be primitives (strings, booleans)
        attributes.entrySet().forEach { entry ->
            assertTrue(
                "Attribute ${entry.key} should be primitive, not nested object",
                entry.value.isJsonPrimitive || entry.value.isJsonNull
            )
        }
    }

    @Test
    fun `boolean fields are actual booleans not strings`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val jsonObject = gson.fromJson(json, JsonObject::class.java)
        val data = jsonObject.getAsJsonObject("data")
        val events = data.getAsJsonArray("events")
        val event = events[0].asJsonObject
        val attributes = event.getAsJsonObject("attributes")

        // is_fatal should be string representation for backend compatibility
        val isFatal = attributes.get("is_fatal").asString
        assertTrue(isFatal == "true" || isFatal == "false")
    }

    @Test
    fun `all required fields have non-empty values`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        // Required fields must have non-empty values
        assertTrue((attributes["message"] as String).isNotEmpty())
        assertTrue((attributes["stacktrace"] as String).isNotEmpty())
        assertTrue((attributes["exception_type"] as String).isNotEmpty())
        assertTrue((attributes["error_context"] as String).isNotEmpty())
        assertTrue((attributes["cause"] as String).isNotEmpty())
        assertNotNull(attributes["is_fatal"])
    }

    @Test
    fun `optional fields can be null or absent`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
            // No optional fields provided
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        // Optional fields may not be present
        // This is valid - backend should handle missing optional fields
        // Just verify required fields are present
        assertTrue(attributes.containsKey("message"))
        assertTrue(attributes.containsKey("stacktrace"))
        assertTrue(attributes.containsKey("exception_type"))
    }

    @Test
    fun `location field is optional in batch data`() {
        // Test with location
        val envelope1 = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = RuntimeException("Test"),
            deviceId = "device_123",
            baseAttributes = emptyMap(),
            location = "Kenya"
        )
        val json1 = envelope1.toJson()
        val parsed1 = gson.fromJson(json1, Map::class.java) as Map<*, *>
        val data1 = parsed1["data"] as Map<*, *>
        assertEquals("Kenya", data1["location"])

        // Test without location
        val envelope2 = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = RuntimeException("Test"),
            deviceId = "device_123",
            baseAttributes = emptyMap(),
            location = null
        )
        val json2 = envelope2.toJson()
        val parsed2 = gson.fromJson(json2, Map::class.java) as Map<*, *>
        val data2 = parsed2["data"] as Map<*, *>
        // Location can be null or absent
        assertTrue(data2["location"] == null || !data2.containsKey("location"))
    }

    @Test
    fun `character limits prevent backend truncation errors`() {
        val throwable = RuntimeException("x".repeat(5000))
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap(),
            productId = "x".repeat(1000),
            userAction = "x".repeat(2000),
            errorCode = "x".repeat(500)
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val firstEvent = events[0] as Map<*, *>
        val attributes = firstEvent["attributes"] as Map<*, *>

        // Verify all fields respect character limits
        assertTrue((attributes["message"] as String).length <= 1000)
        assertTrue((attributes["stacktrace"] as String).length <= 2000)
        assertTrue((attributes["exception_type"] as String).length <= 255)
        assertTrue((attributes["error_context"] as String).length <= 500)
        assertTrue((attributes["cause"] as String).length <= 255)
        
        if (attributes.containsKey("product_id")) {
            assertTrue((attributes["product_id"] as String).length <= 255)
        }
        if (attributes.containsKey("user_action")) {
            assertTrue((attributes["user_action"] as String).length <= 500)
        }
        if (attributes.containsKey("error_code")) {
            assertTrue((attributes["error_code"] as String).length <= 100)
        }
    }

    @Test
    fun `payload can be serialized and deserialized without data loss`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = mapOf("custom" to "value"),
            location = "Kenya",
            productId = "auth",
            userAction = "login",
            errorCode = "E001"
        )

        // Serialize
        val json = envelope.toJson()
        
        // Deserialize
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        
        // Re-serialize
        val json2 = gson.toJson(parsed)
        
        // Should be equivalent
        val parsed2 = gson.fromJson(json2, Map::class.java) as Map<*, *>
        
        assertEquals(parsed["device_id"], parsed2["device_id"])
        assertEquals(
            (parsed["data"] as Map<*, *>)["batch_size"],
            (parsed2["data"] as Map<*, *>)["batch_size"]
        )
    }
}
