package com.androidtel.telemetry_library.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryConfigTest {

    private fun config(allowlist: List<String>) = TelemetryConfig(
        apiKey = "edge_test",
        endpoint = "https://collector.example.com",
        traceHostAllowlist = allowlist
    )

    @Test
    fun `bare host accepted`() {
        val c = config(listOf("api.example.com"))
        assertTrue(c.traceHostAllowlist.contains("api.example.com"))
    }

    @Test
    fun `entry with scheme and path fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(listOf("https://api.example.com/v1"))
        }
    }

    @Test
    fun `entry with port fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(listOf("api.example.com:8080"))
        }
    }

    @Test
    fun `blank entry fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(listOf("   "))
        }
    }

    @Test
    fun `empty allowlist is the default and valid`() {
        val c = TelemetryConfig(apiKey = "edge_test", endpoint = "https://x.example.com")
        assertTrue(c.traceHostAllowlist.isEmpty())
    }
}
