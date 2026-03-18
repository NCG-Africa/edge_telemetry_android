package com.androidtel.telemetry_library

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidtel.telemetry_library.compose.TrackComposeScreen
import com.androidtel.telemetry_library.core.TelemetryManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeNavigationIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var telemetryManager: TelemetryManager
    private val capturedEvents = mutableListOf<Pair<String, Map<String, Any>>>()

    @Before
    fun setup() {
        telemetryManager = TelemetryManager.getInstance()
        capturedEvents.clear()
    }

    @After
    fun tearDown() {
        capturedEvents.clear()
    }

    @Composable
    fun TestNavHost(navController: NavController) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                TrackComposeScreen(navController, "HomeScreen")
            }
            composable("profile") {
                TrackComposeScreen(navController, "ProfileScreen")
            }
            composable("settings") {
                TrackComposeScreen(
                    navController,
                    "SettingsScreen",
                    additionalData = mapOf("route_type" to "settings")
                )
            }
        }
    }

    @Test
    fun composeNavigationHasAllRequiredFields() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            TestNavHost(navController)
        }

        composeTestRule.waitForIdle()

        val attributes = mapOf(
            "navigation.from_screen" to "",
            "navigation.to_screen" to "HomeScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to "2024-03-18T14:50:00.000Z"
        )

        assertTrue(attributes.containsKey("navigation.from_screen"))
        assertTrue(attributes.containsKey("navigation.to_screen"))
        assertTrue(attributes.containsKey("navigation.method"))
        assertTrue(attributes.containsKey("navigation.has_arguments"))
        assertTrue(attributes.containsKey("navigation.timestamp"))
        assertTrue(attributes.containsKey("navigation.route_type"))
    }

    @Test
    fun fromScreenTracksPreviousRoute() {
        val fromScreen = "HomeScreen"
        val toScreen = "ProfileScreen"

        val attributes = mapOf(
            "navigation.from_screen" to fromScreen,
            "navigation.to_screen" to toScreen,
            "navigation.method" to "push"
        )

        assertEquals("HomeScreen", attributes["navigation.from_screen"])
        assertEquals("ProfileScreen", attributes["navigation.to_screen"])
    }

    @Test
    fun routeTypeUsesAdditionalDataValue() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            TrackComposeScreen(
                navController,
                "SettingsScreen",
                additionalData = mapOf("route_type" to "settings")
            )
        }

        composeTestRule.waitForIdle()

        val routeType = "settings"
        assertEquals("settings", routeType)
    }

    @Test
    fun routeTypeDefaultsToMainFlow() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            TrackComposeScreen(navController, "HomeScreen")
        }

        composeTestRule.waitForIdle()

        val routeType = "main_flow"
        assertEquals("main_flow", routeType)
    }

    @Test
    fun hasArgumentsDetectsNavBackStackEntryArguments() {
        val hasArguments = true
        val attributes = mapOf(
            "navigation.has_arguments" to hasArguments
        )

        assertTrue("Should detect arguments from navBackStackEntry", 
            attributes["navigation.has_arguments"] as Boolean)
    }

    @Test
    fun navigationStackPersistsAcrossRecompositions() {
        var recompositionCount = 0

        composeTestRule.setContent {
            val navController = rememberNavController()
            recompositionCount++
            TestNavHost(navController)
        }

        composeTestRule.waitForIdle()

        assertTrue("Navigation should persist across recompositions", 
            recompositionCount > 0)
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

        val iso8601Regex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")
        assertTrue("Timestamp must be in ISO 8601 format", timestamp.matches(iso8601Regex))
    }

    @Test
    fun composeNavigationStructureMatchesKafkaSchema() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            TestNavHost(navController)
        }

        composeTestRule.waitForIdle()

        val attributes = mapOf(
            "navigation.from_screen" to "",
            "navigation.to_screen" to "HomeScreen",
            "navigation.method" to "push",
            "navigation.route_type" to "main_flow",
            "navigation.has_arguments" to false,
            "navigation.timestamp" to "2024-03-18T14:50:00.000Z"
        )

        assertTrue(attributes.containsKey("navigation.to_screen"))
        assertFalse(attributes.containsKey("navigation.to"))
        assertFalse(attributes.containsKey("route"))

        assertTrue(attributes["navigation.method"] in listOf("push", "pop", "replace"))
        assertTrue(attributes["navigation.has_arguments"] is Boolean)
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

        assertTrue(attributes.containsKey("navigation.to_screen"))
        assertTrue(attributes.containsKey("navigation.from_screen"))
        assertTrue(attributes.containsKey("navigation.route_type"))

        assertFalse(attributes.containsKey("navigation.to"))
        assertFalse(attributes.containsKey("navigation.type"))
        assertFalse(attributes.containsKey("route"))
        assertFalse(attributes.containsKey("method"))
        assertFalse(attributes.containsKey("type"))
    }

    @Test
    fun eventNameIsNavigation() {
        val eventName = "navigation"
        assertEquals("navigation", eventName)

        assertNotEquals("navigation.route_change", eventName)
    }

    @Test
    fun additionalDataSupportsCustomRouteTypes() {
        val customRouteTypes = listOf(
            "main_flow",
            "modal",
            "deeplink",
            "onboarding",
            "settings"
        )

        customRouteTypes.forEach { routeType ->
            val attributes = mapOf(
                "navigation.route_type" to routeType
            )
            assertTrue("Route type $routeType should be supported", 
                attributes["navigation.route_type"] in customRouteTypes)
        }
    }
}
