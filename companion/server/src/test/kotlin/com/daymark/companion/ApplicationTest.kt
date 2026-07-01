package com.daymark.companion

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private val testConfig = Config(
        bindAddr = "127.0.0.1",
        port = 8080,
        dataDir = "/tmp",
        basePath = "/",
        webDir = "build/test-web",
        logLevel = "info",
        authToken = null,
        maxBlobBytes = 26_214_400L,
        maxRequestBytes = 27_262_976L,
        maxVersions = 200,
        perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8,
        authLockoutSeconds = 900L,
        rateLimitRps = 100,
    )

    @Test
    fun `healthz returns ok`() = testApplication {
        application { module(testConfig) }
        val res = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("\"ok\":true"))
    }

    @Test
    fun `responses carry strict security headers`() = testApplication {
        application { module(testConfig) }
        val res = client.get("/healthz")
        val csp = res.headers["Content-Security-Policy"]
        assertTrue(csp != null && csp.contains("default-src 'self'"), "CSP default-src missing")
        assertTrue(csp.contains("frame-ancestors 'none'"), "CSP frame-ancestors missing")
        assertTrue(!csp.contains("unsafe-inline"), "CSP must not contain unsafe-inline")
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
        assertEquals("DENY", res.headers["X-Frame-Options"])
        assertEquals("no-referrer", res.headers["Referrer-Policy"])
    }

    @Test
    fun `static index is served at root`() = testApplication {
        File("build/test-web").mkdirs()
        File("build/test-web/index.html").writeText("<!doctype html><title>t</title>hi")
        application { module(testConfig) }
        val res = client.get("/")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("hi"))
    }

    @Test
    fun `base path normalization`() {
        assertEquals("/", Config.normalizeBasePath("/"))
        assertEquals("/", Config.normalizeBasePath(""))
        assertEquals("/daymark", Config.normalizeBasePath("daymark"))
        assertEquals("/daymark", Config.normalizeBasePath("/daymark/"))
    }

    @Test
    fun `config endpoint reports smtpEnabled false by default`() = testApplication {
        application { module(testConfig) }
        val res = client.get("/v1/config")
        assertEquals(HttpStatusCode.OK, res.status)
        // Only the single non-secret capability flag; no config values leaked.
        assertTrue(res.bodyAsText().contains("\"smtpEnabled\":false"), "expected smtpEnabled:false")
    }
}
