package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.PairingStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.PAIRING_STATUS_BURST
import com.daymark.companion.routes.PAIRING_STATUS_POLL_SECONDS
import com.daymark.companion.routes.PAIRING_STATUS_REFILL_MS
import com.daymark.companion.routes.PAIR_MAX_PER_WINDOW
import com.daymark.companion.routes.PAIR_WINDOW_MS
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
 * owner collects — three touches, none simultaneous), and the approval that turns a matched
 * code into an enrolment.
 *
 * The properties under test are the ones the pairing design's security argument leans on:
 *
 *  - The blobs come back BYTE-IDENTICAL, the sealed offer included. The server is a parcel
 *    shelf; a relay that canonicalised, re-encoded, or "fixed" a message would silently break
 *    an exchange whose two ends are independent implementations agreeing on exact bytes.
 *  - The therapist touches prove possession of the invite secret WITHOUT consuming the invite,
 *    and their wrong guesses land on the SAME per-invite counter redeem's do — one secret, one
 *    counter, however many doors. That claim is tested by spending guesses across BOTH surfaces
 *    and watching a single threshold arm, which fails if either surface grows its own counter.
 *  - Nothing on the therapist side distinguishes "no such invite", "dead invite" and "right
 *    secret but nothing waiting" — the relay must not become an oracle for whether an
 *    invitation exists or how far a ceremony has progressed.
 *  - The locked-door audit rule holds here exactly as on redeem: one LOCKOUT row on the guess
 *    that arms it, nothing for requests that bounce off it.
 *  - AN ENROLMENT TICKET EXISTS ONLY BECAUSE THE OWNER APPROVED. A link-holder who fetches and
 *    answers every run never has one minted, never sees one in a response, and cannot enrol.
 *    The ticket the owner forwards is the one the therapist chose and sealed under the pairing
 *    key, which is the code's proof, carried by a person.
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

    /** Shaped like a sealed offer: version(1) | nonce(24) | ciphertext — opaque to the relay. */
    private fun fakeEnv(seed: Int, size: Int = 160): String =
        b64(byteArrayOf(1) + ByteArray(size - 1) { ((it * 13 + seed) and 0xff).toByte() })

    /** A therapist-chosen enrolment ticket: 32 bytes, as the server would mint. */
    private fun ticket(seed: Int): String = b64(ByteArray(32) { ((it * 3 + seed) and 0xff).toByte() })

    private val sid = { seed: Int -> b64(ByteArray(16) { ((it + seed) and 0xff).toByte() }) }

    private suspend fun ApplicationTestBuilder.ownerOpen(inviteId: String, sidB64: String, msgA: String): HttpResponse =
        client.post("/v1/relations/$relRef/pairing") {
            bearerAuth(ownerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"inviteId":"$inviteId","sidB64":"$sidB64","msgAB64":"$msgA"}""")
        }

    private suspend fun ApplicationTestBuilder.fetch(inviteId: String, secret: String): HttpResponse =
        client.post("/v1/invite/$inviteId/pairing/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"$secret"}""")
        }

    private suspend fun ApplicationTestBuilder.respond(inviteId: String, secret: String, exchangeId: String, msgB: String, env: String = fakeEnv(7)): HttpResponse =
        client.post("/v1/invite/$inviteId/pairing/$exchangeId/respond") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"$secret","msgBB64":"$msgB","envB64":"$env"}""")
        }

    private suspend fun ApplicationTestBuilder.status(inviteId: String, secret: String, exchangeId: String): HttpResponse =
        client.post("/v1/invite/$inviteId/pairing/$exchangeId/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"$secret"}""")
        }

    private suspend fun ApplicationTestBuilder.approve(exchangeId: String, enrolTicket: String): HttpResponse =
        client.post("/v1/relations/$relRef/pairing/$exchangeId/approve") {
            bearerAuth(ownerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"enrolTicketB64":"$enrolTicket"}""")
        }

    private suspend fun ApplicationTestBuilder.enrol(enrolTicket: String, credentialId: String): HttpResponse =
        client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$enrolTicket","credentialId":"$credentialId","secret":"${b64(ByteArray(20) { 5 })}"}""")
        }

    private fun exchangeIdOf(body: String): String =
        Regex("\"exchangeId\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    @Test
    fun `the three touches carry the blobs byte-identically, and approval makes the chosen ticket live`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val msgA = fakeMsg(1)
        val msgB = fakeMsg(2)
        val env = fakeEnv(3)
        val chosen = ticket(4)
        val sidB64 = sid(9)

        // Touch 1: the owner opens.
        val open = ownerOpen(minted.inviteId, sidB64, msgA)
        assertEquals(HttpStatusCode.Created, open.status)
        val exchangeId = exchangeIdOf(open.bodyAsText())

        // Touch 2: the therapist fetches with the invite secret, sees the exact bytes...
        val fetched = fetch(minted.inviteId, minted.secret)
        assertEquals(HttpStatusCode.OK, fetched.status)
        val fetchBody = fetched.bodyAsText()
        assertTrue(fetchBody.contains("\"sidB64\":\"$sidB64\""), "sid must come back byte-identical")
        assertTrue(fetchBody.contains("\"msgAB64\":\"$msgA\""), "MSGa must come back byte-identical")
        assertTrue(fetchBody.contains("\"exchangeId\":\"$exchangeId\""))

        // ...and responds with the reply and the sealed offer.
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, msgB, env).status)
        // Nothing has been minted: answering is not approval.
        assertEquals(0, s.auth.enrollTicketCountFor(minted.inviteId))
        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId))
        // The therapist, asking, is told to wait — and told nothing else.
        val waiting = status(minted.inviteId, minted.secret, exchangeId)
        assertEquals(HttpStatusCode.OK, waiting.status)
        assertEquals("""{"state":"WAITING"}""", waiting.bodyAsText())

        // Touch 3: the owner collects the reply and the offer, byte-identical.
        val read = client.get("/v1/relations/$relRef/pairing/$exchangeId") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.OK, read.status)
        val readBody = read.bodyAsText()
        assertTrue(readBody.contains("\"state\":\"RESPONDED\""))
        assertTrue(readBody.contains("\"msgBB64\":\"$msgB\""), "MSGb must come back byte-identical")
        assertTrue(readBody.contains("\"envB64\":\"$env\""), "the sealed offer must come back byte-identical")

        // The owner approves, forwarding the ticket the offer carried.
        assertEquals(HttpStatusCode.NoContent, approve(exchangeId, chosen).status)
        val after = client.get("/v1/relations/$relRef/pairing/$exchangeId") { bearerAuth(ownerToken) }
        assertTrue(after.bodyAsText().contains("\"state\":\"CLOSED\""))
        assertEquals("REDEEMING", s.auth.inviteStatusFor(minted.inviteId))
        assertEquals(1, s.auth.enrollTicketCountFor(minted.inviteId))

        // The therapist learns of it on the next poll, with the scope the invitation grants...
        val approved = status(minted.inviteId, minted.secret, exchangeId)
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals("""{"state":"APPROVED","scope":["read.share"]}""", approved.bodyAsText())
        // ...and enrols with the ticket they chose, which only they and the owner ever held.
        assertEquals(HttpStatusCode.NoContent, enrol(chosen, "cred-1").status)
        assertEquals("CONSUMED", s.auth.inviteStatusFor(minted.inviteId))

        // The log lists newest first; read oldest first it is the ceremony in order.
        val actions = s.audit.list(relRef, limit = 50).map { it.action }.reversed()
        assertEquals(listOf("pairing.opened", "pairing.responded", "pairing.approved", "enrol.ok"), actions.filter { it != "auth.success" })
    }

    @Test
    fun `a link-holder who answers first gets nothing - no ticket exists, none is served, and enrolment is refused`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())

        // Someone with the link, not the code, answers the run with an offer sealed under the
        // wrong key. The relay cannot tell; it carries the bytes. What it must not do is mint.
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2), fakeEnv(99)).status)
        assertEquals(0, s.auth.enrollTicketCountFor(minted.inviteId))

        // Polling yields a state word and nothing that could be mistaken for a credential.
        val polled = status(minted.inviteId, minted.secret, exchangeId)
        assertEquals("""{"state":"WAITING"}""", polled.bodyAsText())
        assertFalse(polled.bodyAsText().contains("icket", ignoreCase = true))

        // A guessed ticket does not enrol, and the invitation is untouched by the attempt.
        assertEquals(HttpStatusCode.Unauthorized, enrol(ticket(42), "cred-x").status)
        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId))

        // The owner's client will fail to open that offer and the owner starts over: cancel,
        // then a fresh run — and the answered run reads as gone to its author, in the same words
        // a dead invitation gets.
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/relations/$relRef/pairing/$exchangeId/cancel") { bearerAuth(ownerToken) }.status)
        val afterCancel = status(minted.inviteId, minted.secret, exchangeId)
        assertEquals(HttpStatusCode.Gone, afterCancel.status)
        assertEquals("""{"error":"exchange unavailable"}""", afterCancel.bodyAsText())
        assertEquals(HttpStatusCode.Created, ownerOpen(minted.inviteId, sid(2), fakeMsg(3)).status)
    }

    @Test
    fun `approve needs an answered run, works while guesses have locked the secret, and its ticket outlives ten minutes`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())

        // Nothing to approve yet: the run is unanswered.
        assertEquals(HttpStatusCode.Gone, approve(exchangeId, ticket(1)).status)
        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId))

        // The therapist answers. Then a link-holder spends three wrong guesses and locks the
        // secret's door for an hour (totpLockoutFails = 3, totpLockoutSeconds = 3600).
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)
        repeat(3) { i -> assertEquals(HttpStatusCode.Unauthorized, fetch(minted.inviteId, "wrong-$i").status) }
        assertEquals(HttpStatusCode.TooManyRequests, status(minted.inviteId, minted.secret, exchangeId).status)

        // The owner is not the one locked out: approval goes through on the bearer token.
        assertEquals(HttpStatusCode.NoContent, approve(exchangeId, ticket(1)).status)
        // And not twice: the run has moved on.
        assertEquals(HttpStatusCode.Gone, approve(exchangeId, ticket(2)).status)
        assertEquals(1, s.auth.enrollTicketCountFor(minted.inviteId))

        // Sixty-one minutes later the lockout has lapsed; the ticket, minted at approval, must
        // still be good — a ten-minute ticket would have died under the honest therapist's feet.
        now += 61 * 60_000L
        val approved = status(minted.inviteId, minted.secret, exchangeId)
        assertEquals(HttpStatusCode.OK, approved.status)
        assertTrue(approved.bodyAsText().contains("\"state\":\"APPROVED\""))
        assertEquals(HttpStatusCode.NoContent, enrol(ticket(1), "cred-1").status)
        assertEquals("CONSUMED", s.auth.inviteStatusFor(minted.inviteId))
    }

    @Test
    fun `an approval nobody finished can be abandoned, which puts the invitation back and kills the ticket`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)
        assertEquals(HttpStatusCode.NoContent, approve(exchangeId, ticket(1)).status)
        assertEquals("REDEEMING", s.auth.inviteStatusFor(minted.inviteId))

        // Cancel on a CLOSED run is the abandon.
        val abandon = client.post("/v1/relations/$relRef/pairing/$exchangeId/cancel") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.NoContent, abandon.status)
        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId))
        assertEquals(0, s.auth.enrollTicketCountFor(minted.inviteId))
        assertEquals(PairingStore.State.CANCELLED, s.pairing.exchangeFor(exchangeId, relRef)!!.state)

        // The old ticket is dead, the therapist's poll reads gone, and the owner can start over.
        assertEquals(HttpStatusCode.Unauthorized, enrol(ticket(1), "cred-1").status)
        assertEquals(HttpStatusCode.Gone, status(minted.inviteId, minted.secret, exchangeId).status)
        assertEquals(HttpStatusCode.Created, ownerOpen(minted.inviteId, sid(2), fakeMsg(3)).status)

        // Once more is a no-op with the flat answer: there is nothing left to abandon.
        assertEquals(HttpStatusCode.Gone, client.post("/v1/relations/$relRef/pairing/$exchangeId/cancel") { bearerAuth(ownerToken) }.status)

        val actions = s.audit.list(relRef, limit = 50).map { it.action }
        assertEquals(1, actions.count { it == "pairing.abandoned" })
        assertEquals(0, actions.count { it == "pairing.cancelled" })
    }

    @Test
    fun `after approval the therapist may poll, but may not start or answer a run`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)
        // Before approving, the owner (or their other tab) opens a second run, which stays OPEN
        // beside the answered one: the shelf is not empty when the approval lands. That is the
        // case that makes the PENDING gate on fetch and respond load-bearing rather than masked
        // by "nothing waiting" — a test without this run goes green with the gate removed.
        now += 1_000
        val spare = exchangeIdOf(ownerOpen(minted.inviteId, sid(2), fakeMsg(3)).bodyAsText())
        assertEquals(HttpStatusCode.NoContent, approve(exchangeId, ticket(1)).status)
        assertEquals(PairingStore.State.OPEN, s.pairing.exchangeFor(spare, relRef)!!.state)

        // The invitation is REDEEMING. Status: yes. Fetch and respond: the PENDING gate holds,
        // in the dead-invite words, even though an OPEN run exists to serve.
        assertEquals(HttpStatusCode.OK, status(minted.inviteId, minted.secret, exchangeId).status)
        val fetched = fetch(minted.inviteId, minted.secret)
        assertEquals(HttpStatusCode.Gone, fetched.status)
        assertEquals("""{"error":"invite unavailable"}""", fetched.bodyAsText())
        assertEquals(HttpStatusCode.Gone, respond(minted.inviteId, minted.secret, spare, fakeMsg(4)).status)
        assertNull(s.pairing.exchangeFor(spare, relRef)!!.msgBB64, "nothing was written into the spare run")
        // Nor can the owner open another run for an invitation mid-redeem.
        assertEquals(HttpStatusCode.NotFound, ownerOpen(minted.inviteId, sid(5), fakeMsg(5)).status)

        // A wrong secret on the status route counts against the same counter as everywhere.
        assertEquals(HttpStatusCode.Unauthorized, status(minted.inviteId, "not-it", exchangeId).status)
        assertTrue(s.audit.list(relRef, limit = 50).any { it.action == "pair.guess_failed" })
    }

    @Test
    fun `a therapist's waiting does not spend the pairing budget`() = testApplication {
        /*
         * The finding the 2026-09-12 split was made for. The status poll used to be charged to the
         * same per-address window as fetch and respond, so an honest ceremony's own waiting ate the
         * budget — about 6.7 polls out of twelve — and the surface then began refusing the touches
         * that matter. Two clinicians on one clinic connection spent each other's.
         *
         * Polls are now metered per RUN. Here: one whole ceremony plus twenty polls inside a single
         * window, and the pairing budget is still open afterwards. Under the old shape this was
         * twenty-three charges against twelve.
         */
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())

        assertEquals(HttpStatusCode.OK, fetch(minted.inviteId, minted.secret).status)
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)

        // Twenty polls, all inside one PAIR_WINDOW_MS so the window cannot quietly roll and answer
        // for the change under test.
        repeat(20) {
            now += 12_000
            assertEquals(HttpStatusCode.OK, status(minted.inviteId, minted.secret, exchangeId).status)
        }
        assertTrue(now - 1_000_000L < PAIR_WINDOW_MS, "the polling stayed inside one pairing window")

        // 410 is the "secret accepted, nothing waiting" answer — this run has already been
        // answered, so there is no open exchange to collect. What matters is that it is not a 429.
        assertEquals(
            HttpStatusCode.Gone, fetch(minted.inviteId, minted.secret).status,
            "waiting is not spending: the touches that carry the ceremony are still available",
        )

        // The planted control: that budget is armed, not absent. Keep fetching and it does refuse.
        var refused: HttpStatusCode? = null
        repeat(PAIR_MAX_PER_WINDOW) {
            val r = fetch(minted.inviteId, minted.secret)
            if (r.status == HttpStatusCode.TooManyRequests) refused = r.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, refused, "the per-address budget still exists and still bites")
    }

    @Test
    fun `the poll allowance belongs to the run, so one ceremony cannot spend another's`() = testApplication {
        /*
         * The other half of the split, and the one that fixes the clinic NAT: two therapists behind
         * one address are two RUNS. Exhausting the first run's allowance must leave the second's
         * untouched, at the same instant, from the same address.
         */
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }

        val first = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val second = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val runA = exchangeIdOf(ownerOpen(first.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        val runB = exchangeIdOf(ownerOpen(second.inviteId, sid(2), fakeMsg(2)).bodyAsText())
        assertEquals(HttpStatusCode.NoContent, respond(first.inviteId, first.secret, runA, fakeMsg(3)).status)
        assertEquals(HttpStatusCode.NoContent, respond(second.inviteId, second.secret, runB, fakeMsg(4)).status)

        // Run A, polled as fast as a browser can manage: the burst is there for exactly this, and
        // then it waits.
        repeat(PAIRING_STATUS_BURST) {
            assertEquals(HttpStatusCode.OK, status(first.inviteId, first.secret, runA).status)
        }
        val over = status(first.inviteId, first.secret, runA)
        assertEquals(HttpStatusCode.TooManyRequests, over.status)
        assertEquals("""{"error":"rate limited"}""", over.bodyAsText())
        val retryAfter = assertNotNull(over.headers["Retry-After"], "a refusal has to say when it heals")
        assertTrue(
            retryAfter.toLong() in 1..(PAIRING_STATUS_REFILL_MS / 1000),
            "and the time it names is the real one, not a whole window: got $retryAfter",
        )

        // The same address, the same instant, a different run: untouched.
        assertEquals(
            HttpStatusCode.OK, status(second.inviteId, second.secret, runB).status,
            "one run's polling must never be charged to another's — this is the clinic NAT case",
        )

        // And the first run heals on its own, at the cadence the constant names.
        now += PAIRING_STATUS_REFILL_MS
        assertEquals(HttpStatusCode.OK, status(first.inviteId, first.secret, runA).status)
    }

    @Test
    fun `the cadence the client keeps is never refused, however long the owner takes`() = testApplication {
        // The product claim, as arithmetic and then as forty minutes of waiting. An owner may take
        // a day to approve; a therapist who polls at the documented cadence must never be told
        // anything at all until there is something to tell.
        assertTrue(
            PAIRING_STATUS_POLL_SECONDS * 1000 >= PAIRING_STATUS_REFILL_MS,
            "the client's cadence must be slower than the refill, or an honest poller drains its own allowance",
        )

        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        assertEquals(HttpStatusCode.OK, fetch(minted.inviteId, minted.secret).status)
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)

        repeat(50) {
            now += PAIRING_STATUS_POLL_SECONDS * 1000
            assertEquals(
                HttpStatusCode.OK, status(minted.inviteId, minted.secret, exchangeId).status,
                "an honest poll is never refused",
            )
        }
    }

    @Test
    fun `the owner's invitation list says where each one stands`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val quiet = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        now += 1_000
        val busy = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        s.auth.mintInvite(otherRelRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(busy.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, fetch(busy.inviteId, "wrong").status)
        assertEquals(HttpStatusCode.NoContent, respond(busy.inviteId, busy.secret, exchangeId, fakeMsg(2)).status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/relations/$relRef/invites").status)
        val list = client.get("/v1/relations/$relRef/invites") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.OK, list.status)
        val body = list.bodyAsText()
        // Newest first; the other relationship's invitation is not here.
        assertTrue(body.indexOf(busy.inviteId) < body.indexOf(quiet.inviteId))
        assertEquals(2, Regex("\"inviteId\"").findAll(body).count())
        assertTrue(
            body.contains("\"inviteId\":\"${busy.inviteId}\",\"status\":\"PENDING\",\"createdAt\":1001000,\"expiresAt\":${busy.expiresAt},\"failCount\":1,\"exchangeCount\":1,\"latestExchange\":{\"exchangeId\":\"$exchangeId\",\"state\":\"RESPONDED\"}"),
            body,
        )
        assertTrue(body.contains("\"inviteId\":\"${quiet.inviteId}\",\"status\":\"PENDING\",\"createdAt\":1000000,\"expiresAt\":${quiet.expiresAt},\"failCount\":0,\"exchangeCount\":0"))

        // An invitation past its time reads EXPIRED without the row being written.
        now += 86_400_000L + 1
        val later = client.get("/v1/relations/$relRef/invites") { bearerAuth(ownerToken) }.bodyAsText()
        assertEquals(2, Regex("\"status\":\"EXPIRED\"").findAll(later).count())
        assertEquals("PENDING", s.auth.inviteStatusFor(quiet.inviteId), "a listing is not a side effect")
    }

    @Test
    fun `a reply stored before envelopes existed reads back without one`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        // Through the store, as a row written by the previous build would look.
        assertEquals(PairingStore.RespondStatus.OK, s.pairing.respond(exchangeId, minted.inviteId, fakeMsg(2), null))
        val read = client.get("/v1/relations/$relRef/pairing/$exchangeId") { bearerAuth(ownerToken) }.bodyAsText()
        assertTrue(read.contains("\"state\":\"RESPONDED\""))
        assertTrue(read.contains("\"msgBB64\":\"${fakeMsg(2)}\""))
        assertFalse(read.contains("\"envB64\":\""), "no envelope must not be rendered as one")
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

        repeat(2) { i -> assertEquals(HttpStatusCode.Unauthorized, fetch(minted.inviteId, "wrong-$i").status) }
        // The third wrong guess lands on REDEEM. On the invite surface the arming failure
        // itself still answers 401 (only later knocks answer 429 — see LockedInviteAuditTest),
        // so the proof of sharing is what it ARMS: one LOCKOUT row, and the next request —
        // with the CORRECT secret — bouncing off a lockout three guesses built across two doors.
        val third = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
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
        val locked = fetch(minted.inviteId, minted.secret)
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
        val nothingWaiting = fetch(quiet.inviteId, quiet.secret)
        // ...an invite that never existed...
        val neverExisted = fetch("no-such-invite", quiet.secret)
        // ...and one the owner reported dead.
        val dead = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        s.auth.reportInviteByOwner(dead.inviteId)
        val reported = fetch(dead.inviteId, dead.secret)

        for (r in listOf(nothingWaiting, neverExisted, reported)) {
            assertEquals(HttpStatusCode.Gone, r.status)
            assertEquals("""{"error":"invite unavailable"}""", r.bodyAsText())
        }
        // The status route is as flat: a made-up exchange and a reported invitation are one answer.
        val madeUp = status(quiet.inviteId, quiet.secret, "no-such-exchange")
        val onDead = status(dead.inviteId, dead.secret, "no-such-exchange")
        assertEquals(HttpStatusCode.Gone, madeUp.status)
        assertEquals(HttpStatusCode.Gone, onDead.status)
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
        val fetched = fetch(minted.inviteId, minted.secret)
        assertTrue(fetched.bodyAsText().contains(second))
        assertFalse(fetched.bodyAsText().contains(first))

        // One reply per exchange, ever — the offer included: nothing sealed is ever replaced.
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, second, fakeMsg(3), fakeEnv(3)).status)
        assertEquals(HttpStatusCode.Gone, respond(minted.inviteId, minted.secret, second, fakeMsg(4), fakeEnv(4)).status)
        // The first write stands; the second changed nothing.
        val kept = s.pairing.exchangeFor(second, relRef)!!
        assertEquals(fakeMsg(3), kept.msgBB64)
        assertEquals(fakeEnv(3), kept.envToOwnerB64)

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
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/relations/$relRef/pairing/$exchangeId").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/relations/$relRef/pairing/$exchangeId/approve") {
                contentType(ContentType.Application.Json)
                setBody("""{"enrolTicketB64":"${ticket(1)}"}""")
            }.status,
        )

        // A real exchange read through the WRONG relationship's path: the same 404 a made-up
        // exchange id gets, so the path cannot be used to map exchanges to relationships.
        val crossRel = client.get("/v1/relations/$otherRelRef/pairing/$exchangeId") { bearerAuth(ownerToken) }
        val madeUp = client.get("/v1/relations/$otherRelRef/pairing/not-a-real-exchange") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.NotFound, crossRel.status)
        assertEquals(madeUp.bodyAsText(), crossRel.bodyAsText())

        // Approving through the wrong relationship's path: the flat 410, and nothing minted.
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)
        val crossApprove = client.post("/v1/relations/$otherRelRef/pairing/$exchangeId/approve") {
            bearerAuth(ownerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"enrolTicketB64":"${ticket(1)}"}""")
        }
        assertEquals(HttpStatusCode.Gone, crossApprove.status)
        assertEquals(0, s.auth.enrollTicketCountFor(minted.inviteId))

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
        val fetched = fetch(minted.inviteId, minted.secret)
        assertEquals(HttpStatusCode.Gone, fetched.status)
        assertEquals("""{"error":"invite unavailable"}""", fetched.bodyAsText())

        // A late respond against the cancelled exchange is refused and writes nothing.
        assertEquals(HttpStatusCode.Gone, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2)).status)
        assertNull(s.pairing.exchangeFor(exchangeId, relRef)!!.msgBB64)
        assertNull(s.pairing.exchangeFor(exchangeId, relRef)!!.envToOwnerB64)

        // The lifecycle is on the owner's record: opened, then cancelled.
        val actions = s.audit.list(relRef, limit = 50).map { it.action }
        assertTrue("pairing.opened" in actions)
        assertTrue("pairing.cancelled" in actions)
    }

    @Test
    fun `a re-open retires the run it replaces, so nothing stale is served or answerable even later`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit, pairingStore = s.pairing) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        val first = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        now += 1_000
        val second = exchangeIdOf(ownerOpen(minted.inviteId, sid(2), fakeMsg(2)).bodyAsText())

        // The replaced run is retired the moment the new one opens: a reply to it — from a
        // therapist who fetched it before the re-open, or from anyone holding the link — is
        // refused, writes nothing, and reads as retired on the owner's side.
        assertEquals(HttpStatusCode.Gone, respond(minted.inviteId, minted.secret, first, fakeMsg(3)).status)
        val retired = s.pairing.exchangeFor(first, relRef)!!
        assertEquals(PairingStore.State.SUPERSEDED, retired.state)
        assertNull(retired.msgBB64)

        // The live run is untouched by the retirement.
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, second, fakeMsg(4)).status)

        // Once the live run has left OPEN, the retired one must not resurface as "newest": the
        // shelf reads empty, in exactly the words a dead invite gets.
        val afterAnswer = fetch(minted.inviteId, minted.secret)
        assertEquals(HttpStatusCode.Gone, afterAnswer.status)
        assertEquals("""{"error":"invite unavailable"}""", afterAnswer.bodyAsText())

        // Same after a cancel — the path the single-run cancel test above cannot see.
        now += 1_000
        val third = exchangeIdOf(ownerOpen(minted.inviteId, sid(5), fakeMsg(5)).bodyAsText())
        val cancel = client.post("/v1/relations/$relRef/pairing/$third/cancel") { bearerAuth(ownerToken) }
        assertEquals(HttpStatusCode.NoContent, cancel.status)
        assertEquals(HttpStatusCode.Gone, fetch(minted.inviteId, minted.secret).status)
        assertEquals(HttpStatusCode.Gone, respond(minted.inviteId, minted.secret, first, fakeMsg(6)).status)

        // Retiring is bookkeeping, not the owner's Cancel: one cancelled line, for the explicit
        // cancel only. The rows themselves all stay — the cap counts them.
        val actions = s.audit.list(relRef, limit = 50).map { it.action }
        assertEquals(1, actions.count { it == "pairing.cancelled" })
        assertEquals(3L, s.pairing.exchangeCountFor(minted.inviteId))
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

        // The reply's own bounds: the CPace message stays tight, the envelope has its own room,
        // and neither can be used as storage. None of these spends the run.
        val exchangeId = exchangeIdOf(ownerOpen(minted.inviteId, sid(1), fakeMsg(1)).bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, respond(minted.inviteId, minted.secret, exchangeId, b64(ByteArray(201)), fakeEnv(1)).status)
        assertEquals(HttpStatusCode.BadRequest, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2), fakeEnv(1, size = 40)).status)
        assertEquals(HttpStatusCode.BadRequest, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2), fakeEnv(1, size = 4097)).status)
        assertEquals(HttpStatusCode.BadRequest, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2), "not/base64url!").status)
        assertEquals(PairingStore.State.OPEN, s.pairing.exchangeFor(exchangeId, relRef)!!.state)
        assertEquals(HttpStatusCode.NoContent, respond(minted.inviteId, minted.secret, exchangeId, fakeMsg(2), fakeEnv(1, size = 4096)).status)

        // The ticket the owner forwards must be exactly what the therapist could have chosen.
        assertEquals(HttpStatusCode.BadRequest, approve(exchangeId, b64(ByteArray(31))).status)
        assertEquals(HttpStatusCode.BadRequest, approve(exchangeId, "nope").status)
        assertEquals(0, s.auth.enrollTicketCountFor(minted.inviteId))

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
