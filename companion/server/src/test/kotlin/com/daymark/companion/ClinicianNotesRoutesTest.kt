package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.ClinicianNotesStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE CLINICIAN'S LOCKED NOTEBOOK (#548): only their session reaches it, writes cannot overwrite each
 * other, the owner can neither read it nor see it in their log, and the ending removes it.
 * Every refusal below is paired with the same request succeeding for the clinician.
 */
class ClinicianNotesRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val inboxA = "inbox-token-a"
    private val relRefA = Secrets.relRefOf(inboxA)
    private val relRefB = Secrets.relRefOf("inbox-token-b")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    private class Fixture(val dir: String, val cfg: Config, val auth: AuthStore, val rel: RelationStore, val audit: AuditStore)

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("clinician-notes-test").toString()
        val cfg = config(dir)
        return Fixture(dir, cfg, AuthStore(dir), RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes), AuditStore(dir))
    }

    private fun Fixture.session(relRef: String = relRefA) =
        auth.createSession("cred-a", relRef, cfg.sessionIdleSeconds, cfg.sessionAbsoluteSeconds)

    private suspend fun HttpClient.readNotes(relRef: String, sessionId: String?, bearer: Boolean = false): HttpResponse =
        get("/v1/relations/$relRef/notes") {
            if (sessionId != null) header(HttpHeaders.Cookie, "daymark_session=$sessionId")
            if (bearer) header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }

    private suspend fun HttpClient.writeNotes(relRef: String, sessionId: String?, csrf: String?, base: Long?, body: ByteArray, bearer: Boolean = false): HttpResponse =
        put("/v1/relations/$relRef/notes") {
            if (sessionId != null) header(HttpHeaders.Cookie, "daymark_session=$sessionId")
            if (csrf != null) header("X-CSRF-Token", csrf)
            if (base != null) header("X-Notes-Base", base.toString())
            if (bearer) header(HttpHeaders.Authorization, "Bearer $ownerToken")
            setBody(body)
        }

    private val sealed = "sealed-notebook-bytes".toByteArray()

    @Test
    fun `the clinician keeps a notebook and reads it back with its version`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val s = f.session()

        val empty = client.readNotes(relRefA, s.sessionId)
        assertEquals(HttpStatusCode.NoContent, empty.status)
        assertEquals("0", empty.headers["X-Notes-Version"])

        val w = client.writeNotes(relRefA, s.sessionId, s.csrfToken, 0, sealed)
        assertEquals(HttpStatusCode.OK, w.status, w.bodyAsText())
        assertTrue(w.bodyAsText().contains("\"version\":1"))

        val r = client.readNotes(relRefA, s.sessionId)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("1", r.headers["X-Notes-Version"])
        assertContentEquals(sealed, r.readRawBytes())
        assertEquals("no-store", r.headers["Cache-Control"])
    }

    @Test
    fun `a write based on an old version is refused and changes nothing`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val s = f.session()
        assertEquals(HttpStatusCode.OK, client.writeNotes(relRefA, s.sessionId, s.csrfToken, 0, sealed).status)
        assertEquals(HttpStatusCode.OK, client.writeNotes(relRefA, s.sessionId, s.csrfToken, 1, "second".toByteArray()).status)

        val stale = client.writeNotes(relRefA, s.sessionId, s.csrfToken, 1, "from another tab".toByteArray())
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue(stale.bodyAsText().contains("\"current\":2"))
        assertContentEquals("second".toByteArray(), client.readNotes(relRefA, s.sessionId).readRawBytes())
    }

    @Test
    fun `a write needs the anti-forgery token and a base version`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val s = f.session()
        assertEquals(HttpStatusCode.Unauthorized, client.writeNotes(relRefA, s.sessionId, null, 0, sealed).status)
        assertEquals(HttpStatusCode.Unauthorized, client.writeNotes(relRefA, s.sessionId, "wrong", 0, sealed).status)
        assertEquals(HttpStatusCode.BadRequest, client.writeNotes(relRefA, s.sessionId, s.csrfToken, null, sealed).status)
        // Control: the same write with both is taken.
        assertEquals(HttpStatusCode.OK, client.writeNotes(relRefA, s.sessionId, s.csrfToken, 0, sealed).status)
    }

    @Test
    fun `the owner cannot read or write the notebook, and their log says nothing of it`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val s = f.session()
        assertEquals(HttpStatusCode.OK, client.writeNotes(relRefA, s.sessionId, s.csrfToken, 0, sealed).status)

        assertEquals(HttpStatusCode.Unauthorized, client.readNotes(relRefA, null, bearer = true).status)
        assertEquals(HttpStatusCode.Unauthorized, client.writeNotes(relRefA, null, null, 1, "x".toByteArray(), bearer = true).status)
        // Control: the clinician's session still reads it.
        assertEquals(HttpStatusCode.OK, client.readNotes(relRefA, s.sessionId).status)

        val log = f.audit.list(relRefA).map { it.action }
        assertFalse(log.any { it.contains("note") }, log.toString())
    }

    @Test
    fun `a session for one relationship cannot reach another's notebook`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val a = f.session(relRefA)
        val b = f.session(relRefB)
        assertEquals(HttpStatusCode.OK, client.writeNotes(relRefA, a.sessionId, a.csrfToken, 0, sealed).status)
        assertEquals(HttpStatusCode.Forbidden, client.readNotes(relRefA, b.sessionId).status)
        assertEquals(HttpStatusCode.Forbidden, client.writeNotes(relRefA, b.sessionId, b.csrfToken, 1, "x".toByteArray()).status)
        assertContentEquals(sealed, client.readNotes(relRefA, a.sessionId).readRawBytes())
    }

    @Test
    fun `an oversized notebook is refused`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val s = f.session()
        val big = ByteArray((ClinicianNotesStore.DEFAULT_MAX_BYTES + 1).toInt())
        assertEquals(HttpStatusCode.PayloadTooLarge, client.writeNotes(relRefA, s.sessionId, s.csrfToken, 0, big).status)
        assertEquals(HttpStatusCode.NoContent, client.readNotes(relRefA, s.sessionId).status)
    }

    @Test
    fun `leaving removes the notebook, and nothing can reach it after`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val s = f.session()
        assertEquals(HttpStatusCode.OK, client.writeNotes(relRefA, s.sessionId, s.csrfToken, 0, sealed).status)
        val file = Path.of(f.dir, "clinician-notes", relRefA)
        assertTrue(Files.exists(file), "control: the notebook is on disk before leaving")

        val left = client.post("/v1/relations/$relRefA/ending") {
            header(HttpHeaders.Cookie, "daymark_session=${s.sessionId}")
            header("X-CSRF-Token", s.csrfToken)
        }
        assertEquals(HttpStatusCode.NoContent, left.status)
        assertFalse(Files.exists(file))
        assertEquals(HttpStatusCode.Unauthorized, client.readNotes(relRefA, s.sessionId).status)
    }

    @Test
    fun `the bytes on disk are exactly what the clinician sent, behind the version`() {
        val dir = Files.createTempDirectory("clinician-notes-store").toString()
        val store = ClinicianNotesStore(dir)
        assertEquals(ClinicianNotesStore.Outcome.Written(1), store.write(relRefA, 0, sealed))
        val raw = Files.readAllBytes(Path.of(dir, "clinician-notes", relRefA))
        assertContentEquals(sealed, raw.copyOfRange(ClinicianNotesStore.HEADER, raw.size))
        assertEquals(true, store.delete(relRefA))
        assertEquals(false, store.delete(relRefA))
    }
}
