package com.androidtel.telemetry_library.navigation

import com.androidtel.telemetry_library.core.navigation.NavigationMethod
import com.androidtel.telemetry_library.core.navigation.NavigationStackTracker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class NavigationStackTrackerTest {
    
    private lateinit var tracker: NavigationStackTracker
    
    @Before
    fun setup() {
        tracker = NavigationStackTracker()
    }
    
    @Test
    fun `push navigation on empty stack has null from_screen`() {
        val event = tracker.push("ScreenA")
        
        assertNull(event.fromScreen)
        assertEquals("ScreenA", event.toScreen)
        assertEquals(NavigationMethod.PUSH, event.method)
    }
    
    @Test
    fun `push navigation tracks from_screen`() {
        tracker.push("ScreenA")
        val event = tracker.push("ScreenB")
        
        assertEquals("ScreenA", event.fromScreen)
        assertEquals("ScreenB", event.toScreen)
        assertEquals(NavigationMethod.PUSH, event.method)
    }
    
    @Test
    fun `pop navigation returns to previous screen`() {
        tracker.push("ScreenA")
        tracker.push("ScreenB")
        val event = tracker.pop()
        
        assertEquals("ScreenB", event?.fromScreen)
        assertEquals("ScreenA", event?.toScreen)
        assertEquals(NavigationMethod.POP, event?.method)
    }
    
    @Test
    fun `pop on single screen returns null`() {
        tracker.push("ScreenA")
        val event = tracker.pop()
        
        assertNull(event)
    }
    
    @Test
    fun `pop on empty stack returns null`() {
        val event = tracker.pop()
        
        assertNull(event)
    }
    
    @Test
    fun `replace navigation updates current screen`() {
        tracker.push("ScreenA")
        val event = tracker.replace("ScreenB")
        
        assertEquals("ScreenA", event.fromScreen)
        assertEquals("ScreenB", event.toScreen)
        assertEquals(NavigationMethod.REPLACE, event.method)
    }
    
    @Test
    fun `replace on empty stack has null from_screen`() {
        val event = tracker.replace("ScreenA")
        
        assertNull(event.fromScreen)
        assertEquals("ScreenA", event.toScreen)
        assertEquals(NavigationMethod.REPLACE, event.method)
    }
    
    @Test
    fun `getCurrentScreen returns top of stack`() {
        tracker.push("ScreenA")
        tracker.push("ScreenB")
        
        assertEquals("ScreenB", tracker.getCurrentScreen())
    }
    
    @Test
    fun `getCurrentScreen returns null on empty stack`() {
        assertNull(tracker.getCurrentScreen())
    }
    
    @Test
    fun `getPreviousScreen returns second from top`() {
        tracker.push("ScreenA")
        tracker.push("ScreenB")
        tracker.push("ScreenC")
        
        assertEquals("ScreenB", tracker.getPreviousScreen())
    }
    
    @Test
    fun `getPreviousScreen returns null when stack has one screen`() {
        tracker.push("ScreenA")
        
        assertNull(tracker.getPreviousScreen())
    }
    
    @Test
    fun `timestamp is ISO 8601 format`() {
        val event = tracker.push("ScreenA")
        
        assertTrue(event.timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z")))
    }
    
    @Test
    fun `method enum converts to lowercase correctly`() {
        assertEquals("push", NavigationMethod.PUSH.toLowerCaseString())
        assertEquals("pop", NavigationMethod.POP.toLowerCaseString())
        assertEquals("replace", NavigationMethod.REPLACE.toLowerCaseString())
    }
    
    @Test
    fun `concurrent push operations are thread-safe`() {
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(100)
        
        repeat(100) { index ->
            executor.submit {
                tracker.push("Screen$index")
                latch.countDown()
            }
        }
        
        latch.await()
        executor.shutdown()
        
        assertNotNull(tracker.getCurrentScreen())
    }
    
    @Test
    fun `multiple push and pop operations maintain stack integrity`() {
        tracker.push("ScreenA")
        tracker.push("ScreenB")
        tracker.push("ScreenC")
        
        assertEquals("ScreenC", tracker.getCurrentScreen())
        
        tracker.pop()
        assertEquals("ScreenB", tracker.getCurrentScreen())
        
        tracker.pop()
        assertEquals("ScreenA", tracker.getCurrentScreen())
    }
}
