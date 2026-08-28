package com.daymark.companion.org

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tenant model itself: the role catalog, and the store that keeps practices apart.
 *
 * Weighted almost entirely towards what the model REFUSES, because the refusals are what the
 * clinical layer is for. A practice that can seat a clinician is one afternoon of plumbing. A
 * practice whose admin cannot reach the practice next door, cannot hand anybody a key by promoting
 * them, and cannot leave itself unmanageable is the actual product, and none of those are visible in
 * a happy-path test.
 *
 * The two claims worth naming, since every test below is one of them in a particular disguise:
 *
 *   1. A ROLE NEVER CARRIES A KEY. Not "is not currently given one" — there is no field, no action
 *      and no plane in which one could be named.
 *   2. A MEMBERSHIP HAS NO NAME THAT DOES NOT CONTAIN ITS PRACTICE. Tenant isolation is in the keys
 *      of the schema rather than in a WHERE clause somebody has to remember.
 */
class OrgModelTest {

    private fun store() = OrgStore(Files.createTempDirectory("org-model-test").toString())

    // ---- The role catalog --------------------------------------------------------

    @Test
    fun `no role in the catalog carries an action in the data plane`() {
        // The whole design in one assertion. Every capability any role holds is control or
        // monitoring; none is data. That is what makes "an admin can revoke anyone and see
        // who-accessed-what, yet cannot read a single clinical note" a property rather than a claim.
        for (role in OrgRole.entries) {
            for (capability in role.capabilities) {
                assertNotEquals(Plane.DATA, capability.plane, "$role carries ${capability.name}, which is data-plane")
            }
        }
        assertTrue(OrgAction.entries.none { it.plane == Plane.DATA }, OrgAction.entries.toString())

        // Non-vacuity, and the reason Plane.DATA is declared despite nothing being in it: the
        // assertions above say something only because DATA is a value the compiler would have
        // accepted. Delete it and they pass for the wrong reason — they become "no enum entry has a
        // property that cannot exist", which is true of every enum ever written.
        assertTrue(Plane.entries.contains(Plane.DATA))
    }

    @Test
    fun `the catalog has no member role for a patient or for the platform`() {
        // Both are in the spec's role table and neither is a member of a practice. A PATIENT role
        // would let an administrator assert a clinical relationship the patient never consented to,
        // from the control plane, with one row — the patient is the root of consent and is not owned
        // by the org. A PLATFORM_SYSADMIN role would seat the person who runs the server inside a
        // tenant while they also stand outside every tenant, which is the god admin the three-plane
        // rule exists to rule out.
        //
        // The refusal has to be the absence of a value, not a check: with nothing to write into the
        // column there is no request body that expresses it and no handler that can be talked into it.
        val vocabulary = OrgRole.entries.map { it.name.lowercase() } + OrgRole.entries.map { it.wire.lowercase() }
        for (forbidden in listOf("patient", "owner", "sysadmin", "platform")) {
            assertTrue(vocabulary.none { it.contains(forbidden) }, "role catalog contains '$forbidden': $vocabulary")
        }
    }

    @Test
    fun `a supervisor holds exactly what a clinician holds - seniority is not access`() {
        // The spec is explicit: a supervisor reads "only via explicit, consented grant (clinical
        // supervision), never by title". So the supervisor's control-plane standing is identical to
        // a clinician's, which looks redundant and is the assertion. If these two ever diverge, the
        // change was almost certainly made in the wrong file.
        assertEquals(OrgRole.CLINICIAN.capabilities, OrgRole.SUPERVISOR.capabilities)
        assertFalse(OrgRole.SUPERVISOR.can(OrgAction.MANAGE_MEMBERSHIP))
        assertFalse(OrgRole.SUPERVISOR.can(OrgAction.REVIEW_ORG_AUDIT))
    }

    @Test
    fun `front desk is scheduling metadata and nothing else`() {
        assertEquals(
            setOf(OrgAction.VIEW_ROSTER, OrgAction.MANAGE_SCHEDULING_METADATA),
            OrgRole.FRONT_DESK.capabilities,
        )
        assertFalse(OrgRole.FRONT_DESK.can(OrgAction.MANAGE_MEMBERSHIP))
        assertFalse(OrgRole.FRONT_DESK.can(OrgAction.REVIEW_ORG_AUDIT))
        // And the capability it does hold is not a back door into the clinical side by another name.
        assertNotEquals(Plane.DATA, OrgAction.MANAGE_SCHEDULING_METADATA.plane)
    }

    @Test
    fun `membership authority and audit review belong to the org admin alone`() {
        assertEquals(listOf(OrgRole.ORG_ADMIN), OrgRole.entries.filter { it.can(OrgAction.MANAGE_MEMBERSHIP) })
        assertEquals(listOf(OrgRole.ORG_ADMIN), OrgRole.entries.filter { it.can(OrgAction.REVIEW_ORG_AUDIT) })
        // Every role can see who works there. A practice whose members cannot tell who works there
        // is not a practice, and a roster is membership metadata rather than clinical content.
        assertTrue(OrgRole.entries.all { it.can(OrgAction.VIEW_ROSTER) })
    }

    @Test
    fun `an unknown role on the wire is refused rather than defaulted`() {
        assertNull(OrgRole.fromWire("god"))
        assertNull(OrgRole.fromWire("ORG_ADMIN"), "the wire form is lowercase; the enum name must not parse")
        assertNull(OrgRole.fromWire(""))
        assertEquals(OrgRole.ORG_ADMIN, OrgRole.fromWire("org_admin"))
    }

    @Test
    fun `a membership record has nowhere to put a key`() {
        // A structural guard on the type an admin's roster read is built from. It is made of an
        // identifier, a role name, two pieces of provenance and one fact about the person's own
        // agreement; if a `wrappedKey`, `grantRef` or `patients` field is ever added, the roster
        // stops being membership metadata and this fails.
        //
        // `accepted` was argued for on its way in, as this list requires. It is a boolean about the
        // membership rather than about anything clinical, it opens nothing, and it is the only fact
        // that distinguishes a practice's claim about somebody from the person's agreement to it —
        // which is what every act that reaches past the roster and touches the PERSON is gated on.
        val fields = Membership::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(setOf("orgId", "memberId", "role", "addedAt", "addedBy", "accepted"), fields)
    }

    // ---- A seat is an offer until the person takes it ----------------------------

    @Test
    fun `a seat starts unaccepted and only the store's accept call changes that`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.addMember("orgA", "hire", OrgRole.CLINICIAN, "adminA")

        // Nobody is accepted by being named — not the practice's founder, seated by the operator's
        // provisioning identity, and not a clinician an admin added. An admin can say who works
        // there; only the person can say that the seat is theirs.
        assertFalse(s.membership("orgA", "adminA")!!.accepted)
        assertFalse(s.membership("orgA", "hire")!!.accepted)
        assertTrue(s.roster("orgA").none { it.accepted })

        assertEquals(OrgWrite.OK, s.acceptMembership("orgA", "hire"))
        assertTrue(s.membership("orgA", "hire")!!.accepted)
        // Idempotent: a client retrying on a flaky connection must not be a different outcome.
        assertEquals(OrgWrite.OK, s.acceptMembership("orgA", "hire"))
        assertTrue(s.membership("orgA", "hire")!!.accepted)
        // And it did not sweep the practice: acceptance is one person's answer about one seat.
        assertFalse(s.membership("orgA", "adminA")!!.accepted)
    }

    @Test
    fun `accepting at one practice says nothing at another`() {
        // The same asymmetry the rest of this file is about, applied to the fact that gives a
        // practice standing over a person. Somebody who agreed to work at A has agreed to nothing
        // at B, and B must not be able to inherit A's answer — otherwise an admin at B could seat
        // any id that had ever accepted anywhere and immediately hold the same lever over them.
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.createOrg("orgB", "Practice B", "adminB", "platform")
        s.addMember("orgA", "shared", OrgRole.CLINICIAN, "adminA")
        s.addMember("orgB", "shared", OrgRole.CLINICIAN, "adminB")

        assertEquals(OrgWrite.OK, s.acceptMembership("orgA", "shared"))
        assertTrue(s.membership("orgA", "shared")!!.accepted)
        assertFalse(s.membership("orgB", "shared")!!.accepted, "acceptance must not cross a practice boundary")
    }

    @Test
    fun `a seat that was left and re-offered has to be accepted again`() {
        // Re-adding is an insert, so the new row is a new offer. This matters because removal is
        // what the acceptance flag gates: if a re-add inherited the old answer, an admin could
        // remove somebody, re-add them, and be holding the lever again without the person ever
        // having been asked a second time.
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.addMember("orgA", "boomerang", OrgRole.CLINICIAN, "adminA")
        s.acceptMembership("orgA", "boomerang")
        assertEquals(OrgWrite.OK, s.removeMember("orgA", "boomerang"))
        assertEquals(OrgWrite.OK, s.addMember("orgA", "boomerang", OrgRole.CLINICIAN, "adminA"))
        assertFalse(s.membership("orgA", "boomerang")!!.accepted)
    }

    @Test
    fun `nobody can accept a seat that was never offered`() {
        // Fail closed, and in particular no create-on-accept: a stranger cannot seat themselves by
        // accepting an invitation nobody issued, and a malformed id reads as "not a member" rather
        // than throwing, exactly as the authorization primitive does.
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        assertEquals(OrgWrite.NOT_A_MEMBER, s.acceptMembership("orgA", "nobody"))
        assertNull(s.membership("orgA", "nobody"))
        assertEquals(OrgWrite.NOT_A_MEMBER, s.acceptMembership("orgNeverCreated", "adminA"))
        assertEquals(OrgWrite.BAD_NAME, s.acceptMembership("orgA", "bad id"))
    }

    // ---- Tenant isolation --------------------------------------------------------

    @Test
    fun `one person in two practices is two independent memberships`() {
        val s = store()
        assertEquals(OrgWrite.OK, s.createOrg("orgA", "Practice A", "shared", "platform"))
        assertEquals(OrgWrite.OK, s.createOrg("orgB", "Practice B", "adminB", "platform"))
        assertEquals(OrgWrite.OK, s.addMember("orgB", "shared", OrgRole.FRONT_DESK, "adminB"))

        // Same person, same id, two practices, two roles — and neither leaks into the other. This is
        // "scoped to that practice, not a global super-admin" as data rather than as a sentence:
        // administering one practice says nothing at all about standing in another.
        assertEquals(OrgRole.ORG_ADMIN, s.membership("orgA", "shared")?.role)
        assertEquals(OrgRole.FRONT_DESK, s.membership("orgB", "shared")?.role)
    }

    @Test
    fun `a roster query for one practice cannot return another practice's rows`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.createOrg("orgB", "Practice B", "adminB", "platform")
        s.addMember("orgA", "clinA", OrgRole.CLINICIAN, "adminA")
        s.addMember("orgB", "clinB", OrgRole.CLINICIAN, "adminB")

        assertEquals(listOf("adminA", "clinA"), s.roster("orgA").map { it.memberId })
        assertEquals(listOf("adminB", "clinB"), s.roster("orgB").map { it.memberId })
        // Every row a roster returns names the practice it was asked about. A row that had drifted
        // in from the other tenant would be caught here even if the ids happened not to overlap.
        assertTrue(s.roster("orgA").all { it.orgId == "orgA" })
        assertTrue(s.roster("orgB").all { it.orgId == "orgB" })
        // A practice nobody created is empty rather than everything.
        assertTrue(s.roster("orgC").isEmpty())
    }

    @Test
    fun `a membership has no name that does not contain its practice`() {
        // The structural half of tenant isolation. The primary key is a digest of the PAIR, so there
        // is no "look up member X" a careless caller could write against it — the key is not
        // derivable from X. Two practices holding the same person are two unrelated addresses.
        assertNotEquals(OrgStore.memberRef("orgA", "shared"), OrgStore.memberRef("orgB", "shared"))
        assertNotEquals(OrgStore.memberRef("orgA", "one"), OrgStore.memberRef("orgA", "two"))
        assertEquals(OrgStore.memberRef("orgA", "shared"), OrgStore.memberRef("orgA", "shared"))
        // Injective: the charset excludes the separator from both halves, so no two distinct pairs
        // can collide by sliding the boundary. Without that, ("a", "b:c") and ("a:b", "c") would be
        // the same row.
        assertNotEquals(OrgStore.memberRef("a-b", "c"), OrgStore.memberRef("a", "b-c"))
    }

    @Test
    fun `removing somebody from one practice leaves the other untouched`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.createOrg("orgB", "Practice B", "adminB", "platform")
        s.addMember("orgA", "shared", OrgRole.CLINICIAN, "adminA")
        s.addMember("orgB", "shared", OrgRole.CLINICIAN, "adminB")

        assertEquals(OrgWrite.OK, s.removeMember("orgA", "shared"))
        assertNull(s.membership("orgA", "shared"))
        // Still employed next door. A remove keyed on the person rather than on the pair would have
        // ended two jobs on one click, and nothing would have complained.
        assertEquals(OrgRole.CLINICIAN, s.membership("orgB", "shared")?.role)
    }

    @Test
    fun `a role change reaches only the practice it was made in`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.createOrg("orgB", "Practice B", "adminB", "platform")
        s.addMember("orgA", "shared", OrgRole.CLINICIAN, "adminA")
        s.addMember("orgB", "shared", OrgRole.CLINICIAN, "adminB")

        assertEquals(OrgWrite.OK, s.changeRole("orgA", "shared", OrgRole.SUPERVISOR))
        assertEquals(OrgRole.SUPERVISOR, s.membership("orgA", "shared")?.role)
        assertEquals(OrgRole.CLINICIAN, s.membership("orgB", "shared")?.role)
    }

    @Test
    fun `a practice cannot be left with no admin`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.addMember("orgA", "clinA", OrgRole.CLINICIAN, "adminA")

        // An admin-less practice is unrecoverable from inside the control plane: nobody left can add
        // a member, change a role or revoke anyone, and the audit review that would show what
        // happened is itself admin-gated.
        assertEquals(OrgWrite.LAST_ADMIN, s.removeMember("orgA", "adminA"))
        assertEquals(OrgWrite.LAST_ADMIN, s.changeRole("orgA", "adminA", OrgRole.CLINICIAN))
        assertEquals(OrgRole.ORG_ADMIN, s.membership("orgA", "adminA")?.role, "the refusal must not have half-applied")

        // With a second admin in place, both become ordinary operations. The rule is about the
        // practice keeping an administrator, never about a particular person keeping a job.
        s.addMember("orgA", "adminA2", OrgRole.ORG_ADMIN, "adminA")
        assertEquals(OrgWrite.OK, s.changeRole("orgA", "adminA", OrgRole.CLINICIAN))
        // And the guard moves with the fact rather than with the person: adminA2 is now the last
        // one, so it is adminA2 who cannot leave.
        assertEquals(OrgWrite.LAST_ADMIN, s.removeMember("orgA", "adminA2"))
    }

    @Test
    fun `the last admin rule counts admins rather than members`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        s.addMember("orgA", "second", OrgRole.ORG_ADMIN, "adminA")
        // Two admins: either may go.
        assertEquals(OrgWrite.OK, s.removeMember("orgA", "second"))
        // One left: it may not.
        assertEquals(OrgWrite.LAST_ADMIN, s.removeMember("orgA", "adminA"))
    }

    @Test
    fun `a member cannot be seated in a practice that does not exist`() {
        val s = store()
        assertEquals(OrgWrite.NO_SUCH_ORG, s.addMember("never-created", "someone", OrgRole.CLINICIAN, "whoever"))
        assertNull(s.membership("never-created", "someone"))
    }

    @Test
    fun `adding somebody twice is refused rather than silently re-roling them`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        // An add that quietly re-roled would be the last-admin guard bypassed by a typo: "add
        // adminA as a clinician" would have demoted the practice's only administrator.
        assertEquals(OrgWrite.ALREADY_EXISTS, s.addMember("orgA", "adminA", OrgRole.CLINICIAN, "adminA"))
        assertEquals(OrgRole.ORG_ADMIN, s.membership("orgA", "adminA")?.role)
    }

    @Test
    fun `a practice id is never reused`() {
        val s = store()
        assertEquals(OrgWrite.OK, s.createOrg("orgA", "Practice A", "adminA", "platform"))
        assertEquals(OrgWrite.ALREADY_EXISTS, s.createOrg("orgA", "Somebody Else", "attacker", "platform"))
        // Nothing of the second attempt survived — the practice, its name and its admin are the
        // originals. A create that partially applied would have seated a stranger.
        assertEquals("Practice A", s.org("orgA")?.name)
        assertEquals(listOf("adminA"), s.roster("orgA").map { it.memberId })
    }

    @Test
    fun `ids outside the strict charset are refused, and a malformed one reads as not a member`() {
        val s = store()
        s.createOrg("orgA", "Practice A", "adminA", "platform")
        assertEquals(OrgWrite.BAD_NAME, s.addMember("orgA", "bad id", OrgRole.CLINICIAN, "adminA"))
        assertEquals(OrgWrite.BAD_NAME, s.addMember("orgA", "../../etc", OrgRole.CLINICIAN, "adminA"))
        assertEquals(OrgWrite.BAD_NAME, s.createOrg("bad org", "Name", "adminX", "platform"))
        assertEquals(OrgWrite.BAD_NAME, s.createOrg("orgZ", "   ", "adminX", "platform"))
        assertEquals(OrgWrite.BAD_NAME, s.createOrg("orgZ", "a".repeat(200), "adminX", "platform"))

        // A malformed id arriving at the AUTHORIZATION primitive must read as "not a member" rather
        // than throw: the id it is usually handed comes from a session, and a 500 on the gate is a
        // gate that fails open in the reader's imagination and noisily in production.
        assertNull(s.membership("orgA", "bad id"))
        assertNull(s.membership("bad org", "adminA"))
        assertTrue(s.roster("bad org").isEmpty())
    }
}
