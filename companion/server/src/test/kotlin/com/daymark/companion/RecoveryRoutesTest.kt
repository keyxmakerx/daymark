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

class RecoveryRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val publicBaseUrl = "https://companion.example.org"

    private fun config(
        dir: String,
        authToken: String? = ownerToken,
        reissueMaxPerHour: Int = 5,
        publicBaseUrl: String? = this.publicBaseUrl,
    ) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = authToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        reissueMaxPerHour = reissueMaxPerHour, reissueConfirmTtlSeconds = 3600L,
        publicBaseUrl = publicBaseUrl,
    )

    private fun tmpDir() = Files.createTempDirectory("recovery-routes-test").toString()

    private fun mailerWithTransport(): Pair<Mailer, InMemoryMailTransport> {
        val transport = InMemoryMailTransport()
        val cfg = MailerConfig(host = "mail.example.org", port = 587, user = null, pass = null, from = "companion@example.org", tls = MailerConfig.TlsMode.STARTTLS)
        return Mailer.forConfig(cfg, transport) to transport
    }

    private fun confirmTokenFrom(body: String): String =
        Regex("#t=([^\\s\"]+)").find(body)!!.groupValues[1]

    /** The recovery email send is dispatched to a background task, never awaited by the HTTP
     *  response — poll briefly rather than asserting immediately. */
    private suspend fun awaitSentCount(transport: InMemoryMailTransport, expected: Int, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (transport.sent.size < expected && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertEquals(expected, transport.sent.size)
    }

    @Test
    fun `recovery routes are fail-closed when no auth token is configured`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir, authToken = null)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/owner/notifications").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"a@example.org"}""")
        }.status)
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

    @Test
    fun `full recovery round-trip rotates the token and old token stops working`() = testApplication {
        val dir = tmpDir()
        val (mailer, transport) = mailerWithTransport()
        application { module(config(dir), mailer = mailer) }

        client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":[]}""")
        }

        val req = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, req.status)
        awaitSentCount(transport, 1)
        val sent = transport.sent[0]
        assertTrue(sent.body.contains(publicBaseUrl), "recovery link must use the configured public base URL")
        val confirmToken = confirmTokenFrom(sent.body)

        val confirm = client.post("/v1/recovery/confirm") {
            contentType(ContentType.Application.Json); setBody("""{"confirmToken":"$confirmToken"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status)
        val newToken = Regex("\"newToken\":\"([^\"]+)\"").find(confirm.bodyAsText())!!.groupValues[1]
        assertTrue(newToken.isNotEmpty() && newToken != ownerToken)

        // A security-notice receipt was sent (in addition to the confirmation link email),
        // also dispatched asynchronously.
        awaitSentCount(transport, 2)

        // Old token now fails; new token now works.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/owner/notifications") { header(HttpHeaders.Authorization, "Bearer $ownerToken") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/owner/notifications") { header(HttpHeaders.Authorization, "Bearer $newToken") }.status,
        )

        // The confirm link is single-use: replaying it is Gone.
        val replay = client.post("/v1/recovery/confirm") {
            contentType(ContentType.Application.Json); setBody("""{"confirmToken":"$confirmToken"}""")
        }
        assertEquals(HttpStatusCode.Gone, replay.status)
    }

    @Test
    fun `recovery request matches a registered email case-insensitively`() = testApplication {
        val dir = tmpDir()
        val (mailer, transport) = mailerWithTransport()
        application { module(config(dir), mailer = mailer) }

        client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"Owner@Example.ORG","events":[]}""")
        }

        val req = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, req.status)
        awaitSentCount(transport, 1)
    }

    @Test
    fun `without a configured public base URL, recovery is accepted but never sends a link`() = testApplication {
        val dir = tmpDir()
        val (mailer, transport) = mailerWithTransport()
        application { module(config(dir, publicBaseUrl = null), mailer = mailer) }

        client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":[]}""")
        }
        val req = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, req.status)
        // Give any (incorrectly fired) async send a chance to land before asserting it never did —
        // a real regression here would be a critical account-takeover vector (a Host-header-built
        // link), not a flaky test to shrug off.
        delay(300)
        assertEquals(0, transport.sent.size)
    }

    @Test
    fun `request with a non-matching or unregistered email is silently a no-op but still 202`() = testApplication {
        val dir = tmpDir()
        val (mailer, transport) = mailerWithTransport()
        application { module(config(dir), mailer = mailer) }

        // Nothing registered yet.
        val res1 = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"anyone@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, res1.status)
        delay(300)
        assertEquals(0, transport.sent.size)

        client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":[]}""")
        }
        // Wrong email vs the registered one: identical 202, still nothing sent.
        val res2 = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"wrong@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, res2.status)
        delay(300)
        assertEquals(0, transport.sent.size)
    }

    @Test
    fun `confirm with an unknown token is Gone`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir)) }
        val res = client.post("/v1/recovery/confirm") {
            contentType(ContentType.Application.Json); setBody("""{"confirmToken":"not-a-real-token"}""")
        }
        assertEquals(HttpStatusCode.Gone, res.status)
    }

    @Test
    fun `recovery request is rate-limited per source`() = testApplication {
        val dir = tmpDir()
        val (mailer, transport) = mailerWithTransport()
        application { module(config(dir, reissueMaxPerHour = 1), mailer = mailer) }
        client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":[]}""")
        }
        val first = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, first.status)
        awaitSentCount(transport, 1)

        // Second request from the same source within the window: still 202 (non-enumerating),
        // but the rate limiter suppresses the actual mint/send.
        val second = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"owner@example.org"}""")
        }
        assertEquals(HttpStatusCode.Accepted, second.status)
        delay(300)
        assertEquals(1, transport.sent.size)
    }
}
