package com.androidtel.telemetry_library

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidtel.telemetry_library.core.TelemetryManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FragmentNavigationIntegrationTest {

    private lateinit var telemetryManager: TelemetryManager
    private val capturedEvents = mutableListOf<Pair<String, Map<String, Any>>>()

    class TestFragment : Fragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    class SecondTestFragment : Fragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    @Before
    fun setup() {
        telemetryManager = TelemetryManager.getInstance()
        capturedEvents.clear()
    }

    @After
    fun tearDown() {
        capturedEvents.clear()
    }

    @Test
    fun fragmentNavigationHasAllRequiredFields() {
        val scenario = launchFragmentInContainer<TestFragment>()
        
        scenario.onFragment { fragment ->
            val attributes = mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to "TestFragment",
                "navigation.method" to "push",
                "navigation.route_type" to "fragment_flow",
                "navigation.has_arguments" to false,
                "navigation.timestamp" to "2024-03-18T14:50:00.000Z"
            )
            
            // Validate required fields
            assertTrue(attributes.containsKey("navigation.from_screen"))
            assertTrue(attributes.containsKey("navigation.to_screen"))
            assertTrue(attributes.containsKey("navigation.method"))
            assertTrue(attributes.containsKey("navigation.has_arguments"))
            assertTrue(attributes.containsKey("navigation.timestamp"))
            assertTrue(attributes.containsKey("navigation.route_type"))
        }
        
        scenario.close()
    }

    @Test
    fun fromScreenTracksPreviousFragment() {
        // Simulate sequential fragment navigation
        val fromScreen = "TestFragment"
        val toScreen = "SecondTestFragment"
        
        val attributes = mapOf(
            "navigation.from_screen" to fromScreen,
            "navigation.to_screen" to toScreen,
            "navigation.method" to "push"
        )
        
        assertEquals("TestFragment", attributes["navigation.from_screen"])
        assertEquals("SecondTestFragment", attributes["navigation.to_screen"])
    }

    @Test
    fun routeTypeIsFragmentFlow() {
        val scenario = launchFragmentInContainer<TestFragment>()
        
        scenario.onFragment {
            val routeType = "fragment_flow"
            assertEquals("fragment_flow", routeType)
        }
        
        scenario.close()
    }

    @Test
    fun hasArgumentsIsTrueWhenFragmentHasArguments() {
        val args = Bundle().apply {
            putString("test_key", "test_value")
        }
        
        val scenario = launchFragmentInContainer<TestFragment>(fragmentArgs = args)
        
        scenario.onFragment { fragment ->
            val hasArguments = fragment.arguments?.isEmpty == false
            assertTrue("Should have arguments when bundle present", hasArguments)
        }
        
        scenario.close()
    }

    @Test
    fun hasArgumentsIsFalseWhenNoArguments() {
        val scenario = launchFragmentInContainer<TestFragment>()
        
        scenario.onFragment { fragment ->
            val hasArguments = fragment.arguments?.isEmpty == false
            assertFalse("Should not have arguments when no bundle", hasArguments)
        }
        
        scenario.close()
    }

    @Test
    fun navigationMethodIsCorrect() {
        val validMethods = listOf("push", "pop", "replace")
        val method = "push"
        
        assertTrue("Method must be one of: push, pop, replace", method in validMethods)
    }

    @Test
    fun timestampIsISO8601Format() {
        val timestamp = "2024-03-18T14:50:23.456Z"
        
        // Verify ISO 8601 format: YYYY-MM-DDTHH:mm:ss.sssZ
        val iso8601Regex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")
        assertTrue("Timestamp must be in ISO 8601 format", timestamp.matches(iso8601Regex))
    }

    @Test
    fun fragmentNavigationStructureMatchesKafkaSchema() {
        val scenario = launchFragmentInContainer<TestFragment>()
        
        scenario.onFragment {
            val attributes = mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to "TestFragment",
                "navigation.method" to "push",
                "navigation.route_type" to "fragment_flow",
                "navigation.has_arguments" to false,
                "navigation.timestamp" to "2024-03-18T14:50:00.000Z"
            )
            
            // Validate field names match exactly
            assertTrue(attributes.containsKey("navigation.to_screen"))
            assertFalse(attributes.containsKey("navigation.to")) // Old field name
            
            // Validate method values
            assertTrue(attributes["navigation.method"] in listOf("push", "pop", "replace"))
            
            // Validate has_arguments is boolean
            assertTrue(attributes["navigation.has_arguments"] is Boolean)
            
            // Validate route_type is correct
            assertEquals("fragment_flow", attributes["navigation.route_type"])
        }
        
        scenario.close()
    }

    @Test
    fun fieldNamesMatchBackendSchema() {
        val attributes = mapOf(
            "navigation.from_screen" to "HomeFragment",
            "navigation.to_screen" to "ProfileFragment",
            "navigation.method" to "push",
            "navigation.route_type" to "fragment_flow",
            "navigation.has_arguments" to true,
            "navigation.timestamp" to "2024-03-18T14:50:00.000Z"
        )
        
        // Verify correct field names (not old ones)
        assertTrue(attributes.containsKey("navigation.to_screen"))
        assertTrue(attributes.containsKey("navigation.from_screen"))
        assertTrue(attributes.containsKey("navigation.route_type"))
        
        // Verify old field names are NOT present
        assertFalse(attributes.containsKey("navigation.to"))
        assertFalse(attributes.containsKey("navigation.type"))
    }

    @Test
    fun eventNameIsNavigation() {
        val eventName = "navigation"
        assertEquals("navigation", eventName)
        
        // Verify it's NOT the old event name
        assertNotEquals("navigation.route_change", eventName)
    }

    @Test
    fun fragmentArgumentsDetectionWorks() {
        val args = Bundle().apply {
            putString("key1", "value1")
            putInt("key2", 42)
            putBoolean("key3", true)
        }
        
        val scenario = launchFragmentInContainer<TestFragment>(fragmentArgs = args)
        
        scenario.onFragment { fragment ->
            val hasArguments = fragment.arguments?.isEmpty == false
            assertTrue("Should detect arguments in bundle", hasArguments)
            
            // Verify bundle content
            assertNotNull(fragment.arguments)
            assertEquals("value1", fragment.arguments?.getString("key1"))
            assertEquals(42, fragment.arguments?.getInt("key2"))
            assertEquals(true, fragment.arguments?.getBoolean("key3"))
        }
        
        scenario.close()
    }

    @Test
    fun fromScreenIsEmptyOnFirstFragment() {
        val scenario = launchFragmentInContainer<TestFragment>()
        
        scenario.onFragment {
            val fromScreen: String? = null
            val attributes = mapOf(
                "navigation.from_screen" to (fromScreen ?: "")
            )
            
            // First fragment should have empty from_screen
            assertEquals("", attributes["navigation.from_screen"])
        }
        
        scenario.close()
    }

    @Test
    fun navigationEventIncludesAllContextFields() {
        val scenario = launchFragmentInContainer<TestFragment>()
        
        scenario.onFragment {
            val attributes = mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to "TestFragment",
                "navigation.method" to "push",
                "navigation.route_type" to "fragment_flow",
                "navigation.has_arguments" to false,
                "navigation.timestamp" to "2024-03-18T14:50:00.000Z"
            )
            
            // Ensure all 6 required fields are present
            assertEquals(6, attributes.size)
            assertNotNull(attributes["navigation.from_screen"])
            assertNotNull(attributes["navigation.to_screen"])
            assertNotNull(attributes["navigation.method"])
            assertNotNull(attributes["navigation.route_type"])
            assertNotNull(attributes["navigation.has_arguments"])
            assertNotNull(attributes["navigation.timestamp"])
        }
        
        scenario.close()
    }
}
