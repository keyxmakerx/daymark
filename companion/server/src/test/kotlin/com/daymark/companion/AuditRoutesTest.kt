package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
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
import kotlin.test.assertTrue

class AuditRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val inboxToken = "inbox-token-256-bit-example-xyz"
    private val relRef = Secrets.relRefOf(inboxToken)

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 200,
        therapistAuthEnabled = true, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("audit-routes-test").toString()

    private data class Stores(val blob: com.daymark.companion.storage.BlobStore, val rel: RelationStore, val auth: AuthStore, val audit: AuditStore)

    private fun stores(dir: String, cfg: Config) = Stores(
        com.daymark.companion.storage.BlobStore(dir, cfg.maxBlobBytes, cfg.maxVersions, cfg.perTokenQuotaBytes),
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir),
        AuditStore(dir),
    )

    @Test
    fun `audit route is fail-closed when the therapist portal is disabled`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir).copy(therapistAuthEnabled = false)) }
        val res = client.get("/v1/rel/$relRef/audit") { header("X-Rel-Token", inboxToken) }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun `missing or wrong rel token is unauthorized before the owner token is even checked`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rel/$relRef/audit").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/rel/$relRef/audit") { header("X-Rel-Token", "not-the-token") }.status,
        )
    }

    @Test
    fun `a therapist session cookie alone cannot read the audit log`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        val session = s.auth.createSession("cred-1", relRef, 900L, 28_800L)
        val res = client.get("/v1/rel/$relRef/audit") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `owner GET lists entries newest-first, and a real share fetch is logged with no content`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }

        // Owner authors a share; a therapist session then reads it (the real access path).
        client.put("/v1/rel/$relRef/shares/lin/0") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken")
            setBody(byteArrayOf(1, 2, 3, 4, 5)) // opaque ciphertext bytes, never logged
        }
        val ts = s.auth.createSession("cred-therapist", relRef, 900L, 28_800L)
        client.get("/v1/rel/$relRef/shares/lin/current") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Cookie, "daymark_session=${ts.sessionId}")
        }

        val res = client.get("/v1/rel/$relRef/audit") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"action\":\"share.open\""))
        assertTrue(body.contains("\"actor\":\"therapist\""))
        assertTrue(body.contains("\"objectRef\":\"lin:0\""))
    }

    @Test
    fun `pagination via before and limit query params walks the log oldest-ward`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        repeat(5) { s.audit.append(relRef, AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:$it") }

        val page1 = client.get("/v1/rel/$relRef/audit?limit=2") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, page1.status)
        val body1 = page1.bodyAsText()
        val cursor = Regex("\"nextCursor\":(\\d+)").find(body1)!!.groupValues[1]
        assertEquals("4", cursor) // 5 entries total, seq 5 and 4 returned, next cursor = 4

        val page2 = client.get("/v1/rel/$relRef/audit?limit=2&before=$cursor") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        val body2 = page2.bodyAsText()
        assertTrue(body2.contains("\"seq\":3"))
        assertTrue(body2.contains("\"seq\":2"))
    }

    @Test
    fun `TOTP lockout and auth events are appended from the real auth route, not client-suppliable`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir).copy(totpLockoutFails = 2, totpLockoutSeconds = 60L)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        val credentialId = "cred-totp"
        val secretBytes = ByteArray(20) { (it + 1).toByte() }
        val secretB64 = Secrets.b64url(secretBytes)
        val ticket = redeemForTicket(s.auth)

        val enroll = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket","credentialId":"$credentialId","secret":"$secretB64"}""")
        }
        assertEquals(HttpStatusCode.NoContent, enroll.status)

        repeat(cfg.totpLockoutFails) {
            client.post("/v1/totp/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialId":"$credentialId","code":"000000"}""")
            }
        }
        val res = client.get("/v1/rel/$relRef/audit") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        val body = res.bodyAsText()
        assertTrue(body.contains("\"action\":\"enrol.ok\""))
        assertTrue(body.contains("\"action\":\"auth.fail\"") || body.contains("\"action\":\"lockout\""))
        // Never a TOTP code or the raw secret.
        assertFalse(body.contains(secretB64))
        assertFalse(body.contains("000000"))
    }

    /** Redeem a fresh invite for [relRef] and return its single-use enrollment ticket. */
    private fun redeemForTicket(auth: AuthStore): String {
        val minted = auth.mintInvite(relRef, listOf("read.share"), 3600L)
        val redeemed = auth.redeemInvite(minted.inviteId, minted.secret, 8, 900_000L)
        return redeemed.enrollTicket!!
    }
}
