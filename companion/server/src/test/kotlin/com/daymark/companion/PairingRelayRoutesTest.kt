package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.PairingStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CPace relay, over the wire (plan §3.7.3: owner posts, therapist fetches and responds,
 * owner collects — three touches, none simultaneous).
 *
 * The properties under test are the ones the pairing design's security argument leans on:
 *
 *  - The blobs come back BYTE-IDENTICAL. The server is a parcel shelf; a relay that
 *    canonicalised, re-encoded, or "fixed" a message would silently break an exchange whose
 *    two ends are independent implementations agreeing on exact bytes.
 *  - The therapist touches prove possession of the invite secret WITHOUT consuming the invite,
 *    and their wrong guesses land on the SAME per-invite counter redeem's do — one secret, one
 *    counter, however many doors. That claim is tested by spending guesses across BOTH surfaces
 *    and watching a single threshold arm, which fails if either surface grows its own counter.
 *  - Nothing on the therapist side distinguishes "no such invite", "dead invite" and "right
 *    secret but nothing waiting" — the relay must not become an oracle for whether an
 *    invitation exists or how far a ceremony has progressed.
 *  - The locked-door audit rule holds here exactly as on redeem: one LOCKOUT row on the guess
 *    that arms it, nothing for requests that bounce off it.
 */
class PairingRelayRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("inbox-token-256-bit-example-xyz")
    private val otherRelRef = Secrets.relRefOf("a-different-inbox-token-entirely")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 3_600L,
        inviteTtlSeconds = 86_400L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("pairing-relay-test").toString()

    private data class Stores(
        val rel: RelationStore,
        val auth: AuthStore,
        val audit: AuditStore,
        val pairing: PairingStore,
    )

    private fun stores(dir: String, cfg: Config, clock: () -> Long) = Stores(
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir, clock),
        AuditStore(dir),
        PairingStore(dir, clock),
    )

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Shaped like a real MSGa/MSGb: lv(32-byte point) + lv(3-byte AD) — but arbitrary bytes,
     *  because the relay must carry whatever the protocol produces without an opinion. */
    private fun fakeMsg(seed: Int): String {
        val point = ByteArray(32) { ((it * 7 + seed) and 0xff).toByte() }
        val ad = byteArrayOf(0x41, 0x44, (0x61 + seed % 3).toByte())
        return b64(byteArrayOf(32) + point + byteArrayOf(3) + ad)
    }

    private val sid = { seed: Int -> b64(ByteArray(16) { ((it + seed) and 0xff).toByte() }) }

    private suspend fun ApplicationTestBuilder.ownerOpen(inviteId: String, sidB64: String, msgA: String): HttpResponse =
        client.post("/v1/relations/$relRef/pairing") {
            bearerAuth(ownerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"inviteId":"$inviteId","sidB64":"$sidB64","msgAB64":"$msgA"}""")
        }

    private fun exchangeIdOf(body: String): String =
        Regex("\"exchangeId\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    @Test
    fun `the three touches carry the blobs byte-identically and walk the state machine`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val msgA = fakeMsg(1)
        val msgB = fakeMsg(2)
        val sidB64 = sid(9)

        // Touch 1: the owner opens.
        val open = ownerOpen(minted.inviteId, sidB64, msgA)
        assertEquals(HttpStatusCode.Created, open.status)
        val exchangeId = exchangeIdOf(open.bodyAsText())

        // Touch 2: the therapist fetches with the invite secret, sees the exact bytes...
        val fetch = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.OK, fetch.status)
        val fetchBody = fetch.bodyAsText()
        assertTrue(fetchBody.contains("\"sidB64\":\"$sidB64\""), "sid must come back byte-identical")
        assertTrue(fetchBody.contains("\"msgAB64\":\"$msgA\""), "MSGa must come back byte-identical")
        assertTrue(fetchBody.contains("\"exchangeId\":\"$exchangeId\""))

        // ...and responds.
        val respond = client.post("/v1/invite/${minted.inviteId}/pairing/$exchangeId/respond") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}","msgBB64":"$msgB"}""")
        }
        assertEquals(HttpStatusCode.NoContent, respond.status)

        // Touch 3: the owner collects the reply, byte-identical, and closes.
        val read = client.get("/v1/relations/$relRef/pairing/$exchangeId") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.OK, read.status)
        val readBody = read.bodyAsText()
        assertTrue(readBody.contains("\"state\":\"RESPONDED\""))
        assertTrue(readBody.contains("\"msgBB64\":\"$msgB\""), "MSGb must come back byte-identical")

        val close = client.post("/v1/relations/$relRef/pairing/$exchangeId/close") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.NoContent, close.status)
        val after = client.get("/v1/relations/$relRef/pairing/$exchangeId") { bearerAuth(ownerToken) }
        assertTrue(after.bodyAsText().contains("\"state\":\"CLOSED\""))

        // The relay consumed nothing: the invite is still PENDING, ready for enrolment.
        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId))
    }

    @Test
    fun `relay guesses and redeem guesses spend one shared counter`() = testApplication {
        // The claim in the routes file is that a separate budget per surface would hand an
        // attacker a multiplier. This test is its proof: two wrong guesses through the relay
        // plus one through redeem arm the threshold-of-three lockout — which can only happen
        // if all three landed on one counter. If either surface grew its own, the third guess
        // would answer 401 and this test fails.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        repeat(2) { i ->
            val r = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json)
                setBody("""{"secret":"wrong-$i"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        // The third wrong guess lands on REDEEM. On the invite surface the arming failure
        // itself still answers 401 (only later knocks answer 429 — see LockedInviteAuditTest),
        // so the proof of sharing is what it ARMS: one LOCKOUT row, and the next request —
        // with the CORRECT secret — bouncing off a lockout three guesses built across two doors.
        val third = client.post("/v1/invite/${minted.inviteId}/redeem") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"also-wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, third.status)
        assertEquals(
            1, s.audit.list(relRef, limit = 50).count { it.action == "lockout" },
            "the third guess must arm the shared lockout — zero rows here means the surfaces keep separate counters",
        )

        // And the relay is refused by that same lockout, silently (no new audit rows).
        val rows = s.audit.list(relRef, limit = 50).size
        val locked = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertEquals(rows, s.audit.list(relRef, limit = 50).size, "a knock on a locked door is not a write")
    }

    @Test
    fun `no invite, dead invite, and nothing-waiting read identically from the outside`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }

        // A real invite with a right secret but no exchange waiting...
        val quiet = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val nothingWaiting = client.post("/v1/invite/${quiet.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${quiet.secret}"}""")
        }
        // ...an invite that never existed...
        val neverExisted = client.post("/v1/invite/no-such-invite/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${quiet.secret}"}""")
        }
        // ...and one the owner reported dead.
        val dead = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        s.auth.reportInviteByOwner(dead.inviteId)
        val reported = client.post("/v1/invite/${dead.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${dead.secret}"}""")
        }

        for (r in listOf(nothingWaiting, neverExisted, reported)) {
            assertEquals(HttpStatusCode.Gone, r.status)
            assertEquals("""{"error":"invite unavailable"}""", r.bodyAsText())
        }
    }

    @Test
    fun `an exchange answers once, the newest open exchange wins, and the cap holds`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        // The owner re-opens (a fresh protocol run after a suspected typo): the therapist must
        // see the NEWEST exchange, because CPace randomness is per-run and stale MSGa is dead.
        val first = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        now += 1_000
        val second = exchangeIdOf(ownerOpen(minted.inviteId, sid(2), fakeMsg(2)).bodyAsText())
        val fetch = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}"}""")
        }
        assertTrue(fetch.bodyAsText().contains(second))
        assertFalse(fetch.bodyAsText().contains(first))

        // One reply per exchange, ever.
        val respond1 = client.post("/v1/invite/${minted.inviteId}/pairing/$second/respond") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}","msgBB64":"${fakeMsg(3)}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, respond1.status)
        val respond2 = client.post("/v1/invite/${minted.inviteId}/pairing/$second/respond") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}","msgBB64":"${fakeMsg(4)}"}""")
        }
        assertEquals(HttpStatusCode.Gone, respond2.status)
        // The first write stands; the second changed nothing.
        val kept = s.pairing.exchangeFor(second, relRef)!!
        assertEquals(fakeMsg(3), kept.msgBB64)

        // The per-invite cap: opens beyond it are refused with the mint-a-fresh-invite answer.
        var opened = 2
        while (opened < PairingStore.MAX_EXCHANGES_PER_INVITE) {
            assertEquals(HttpStatusCode.Created, ownerOpen(minted.inviteId, sid(opened), fakeMsg(opened)).status)
            opened++
        }
        val overCap = ownerOpen(minted.inviteId, sid(99), fakeMsg(99))
        assertEquals(HttpStatusCode.Conflict, overCap.status)
        assertEquals(PairingStore.MAX_EXCHANGES_PER_INVITE.toLong(), s.pairing.exchangeCountFor(minted.inviteId))
    }

    @Test
    fun `owner routes are non-enumerating across relationships and demand the bearer token`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())

        // No token: refused before anything is looked up.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/relations/$relRef/pairing/$exchangeId").status,
        )

        // A real exchange read through the WRONG relationship's path: the same 404 a made-up
        // exchange id gets, so the path cannot be used to map exchanges to relationships.
        val crossRel = client.get("/v1/relations/$otherRelRef/pairing/$exchangeId") { bearerAuth(ownerToken) }
        val madeUp = client.get("/v1/relations/$otherRelRef/pairing/not-a-real-exchange") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.NotFound, crossRel.status)
        assertEquals(madeUp.bodyAsText(), crossRel.bodyAsText())

        // Opening against another relationship's invite: refused, and no exchange appears.
        val crossOpen = client.post("/v1/relations/$otherRelRef/pairing") {
            bearerAuth(ownerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"inviteId":"${minted.inviteId}","sidB64":"${sid(5)}","msgAB64":"${fakeMsg(5)}"}""")
        }
        assertEquals(HttpStatusCode.NotFound, crossOpen.status)
        assertEquals(1L, s.pairing.exchangeCountFor(minted.inviteId))
    }

    @Test
    fun `cancel takes the exchange out of the therapist's reach`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())

        val cancel = client.post("/v1/relations/$relRef/pairing/$exchangeId/cancel") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.NoContent, cancel.status)

        // The fetch now reads exactly like a dead invite — not "cancelled", which would tell a
        // link-holder the owner saw something worth stopping.
        val fetch = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.Gone, fetch.status)
        assertEquals("""{"error":"invite unavailable"}""", fetch.bodyAsText())

        // A late respond against the cancelled exchange is refused and writes nothing.
        val respond = client.post("/v1/invite/${minted.inviteId}/pairing/$exchangeId/respond") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"${minted.secret}","msgBB64":"${fakeMsg(2)}"}""")
        }
        assertEquals(HttpStatusCode.Gone, respond.status)
        assertNull(s.pairing.exchangeFor(exchangeId, relRef)!!.msgBB64)

        // The lifecycle is on the owner's record: opened, then cancelled.
        val actions = s.audit.list(relRef, limit = 50).map { it.action }
        assertTrue("pairing.opened" in actions)
        assertTrue("pairing.cancelled" in actions)
    }

    @Test
    fun `shape checks refuse what no honest client sends`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        // A 15-byte sid, an oversized message, and a message that is not base64url at all.
        for (body in listOf(
            """{"inviteId":"${minted.inviteId}","sidB64":"${b64(ByteArray(15))}","msgAB64":"${fakeMsg(1)}"}""",
            """{"inviteId":"${minted.inviteId}","sidB64":"${sid(1)}","msgAB64":"${b64(ByteArray(201))}"}""",
            """{"inviteId":"${minted.inviteId}","sidB64":"${sid(1)}","msgAB64":"not/base64url!"}""",
        )) {
            val r = client.post("/v1/relations/$relRef/pairing") {
                bearerAuth(ownerToken)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
        assertEquals(0L, s.pairing.exchangeCountFor(minted.inviteId))

        // The invite must be this relationship's AND alive: an expired one is refused.
        val expiring = s.auth.mintInvite(relRef, listOf("read.share"), 10L)
        now += 20_000
        val late = ownerOpen(expiring.inviteId, sid(2), fakeMsg(2))
        assertEquals(HttpStatusCode.NotFound, late.status)
    }

    @Test
    fun `the relay is dark when the portal is off`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir).copy(therapistAuthEnabled = false)
        application { module(cfg) }
        val r = client.post("/v1/invite/whatever/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"x"}""")
        }
        // Fail-closed like every other portal path: a probe cannot tell configured-but-empty
        // from not-configured.
        assertNotNull(r)
        assertTrue(r.status == HttpStatusCode.ServiceUnavailable || r.status == HttpStatusCode.NotFound)
    }
}
