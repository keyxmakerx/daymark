package com.daymark.companion

import com.daymark.companion.auth.Secrets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An invitation link carries the server's configured address, whatever `Host` the request claims
 * (#180). The link carries the invitation's secret, so a link on a host a visitor names would hand
 * that secret to whoever runs the host.
 *
 * `Config.fromEnv` guarantees a running server has the address (PublicAddressRefusalTest); this is
 * the other half — that the address it has is the one the link gets.
 */
class InviteLinkAddressTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("some-inbox-token")
    private val spoofedHost = "evil.example"

    private fun config(publicBaseUrl: String?) = Config(
        bindAddr = "127.0.0.1", port = 8080,
        dataDir = Files.createTempDirectory("invite-link-address").toString(), basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, publicBaseUrl = publicBaseUrl, cookieSecure = false,
    )

    private suspend fun ApplicationTestBuilder.mintLinkClaimingHost(host: String): String {
        val res = client.post("/v1/invite") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header(HttpHeaders.Host, host)
            contentType(ContentType.Application.Json)
            setBody("""{"relRef":"$relRef","scope":["read.share"]}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        return Regex("\"link\":\"([^\"]+)\"").find(res.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun `an invitation link carries the configured address whatever Host the request claims`() = testApplication {
        application { module(config(publicBaseUrl = "https://daymark.example.com/")) }
        val link = mintLinkClaimingHost(spoofedHost)
        assertTrue(link.startsWith("https://daymark.example.com/portal/invite#id="), link)
        assertFalse(spoofedHost in link, link)
    }

    @Test
    fun `the claimed Host does reach the route, so its absence above is the configured address winning`() = testApplication {
        // The positive control. Only a Config built by hand can lack the address — which is why
        // Config.fromEnv, the one main uses, refuses to — and then the claimed Host is what the
        // link gets. Were the test client dropping the header, the test above would pass blind.
        application { module(config(publicBaseUrl = null)) }
        val link = mintLinkClaimingHost(spoofedHost)
        assertTrue(link.startsWith("http://$spoofedHost/portal/invite#id="), link)
    }
}
