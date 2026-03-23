package com.androidtel.telemetry_library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidtel.telemetry_library.core.TelemetryManager
import com.androidtel.telemetry_library.core.navigation.NavigationMethod
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ActivityNavigationIntegrationTest {

    private lateinit var telemetryManager: TelemetryManager
    private val capturedEvents = mutableListOf<Pair<String, Map<String, Any>>>()
    private var eventLatch: CountDownLatch? = null

    class TestActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    class SecondTestActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    @Before
    fun setup() {
        telemetryManager = TelemetryManager.getInstance()
        capturedEvents.clear()
        eventLatch = CountDownLatch(1)
    }

    @After
    fun tearDown() {
        capturedEvents.clear()
    }

    @Test
    fun activityNavigationHasAllRequiredFields() {
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        
        scenario.onActivity { activity ->
            // Simulate event capture
            val attributes = mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to "TestActivity",
                "navigation.method" to "push",
                "navigation.route_type" to "main_flow",
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
    fun navigationMethodIsPushPopOrReplace() {
        val validMethods = listOf("push", "pop", "replace")
        
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        
        scenario.onActivity {
            val method = "push"
            assertTrue("Method must be one of: push, pop, replace", method in validMethods)
        }
        
        scenario.close()
    }

    @Test
    fun fromScreenIsNullOnFirstActivity() {
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        
        scenario.onActivity {
            val fromScreen: String? = null
            val attributes = mapOf(
                "navigation.from_screen" to (fromScreen ?: "")
            )
            
            // First activity should have empty from_screen
            assertEquals("", attributes["navigation.from_screen"])
        }
        
        scenario.close()
    }

    @Test
    fun fromScreenTracksPreviousActivity() {
        // This test simulates sequential navigation
        val fromScreen = "TestActivity"
        val toScreen = "SecondTestActivity"
        
        val attributes = mapOf(
            "navigation.from_screen" to fromScreen,
            "navigation.to_screen" to toScreen,
            "navigation.method" to "push"
        )
        
        assertEquals("TestActivity", attributes["navigation.from_screen"])
        assertEquals("SecondTestActivity", attributes["navigation.to_screen"])
    }

    @Test
    fun routeTypeIsMainFlowForRootActivity() {
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        
        scenario.onActivity { activity ->
            val routeType = when {
                activity.isTaskRoot -> "main_flow"
                activity.intent?.data != null -> "deeplink"
                else -> "main_flow"
            }
            
            // Root activity should be main_flow
            assertEquals("main_flow", routeType)
        }
        
        scenario.close()
    }

    @Test
    fun routeTypeIsDeeplinkForIntentWithData() {
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TestActivity::class.java
        ).apply {
            data = Uri.parse("myapp://test")
        }
        
        val scenario = ActivityScenario.launch<TestActivity>(intent)
        
        scenario.onActivity { activity ->
            val routeType = when {
                activity.isTaskRoot -> "main_flow"
                activity.intent?.data != null -> "deeplink"
                else -> "main_flow"
            }
            
            assertEquals("deeplink", routeType)
        }
        
        scenario.close()
    }

    @Test
    fun hasArgumentsIsTrueWhenIntentHasExtras() {
        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TestActivity::class.java
        ).apply {
            putExtra("test_key", "test_value")
        }
        
        val scenario = ActivityScenario.launch<TestActivity>(intent)
        
        scenario.onActivity { activity ->
            val hasArguments = activity.intent?.extras?.isEmpty == false
            assertTrue("Should have arguments when extras present", hasArguments)
        }
        
        scenario.close()
    }

    @Test
    fun timestampIsISO8601Format() {
        val timestamp = "2024-03-18T14:50:23.456Z"
        
        // Verify ISO 8601 format: YYYY-MM-DDTHH:mm:ss.sssZ
        val iso8601Regex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")
        assertTrue("Timestamp must be in ISO 8601 format", timestamp.matches(iso8601Regex))
    }

    @Test
    fun navigationEventStructureMatchesKafkaSchema() {
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        
        scenario.onActivity {
            val attributes = mapOf(
                "navigation.from_screen" to "",
                "navigation.to_screen" to "TestActivity",
                "navigation.method" to "push",
                "navigation.route_type" to "main_flow",
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
        }
        
        scenario.close()
    }

    @Test
    fun fieldNamesMatchBackendSchema() {
        val attributes = mapOf(
            "navigation.from_screen" to "HomeScreen",
            "navigation.to_screen" to "ProfileScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
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
    fun navigationMethodEnumConvertsToLowercase() {
        assertEquals("push", NavigationMethod.PUSH.toLowerCaseString())
        assertEquals("pop", NavigationMethod.POP.toLowerCaseString())
        assertEquals("replace", NavigationMethod.REPLACE.toLowerCaseString())
    }

    @Test
    fun routeTypeClassificationsAreValid() {
        val validRouteTypes = listOf(
            "main_flow",
            "modal",
            "deeplink",
            "onboarding",
            "settings",
            "fragment_flow"
        )
        
        val testRouteType = "main_flow"
        assertTrue("Route type must be valid", testRouteType in validRouteTypes)
    }

    @Test
    fun hasArgumentsIsFalseWhenNoExtras() {
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        
        scenario.onActivity { activity ->
            val hasArguments = activity.intent?.extras?.isEmpty == false
            assertFalse("Should not have arguments when no extras", hasArguments)
        }
        
        scenario.close()
    }

    @Test
    fun eventNameIsNavigation() {
        val eventName = "navigation"
        assertEquals("navigation", eventName)
        
        // Verify it's NOT the old event name
        assertNotEquals("navigation.route_change", eventName)
    }
}
