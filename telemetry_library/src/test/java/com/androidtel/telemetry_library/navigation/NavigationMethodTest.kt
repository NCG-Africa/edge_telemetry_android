package com.androidtel.telemetry_library.navigation

import com.androidtel.telemetry_library.core.navigation.NavigationMethod
import org.junit.Assert.*
import org.junit.Test

class NavigationMethodTest {
    
    @Test
    fun `NavigationMethod has exactly four values`() {
        val values = NavigationMethod.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(NavigationMethod.INITIAL))
        assertTrue(values.contains(NavigationMethod.PUSH))
        assertTrue(values.contains(NavigationMethod.POP))
        assertTrue(values.contains(NavigationMethod.REPLACE))
    }
    
    @Test
    fun `PUSH converts to lowercase push`() {
        assertEquals("push", NavigationMethod.PUSH.toLowerCaseString())
    }
    
    @Test
    fun `POP converts to lowercase pop`() {
        assertEquals("pop", NavigationMethod.POP.toLowerCaseString())
    }
    
    @Test
    fun `REPLACE converts to lowercase replace`() {
        assertEquals("replace", NavigationMethod.REPLACE.toLowerCaseString())
    }
    
    @Test
    fun `valueOf works for all methods`() {
        assertEquals(NavigationMethod.PUSH, NavigationMethod.valueOf("PUSH"))
        assertEquals(NavigationMethod.POP, NavigationMethod.valueOf("POP"))
        assertEquals(NavigationMethod.REPLACE, NavigationMethod.valueOf("REPLACE"))
    }
    
    @Test
    fun `NavigationMethod enum ordinal values are stable`() {
        assertEquals(0, NavigationMethod.PUSH.ordinal)
        assertEquals(1, NavigationMethod.POP.ordinal)
        assertEquals(2, NavigationMethod.REPLACE.ordinal)
        assertEquals(3, NavigationMethod.INITIAL.ordinal)
    }
    
    @Test
    fun `NavigationMethod name property returns uppercase`() {
        assertEquals("PUSH", NavigationMethod.PUSH.name)
        assertEquals("POP", NavigationMethod.POP.name)
        assertEquals("REPLACE", NavigationMethod.REPLACE.name)
    }
    
    @Test
    fun `toLowerCaseString produces catalogue navigation_method values`() {
        val validKafkaMethods = setOf("push", "pop", "replace", "tab", "deep_link", "initial", "unknown")
        
        NavigationMethod.values().forEach { method ->
            assertTrue(
                "Method ${method.toLowerCaseString()} should be valid for Kafka",
                validKafkaMethods.contains(method.toLowerCaseString())
            )
        }
    }
}
