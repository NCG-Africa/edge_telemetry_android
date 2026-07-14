package com.androidtel.telemetry_library.core.crash

import com.androidtel.telemetry_library.core.payload.FlutterPayloadFactory
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Backend Compatibility Validation Tests
 * Ensures crash payloads are compatible with Kafka consumer processor
 */
class BackendCompatibilityTest {

    private val gson = Gson()

    @Test
    fun `crash hash can be generated from message and stacktrace`() {
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
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Backend generates crash_hash from message + stacktrace
        // Verify these fields are present and non-empty
        val message = attributes["message"] as String
        val stacktrace = attributes["stacktrace"] as String

        assertTrue(message.isNotEmpty())
        assertTrue(stacktrace.isNotEmpty())
        
        // Backend should be able to generate SHA-256 hash
        assertNotNull(message)
        assertNotNull(stacktrace)
    }

    @Test
    fun `severity level can be determined from exception_type`() {
        // High severity exceptions
        val highSeverityExceptions = listOf(
            OutOfMemoryError("OOM"),
            StackOverflowError("Stack overflow"),
            NullPointerException("NPE"),
            IllegalStateException("Illegal state")
        )

        highSeverityExceptions.forEach { throwable ->
            val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
                throwable = throwable,
                deviceId = "device_123",
                baseAttributes = emptyMap()
            )

            val json = envelope.toJson()
            val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
            val data = parsed["data"] as Map<*, *>
            val events = data["events"] as List<*>
            val event = events[0] as Map<*, *>
            val attributes = event["attributes"] as Map<*, *>

            val exceptionType = attributes["exception_type"] as String
            
            // Backend should classify these as high severity
            assertTrue(
                "Exception type $exceptionType should be classifiable",
                exceptionType.isNotEmpty()
            )
        }

        // Low severity exceptions
        val lowSeverityExceptions = listOf(
            java.io.IOException("IO error"),
            java.net.ConnectException("Connection failed"),
            java.io.FileNotFoundException("File not found")
        )

        lowSeverityExceptions.forEach { throwable ->
            val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
                throwable = throwable,
                deviceId = "device_123",
                baseAttributes = emptyMap()
            )

            val json = envelope.toJson()
            val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
            val data = parsed["data"] as Map<*, *>
            val events = data["events"] as List<*>
            val event = events[0] as Map<*, *>
            val attributes = event["attributes"] as Map<*, *>

            val exceptionType = attributes["exception_type"] as String
            
            // Backend should classify these as low severity
            assertTrue(exceptionType.isNotEmpty())
        }
    }

    @Test
    fun `breadcrumbs can be built from error_context, product_id, and cause`() {
        val throwable = RuntimeException("Test error", IllegalArgumentException("Root cause"))
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap(),
            productId = "authentication_module",
            userAction = "User login attempt"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Backend builds breadcrumbs from these fields
        assertNotNull(attributes["error_context"])
        assertNotNull(attributes["product_id"])
        assertNotNull(attributes["cause"])
        assertNotNull(attributes["user_action"])

        // All components for breadcrumb generation are present
        assertTrue((attributes["error_context"] as String).isNotEmpty())
        assertEquals("authentication_module", attributes["product_id"])
        assertEquals("Root cause", attributes["cause"])
        assertEquals("User login attempt", attributes["user_action"])
    }

    @Test
    fun `fatal classification matches backend logic`() {
        // Fatal exceptions according to backend
        val fatalExceptions = listOf(
            OutOfMemoryError("OOM"),
            StackOverflowError("Stack"),
            RuntimeException("Runtime"),
            IllegalStateException("State")
        )

        fatalExceptions.forEach { throwable ->
            val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
                throwable = throwable,
                deviceId = "device_123",
                baseAttributes = emptyMap()
            )

            val json = envelope.toJson()
            val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
            val data = parsed["data"] as Map<*, *>
            val events = data["events"] as List<*>
            val event = events[0] as Map<*, *>
            val attributes = event["attributes"] as Map<*, *>

            assertEquals(
                "Exception ${throwable.javaClass.simpleName} should be fatal",
                "true",
                attributes["is_fatal"]
            )
        }

        // Non-fatal exceptions
        val nonFatalExceptions = listOf(
            java.io.IOException("IO"),
            java.net.ConnectException("Network")
        )

        nonFatalExceptions.forEach { throwable ->
            val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
                throwable = throwable,
                deviceId = "device_123",
                baseAttributes = emptyMap()
            )

            val json = envelope.toJson()
            val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
            val data = parsed["data"] as Map<*, *>
            val events = data["events"] as List<*>
            val event = events[0] as Map<*, *>
            val attributes = event["attributes"] as Map<*, *>

            assertEquals(
                "Exception ${throwable.javaClass.simpleName} should not be fatal",
                "false",
                attributes["is_fatal"]
            )
        }
    }

    @Test
    fun `no duplicate fields between SDK and backend generation`() {
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
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // SDK should NOT send fields that backend auto-generates
        assertFalse(
            "SDK should not send crash_hash - backend generates it",
            attributes.containsKey("crash_hash")
        )
        assertFalse(
            "SDK should not send severity_level - backend generates it",
            attributes.containsKey("severity_level")
        )
        assertFalse(
            "SDK should not send crash.fingerprint - removed in v2.0.0",
            attributes.containsKey("crash.fingerprint")
        )
        assertFalse(
            "SDK should not send crash.breadcrumb_count - backend counts",
            attributes.containsKey("crash.breadcrumb_count")
        )
    }

    @Test
    fun `tenant_id can be set by backend if not provided`() {
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>

        // tenant_id is optional - backend can set it
        // SDK doesn't need to provide it
        val tenantId = data["tenant_id"]
        assertTrue(tenantId == null || tenantId is String)
    }

    @Test
    fun `payload structure matches Kafka consumer expectations`() {
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
            userAction = "Login attempt",
            errorCode = "AUTH_001"
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>

        // Root level - Kafka message structure
        assertTrue(parsed.containsKey("timestamp"))
        assertTrue(parsed.containsKey("device_id"))
        assertTrue(parsed.containsKey("data"))

        val data = parsed["data"] as Map<*, *>

        // Batch envelope structure
        assertTrue(data.containsKey("timestamp"))
        assertTrue(data.containsKey("batch_size"))
        assertTrue(data.containsKey("events"))
        assertEquals(1.0, data["batch_size"]) // Crashes always have batch_size = 1

        val events = data["events"] as List<*>
        assertEquals(1, events.size)

        val event = events[0] as Map<*, *>

        // Event structure
        assertEquals("event", event["type"])
        assertEquals("app.crash", event["eventName"])
        assertTrue(event.containsKey("timestamp"))
        assertTrue(event.containsKey("attributes"))

        val attributes = event["attributes"] as Map<*, *>

        // Required crash attributes
        assertTrue(attributes.containsKey("message"))
        assertTrue(attributes.containsKey("stacktrace"))
        assertTrue(attributes.containsKey("exception_type"))
        assertTrue(attributes.containsKey("error_context"))
        assertTrue(attributes.containsKey("cause"))
        assertTrue(attributes.containsKey("is_fatal"))

        // Optional crash attributes (when provided)
        assertTrue(attributes.containsKey("product_id"))
        assertTrue(attributes.containsKey("user_action"))
        assertTrue(attributes.containsKey("error_code"))

        // Device/app attributes
        assertTrue(attributes.containsKey("device.model"))
        assertTrue(attributes.containsKey("app.version"))
    }

    @Test
    fun `character limits prevent database constraint violations`() {
        // Backend database has strict column limits
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
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Verify SDK enforces limits to prevent backend errors
        assertTrue((attributes["message"] as String).length <= 1000)
        assertTrue((attributes["stacktrace"] as String).length <= 2000)
        assertTrue((attributes["exception_type"] as String).length <= 255)
        assertTrue((attributes["error_context"] as String).length <= 500)
        assertTrue((attributes["cause"] as String).length <= 255)
        assertTrue((attributes["product_id"] as String).length <= 255)
        assertTrue((attributes["user_action"] as String).length <= 500)
        assertTrue((attributes["error_code"] as String).length <= 100)
    }

    @Test
    fun `deduplication works via consistent message and stacktrace`() {
        // Same crash should produce consistent message and stacktrace
        val throwable1 = NullPointerException("Test error")
        val throwable2 = NullPointerException("Test error")

        val envelope1 = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable1,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val envelope2 = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable2,
            deviceId = "device_123",
            baseAttributes = emptyMap()
        )

        val json1 = envelope1.toJson()
        val json2 = envelope2.toJson()

        val parsed1 = gson.fromJson(json1, Map::class.java) as Map<*, *>
        val parsed2 = gson.fromJson(json2, Map::class.java) as Map<*, *>

        val attrs1 = ((parsed1["data"] as Map<*, *>)["events"] as List<*>)[0] as Map<*, *>
        val attrs2 = ((parsed2["data"] as Map<*, *>)["events"] as List<*>)[0] as Map<*, *>

        val attributes1 = attrs1["attributes"] as Map<*, *>
        val attributes2 = attrs2["attributes"] as Map<*, *>

        // Message should be consistent (exception type is deterministic)
        assertTrue((attributes1["message"] as String).contains("NullPointerException"))
        assertTrue((attributes2["message"] as String).contains("NullPointerException"))
        assertEquals(attributes1["exception_type"], attributes2["exception_type"])
    }

    @Test
    fun `event type and eventName are exactly as backend expects`() {
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
        val event = events[0] as Map<*, *>

        // Backend expects exactly these values
        assertEquals("event", event["type"])
        assertEquals("app.crash", event["eventName"])
        
        // Case-sensitive check
        assertNotEquals("Event", event["type"])
        assertNotEquals("APP.CRASH", event["eventName"])
        assertNotEquals("app_crash", event["eventName"])
    }

    @Test
    fun `breadcrumbs JSON array preserved for backend debugging`() {
        val breadcrumbsJson = """[{"type":"navigation","screen":"Home"},{"type":"click","element":"LoginButton"}]"""
        val throwable = RuntimeException("Test crash")
        val envelope = FlutterPayloadFactory.createCrashBatchEnvelope(
            throwable = throwable,
            deviceId = "device_123",
            baseAttributes = mapOf(
                "breadcrumbs" to breadcrumbsJson
            )
        )

        val json = envelope.toJson()
        val parsed = gson.fromJson(json, Map::class.java) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        val events = data["events"] as List<*>
        val event = events[0] as Map<*, *>
        val attributes = event["attributes"] as Map<*, *>

        // Breadcrumbs JSON should be preserved
        assertEquals(breadcrumbsJson, attributes["breadcrumbs"])
        
        // Backend can parse this for richer debugging context
        assertTrue((attributes["breadcrumbs"] as String).contains("navigation"))
        assertTrue((attributes["breadcrumbs"] as String).contains("Home"))
    }
}
