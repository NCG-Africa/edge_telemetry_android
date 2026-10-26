package com.androidtel.telemetry_library.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** RN v4 contract §4.4.1 — the canonical endpoint-path template, ported verbatim (#143). */
class UrlTemplateTest {

    private fun t(url: String) = templateUrl(url.toHttpUrl())

    @Test
    fun `digit or long segments become id, vN and plain words survive`() {
        assertEquals("api.example.com/v2/users/{id}/orders", t("https://api.example.com/v2/users/42/orders"))
        assertEquals("api.example.com/users/{id}", t("https://api.example.com/users/a1b2"))
        assertEquals("api.example.com/tokens/{id}", t("https://api.example.com/tokens/" + "x".repeat(32)))
        assertEquals("api.example.com/tokens/" + "x".repeat(31), t("https://api.example.com/tokens/" + "x".repeat(31)))
        assertEquals("api.example.com/v10/a", t("https://api.example.com/v10/a"))
    }

    @Test
    fun `no scheme, query or fragment, trailing slash dropped, no case folding`() {
        assertEquals("api.example.com/Users/Me", t("https://api.example.com/Users/Me/?q=1#frag"))
    }

    @Test
    fun `empty path is slash`() {
        assertEquals("api.example.com/", t("https://api.example.com"))
        assertEquals("api.example.com/", t("https://api.example.com/"))
    }

    @Test
    fun `non-default port kept, default port dropped`() {
        assertEquals("localhost:8080/a", t("http://localhost:8080/a"))
        assertEquals("api.example.com/a", t("https://api.example.com:443/a"))
        assertEquals("[::1]:8080/a", t("http://[::1]:8080/a"))
    }

    @Test
    fun `depth capped at 6 segments then ellipsis`() {
        assertEquals("h.com/a/b/c/d/e/f/…", t("https://h.com/a/b/c/d/e/f/g/h"))
        assertEquals("h.com/a/b/c/d/e/f", t("https://h.com/a/b/c/d/e/f"))
    }
}
