package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.org.OrgRole
import com.daymark.companion.org.OrgStore
import com.daymark.companion.routes.attachMessage
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import com.daymark.companion.storage.Schema
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STAFF ACCOUNTS (#314): one account per person, relationships attached to it, staff invitations,
 * closing a lost account, and attaching a relationship by key proof. The test names follow the
 * issue's "How to check it" list, one for each line.
 *
 * Every absence assertion has a positive control beside it.
 */
class StaffAccountTest {

    private val ownerToken = "owner-token-abc"
    private val address = "https://daymark.test"

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 5, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false, publicBaseUrl = address,
    )

    private class Fixture(
        val dir: String,
        val cfg: Config,
        val auth: AuthStore,
        val rel: RelationStore,
        val audit: AuditStore,
        val orgs: OrgStore,
        val orgAudit: AuditStore,
    )

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("staff-account-test").toString()
        val cfg = config(dir)
        return Fixture(
            dir, cfg, AuthStore(dir),
            RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
            AuditStore(dir), OrgStore(dir), AuditStore(dir, dbName = "org-audit.db"),
        )
    }

    private fun ApplicationTestBuilder.serve(f: Fixture) {
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
    }

    private fun relOf(name: String) = Secrets.relRefOf("inbox-$name")

    private fun seedOf(accountId: String) = Secrets.blake2b(accountId.toByteArray(), 20)

    private fun code(seed: ByteArray, offsetSteps: Int = 0) = Totp.code(seed, System.currentTimeMillis() / 1000 + offsetSteps * 30L)

    /** A ticket the owner's approval would have minted for [relRef]: the real invite path, store-side. */
    private fun ticketFor(f: Fixture, relRef: String): String {
        val invite = f.auth.mintInvite(relRef, listOf("shares"), 3600L)
        return f.auth.redeemInvite(invite.inviteId, invite.secret, 5, 60_000L).enrollTicket ?: error("no ticket")
    }

    /** An account made by a patient's invitation for [relRef], as the clinician's first acceptance makes it. */
    private fun enrol(f: Fixture, accountId: String, relRef: String): ByteArray {
        val seed = seedOf(accountId)
        val r = f.auth.enrollTotp(ticketFor(f, relRef), accountId, Secrets.b64url(seed))
        check(r.status == AuthStore.EnrollStatus.OK) { "enrol said ${r.status}" }
        return seed
    }

    private class Signed(val sessionId: String, val csrf: String)

    private suspend fun HttpClient.signIn(accountId: String, code: String): Signed? {
        val res = post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"$accountId","code":"$code"}""")
        }
        if (res.status != HttpStatusCode.OK) return null
        val cookie = res.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("daymark_session=") }
        val sessionId = cookie.substringAfter("daymark_session=").substringBefore(';')
        val csrf = Json.parseToJsonElement(res.bodyAsText()).jsonObject["csrfToken"]!!.jsonPrimitive.content
        return Signed(sessionId, csrf)
    }

    private fun HttpRequestBuilder.as_(s: Signed, csrf: Boolean = false) {
        header(HttpHeaders.Cookie, "daymark_session=${s.sessionId}")
        if (csrf) header("X-CSRF-Token", s.csrf)
    }

    private fun HttpRequestBuilder.as_(s: AuthStore.NewSession, csrf: Boolean = false, stepUp: String? = null) {
        header(HttpHeaders.Cookie, "daymark_session=${s.sessionId}")
        if (csrf) header("X-CSRF-Token", s.csrfToken)
        if (stepUp != null) header("X-Stepup-Code", stepUp)
    }

    private fun publishOwnerKeys(f: Fixture, relRef: String) {
        f.auth.registerOwnerKeys(relRef, Secrets.b64url(ByteArray(32) { 1 }), Secrets.b64url(ByteArray(32) { 2 }))
    }

    private suspend fun HttpClient.ownerKeys(relRef: String, s: Signed): HttpResponse =
        get("/v1/relations/$relRef/owner-keys") { as_(s) }

    /** A clinician's Ed25519 key for one relationship, registered as the therapist-keys route does. */
    private class ClinicianKey(val seed: ByteArray) {
        val publicKey = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
        fun sign(message: ByteArray) = ByteArray(64).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }
    }

    private fun registerClinicianKey(f: Fixture, relRef: String, fill: Byte): ClinicianKey {
        val key = ClinicianKey(ByteArray(32) { fill })
        f.auth.registerTherapistKeys(relRef, Secrets.b64url(ByteArray(32) { 9 }), Secrets.b64url(key.publicKey))
        return key
    }

    private suspend fun HttpClient.challenge(s: Signed, relRef: String): Pair<String, String> {
        val res = post("/v1/account/attach/challenge") {
            as_(s, csrf = true)
            contentType(ContentType.Application.Json)
            setBody("""{"relRef":"$relRef"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(address, body["address"]!!.jsonPrimitive.content)
        return body["challengeId"]!!.jsonPrimitive.content to body["challenge"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.attach(s: Signed, relRef: String, challengeId: String, signature: ByteArray): HttpResponse =
        post("/v1/account/attach") {
            as_(s, csrf = true)
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challengeId","relRef":"$relRef","signature":"${Secrets.b64url(signature)}"}""")
        }

    // ── an account signs in once ─────────────────────────────────────────────────────────────

    @Test
    fun `an account signs in once and reaches only the relationships attached to it`() = testApplication {
        val f = fixture()
        serve(f)
        val relA = relOf("a")
        val relB = relOf("b")
        val relOther = relOf("other")
        val seed = enrol(f, "clin-1", relA)
        // The second patient's invitation, accepted by the same account.
        assertEquals(AuthStore.EnrollStatus.OK, f.auth.attachByInvitation(ticketFor(f, relB), "clin-1").status)
        enrol(f, "clin-2", relOther)
        listOf(relA, relB, relOther).forEach { publishOwnerKeys(f, it) }

        val s = assertNotNull(client.signIn("clin-1", code(seed)))
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relA, s).status)
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relB, s).status)
        // Another clinician's relationship is not reached by this sign-in. Control: both of this
        // account's own relationships answered 200 above with the same session.
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relOther, s).status)

        val list = client.get("/v1/account/relationships") { as_(s) }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains(relA) && list.bodyAsText().contains(relB), list.bodyAsText())
        assertFalse(list.bodyAsText().contains(relOther), list.bodyAsText())

        // The sign-in line reaches each relationship's own log, once.
        assertEquals(1, f.audit.list(relA).count { it.action == "auth.success" })
        assertEquals(1, f.audit.list(relB).count { it.action == "auth.success" })
        assertEquals(0, f.audit.list(relOther).count { it.action == "auth.success" })
    }

    @Test
    fun `a request naming an unattached relationship is refused exactly as an unknown one is`() = testApplication {
        val f = fixture()
        serve(f)
        val relA = relOf("a")
        val relTheirs = relOf("theirs")
        val seed = enrol(f, "clin-1", relA)
        enrol(f, "clin-2", relTheirs)
        publishOwnerKeys(f, relA)
        publishOwnerKeys(f, relTheirs)
        val s = assertNotNull(client.signIn("clin-1", code(seed)))

        val unattached = client.ownerKeys(relTheirs, s)
        val unknown = client.ownerKeys(relOf("never-existed"), s)
        assertEquals(unknown.status, unattached.status)
        assertEquals(unknown.bodyAsText(), unattached.bodyAsText())
        // Control: the refusal is a refusal, and the account's own relationship is answered.
        assertEquals(HttpStatusCode.Forbidden, unknown.status)
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relA, s).status)

        // The data plane says the same: one 401 for both.
        val blobUnattached = client.get("/v1/rel/$relTheirs/shares/share") { as_(s); header("X-Rel-Token", "inbox-theirs") }
        val blobUnknown = client.get("/v1/rel/${relOf("never-existed")}/shares/share") { as_(s); header("X-Rel-Token", "inbox-never-existed") }
        assertEquals(blobUnknown.status, blobUnattached.status)
        assertEquals(blobUnknown.bodyAsText(), blobUnattached.bodyAsText())
    }

    // ── leaving one relationship ─────────────────────────────────────────────────────────────

    @Test
    fun `leaving one relationship leaves the others untouched, and the ended one cannot be reached with the shared sign-in`() = testApplication {
        val f = fixture()
        serve(f)
        val relA = relOf("a")
        val relB = relOf("b")
        val seed = enrol(f, "clin-1", relA)
        f.auth.attachByInvitation(ticketFor(f, relB), "clin-1")
        publishOwnerKeys(f, relA)
        publishOwnerKeys(f, relB)
        val s = assertNotNull(client.signIn("clin-1", code(seed)))
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relA, s).status)

        val left = client.post("/v1/relations/$relA/ending") { as_(s, csrf = true) }
        assertEquals(HttpStatusCode.NoContent, left.status)
        // Leaving again answers as the first did, and writes no second line.
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/relations/$relA/ending") { as_(s, csrf = true) }.status)
        assertEquals(1, f.audit.list(relA).count { it.action == "relationship.ended" })

        // The same session: the ended relationship is refused, the other one carries on.
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relA, s).status)
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relB, s).status)
        // A fresh sign-in reaches it no more either; the account itself is open.
        val again = assertNotNull(client.signIn("clin-1", code(seed, 1)))
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relA, again).status)
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relB, again).status)
        assertEquals(listOf(relB), f.auth.relationshipsOf("clin-1"))
    }

    // ── closing a lost account, and its replacement ──────────────────────────────────────────

    /** A practice whose administrator holds a real authenticator, for step-up. */
    private fun practice(f: Fixture): Pair<AuthStore.NewSession, ByteArray> {
        val adminSeed = seedOf("admin")
        assertTrue(f.auth.createAccount("admin", Secrets.b64url(adminSeed), 0))
        f.orgs.createOrg("orgA", "Practice A", "admin", "platform")
        f.orgs.acceptMembership("orgA", "admin")
        return f.auth.createSession("admin", f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds) to adminSeed
    }

    @Test
    fun `closing a lost account ends its sign-in and writes one audit row`() = testApplication {
        val f = fixture()
        serve(f)
        val (admin, adminSeed) = practice(f)
        val relA = relOf("a")
        val seed = enrol(f, "lost", relA)
        publishOwnerKeys(f, relA)
        f.orgs.addMember("orgA", "lost", OrgRole.CLINICIAN, "admin")
        f.orgs.acceptMembership("orgA", "lost")
        val s = assertNotNull(client.signIn("lost", code(seed)))
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relA, s).status)

        val closed = client.post("/v1/orgs/orgA/members/lost/close") { as_(admin, csrf = true, stepUp = code(adminSeed)) }
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        // Closing again is quiet.
        assertEquals(HttpStatusCode.OK, client.post("/v1/orgs/orgA/members/lost/close") { as_(admin, csrf = true, stepUp = code(adminSeed, 1)) }.status)
        assertEquals(1, f.orgAudit.list("orgA").count { it.action == "org.account_closed" })

        // The open session is gone, and the authenticator opens nothing any more.
        assertEquals(HttpStatusCode.Unauthorized, client.ownerKeys(relA, s).status)
        assertNull(client.signIn("lost", code(seed, 1)))
        assertTrue(f.auth.isClosed("lost"))
        // Control: the account was real and signing in worked a moment ago, above.
    }

    @Test
    fun `an administrator-seated replacement account reaches no relationship and cannot end one`() = testApplication {
        val f = fixture()
        serve(f)
        val (admin, adminSeed) = practice(f)
        val relA = relOf("a")
        enrol(f, "lost", relA)
        publishOwnerKeys(f, relA)
        f.orgs.addMember("orgA", "lost", OrgRole.CLINICIAN, "admin")
        f.orgs.acceptMembership("orgA", "lost")
        client.post("/v1/orgs/orgA/members/lost/close") { as_(admin, csrf = true, stepUp = code(adminSeed)) }

        // The administrator seats the replacement through a staff invitation.
        val minted = client.post("/v1/orgs/orgA/staff-invites") {
            as_(admin, csrf = true, stepUp = code(adminSeed, 1))
            contentType(ContentType.Application.Json)
            setBody("""{"role":"clinician"}""")
        }
        assertEquals(HttpStatusCode.Created, minted.status, minted.bodyAsText())
        val link = Json.parseToJsonElement(minted.bodyAsText()).jsonObject["link"]!!.jsonPrimitive.content
        assertTrue(link.startsWith("$address/practice.html#join="), link)
        val invitation = link.substringAfter("#join=")

        val newSeed = seedOf("replacement")
        val joined = client.post("/v1/orgs/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"invitation":"$invitation","accountId":"replacement","totpSecret":"${Secrets.b64url(newSeed)}","totpCode":"${code(newSeed)}"}""")
        }
        assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
        assertEquals(true, f.orgs.membership("orgA", "replacement")?.accepted)
        assertTrue(f.auth.relationshipsOf("replacement").isEmpty())

        val s = assertNotNull(client.signIn("replacement", code(newSeed, 1)))
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relA, s).status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/relations/$relA/ending") { as_(s, csrf = true) }.status)
        assertNull(f.auth.relationshipEnding(relA))
        // The invitation was good once.
        val reused = client.post("/v1/orgs/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"invitation":"$invitation","accountId":"second","totpSecret":"${Secrets.b64url(seedOf("second"))}","totpCode":"${code(seedOf("second"))}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status)
        assertFalse(f.auth.accountExists("second"))
    }

    // ── a key proof ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a correct key proof attaches exactly that relationship`() = testApplication {
        val f = fixture()
        serve(f)
        val relA = relOf("a")
        val relB = relOf("b")
        val relC = relOf("c")
        // Three relationships made one credential each before accounts: here, three accounts.
        val seed = enrol(f, "clin-main", relA)
        enrol(f, "clin-old-b", relB)
        enrol(f, "clin-old-c", relC)
        listOf(relA, relB, relC).forEach { publishOwnerKeys(f, it) }
        val keyB = registerClinicianKey(f, relB, 3)
        registerClinicianKey(f, relC, 4)
        val s = assertNotNull(client.signIn("clin-main", code(seed)))
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relB, s).status)

        // A wrong signature is refused, and spends the challenge.
        val (badId, badChallenge) = client.challenge(s, relB)
        val wrongKey = ClinicianKey(ByteArray(32) { 5 })
        val wrong = client.attach(s, relB, badId, wrongKey.sign(attachMessage("clin-main", relB, address, badChallenge)))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        // A proof for a different account does not pass for this one.
        val (otherId, otherChallenge) = client.challenge(s, relB)
        assertEquals(HttpStatusCode.Unauthorized, client.attach(s, relB, otherId, keyB.sign(attachMessage("someone-else", relB, address, otherChallenge))).status)
        assertEquals("clin-old-b", f.auth.attachmentOf(relB)?.accountId)

        // The right key over a fresh challenge attaches relB, and nothing else.
        val (id, challenge) = client.challenge(s, relB)
        val good = client.attach(s, relB, id, keyB.sign(attachMessage("clin-main", relB, address, challenge)))
        assertEquals(HttpStatusCode.NoContent, good.status, good.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relB, s).status)
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relC, s).status)
        assertEquals(listOf(relA, relB), f.auth.relationshipsOf("clin-main"))
        assertEquals(1, f.audit.list(relB).count { it.action == "relationship.attached" })
        // The account that held it before reaches it no more.
        assertFalse(f.auth.reaches("clin-old-b", relB))

        // The same signed challenge, replayed, is refused: it was spent.
        assertEquals(HttpStatusCode.Unauthorized, client.attach(s, relB, id, keyB.sign(attachMessage("clin-main", relB, address, challenge))).status)
        // relB's key does not attach relC.
        val (cId, cChallenge) = client.challenge(s, relC)
        assertEquals(HttpStatusCode.Unauthorized, client.attach(s, relC, cId, keyB.sign(attachMessage("clin-main", relC, address, cChallenge))).status)
        assertEquals("clin-old-c", f.auth.attachmentOf(relC)?.accountId)
    }

    @Test
    fun `an ended relationship cannot be attached by key proof`() = testApplication {
        val f = fixture()
        serve(f)
        val relB = relOf("b")
        val seed = enrol(f, "clin-main", relOf("a"))
        enrol(f, "clin-old", relB)
        val key = registerClinicianKey(f, relB, 3)
        f.auth.endRelationship(relB, "clin-old")
        val s = assertNotNull(client.signIn("clin-main", code(seed)))
        val (id, challenge) = client.challenge(s, relB)
        assertEquals(HttpStatusCode.Unauthorized, client.attach(s, relB, id, key.sign(attachMessage("clin-main", relB, address, challenge))).status)
        assertEquals("clin-old", f.auth.attachmentOf(relB)?.accountId)
    }

    // ── invitations ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a leftover invitation can never attach a relationship to a second account`() {
        val f = fixture()
        val relA = relOf("a")
        enrol(f, "clin-1", relA)
        // A second invitation for the same relationship, taken by somebody else, both ways.
        assertEquals(AuthStore.EnrollStatus.ALREADY_ENROLLED, f.auth.enrollTotp(ticketFor(f, relA), "intruder", Secrets.b64url(seedOf("x"))).status)
        assertTrue(f.auth.createAccount("other", Secrets.b64url(seedOf("other")), 0))
        assertEquals(AuthStore.EnrollStatus.ALREADY_ENROLLED, f.auth.attachByInvitation(ticketFor(f, relA), "other").status)
        assertEquals("clin-1", f.auth.attachmentOf(relA)?.accountId)
        assertFalse(f.auth.accountExists("intruder"))
        // Control: the same calls on a relationship nobody holds succeed.
        assertEquals(AuthStore.EnrollStatus.OK, f.auth.attachByInvitation(ticketFor(f, relOf("fresh")), "other").status)
    }

    @Test
    fun `a receptionist joins a practice with a staff invitation and is seated under their own account`() = testApplication {
        val f = fixture()
        serve(f)
        val (admin, adminSeed) = practice(f)
        val minted = client.post("/v1/orgs/orgA/staff-invites") {
            as_(admin, csrf = true, stepUp = code(adminSeed))
            contentType(ContentType.Application.Json)
            setBody("""{"role":"front_desk"}""")
        }
        val invitation = Json.parseToJsonElement(minted.bodyAsText()).jsonObject["link"]!!.jsonPrimitive.content.substringAfter("#join=")
        val seed = seedOf("desk")
        // A wrong code spends nothing.
        val wrongCode = if (code(seed) == "000000") "111111" else "000000"
        val mistyped = client.post("/v1/orgs/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"invitation":"$invitation","accountId":"desk","totpSecret":"${Secrets.b64url(seed)}","totpCode":"$wrongCode"}""")
        }
        assertNotEquals(HttpStatusCode.OK, mistyped.status)
        assertTrue(f.auth.staffInviteLive(invitation))

        val joined = client.post("/v1/orgs/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"invitation":"$invitation","accountId":"desk","totpSecret":"${Secrets.b64url(seed)}","totpCode":"${code(seed)}"}""")
        }
        assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
        val seat = assertNotNull(f.orgs.membership("orgA", "desk"))
        assertEquals(OrgRole.FRONT_DESK, seat.role)
        assertTrue(seat.accepted)
        assertEquals(1, f.orgAudit.list("orgA").count { it.action == "org.member_joined" })
        assertEquals(1, f.orgAudit.list("orgA").count { it.action == "org.staff_invited" })
        // The join signed them in.
        val cookie = joined.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("daymark_session=") }
        val roster = client.get("/v1/orgs/orgA/members") { header(HttpHeaders.Cookie, cookie.substringBefore(';')) }
        assertEquals(HttpStatusCode.OK, roster.status)
    }

    @Test
    fun `the operator gives out administrator invitations only`() = testApplication {
        val f = fixture()
        serve(f)
        practice(f)
        val asAdmin = client.post("/v1/orgs/orgA/staff-invites") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"org_admin"}""")
        }
        assertEquals(HttpStatusCode.Created, asAdmin.status, asAdmin.bodyAsText())
        val asClinician = client.post("/v1/orgs/orgA/staff-invites") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"clinician"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, asClinician.status)
    }

    // ── migration ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `each per-relationship credential becomes its own account, and still signs in`() = testApplication {
        val dir = Files.createTempDirectory("staff-migration-test")
        val seed = seedOf("cred-old")
        // auth.db as version 1 left it: one credential per relationship, and a live old session.
        Schema("auth.db", listOf(AuthStore.SCHEMA.versions[0])).open(dir).use { c ->
            c.createStatement().use { st ->
                st.execute("INSERT INTO totp(credential_id, rel_ref, secret_b64, created_at) VALUES ('cred-old', '${relOf("a")}', '${Secrets.b64url(seed)}', 1)")
                st.execute("INSERT INTO totp(credential_id, rel_ref, secret_b64, created_at) VALUES ('cred-two', '${relOf("b")}', '${Secrets.b64url(seedOf("cred-two"))}', 2)")
                st.execute("INSERT INTO sessions VALUES ('h', 'cred-old', '${relOf("a")}', 'csrf', 1, 1, 9999999999999, 0)")
            }
        }
        val cfg = config(dir.toString())
        val f = Fixture(
            dir.toString(), cfg, AuthStore(dir.toString()),
            RelationStore(dir.toString(), cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
            AuditStore(dir.toString()), OrgStore(dir.toString()), AuditStore(dir.toString(), dbName = "org-audit.db"),
        )
        assertEquals(listOf(relOf("a")), f.auth.relationshipsOf("cred-old"))
        assertEquals(listOf(relOf("b")), f.auth.relationshipsOf("cred-two"))
        assertEquals("migrated", f.auth.attachmentOf(relOf("a"))?.how)
        // The old rows are where version 1 left them; nothing deleted them.
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("auth.db")}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM totp").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) } }
        }

        serve(f)
        publishOwnerKeys(f, relOf("a"))
        val s = assertNotNull(client.signIn("cred-old", code(seed)))
        assertEquals(HttpStatusCode.OK, client.ownerKeys(relOf("a"), s).status)
        assertEquals(HttpStatusCode.Forbidden, client.ownerKeys(relOf("b"), s).status)
    }

    // ── what an administrator is handed ──────────────────────────────────────────────────────

    /** Response types an administrator's console reads: every `…Dto` in the admin and practice routes. */
    private val adminRouteFiles = listOf("AdminRoutes.kt", "OrgRoutes.kt")
        .map { File("src/main/kotlin/com/daymark/companion/routes/$it").readText() }

    /** A field of an administrator response that would carry something that opens content, or a credential. */
    private val forbidden = Regex("""(?i)(key|seed|secret|cipher|wrapped|inbox|token)""")

    /** The session's own anti-forgery token is the administrator's, and opens nothing of anybody else's. */
    private val allowed = setOf("csrfToken")

    private fun offendingFields(source: String): List<String> =
        Regex("""@Serializable data class (\w+Dto)\(([^)]*)\)""").findAll(source).flatMap { m ->
            Regex("""val (\w+)\s*:""").findAll(m.groupValues[2]).map { it.groupValues[1] }
                .filter { forbidden.containsMatchIn(it) && it !in allowed }
                .map { "${m.groupValues[1]}.$it" }
        }.toList()

    @Test
    fun `no administrator response type has a key, seed, token or ciphertext field`() {
        val dtos = adminRouteFiles.sumOf { Regex("""@Serializable data class \w+Dto\(""").findAll(it).count() }
        assertTrue(dtos >= 10, "the scan found $dtos response types, so it is not reading the route files")
        assertEquals(emptyList(), adminRouteFiles.flatMap { offendingFields(it) })
        // Control: a planted field is seen.
        val planted = "@Serializable data class PlantedDto(val memberId: String, val inboxToken: String, val wrappedKey: String)"
        assertEquals(listOf("PlantedDto.inboxToken", "PlantedDto.wrappedKey"), offendingFields(planted))
    }

    @Test
    fun `no route attaches a relationship through the fixture door`() {
        val callers = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("attachForFixture(") }
            .map { it.name }.toList()
        assertEquals(listOf("AuthStore.kt"), callers, "only its definition may name it")
        // Control: the test fixture that uses it is seen by the same search.
        assertTrue(File("src/test/kotlin/com/daymark/companion/StaffFixtures.kt").readText().contains("attachForFixture("))
    }

    @Test
    fun `the attach message is fixed, one field per line`() {
        assertEquals(
            "daymark-staff-attach-v1\nacct\nrel\nhttps://daymark.test\nchal",
            attachMessage("acct", "rel", "https://daymark.test", "chal").toString(Charsets.UTF_8),
        )
        assertEquals(DeviceSignature.PUBLIC_KEY_BYTES, ClinicianKey(ByteArray(32)).publicKey.size)
        assertTrue(Path.of("src/main/kotlin").toFile().exists())
    }
}
