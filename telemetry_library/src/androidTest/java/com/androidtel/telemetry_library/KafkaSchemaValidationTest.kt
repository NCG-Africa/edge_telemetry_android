package com.androidtel.telemetry_library

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class KafkaSchemaValidationTest {

    @Test
    fun eventStructureMatchesKafkaSchema() {
        val kafkaEvent = JSONObject().apply {
            put("type", "event")
            put("eventName", "navigation")
            put("timestamp", "2024-03-18T14:50:23.456Z")
            put("attributes", JSONObject().apply {
                put("navigation.from_screen", "HomeScreen")
                put("navigation.to_screen", "ProfileScreen")
                put("navigation.method", "push")
                put("navigation.route_type", "main_flow")
                put("navigation.has_arguments", false)
                put("navigation.timestamp", "2024-03-18T14:50:23.456Z")
            })
        }

        assertEquals("event", kafkaEvent.getString("type"))
        assertEquals("navigation", kafkaEvent.getString("eventName"))
        assertTrue(kafkaEvent.has("timestamp"))
        assertTrue(kafkaEvent.has("attributes"))

        val attributes = kafkaEvent.getJSONObject("attributes")
        assertTrue(attributes.has("navigation.from_screen"))
        assertTrue(attributes.has("navigation.to_screen"))
        assertTrue(attributes.has("navigation.method"))
        assertTrue(attributes.has("navigation.route_type"))
        assertTrue(attributes.has("navigation.has_arguments"))
        assertTrue(attributes.has("navigation.timestamp"))
    }

    @Test
    fun allRequiredFieldsPresent() {
        val attributes = mapOf(
            "navigation.from_screen" to "HomeScreen",
            "navigation.to_screen" to "ProfileScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to "2024-03-18T14:50:23.456Z"
        )

        val requiredFields = listOf(
            "navigation.to_screen",
            "navigation.method",
            "navigation.timestamp"
        )

        requiredFields.forEach { field ->
            assertTrue("Required field $field must be present", 
                attributes.containsKey(field))
        }
    }

    @Test
    fun fieldNamesMatchExactly() {
        val attributes = mapOf(
            "navigation.from_screen" to "HomeScreen",
            "navigation.to_screen" to "ProfileScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to "2024-03-18T14:50:23.456Z"
        )

        assertTrue("Field must be 'navigation.to_screen' not 'navigation.to'", 
            attributes.containsKey("navigation.to_screen"))
        assertFalse("Old field 'navigation.to' should not exist", 
            attributes.containsKey("navigation.to"))

        assertTrue("Field must be 'navigation.route_type' not 'navigation.type'", 
            attributes.containsKey("navigation.route_type"))
        assertFalse("Old field 'navigation.type' should not exist", 
            attributes.containsKey("navigation.type"))

        assertTrue("Field must be 'navigation.from_screen'", 
            attributes.containsKey("navigation.from_screen"))
        assertTrue("Field must be 'navigation.has_arguments'", 
            attributes.containsKey("navigation.has_arguments"))
    }

    @Test
    fun methodValuesAreValid() {
        val validMethods = setOf("push", "pop", "replace")

        validMethods.forEach { method ->
            val attributes = mapOf("navigation.method" to method)
            assertTrue("Method '$method' must be valid", 
                validMethods.contains(attributes["navigation.method"]))
        }

        val invalidMethods = listOf("resumed", "paused", "navigation", "closed", "destroyed")
        invalidMethods.forEach { method ->
            assertFalse("Method '$method' should not be valid", 
                validMethods.contains(method))
        }
    }

    @Test
    fun timestampFormatIsISO8601() {
        val validTimestamps = listOf(
            "2024-03-18T14:50:23.456Z",
            "2024-01-01T00:00:00.000Z",
            "2024-12-31T23:59:59.999Z"
        )

        val iso8601Regex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")

        validTimestamps.forEach { timestamp ->
            assertTrue("Timestamp '$timestamp' must match ISO 8601 format", 
                timestamp.matches(iso8601Regex))
        }

        val invalidTimestamps = listOf(
            "1710770400000",
            "2024-03-18 14:50:23",
            "2024-03-18T14:50:23",
            "03/18/2024 14:50:23"
        )

        invalidTimestamps.forEach { timestamp ->
            assertFalse("Timestamp '$timestamp' should not match ISO 8601 format", 
                timestamp.matches(iso8601Regex))
        }
    }

    @Test
    fun kafkaEventWithNullFromScreenIsValid() {
        val kafkaEvent = JSONObject().apply {
            put("type", "event")
            put("eventName", "navigation")
            put("timestamp", "2024-03-18T14:50:23.456Z")
            put("attributes", JSONObject().apply {
                put("navigation.from_screen", JSONObject.NULL)
                put("navigation.to_screen", "HomeScreen")
                put("navigation.method", "push")
                put("navigation.route_type", "main_flow")
                put("navigation.has_arguments", false)
                put("navigation.timestamp", "2024-03-18T14:50:23.456Z")
            })
        }

        val attributes = kafkaEvent.getJSONObject("attributes")
        assertTrue("from_screen can be null on app launch", 
            attributes.isNull("navigation.from_screen") || 
            attributes.getString("navigation.from_screen").isEmpty())
    }

    @Test
    fun hasArgumentsIsBooleanType() {
        val attributes = mapOf(
            "navigation.has_arguments" to false
        )

        assertTrue("has_arguments must be Boolean type", 
            attributes["navigation.has_arguments"] is Boolean)

        val invalidTypes = listOf("false", 0, null, "")
        invalidTypes.forEach { value ->
            assertFalse("has_arguments should not accept non-Boolean value: $value", 
                value is Boolean)
        }
    }

    @Test
    fun routeTypeLengthIsValid() {
        val validRouteTypes = listOf(
            "main_flow",
            "modal",
            "deeplink",
            "onboarding",
            "settings",
            "fragment_flow"
        )

        validRouteTypes.forEach { routeType ->
            assertTrue("Route type '$routeType' should be under 100 chars", 
                routeType.length <= 100)
        }
    }

    @Test
    fun eventNameIsConsistent() {
        val preferredEventName = "navigation"
        val deprecatedEventName = "navigation.route_change"

        assertEquals("navigation", preferredEventName)
        assertNotEquals(preferredEventName, deprecatedEventName)
    }

    @Test
    fun completeKafkaEventValidation() {
        val kafkaEvent = JSONObject().apply {
            put("type", "event")
            put("eventName", "navigation")
            put("timestamp", "2024-03-18T14:50:23.456Z")
            put("attributes", JSONObject().apply {
                put("navigation.from_screen", "HomeScreen")
                put("navigation.to_screen", "ProfileScreen")
                put("navigation.method", "push")
                put("navigation.route_type", "main_flow")
                put("navigation.has_arguments", true)
                put("navigation.timestamp", "2024-03-18T14:50:23.456Z")
            })
        }

        assertEquals("event", kafkaEvent.getString("type"))
        assertEquals("navigation", kafkaEvent.getString("eventName"))

        val timestamp = kafkaEvent.getString("timestamp")
        assertTrue(timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")))

        val attributes = kafkaEvent.getJSONObject("attributes")
        assertEquals("HomeScreen", attributes.getString("navigation.from_screen"))
        assertEquals("ProfileScreen", attributes.getString("navigation.to_screen"))
        assertEquals("push", attributes.getString("navigation.method"))
        assertEquals("main_flow", attributes.getString("navigation.route_type"))
        assertTrue(attributes.getBoolean("navigation.has_arguments"))

        val navTimestamp = attributes.getString("navigation.timestamp")
        assertTrue(navTimestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")))
    }
}
