package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GET /v1/relations/{relRef}/audit-chain — the chain check over HTTP.
 *
 * The gate matters more than the arithmetic here (the arithmetic has its own suite in
 * storage/AuditChainVerificationTest.kt): a head plus an entry count per relRef, served to an
 * anonymous caller, would answer "does this relationship exist and how active is it" for any
 * reference someone cares to probe. So the refusals are tested as carefully as the result, and
 * the result is tested to carry NOTHING but counts, extents and one hash — never an entry.
 */
class AuditChainRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("inbox-token-256-bit-example-xyz")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 200,
        therapistAuthEnabled = true, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("audit-chain-routes-test").toString()

    private data class Stores(
        val blob: com.daymark.companion.storage.BlobStore,
        val rel: RelationStore,
        val auth: AuthStore,
        val audit: AuditStore,
    )

    private fun stores(dir: String, cfg: Config) = Stores(
        com.daymark.companion.storage.BlobStore(dir, cfg.maxBlobBytes, cfg.maxVersions, cfg.perTokenQuotaBytes),
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir),
        AuditStore(dir),
    )

    @Test
    fun `chain route is fail-closed when the therapist portal is disabled`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir).copy(therapistAuthEnabled = false)) }
        // 503 from the catch-all stub, so a probe cannot tell "no portal" from "portal but no
        // chain" any more than it can on the neighbouring key routes.
        val res = client.get("/v1/relations/$relRef/audit-chain")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun `the route refuses without the owner token`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        s.audit.append(relRef, AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)

        // No credential at all.
        val bare = client.get("/v1/relations/$relRef/audit-chain")
        assertEquals(HttpStatusCode.Unauthorized, bare.status)
        // A wrong bearer token is the same refusal — non-enumerating, like the whole owner surface.
        val wrong = client.get("/v1/relations/$relRef/audit-chain") {
            header(HttpHeaders.Authorization, "Bearer not-the-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        // And neither refusal leaked anything about the chain that exists behind the gate.
        assertFalse(bare.bodyAsText().contains("entryCount"))
        assertFalse(wrong.bodyAsText().contains("headHash"))
    }

    @Test
    fun `a therapist session cookie alone cannot read the chain`() = testApplication {
        // The gate is the OWNER's, exactly as on the therapist-keys read. A clinician's session is
        // real authentication — for a different surface, and re-presenting it here must not help.
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        val session = s.auth.createSession("cred-1", relRef, 900L, 28_800L)
        val res = client.get("/v1/relations/$relRef/audit-chain") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `owner GET reports the stored chain, and reading it does not move the head`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        s.audit.append(relRef, AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        s.audit.append(relRef, AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0")
        val head = s.audit.append(relRef, AuditActor.OWNER, AuditAction.SHARE_REVOKE, objectRef = "lin")

        val res = client.get("/v1/relations/$relRef/audit-chain") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"entryCount\":3"))
        assertTrue(body.contains("\"headSeq\":3"))
        assertTrue(body.contains("\"headHash\":\"${head.entryHash}\""))
        // A clean chain has NO firstBreakSeq field at all (explicitNulls=false), so the absence a
        // client renders as "no break reported" is the wire's own shape, not an inference.
        assertFalse(body.contains("firstBreakSeq"))
        // And nothing entry-shaped travels: no actors, no actions, no timestamps, no objectRefs.
        assertFalse(body.contains("share.open"))
        assertFalse(body.contains("therapist"))
        assertFalse(body.contains("lin:0"))

        // Read again: byte-identical, which proves the check appended nothing — the whole value
        // of the head is that LOOKING at it does not change it. Then confirm against the store.
        val again = client.get("/v1/relations/$relRef/audit-chain") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(body, again.bodyAsText())
        assertEquals(3, s.audit.list(relRef, limit = 50).size)
    }

    @Test
    fun `a row edited in the database surfaces as a break at that seq through the route`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        repeat(4) { s.audit.append(relRef, AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:$it") }
        // The hostile-operator move, done the way a hostile operator does it: at the file.
        DriverManager.getConnection("jdbc:sqlite:" + Path.of(dir).resolve("audit.db")).use { conn ->
            conn.prepareStatement("UPDATE audit_events SET object_ref='lin:99' WHERE rel_ref=? AND seq=2").use { ps ->
                ps.setString(1, relRef)
                ps.executeUpdate()
            }
        }

        val body = client.get("/v1/relations/$relRef/audit-chain") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }.bodyAsText()
        assertTrue(body.contains("\"firstBreakSeq\":2"), "the break must be reported at the edited entry, got: $body")
        assertTrue(body.contains("\"entryCount\":4"))
    }

    @Test
    fun `an unknown relationship reads as an empty chain, not as a different answer`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        val res = client.get("/v1/relations/${Secrets.relRefOf("never-used-token")}/audit-chain") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("\"entryCount\":0"))
    }

    @Test
    fun `a malformed relRef is refused before the store is asked`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg)
        application { module(cfg, s.blob, null, s.rel, s.auth, s.audit) }
        // Outside the store's charset. Refused as a bad request, never surfaced as a 500 — a
        // malformed reference is a client bug or a probe, not a server failure.
        val res = client.get("/v1/relations/bad%21ref/audit-chain") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
