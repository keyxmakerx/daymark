package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.org.OrgRole
import com.daymark.companion.org.OrgStore
import com.daymark.companion.org.OrgWrite
import com.daymark.companion.routes.OrgMemberDto
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The org / practice control plane over HTTP.
 *
 * Almost every test here is a REFUSAL, because the refusals are the product. A practice that can
 * seat a clinician is plumbing; a practice whose administrator can revoke anyone, re-role anyone and
 * read every line of the practice's history while still being unable to open one clinical note is
 * the thing docs/COMPANION_ACCESS_CONTROL.md is asking for, and none of it is visible in a
 * happy-path test.
 *
 * The two claims under test, which the whole design rests on:
 *
 *   A ROLE NEVER CARRIES A KEY, and MEMBERSHIP NEVER GRANTS READ. Read capability comes only from a
 *   grant a patient minted on their own device. So the tests below do not merely assert that an
 *   admin is refused a key — they set up a REAL sealed share, prove a clinician holding the
 *   patient's relationship can read it, and then prove that org standing of every kind, up to and
 *   including the practice's administrator, reads nothing. A refusal is only evidence when the thing
 *   refused was genuinely there to be had.
 */
class OrgControlPlaneTest {

    private val ownerToken = "owner-token-abc"

    /** The patient's relationship: the inbox token addresses it, its digest names it. */
    private val patientInbox = "inbox-token-for-the-patient-xyz"
    private val patientRel = Secrets.relRefOf(patientInbox)

    /** What a real sealed share looks like from the server's side: opaque bytes it cannot read. */
    private val ciphertext = "SEALED-CLINICAL-NOTE-THE-SERVER-CANNOT-READ".toByteArray()

    /** The owner console's real header, lifted from RelationRoutesTest: expiry in the year 2100. */
    private val shareMeta =
        "eyJzaGFyZUlkIjogInQiLCAidmVyc2lvbiI6IDAsICJleHBpcnkiOiA0MTAyNDQ0ODAwMDAwLCAib3duZXJTaWduaW5nRnAiOiAiZnAifQ"

    private fun config(dir: String, therapistAuth: Boolean = true) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = therapistAuth, totpLockoutFails = 5, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("org-control-plane-test").toString()

    /**
     * Every store the module needs. The clocks are REAL here, unlike the public-key relay's tests:
     * step-up verifies a live TOTP window against `System.currentTimeMillis()`, so a frozen store
     * clock would put the test and the route in different minutes.
     */
    private class Fixture(
        val cfg: Config,
        val auth: AuthStore,
        val rel: RelationStore,
        val audit: AuditStore,
        val orgs: OrgStore,
        val orgAudit: AuditStore,
    )

    private fun fixture(): Fixture {
        val dir = tmpDir()
        val cfg = config(dir)
        return Fixture(
            cfg,
            AuthStore(dir),
            RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
            AuditStore(dir),
            OrgStore(dir),
            AuditStore(dir, dbName = "org-audit.db"),
        )
    }

    // ---- request shorthands ------------------------------------------------------

    private fun HttpRequestBuilder.asMember(
        session: AuthStore.NewSession,
        csrf: Boolean = false,
        stepUp: String? = null,
    ) {
        header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        if (csrf) header("X-CSRF-Token", session.csrfToken)
        if (stepUp != null) header("X-Stepup-Code", stepUp)
    }

    private fun HttpRequestBuilder.asOwner() {
        header(HttpHeaders.Authorization, "Bearer $ownerToken")
    }

    private fun session(f: Fixture, credentialId: String, relRef: String): AuthStore.NewSession =
        f.auth.createSession(credentialId, relRef, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

    /**
     * Enrol a real TOTP authenticator for a member, through the real invite/redeem/enrol path.
     *
     * Step-up is only meaningful if the code it checks is a real one, so the test does not reach
     * behind the store to plant a credential. Each credential needs its own relationship because
     * enrolment is one-per-relationship by unique index.
     */
    private fun enrolAuthenticator(
        f: Fixture,
        credentialId: String,
        /**
         * Which relationship the credential is enrolled against. Defaults to one of its own, which
         * is what every step-up test wants; the revocation tests pass the PATIENT's relationship on
         * purpose, because a credential enrolled there is what a real clinician holds and the only
         * thing that can sign in and read a real share.
         */
        relRef: String = Secrets.relRefOf("authenticator-for-$credentialId"),
    ): ByteArray {
        val invite = f.auth.mintInvite(relRef, listOf("assignments"), 3600L)
        val redeemed = f.auth.redeemInvite(invite.inviteId, invite.secret, 5, 60_000L)
        val ticket = redeemed.enrollTicket ?: error("test setup: no enrolment ticket")
        val seed = Secrets.blake2b(credentialId.toByteArray(Charsets.UTF_8), 20)
        val enrolled = f.auth.enrollTotp(ticket, credentialId, Secrets.b64url(seed))
        check(enrolled.status == AuthStore.EnrollStatus.OK) { "test setup: enrol said ${enrolled.status}" }
        return seed
    }

    private fun stepUpCode(seed: ByteArray, offsetSeconds: Long = 0): String =
        Totp.code(seed, System.currentTimeMillis() / 1000 + offsetSeconds)

    /** A six-digit code that is not valid in any step the server could plausibly be in. */
    private fun definitelyWrongCode(seed: ByteArray): String {
        val nowS = System.currentTimeMillis() / 1000
        val plausible = (-2L..2L).map { Totp.code(seed, nowS + it * 30) }.toSet()
        var n = 0
        while (true) {
            val candidate = n.toString().padStart(6, '0')
            if (candidate !in plausible) return candidate
            n++
        }
    }

    private fun addMemberBody(memberId: String, role: OrgRole) = """{"memberId":"$memberId","role":"${role.wire}"}"""

    // ---- tenancy -----------------------------------------------------------------

    @Test
    fun `an org admin of one practice cannot read, modify or enumerate another`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.createOrg("orgB", "Practice B", "adminB", "platform")
        f.orgs.addMember("orgB", "clinB", OrgRole.CLINICIAN, "adminB")
        val seed = enrolAuthenticator(f, "adminA")
        val adminA = session(f, "adminA", patientRel)

        // In their own practice they are the administrator, and the roster is theirs to read.
        val own = client.get("/v1/orgs/orgA/members") { asMember(adminA) }
        assertEquals(HttpStatusCode.OK, own.status)
        assertTrue(own.bodyAsText().contains("adminA"), own.bodyAsText())

        // Next door they are a stranger, and every method says so identically. 404 rather than 403
        // on purpose: 403 would confirm practice B exists, and an attacker with any valid session
        // could then walk practice ids and map every clinic on the deployment.
        val reads = listOf(
            client.get("/v1/orgs/orgB/members") { asMember(adminA) },
            client.get("/v1/orgs/orgB/audit") { asMember(adminA) },
        )
        val writes = listOf(
            client.post("/v1/orgs/orgB/members") {
                asMember(adminA, csrf = true, stepUp = stepUpCode(seed))
                contentType(ContentType.Application.Json)
                setBody(addMemberBody("planted", OrgRole.ORG_ADMIN))
            },
            client.post("/v1/orgs/orgB/members/clinB/role") {
                asMember(adminA, csrf = true, stepUp = stepUpCode(seed))
                contentType(ContentType.Application.Json)
                setBody("""{"role":"front_desk"}""")
            },
            client.delete("/v1/orgs/orgB/members/clinB") { asMember(adminA, csrf = true, stepUp = stepUpCode(seed)) },
        )
        for (res in reads + writes) {
            assertEquals(HttpStatusCode.NotFound, res.status, res.bodyAsText())
            // Not even the ids they guessed come back. A body echoing "clinB" would confirm the
            // person as surely as a 403 would confirm the practice.
            assertFalse(res.bodyAsText().contains("clinB"), res.bodyAsText())
            assertFalse(res.bodyAsText().contains("adminB"), res.bodyAsText())
        }

        // And practice B is exactly as it was. The status codes are the smaller half of this test:
        // a refusal that had nevertheless applied would pass a status-only assertion and be the bug.
        assertEquals(listOf("adminB", "clinB"), f.orgs.roster("orgB").map { it.memberId })
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgB", "clinB")?.role)
        assertNull(f.orgs.membership("orgB", "planted"))
    }

    @Test
    fun `a practice you are not in and a practice that does not exist are answered identically`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgReal", "A Real Practice", "adminReal", "platform")
        // A valid session for somebody who is in no practice at all.
        val outsider = session(f, "outsider", patientRel)

        val real = client.get("/v1/orgs/orgReal/members") { asMember(outsider) }
        val imaginary = client.get("/v1/orgs/orgNeverCreated/members") { asMember(outsider) }
        assertEquals(HttpStatusCode.NotFound, real.status)
        assertEquals(HttpStatusCode.NotFound, imaginary.status)
        // Byte-identical bodies. Any difference at all — a word, a field, a length — is the oracle.
        assertEquals(imaginary.bodyAsText(), real.bodyAsText())
        assertFalse(real.bodyAsText().contains("adminReal"), real.bodyAsText())
    }

    // ---- membership grants no read -----------------------------------------------

    @Test
    fun `a supervisor gets no read by title - only a patient grant opens anything`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "clinician1", OrgRole.CLINICIAN, "adminA")
        f.orgs.addMember("orgA", "supervisor1", OrgRole.SUPERVISOR, "adminA")

        // The patient publishes a sealed share into the relationship they hold with clinician1.
        // This is the "grant" end of the model: the clinician's session is bound to that
        // relationship because the patient put them there, not because the practice did.
        assertEquals(
            HttpStatusCode.Created,
            client.put("/v1/rel/$patientRel/shares/lin/0") {
                asOwner(); header("X-Rel-Token", patientInbox); header("X-Share-Meta", shareMeta); setBody(ciphertext)
            }.status,
        )
        val clinicianSession = session(f, "clinician1", patientRel)
        // Non-vacuity: there really is something to read, and somebody who was granted it can.
        val granted = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); asMember(clinicianSession)
        }
        assertEquals(HttpStatusCode.OK, granted.status)
        assertContentEquals(ciphertext, granted.bodyAsBytes())

        // The supervisor oversees that clinician and is senior to them in the practice. They are
        // handed the relationship's own addressing token — the strongest position org standing could
        // possibly put them in — and they still read nothing, because supervision is not consent.
        // The spec: a supervisor reads "only via explicit, consented grant, never by title".
        val supervisorSession = session(f, "supervisor1", Secrets.relRefOf("supervisor-own-relationship"))
        val refused = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); asMember(supervisorSession)
        }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertFalse(refused.bodyAsText().contains("SEALED"), refused.bodyAsText())

        // Their org standing is real and useful and buys them exactly nothing here: they can see the
        // roster, and that is where it stops.
        assertEquals(HttpStatusCode.OK, client.get("/v1/orgs/orgA/members") { asMember(supervisorSession) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/orgs/orgA/audit") { asMember(supervisorSession) }.status)
    }

    @Test
    fun `a front desk member reaches scheduling standing and nothing clinical`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "frontdesk1", OrgRole.FRONT_DESK, "adminA")
        f.orgs.addMember("orgA", "clinician1", OrgRole.CLINICIAN, "adminA")
        client.put("/v1/rel/$patientRel/shares/lin/0") {
            asOwner(); header("X-Rel-Token", patientInbox); header("X-Share-Meta", shareMeta); setBody(ciphertext)
        }
        val seed = enrolAuthenticator(f, "frontdesk1")
        val desk = session(f, "frontdesk1", Secrets.relRefOf("front-desk-own-relationship"))

        // Membership logistics: they can see who works there. That is the role's whole standing in
        // this surface, and it is metadata about staff rather than about anybody's care.
        assertEquals(HttpStatusCode.OK, client.get("/v1/orgs/orgA/members") { asMember(desk) }.status)

        // Everything that changes who may be granted is refused — with a real step-up code attached,
        // so the refusal is about the ROLE rather than about a missing header.
        val membershipWrites = listOf(
            client.post("/v1/orgs/orgA/members") {
                asMember(desk, csrf = true, stepUp = stepUpCode(seed))
                contentType(ContentType.Application.Json); setBody(addMemberBody("smuggled", OrgRole.CLINICIAN))
            },
            client.post("/v1/orgs/orgA/members/clinician1/role") {
                asMember(desk, csrf = true, stepUp = stepUpCode(seed))
                contentType(ContentType.Application.Json); setBody("""{"role":"org_admin"}""")
            },
            client.delete("/v1/orgs/orgA/members/clinician1") { asMember(desk, csrf = true, stepUp = stepUpCode(seed)) },
            client.get("/v1/orgs/orgA/audit") { asMember(desk) },
        )
        for (res in membershipWrites) assertEquals(HttpStatusCode.Forbidden, res.status, res.bodyAsText())
        assertNull(f.orgs.membership("orgA", "smuggled"))
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgA", "clinician1")?.role)

        // And nothing clinical: scheduling metadata only, per the catalog. Holding the relationship's
        // addressing token changes nothing, because addressing is not consent.
        val clinical = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); asMember(desk)
        }
        assertEquals(HttpStatusCode.Unauthorized, clinical.status)
        assertFalse(clinical.bodyAsText().contains("SEALED"), clinical.bodyAsText())

        // The refusals are the practice's to see. A front-desk account repeatedly trying to add
        // members is either a broken client or somebody finding the wall, and the server cannot tell
        // those apart — so it refuses both identically and writes the line.
        val denials = f.orgAudit.list("orgA").filter { it.action == "org.action_denied" }
        assertTrue(denials.isNotEmpty(), f.orgAudit.list("orgA").toString())
        assertTrue(denials.all { it.actor == "org_member" }, denials.toString())
    }

    @Test
    fun `no route in the control plane hands back a key, a grant or any ciphertext`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "clinician1", OrgRole.CLINICIAN, "adminA")
        val seed = enrolAuthenticator(f, "adminA")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        // Put real material on the server first, so "nothing came back" means something. A sealed
        // share, and a published pair of public halves for the same relationship.
        client.put("/v1/rel/$patientRel/shares/lin/0") {
            asOwner(); header("X-Rel-Token", patientInbox); header("X-Share-Meta", shareMeta); setBody(ciphertext)
        }
        val published = Secrets.b64url(ByteArray(32) { (it + 1).toByte() })
        f.auth.registerTherapistKeys(patientRel, published, published)

        // Every route the control plane answers, exercised as the practice's administrator.
        val everything = listOf(
            client.get("/v1/orgs/orgA/members") { asMember(admin) },
            client.get("/v1/orgs/orgA/audit") { asMember(admin) },
            client.post("/v1/orgs/orgA/members") {
                asMember(admin, csrf = true, stepUp = stepUpCode(seed))
                contentType(ContentType.Application.Json); setBody(addMemberBody("newcomer", OrgRole.CLINICIAN))
            },
            client.post("/v1/orgs/orgA/members/clinician1/role") {
                asMember(admin, csrf = true, stepUp = stepUpCode(seed, 30))
                contentType(ContentType.Application.Json); setBody("""{"role":"supervisor"}""")
            },
            client.delete("/v1/orgs/orgA/members/clinician1") { asMember(admin, csrf = true) },
        )
        for (res in everything) {
            val body = res.bodyAsText()
            assertTrue(res.status.value < 500, "$body")
            assertFalse(body.contains("SEALED"), "a control-plane route returned ciphertext: $body")
            assertFalse(body.contains(published), "a control-plane route returned key material: $body")
        }

        // And the administrator cannot reach the data plane by hand either. The public-key read is
        // the operator's, not a member's; the share needs a grant this session does not carry.
        val keyRead = client.get("/v1/relations/$patientRel/therapist-keys") { asMember(admin) }
        assertEquals(HttpStatusCode.Unauthorized, keyRead.status)
        assertFalse(keyRead.bodyAsText().contains(published))
        val shareRead = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); asMember(admin)
        }
        assertEquals(HttpStatusCode.Unauthorized, shareRead.status)
        assertFalse(shareRead.bodyAsText().contains("SEALED"))
    }

    @Test
    fun `the roster wire type has nowhere to put a key`() {
        // A structural guard on the shape an admin's roster read hands back. Four fields: an
        // identifier, a role name, a timestamp, and whether the person has agreed the seat is
        // theirs. If a `wrappedKey`, `grantRef` or `patients` field is ever added, membership starts
        // carrying read capability and this fails first.
        //
        // `accepted` had to be argued for to get in here, which is the point of an exact list. It
        // is a boolean about the membership, it opens nothing, and it is the fact that separates a
        // practice's claim about somebody from that person's agreement — the thing the session cut
        // on removal is gated on, and therefore something an admin's roster has to be able to show.
        val fields = OrgMemberDto::class.java.declaredFields
            // Instance fields only: the serialization plugin adds a static `Companion`, which is
            // machinery rather than something that travels on the wire.
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("memberId", "role", "addedAt", "accepted"), fields)
    }

    // ---- revocation --------------------------------------------------------------

    @Test
    fun `removing a member takes effect immediately and is audited`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.createOrg("orgB", "Practice B", "adminB", "platform")
        f.orgs.addMember("orgA", "leaver", OrgRole.CLINICIAN, "adminA")
        // The same person also works next door. Sessions belong to a credential rather than to a
        // practice, so their being signed out is going to cross that boundary; their STANDING must
        // not, and the assertion at the end of this test is what says so.
        f.orgs.addMember("orgB", "leaver", OrgRole.CLINICIAN, "adminB")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))
        client.put("/v1/rel/$patientRel/shares/lin/0") {
            asOwner(); header("X-Rel-Token", patientInbox); header("X-Share-Meta", shareMeta); setBody(ciphertext)
        }

        // The departing clinician is signed in and reading, right now, with hours left on a session
        // that nothing is about to expire.
        val leaverSession = session(f, "leaver", patientRel)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/rel/$patientRel/shares/lin/current") {
                header("X-Rel-Token", patientInbox); asMember(leaverSession)
            }.status,
        )

        // And they took the seat themselves, which is what gives practice A any standing over their
        // sessions at all. Without this the removal below cuts nothing — see the test named for it.
        val accepted = client.post("/v1/orgs/orgA/members/me/accept") { asMember(leaverSession, csrf = true) }
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        assertTrue(f.orgs.membership("orgA", "leaver")!!.accepted)
        // Accepting at A is an answer about A. Practice B seated the same person and has not been
        // told anything by this.
        assertFalse(f.orgs.membership("orgB", "leaver")!!.accepted)

        // One request, no step-up. Revocation is deliberately the cheap direction.
        val removed = client.delete("/v1/orgs/orgA/members/leaver") { asMember(admin, csrf = true) }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.bodyAsText().contains("\"sessionsCut\":1"), removed.bodyAsText())

        // IMMEDIATELY, not at next session expiry: the same request that worked a moment ago is
        // refused now, on a session whose idle and absolute deadlines are both still hours away.
        // What that proves is that ONE SESSION IS GONE — nothing more, and the test below named for
        // the limit drives the same person straight back in through the front door to show it.
        val afterwards = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); asMember(leaverSession)
        }
        assertEquals(HttpStatusCode.Unauthorized, afterwards.status)
        assertFalse(afterwards.bodyAsText().contains("SEALED"))
        assertEquals(AuthStore.SessionCheck.MISSING, f.auth.validateSession(leaverSession.sessionId, f.cfg.sessionIdleSeconds).check)
        assertNull(f.orgs.membership("orgA", "leaver"))

        // AND THE DATA PLANE WAS NOT TOUCHED. The control plane cut access; it did not reach into
        // anybody's ciphertext, because it has never been able to. The patient's share is exactly
        // where the patient left it, and the patient is still the only one who decides otherwise.
        val ownerRead = client.get("/v1/rel/$patientRel/shares/lin/current") { asOwner(); header("X-Rel-Token", patientInbox) }
        assertEquals(HttpStatusCode.OK, ownerRead.status)
        assertContentEquals(ciphertext, ownerRead.bodyAsBytes())

        // AND PRACTICE B DID NOT LOSE AN EMPLOYEE. One administrator's revocation reached exactly
        // as far as their own practice: the interruption crossed the boundary because a session is
        // a credential's, the authority did not, and signing in again restores the standing that
        // practice A was never able to touch.
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgB", "leaver")?.role)
        assertEquals(listOf("adminB", "leaver"), f.orgs.roster("orgB").map { it.memberId })
        assertTrue(f.orgAudit.list("orgB").isEmpty(), "practice B's chain must record nothing about practice A's act")

        // Audited, in the practice's own chain, with the acting administrator recorded and no
        // content of any kind.
        val entries = f.orgAudit.list("orgA")
        val removal = entries.firstOrNull { it.action == "org.member_removed" }
        assertNotNull(removal, entries.toString())
        assertEquals("leaver", removal.objectRef)
        assertEquals("adminA", removal.meta?.get("actor"))
        assertEquals("org_admin", removal.actor)
        assertTrue(entries.none { (it.meta?.values ?: emptyList()).any { v -> v.contains("SEALED") } })
        // The admin can read that history back through the route as well — "audit review" is in
        // their row of the catalog.
        val review = client.get("/v1/orgs/orgA/audit") { asMember(admin) }
        assertEquals(HttpStatusCode.OK, review.status)
        assertTrue(review.bodyAsText().contains("org.member_removed"), review.bodyAsText())
        // A practice name is not in the log. It is a human-readable label that would identify a real
        // clinic in a leaked file and buys nothing operationally — the id is what the chain is keyed
        // on. "Minimize and don't over-log" applies to metadata too.
        assertFalse(review.bodyAsText().contains("Practice A"), review.bodyAsText())
    }

    @Test
    fun `an admin cannot reach a credential by seating an id whose owner never accepted`() = testApplication {
        /*
         * THE CROSS-TENANT LEVER, and the reason a seat is an offer rather than a fact.
         *
         * Every request below is an ordinary, authorised act by practice A's own administrator
         * inside practice A's own path space — which is exactly why the tenancy test next door does
         * not see it. That test walks /v1/orgs/orgB/... and finds a wall. This one never leaves
         * /v1/orgs/orgA/..., and the effect lands on a clinician of practice B anyway, because the
         * thing removal reaches for is a SESSION and a session belongs to a credential rather than
         * to a practice. Add, then remove: two boring admin requests whose combined effect is
         * somebody else's clinician signed out mid-appointment, with practice B's chain recording
         * nothing and practice B's admin left to guess.
         *
         * Acceptance is what closes it, so the assertions are about a person who never gave it.
         */
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.createOrg("orgB", "Practice B", "adminB", "platform")
        f.orgs.addMember("orgB", "clinB", OrgRole.CLINICIAN, "adminB")
        f.orgs.acceptMembership("orgB", "clinB")
        val seed = enrolAuthenticator(f, "adminA")
        val adminA = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        // Practice B's clinician is signed in and working, on a relationship a patient gave them.
        client.put("/v1/rel/$patientRel/shares/lin/0") {
            asOwner(); header("X-Rel-Token", patientInbox); header("X-Share-Meta", shareMeta); setBody(ciphertext)
        }
        val victim = session(f, "clinB", patientRel)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/rel/$patientRel/shares/lin/current") {
                header("X-Rel-Token", patientInbox); asMember(victim)
            }.status,
        )

        // adminA seats them in practice A. This is allowed and stays allowed: the server does not
        // audit a practice's hiring, and refusing ids that name no credential would answer "does
        // this person have an account here?" for anybody holding an admin seat anywhere. What the
        // seat is NOT is agreement, and the response says so in the field that carries the answer.
        val seated = client.post("/v1/orgs/orgA/members") {
            asMember(adminA, csrf = true, stepUp = stepUpCode(seed))
            contentType(ContentType.Application.Json); setBody(addMemberBody("clinB", OrgRole.FRONT_DESK))
        }
        assertEquals(HttpStatusCode.Created, seated.status, seated.bodyAsText())
        assertTrue(seated.bodyAsText().contains("\"accepted\":false"), seated.bodyAsText())
        assertFalse(f.orgs.membership("orgA", "clinB")!!.accepted)

        // And now the other half of the lever, which does nothing.
        val removed = client.delete("/v1/orgs/orgA/members/clinB") { asMember(adminA, csrf = true) }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.bodyAsText().contains("\"sessionsCut\":0"), removed.bodyAsText())

        // The victim is untouched in every way that can be checked: the session is live, the read
        // that depends on it still works and still returns the patient's bytes, and their standing
        // at their own practice is exactly as practice B left it.
        assertEquals(AuthStore.SessionCheck.OK, f.auth.validateSession(victim.sessionId, f.cfg.sessionIdleSeconds).check)
        val stillWorking = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); asMember(victim)
        }
        assertEquals(HttpStatusCode.OK, stillWorking.status)
        assertContentEquals(ciphertext, stillWorking.bodyAsBytes())
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgB", "clinB")?.role)
        assertTrue(f.orgs.membership("orgB", "clinB")!!.accepted)
        assertTrue(f.orgAudit.list("orgB").isEmpty(), "practice B's chain must record nothing about practice A's act")

        // NOR IS THERE AN ORACLE IN THE ANSWER. The removal of somebody signed in right now must be
        // indistinguishable from the removal of an id that answers to nobody at all — otherwise the
        // pair of requests above is a "is this person online?" probe that any admin can run against
        // any credential id on the deployment, one guess at a time.
        val ghostSeat = client.post("/v1/orgs/orgA/members") {
            asMember(adminA, csrf = true, stepUp = stepUpCode(seed, 30))
            contentType(ContentType.Application.Json); setBody(addMemberBody("ghost", OrgRole.FRONT_DESK))
        }
        assertEquals(HttpStatusCode.Created, ghostSeat.status, ghostSeat.bodyAsText())
        val ghostRemoved = client.delete("/v1/orgs/orgA/members/ghost") { asMember(adminA, csrf = true) }
        assertEquals(HttpStatusCode.OK, ghostRemoved.status)
        // Byte-identical once the id is substituted. Any other difference — a count, a word, a
        // length — is the oracle, and comparing whole bodies is the only way to notice one that
        // arrives in a field nobody thought to assert on.
        assertEquals(ghostRemoved.bodyAsText(), removed.bodyAsText().replace("clinB", "ghost"))
    }

    @Test
    fun `removal ends the membership, not the access - only the patient ends that`() = testApplication {
        /*
         * THE HONEST LIMIT, DRIVEN RATHER THAN DESCRIBED.
         *
         * The test above this one proves a session dies the moment a member is removed, and it is
         * very easy to read that as proof that a departing clinician stopped being able to read
         * anything. It is not, and the difference matters more than the similarity: what practice A
         * ended is a MEMBERSHIP. The clinician's portal credential was enrolled against a patient's
         * relationship on the patient's own invitation, no route in this server disables a
         * credential, and no admin anywhere can withdraw a grant a patient made. So the same person
         * signs in again with the same authenticator and reads the same sealed bytes, and the only
         * party who can change that is the patient.
         *
         * That is the design working — an admin who could end a patient's grant would be an admin
         * with authority in the data plane, which is the one thing the three-plane rule forbids —
         * and it is a limit the product has to state rather than a hole to be quietly closed here.
         * It is written as a test because a limit nobody exercises is a limit that gets described
         * as a cutoff in the next round of copy, and because the second half below is the part that
         * has to keep working: the patient's revocation is the thing that actually ends the read.
         */
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "leaver", OrgRole.CLINICIAN, "adminA")
        // A REAL clinician credential: enrolled against the patient's own relationship, through the
        // real invite/redeem/enrol path, so that signing in again is the front door rather than a
        // store call the test made up.
        val leaverSeed = enrolAuthenticator(f, "leaver", relRef = patientRel)
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))
        client.put("/v1/rel/$patientRel/shares/lin/0") {
            asOwner(); header("X-Rel-Token", patientInbox); header("X-Share-Meta", shareMeta); setBody(ciphertext)
        }
        val leaverSession = session(f, "leaver", patientRel)
        assertEquals(HttpStatusCode.OK, client.post("/v1/orgs/orgA/members/me/accept") { asMember(leaverSession, csrf = true) }.status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/rel/$patientRel/shares/lin/current") {
                header("X-Rel-Token", patientInbox); asMember(leaverSession)
            }.status,
        )

        // The practice removes them, and the session really does die.
        val removed = client.delete("/v1/orgs/orgA/members/leaver") { asMember(admin, csrf = true) }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.bodyAsText().contains("\"sessionsCut\":1"), removed.bodyAsText())
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/rel/$patientRel/shares/lin/current") {
                header("X-Rel-Token", patientInbox); asMember(leaverSession)
            }.status,
        )

        // AND THEY SIGN STRAIGHT BACK IN. Nothing in the removal touched the credential, so the
        // ordinary verify path issues a new session on the next code from the same authenticator.
        val signIn = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"leaver","code":"${stepUpCode(leaverSeed, 30)}"}""")
        }
        assertEquals(HttpStatusCode.OK, signIn.status, signIn.bodyAsText())
        val fresh = signIn.headers[HttpHeaders.SetCookie]
            ?.substringAfter("daymark_session=")?.substringBefore(";")
        assertNotNull(fresh, "the removed member was not issued a fresh session: ${signIn.headers[HttpHeaders.SetCookie]}")

        // AND READ THE SAME BYTES. This is the limit, asserted rather than admitted: the practice's
        // removal did not end this person's access to this patient's material, because it never
        // could. Their standing in the practice is gone — the control plane answers them as a
        // stranger — and their reading is untouched.
        val readAgain = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); header(HttpHeaders.Cookie, "daymark_session=$fresh")
        }
        assertEquals(HttpStatusCode.OK, readAgain.status, readAgain.bodyAsText())
        assertContentEquals(ciphertext, readAgain.bodyAsBytes())
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/orgs/orgA/members") { header(HttpHeaders.Cookie, "daymark_session=$fresh") }.status,
            "the membership really is gone — this test must not be passing because the removal failed",
        )

        // THE THING THAT DOES END IT is the patient withdrawing the share, which is owner-only and
        // lives in the other plane entirely. After it, the same session that just worked reads
        // nothing, and what it gets back carries none of the bytes.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/rel/$patientRel/shares/lin/revoke") { asOwner(); header("X-Rel-Token", patientInbox) }.status,
        )
        val afterPatientRevoked = client.get("/v1/rel/$patientRel/shares/lin/current") {
            header("X-Rel-Token", patientInbox); header(HttpHeaders.Cookie, "daymark_session=$fresh")
        }
        assertEquals(HttpStatusCode.Gone, afterPatientRevoked.status)
        assertFalse(afterPatientRevoked.bodyAsText().contains("SEALED"), afterPatientRevoked.bodyAsText())
    }

    @Test
    fun `nobody can accept a seat on somebody else's behalf`() = testApplication {
        /*
         * Acceptance is the fact that gives a practice standing over a person, so the ways it CANNOT
         * be manufactured are the whole of its value. There is no route that takes whose acceptance
         * it is: the subject comes from the session, so the strongest thing an administrator can do
         * is accept their own seat.
         */
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "hire", OrgRole.CLINICIAN, "adminA")
        val seed = enrolAuthenticator(f, "adminA")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        // Naming the subject in the path reaches no route at all, with or without the step-up code
        // that buys the expensive acts in this file. There is nowhere for the name to go.
        val named = client.post("/v1/orgs/orgA/members/hire/accept") { asMember(admin, csrf = true, stepUp = stepUpCode(seed)) }
        assertTrue(named.status.value >= 400, "${named.status} ${named.bodyAsText()}")
        assertFalse(f.orgs.membership("orgA", "hire")!!.accepted)

        // Naming it in the BODY is the more interesting failure, because the request succeeds: it
        // accepts the administrator's own seat, which is the only seat any request can ever accept.
        // The subject is read from the session, so a body naming somebody else is not refused, it
        // is irrelevant — there is no parameter for it to fill.
        val inBody = client.post("/v1/orgs/orgA/members/me/accept") {
            asMember(admin, csrf = true)
            contentType(ContentType.Application.Json); setBody("""{"memberId":"hire"}""")
        }
        assertEquals(HttpStatusCode.OK, inBody.status, inBody.bodyAsText())
        assertTrue(inBody.bodyAsText().contains("adminA"), inBody.bodyAsText())
        assertFalse(f.orgs.membership("orgA", "hire")!!.accepted)
        assertTrue(f.orgs.membership("orgA", "adminA")!!.accepted)

        // A cross-site page cannot do it either: the cookie rides along on its own, the header does
        // not, and a missing anti-CSRF header is a rejection rather than a bypass.
        val hireSession = session(f, "hire", Secrets.relRefOf("hire-own-relationship"))
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/orgs/orgA/members/me/accept") { asMember(hireSession, csrf = false) }.status,
        )
        assertFalse(f.orgs.membership("orgA", "hire")!!.accepted)

        // And with their own session and their own CSRF token, they accept — once, idempotently,
        // and it is written into the practice's chain by the member rather than by the admin.
        assertEquals(HttpStatusCode.OK, client.post("/v1/orgs/orgA/members/me/accept") { asMember(hireSession, csrf = true) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/orgs/orgA/members/me/accept") { asMember(hireSession, csrf = true) }.status)
        assertTrue(f.orgs.membership("orgA", "hire")!!.accepted)
        val accepts = f.orgAudit.list("orgA").filter { it.action == "org.member_accepted" && it.objectRef == "hire" }
        assertEquals(1, accepts.size, "a retried accept must not write the chain twice: $accepts")
        assertEquals("org_member", accepts.single().actor)
    }

    @Test
    fun `membership is read fresh on every request, so a removal never waits for a session`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "member1", OrgRole.CLINICIAN, "adminA")
        val memberSession = session(f, "member1", Secrets.relRefOf("member-own-relationship"))
        assertEquals(HttpStatusCode.OK, client.get("/v1/orgs/orgA/members") { asMember(memberSession) }.status)

        // Removed at the STORE, leaving the session deliberately untouched. This isolates the claim:
        // it is not session revocation doing the work, it is that nothing caches a role. A role
        // stamped into the session at sign-in would outlive the decision to take it away, and
        // "revoked, effective within eight hours" is not revocation.
        assertEquals(OrgWrite.OK, f.orgs.removeMember("orgA", "member1"))
        assertEquals(
            AuthStore.SessionCheck.OK,
            f.auth.validateSession(memberSession.sessionId, f.cfg.sessionIdleSeconds).check,
            "the session must still be live, or this test proves nothing",
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/orgs/orgA/members") { asMember(memberSession) }.status)
    }

    // ---- the annoyance budget ----------------------------------------------------

    @Test
    fun `widening costs step-up, and a code cannot be spent twice`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        val seed = enrolAuthenticator(f, "adminA")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        suspend fun add(memberId: String, code: String?) = client.post("/v1/orgs/orgA/members") {
            asMember(admin, csrf = true, stepUp = code)
            contentType(ContentType.Application.Json); setBody(addMemberBody(memberId, OrgRole.CLINICIAN))
        }

        // Adding a member changes who *can* be granted, which the annoyance budget prices at
        // step-up. A session alone is not enough: a session in the wrong hands must not be able to
        // add readers to a practice quietly, one at a time, over an afternoon.
        assertEquals(HttpStatusCode.Forbidden, add("nobody1", null).status)
        assertEquals(HttpStatusCode.Forbidden, add("nobody2", definitelyWrongCode(seed)).status)
        assertNull(f.orgs.membership("orgA", "nobody1"))
        assertNull(f.orgs.membership("orgA", "nobody2"))

        val code = stepUpCode(seed)
        assertEquals(HttpStatusCode.Created, add("realHire", code).status)
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgA", "realHire")?.role)

        // The same code again is refused. A step-up that could be replayed would prove somebody was
        // present ONCE, which is exactly the property it exists to improve on — a code seen over a
        // shoulder stays valid for ninety seconds otherwise.
        assertEquals(HttpStatusCode.Forbidden, add("replayed", code).status)
        assertNull(f.orgs.membership("orgA", "replayed"))

        // A fresh code from the next window works, so this is single-use rather than once-ever.
        assertEquals(HttpStatusCode.Created, add("secondHire", stepUpCode(seed, 30)).status)
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgA", "secondHire")?.role)

        // Promotion is the same kind of decision and is priced the same way.
        val promote = client.post("/v1/orgs/orgA/members/realHire/role") {
            asMember(admin, csrf = true)
            contentType(ContentType.Application.Json); setBody("""{"role":"org_admin"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, promote.status)
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgA", "realHire")?.role)
    }

    @Test
    fun `narrowing is deliberately cheap - revocation asks for no step-up at all`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "leaver", OrgRole.CLINICIAN, "adminA")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        // No authenticator is even enrolled for this administrator. That is the point: an admin who
        // has just learned a colleague's laptop is gone must be able to cut them off on whatever
        // device is to hand. "Never make the safe direction expensive" — the asymmetry IS the
        // control, and re-adding the person still costs step-up, so the round trip stays expensive
        // in the widening direction only.
        assertNull(f.auth.getTotp("adminA"))
        val removed = client.delete("/v1/orgs/orgA/members/leaver") { asMember(admin, csrf = true) }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertNull(f.orgs.membership("orgA", "leaver"))
    }

    @Test
    fun `a practice cannot be left without an admin`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        val seed = enrolAuthenticator(f, "adminA")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        // Both exits from being the only administrator are refused. An admin-less practice cannot be
        // repaired from inside the control plane: nobody left could add the missing admin, because
        // adding members is the thing admins do.
        assertEquals(HttpStatusCode.Conflict, client.delete("/v1/orgs/orgA/members/adminA") { asMember(admin, csrf = true) }.status)
        val demote = client.post("/v1/orgs/orgA/members/adminA/role") {
            asMember(admin, csrf = true, stepUp = stepUpCode(seed))
            contentType(ContentType.Application.Json); setBody("""{"role":"clinician"}""")
        }
        assertEquals(HttpStatusCode.Conflict, demote.status)
        assertEquals(OrgRole.ORG_ADMIN, f.orgs.membership("orgA", "adminA")?.role)
    }

    // ---- the gates ---------------------------------------------------------------

    @Test
    fun `a session cookie alone cannot change membership`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        f.orgs.createOrg("orgA", "Practice A", "adminA", "platform")
        f.orgs.addMember("orgA", "clinician1", OrgRole.CLINICIAN, "adminA")
        val seed = enrolAuthenticator(f, "adminA")
        val admin = session(f, "adminA", Secrets.relRefOf("admin-own-relationship"))

        // A browser attaches the session cookie to a cross-site request on its own, so without the
        // anti-CSRF header any page an administrator visited could re-role their colleagues. A
        // missing header is a rejection, never a bypass.
        val noCsrf = listOf(
            client.post("/v1/orgs/orgA/members") {
                asMember(admin, csrf = false, stepUp = stepUpCode(seed))
                contentType(ContentType.Application.Json); setBody(addMemberBody("forged", OrgRole.ORG_ADMIN))
            },
            client.delete("/v1/orgs/orgA/members/clinician1") { asMember(admin, csrf = false) },
        )
        for (res in noCsrf) assertEquals(HttpStatusCode.Unauthorized, res.status, res.bodyAsText())

        // And no session at all is the same 401 — expired, revoked, never-existed and wrong-CSRF are
        // one answer between them.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/orgs/orgA/members").status)
        assertNull(f.orgs.membership("orgA", "forged"))
        assertEquals(OrgRole.CLINICIAN, f.orgs.membership("orgA", "clinician1")?.role)
    }

    @Test
    fun `only the operator's provisioning credential can create a practice`() = testApplication {
        val f = fixture()
        application { module(f.cfg, relationStore = f.rel, authStore = f.auth, auditStore = f.audit, orgStore = f.orgs, orgAuditStore = f.orgAudit) }
        val body = """{"name":"New Practice","adminMemberId":"founder"}"""

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/orgs") { contentType(ContentType.Application.Json); setBody(body) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/orgs") {
                header(HttpHeaders.Authorization, "Bearer not-the-operator")
                contentType(ContentType.Application.Json); setBody(body)
            }.status,
        )
        // A member session is not a provisioning credential. Practices are created in the platform
        // plane, by whoever runs the server; nobody inside a practice can spawn another one.
        val someone = session(f, "someone", patientRel)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/orgs") { asMember(someone, csrf = true); contentType(ContentType.Application.Json); setBody(body) }.status,
        )

        val created = client.post("/v1/orgs") { asOwner(); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, created.status)
        // The id is MINTED server-side rather than accepted from the body: it is what a whole audit
        // chain is keyed on, and a caller-chosen one would let whoever holds the token pick an
        // identifier that collides with something else's.
        val orgId = Regex("\"orgId\":\"([^\"]+)\"").find(created.bodyAsText())?.groupValues?.get(1)
        assertNotNull(orgId, created.bodyAsText())
        assertFalse(body.contains(orgId), "the practice id must not have come from the request")
        assertEquals(OrgRole.ORG_ADMIN, f.orgs.membership(orgId, "founder")?.role)
        // Its chain opens with the creation, written by the platform actor rather than by a member.
        val genesis = f.orgAudit.list(orgId).single()
        assertEquals("org.created", genesis.action)
        assertEquals("platform", genesis.actor)
        assertEquals("founder", genesis.objectRef)
    }

    @Test
    fun `the control plane is fail-closed when the portal is off`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir, therapistAuth = false)) }
        // 503 rather than 404, so a probe cannot tell "no such practice here" from "this deployment
        // has no practices at all".
        for (res in listOf(
            client.get("/v1/orgs/orgA/members"),
            client.get("/v1/orgs/orgA/audit"),
            client.post("/v1/orgs") { asOwner(); contentType(ContentType.Application.Json); setBody("""{"name":"X","adminMemberId":"y"}""") },
            client.post("/v1/orgs/orgA/members") { contentType(ContentType.Application.Json); setBody(addMemberBody("z", OrgRole.CLINICIAN)) },
            client.delete("/v1/orgs/orgA/members/z"),
        )) {
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status, res.bodyAsText())
        }
    }

    // ---- the structural guard ----------------------------------------------------

    @Test
    fun `the control plane's own source reaches nothing in the data plane`() {
        /*
         * The strongest statement available short of a type system, and the reason it is worth a
         * test rather than a comment: every other assertion in this file shows that some particular
         * request did not return key material. This one shows there is no code by which any request
         * could. The control plane does not import the relationship blob layer, does not import the
         * blob store, and does not call the two functions that move a therapist's published public
         * halves — so "an org admin cannot obtain a grant, a key, or any ciphertext through any
         * route in this file" is a fact about what the file is made of.
         *
         * The list below is every server-side symbol that can put ciphertext or key material into a
         * response. If a new one is ever added, it belongs here too — a guard that silently stops
         * covering the thing it was written for is worse than no guard, because it still reads like
         * one in review.
         *
         * The match is textual, so it catches a mention in a comment as readily as a call. That is
         * deliberate rather than a rough edge: these three files have no reason to name a data-plane
         * type even in passing, and a check that tried to be clever about which occurrences "really
         * count" would be a check with a parser in it, and therefore a check with a bug in it.
         */
        val forbidden = listOf(
            "RelationStore",
            "BlobStore",
            "RelMeta",
            "respondBytes",
            "revokeLineage",
            "therapistKeys",
            "registerTherapistKeys",
            "storage.Channel",
        )
        val sources = listOf(
            "src/main/kotlin/com/daymark/companion/routes/OrgRoutes.kt",
            "src/main/kotlin/com/daymark/companion/org/OrgStore.kt",
            "src/main/kotlin/com/daymark/companion/org/OrgRole.kt",
        )
        for (path in sources) {
            val file = File(path)
            // A missing file must fail loudly rather than pass vacuously: a guard that quietly
            // stops examining anything is the failure mode this whole test exists to avoid.
            assertTrue(file.isFile, "control-plane source not found at $path (did it move? the guard must move with it)")
            val text = file.readText()
            for (symbol in forbidden) {
                assertFalse(text.contains(symbol), "$path reaches the data plane through '$symbol'")
            }
        }
    }
}
