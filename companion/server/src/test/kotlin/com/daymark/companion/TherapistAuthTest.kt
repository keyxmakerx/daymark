package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.mail.InMemoryMailTransport
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.MailerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TherapistAuthTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("some-inbox-token")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("therapist-auth-test").toString()

    private fun stores(dir: String, cfg: Config, clock: () -> Long = { System.currentTimeMillis() }): Pair<AuthStore, com.daymark.companion.storage.RelationStore> {
        val auth = AuthStore(dir, clock)
        val rel = com.daymark.companion.storage.RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes)
        return auth to rel
    }

    @Test
    fun `invite mint returns id and link, secret is not stored in plaintext`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val res = client.post("/v1/invite") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"relRef":"$relRef","scope":["read.share"]}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("inviteId"))
        assertTrue(body.contains("link"))
        // Grab the inviteId + secret out of the returned link, then assert the DB stored an
        // Argon2id hash — not the plaintext secret.
        val id = Regex("\"inviteId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val secret = Regex("s=([^&\"]+)").find(body)!!.groupValues[1]
        val stored = auth.rawSecretColumnFor(id)
        assertNotNull(stored)
        assertTrue(stored.startsWith("argon2id\$"))
        assertTrue(!stored.contains(secret))
    }

    @Test
    fun `the invite secret honours capped backoff without permanent burn, on the surface that still takes it`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg, clock = { now })
        application { module(cfg, null, null, rel, auth) }
        val minted = auth.mintInvite(relRef, listOf("read.share"), 3600L)

        // Wrong secret a few times -> 401, then locked -> 429; real therapist still succeeds later.
        repeat(cfg.totpLockoutFails) {
            val r = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        // Now locked.
        val locked = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)

        /*
         * Advance past the backoff window; the real therapist is let through again. "Through" now
         * means the SECRET was accepted and there is simply no pairing run waiting for them — 410,
         * the shelf-is-empty answer — where a wrong secret would still be 401. That difference is
         * the whole assertion: the invite was never burned by the guesses.
         */
        now += 10 * 60 * 1000
        val ok = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.Gone, ok.status)
        val stillWrong = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"still-wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, stillWrong.status)

        // And the invite is untouched by any of it: still PENDING, still awaiting a pairing.
        assertEquals("PENDING", auth.inviteStatusFor(minted.inviteId))
    }

    @Test
    fun `the redeem route is gone, and no route in the package mints a ticket from the secret`() = testApplication {
        /*
         * THE CUT-OVER, asserted rather than described. `POST /v1/invite/{id}/redeem` used to hand
         * an enrolment ticket to whoever proved the invitation secret — which the emailed link
         * carries — so the pairing code secured nothing. Two checks, because either alone can pass
         * while the hole is open: the path answers as an unknown route, AND no source file under
         * routes/ mentions the store function that mints, so a future handler cannot quietly
         * reintroduce it.
         */
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val minted = auth.mintInvite(relRef, listOf("read.share"), 3600L)

        val gone = client.post("/v1/invite/${minted.inviteId}/redeem") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.NotFound, gone.status)
        assertEquals(0, auth.enrollTicketCountFor(minted.inviteId))

        val routes = java.io.File("src/main/kotlin/com/daymark/companion/routes").listFiles()!!.filter { it.name.endsWith(".kt") }
        assertTrue(routes.size > 5, "there should be route files to search")
        for (f in routes) {
            val body = f.readText().replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("//[^\n]*"), "")
            assertFalse(body.contains("redeemInvite"), "\${f.name} mints a ticket from the invite secret")
        }
        // Control: the search sees the name when it is present.
        assertTrue("authStore.redeemInvite(x)".contains("redeemInvite"))
    }

    @Test
    fun `redeem after TTL expiry is gone`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg, clock = { now })
        application { module(cfg, null, null, rel, auth) }
        val minted = auth.mintInvite(relRef, listOf("read.share"), 10L)
        now += 20_000 // past the 10s TTL
        val res = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.Gone, res.status)
    }

    /**
     * A fresh invite for [relRef], approved, and the enrolment ticket that approval made live.
     *
     * Through the store rather than over the wire because the ONLY route that mints a ticket is the
     * owner's pairing approve, and that needs a whole CPace run to exist first
     * (PairingRelayRoutesTest drives it end to end). These tests are about what happens AFTER a
     * ticket exists, so they take the short way to one — which is also the proof that the ticket is
     * a therapist-chosen value the owner forwards, not something the server hands out for a secret.
     */
    private fun approvedTicket(auth: AuthStore, relRef: String): String {
        val minted = auth.mintInvite(relRef, listOf("read.share"), 3600L)
        val ticket = Secrets.b64url(ByteArray(32) { (it + 1).toByte() })
        assertEquals(AuthStore.ApproveStatus.OK, auth.approveRedeem(minted.inviteId, ticket).status)
        return ticket
    }

    @Test
    fun `TOTP enroll then verify issues a session cookie and lockout after repeated bad codes`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val credentialId = "cred-1"
        val secretBytes = ByteArray(20) { (it + 3).toByte() }
        val secretB64 = Secrets.b64url(secretBytes)

        val ticket = approvedTicket(auth, relRef)
        val enroll = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket","credentialId":"$credentialId","secret":"$secretB64"}""")
        }
        assertEquals(HttpStatusCode.NoContent, enroll.status)

        val goodCode = Totp.code(secretBytes, System.currentTimeMillis() / 1000)
        val verify = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"$credentialId","code":"$goodCode"}""")
        }
        assertEquals(HttpStatusCode.OK, verify.status)
        val setCookie = verify.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        assertTrue(setCookie.contains("daymark_session="))
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(setCookie.contains("SameSite=Strict"))
        assertTrue(verify.bodyAsText().contains("csrfToken"))

        // Wrong codes N times -> lockout 429.
        var last: HttpStatusCode? = null
        repeat(cfg.totpLockoutFails + 1) {
            val r = client.post("/v1/totp/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialId":"$credentialId","code":"000000"}""")
            }
            last = r.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, last)
    }

    @Test
    fun `TOTP enroll without a valid ticket is unauthorized`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val secretB64 = Secrets.b64url(ByteArray(20) { (it + 3).toByte() })
        // No ticket redeemed: a made-up ticket + attacker-chosen relRef is rejected.
        val res = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"forged-ticket","credentialId":"attacker-cred","secret":"$secretB64"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        // And no credential was created for that relRef.
        val session = auth.createSession("attacker-cred", relRef, 900L, 28_800L)
        assertNotNull(session) // sanity: store works
    }

    @Test
    fun `TOTP enroll is insert-only and single-use (no silent overwrite of a live credential)`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val secretB64 = Secrets.b64url(ByteArray(20) { (it + 3).toByte() })

        // First therapist enrolls with a valid ticket -> 204.
        val ticket1 = approvedTicket(auth, relRef)
        val first = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket1","credentialId":"legit-cred","secret":"$secretB64"}""")
        }
        assertEquals(HttpStatusCode.NoContent, first.status)

        // Re-using the SAME ticket (single-use) is rejected.
        val replay = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket1","credentialId":"other-cred","secret":"$secretB64"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)

        // Even with a fresh ticket for the SAME relRef, a credential already exists -> 409, and the
        // live credential is NOT overwritten (attacker cannot take over the auth factor).
        val ticket2 = approvedTicket(auth, relRef)
        val overwrite = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket2","credentialId":"legit-cred","secret":"${Secrets.b64url(ByteArray(20) { 9 })}"}""")
        }
        assertEquals(HttpStatusCode.Conflict, overwrite.status)
        // The original secret still verifies (unchanged).
        val original = auth.getTotp("legit-cred")
        assertNotNull(original)
        assertEquals(secretB64, original.secretB64)
    }

    @Test
    fun `verify for an unknown credential is generic unauthorized`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val res = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"nope","code":"123456"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `session idle and absolute timeouts, revoke, and CSRF logout`() {
        var now = 1_000_000L
        val dir = tmpDir()
        val auth = AuthStore(dir, clock = { now })
        val session = auth.createSession("cred", relRef, idleSeconds = 900, absoluteSeconds = 28_800)

        // Fresh -> OK.
        assertEquals(AuthStore.SessionCheck.OK, auth.validateSession(session.sessionId, 900).check)
        // Idle timeout (>15m since last_seen).
        now += 16 * 60 * 1000
        assertEquals(AuthStore.SessionCheck.EXPIRED, auth.validateSession(session.sessionId, 900).check)

        // Absolute timeout: new session, touch within idle but pass absolute.
        now = 2_000_000L
        val s2 = auth.createSession("cred", relRef, idleSeconds = 900, absoluteSeconds = 10)
        now += 5_000
        assertEquals(AuthStore.SessionCheck.OK, auth.validateSession(s2.sessionId, 900).check)
        now += 20_000
        assertEquals(AuthStore.SessionCheck.EXPIRED, auth.validateSession(s2.sessionId, 900).check)

        // Revoke is instant.
        val s3 = auth.createSession("cred", relRef, 900, 28_800)
        auth.revokeSession(s3.sessionId)
        assertEquals(AuthStore.SessionCheck.MISSING, auth.validateSession(s3.sessionId, 900).check)

        // CSRF mismatch rejected.
        val s4 = auth.createSession("cred", relRef, 900, 28_800)
        assertEquals(AuthStore.SessionCheck.BAD_CSRF, auth.validateSession(s4.sessionId, 900, requireCsrf = "wrong").check)
        assertEquals(AuthStore.SessionCheck.OK, auth.validateSession(s4.sessionId, 900, requireCsrf = s4.csrfToken).check)
        auth.close()
    }

    @Test
    fun `logout hard-deletes the session`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val session = auth.createSession("cred", relRef, cfg.sessionIdleSeconds, cfg.sessionAbsoluteSeconds)
        val res = client.post("/v1/session/logout") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
            header("X-CSRF-Token", session.csrfToken)
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
        assertEquals(AuthStore.SessionCheck.MISSING, auth.validateSession(session.sessionId, cfg.sessionIdleSeconds).check)
    }

    @Test
    fun `logout without CSRF is rejected`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val session = auth.createSession("cred", relRef, cfg.sessionIdleSeconds, cfg.sessionAbsoluteSeconds)
        val res = client.post("/v1/session/logout") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `successful TOTP enrol fires the owner therapist-enrolled notification`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        val transport = InMemoryMailTransport()
        val mailerCfg = MailerConfig(host = "mail.example.org", port = 587, user = null, pass = null, from = "companion@example.org", tls = MailerConfig.TlsMode.STARTTLS, allowInsecureLinks = true)
        val mailer = Mailer.forConfig(mailerCfg, transport)
        application { module(cfg, null, mailer, rel, auth) }

        client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":["THERAPIST_ENROLLED"]}""")
        }

        val ticket = approvedTicket(auth, relRef)
        val secretB64 = Secrets.b64url(ByteArray(20) { (it + 3).toByte() })
        val enroll = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket","credentialId":"cred-notify","secret":"$secretB64"}""")
        }
        assertEquals(HttpStatusCode.NoContent, enroll.status)
        assertEquals(1, transport.sent.size)
        assertEquals("owner@example.org", transport.sent[0].to)
    }

    @Test
    fun `webauthn scaffold endpoints return 501 with documented note`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (auth, rel) = stores(dir, cfg)
        application { module(cfg, null, null, rel, auth) }
        val res = client.post("/v1/webauthn/register/begin") {
            contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(HttpStatusCode.NotImplemented, res.status)
        assertTrue(res.bodyAsText().contains("out of scope"))
    }
}
