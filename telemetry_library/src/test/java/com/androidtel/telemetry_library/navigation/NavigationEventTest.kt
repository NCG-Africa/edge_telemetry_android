package com.androidtel.telemetry_library.navigation

import com.androidtel.telemetry_library.core.navigation.NavigationEvent
import com.androidtel.telemetry_library.core.navigation.NavigationMethod
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NavigationEventTest {
    
    @Test
    fun `NavigationEvent with null fromScreen is valid`() {
        val event = NavigationEvent(
            fromScreen = null,
            toScreen = "ScreenA",
            method = NavigationMethod.PUSH,
            timestamp = Instant.now().toString()
        )
        
        assertNull(event.fromScreen)
        assertEquals("ScreenA", event.toScreen)
        assertEquals(NavigationMethod.PUSH, event.method)
        assertNotNull(event.timestamp)
    }
    
    @Test
    fun `NavigationEvent with fromScreen is valid`() {
        val event = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.PUSH,
            timestamp = Instant.now().toString()
        )
        
        assertEquals("ScreenA", event.fromScreen)
        assertEquals("ScreenB", event.toScreen)
        assertEquals(NavigationMethod.PUSH, event.method)
        assertNotNull(event.timestamp)
    }
    
    @Test
    fun `NavigationEvent timestamp is ISO 8601 format`() {
        val timestamp = Instant.now().toString()
        val event = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.PUSH,
            timestamp = timestamp
        )
        
        assertTrue(event.timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")))
    }
    
    @Test
    fun `NavigationEvent supports all navigation methods`() {
        val pushEvent = NavigationEvent(
            fromScreen = null,
            toScreen = "ScreenA",
            method = NavigationMethod.PUSH,
            timestamp = Instant.now().toString()
        )
        assertEquals(NavigationMethod.PUSH, pushEvent.method)
        
        val popEvent = NavigationEvent(
            fromScreen = "ScreenB",
            toScreen = "ScreenA",
            method = NavigationMethod.POP,
            timestamp = Instant.now().toString()
        )
        assertEquals(NavigationMethod.POP, popEvent.method)
        
        val replaceEvent = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.REPLACE,
            timestamp = Instant.now().toString()
        )
        assertEquals(NavigationMethod.REPLACE, replaceEvent.method)
    }
    
    @Test
    fun `NavigationEvent data class equality works correctly`() {
        val timestamp = Instant.now().toString()
        val event1 = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.PUSH,
            timestamp = timestamp
        )
        
        val event2 = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.PUSH,
            timestamp = timestamp
        )
        
        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())
    }
    
    @Test
    fun `NavigationEvent data class copy works correctly`() {
        val event = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.PUSH,
            timestamp = Instant.now().toString()
        )
        
        val copiedEvent = event.copy(toScreen = "ScreenC")
        
        assertEquals("ScreenA", copiedEvent.fromScreen)
        assertEquals("ScreenC", copiedEvent.toScreen)
        assertEquals(NavigationMethod.PUSH, copiedEvent.method)
    }
    
    @Test
    fun `NavigationEvent toString contains all fields`() {
        val event = NavigationEvent(
            fromScreen = "ScreenA",
            toScreen = "ScreenB",
            method = NavigationMethod.PUSH,
            timestamp = Instant.now().toString()
        )
        
        val toString = event.toString()
        assertTrue(toString.contains("ScreenA"))
        assertTrue(toString.contains("ScreenB"))
        assertTrue(toString.contains("PUSH"))
    }
}
