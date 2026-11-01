package com.daymark.companion

import com.daymark.companion.mail.InMemoryMailTransport
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.MailerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val publicBaseUrl = "https://companion.example.org"

    private fun config(
        dir: String,
        authToken: String? = ownerToken,
        publicBaseUrl: String? = this.publicBaseUrl,
    ) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = authToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        publicBaseUrl = publicBaseUrl,
    )

    private fun tmpDir() = Files.createTempDirectory("recovery-routes-test").toString()

    private fun mailerWithTransport(): Pair<Mailer, InMemoryMailTransport> {
        val transport = InMemoryMailTransport()
        val cfg = MailerConfig(host = "mail.example.org", port = 587, user = null, pass = null, from = "companion@example.org", tls = MailerConfig.TlsMode.STARTTLS)
        return Mailer.forConfig(cfg, transport) to transport
    }

    @Test
    fun `notification routes are fail-closed when no auth token is configured`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir, authToken = null)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/owner/notifications").status)
    }

    @Test
    fun `notifications endpoint requires the owner bearer token`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/owner/notifications").status)
        val put = client.put("/v1/owner/notifications") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org","events":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, put.status)
    }

    @Test
    fun `owner can register, read back, and clear notification settings`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir)) }
        val put = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":["NEW_ASSIGNMENT","THERAPIST_ENROLLED"]}""")
        }
        assertEquals(HttpStatusCode.NoContent, put.status)

        val get = client.get("/v1/owner/notifications") { header(HttpHeaders.Authorization, "Bearer $ownerToken") }
        assertEquals(HttpStatusCode.OK, get.status)
        val body = get.bodyAsText()
        assertTrue(body.contains("owner@example.org"))
        assertTrue(body.contains("NEW_ASSIGNMENT"))
        assertTrue(body.contains("THERAPIST_ENROLLED"))

        val badEmail = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"not-an-email","events":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badEmail.status)

        val controlChar = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org\r\nBcc: attacker@evil.example","events":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, controlChar.status)
    }

    /**
     * The emailed access-token reset is removed (#325): neither route exists, on a server with a
     * registered address and mail on, so no mailbox can replace the owner's access. Positive
     * control: the notification route beside them still answers, so a 404 is a missing route and
     * not a missing server.
     */
    @Test
    fun `the emailed token reset is gone, and nothing is mailed`() = testApplication {
        val dir = tmpDir()
        val (mailer, transport) = mailerWithTransport()
        application { module(config(dir), mailer = mailer) }
        val put = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":[]}""")
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val request = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org"}""")
        }
        assertEquals(HttpStatusCode.NotFound, request.status)
        val confirm = client.post("/v1/recovery/confirm") {
            contentType(ContentType.Application.Json); setBody("""{"confirmToken":"anything"}""")
        }
        assertEquals(HttpStatusCode.NotFound, confirm.status)
        delay(200)
        assertEquals(0, transport.sent.size)
    }
}
