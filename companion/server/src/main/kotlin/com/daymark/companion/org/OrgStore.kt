package com.daymark.companion.org

import com.daymark.companion.auth.Secrets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** A practice. Structure and metadata only — the server is multi-tenant and blind. */
data class Org(val orgId: String, val name: String, val createdAt: Long)

/**
 * One person's standing in one practice.
 *
 * Note the shape: a membership is not a person and it is not a practice, it is the *pair*. The same
 * clinician working at two practices has two of these, with two independently-set roles, and an
 * admin at one of them is a stranger at the other. That is what "scoped to that practice, not a
 * global super-admin" means once it is written down as data rather than as a sentence.
 *
 * Note also what is absent, since this is the type an admin's roster read hands back: no key, no
 * grant reference, no patient list, no channel handle. There is nowhere in this record to put one.
 */
data class Membership(
    val orgId: String,
    val memberId: String,
    val role: OrgRole,
    val addedAt: Long,
    val addedBy: String,
    /**
     * Has the person themselves said yes to this seat?
     *
     * A practice seats whoever its admin names, and it must: hiring is not a negotiation the server
     * gets a vote in. But an admin naming a string is a claim ABOUT somebody, not agreement FROM
     * them, and the two are only the same thing when nothing destructive hangs off the row. Removal
     * cuts the member's portal sessions, which are a credential's rather than a practice's, so the
     * unaccepted seat would otherwise be a lever any admin could aim at any credential id they can
     * type — including a clinician of another practice who has never heard of this one.
     *
     * So the flag is not a workflow state. It is the record of the one fact that gives a practice
     * standing over a person: that the person, holding their own session, said this seat is theirs.
     * Nothing an admin can send sets it — see [acceptMembership] and the route that calls it — and
     * everything the practice does TO a member rather than to its own roster is gated on it.
     */
    val accepted: Boolean,
)

/** Outcome of a control-plane mutation. One enum for all of them, so routes map them in one place. */
enum class OrgWrite {
    OK,

    /** An id or a practice name outside the strict charset / length. A client bug or a probe. */
    BAD_NAME,

    /** No such practice. Never distinguished from "you are not in it" at the HTTP layer — see OrgRoutes. */
    NO_SUCH_ORG,

    /** The practice id is taken, or the person is already a member. Insert-only; nothing was touched. */
    ALREADY_EXISTS,

    /** The person named is not in this practice. Also the answer when they are in a *different* one. */
    NOT_A_MEMBER,

    /**
     * Refused: the practice would have been left with no admin.
     *
     * Not paternalism. An admin-less practice is unrecoverable from inside the control plane — no
     * one left can add a member, change a role, or revoke anyone, and the audit review that would
     * show what happened is itself admin-gated. The only exit is the operator's provisioning
     * credential, i.e. an out-of-band escalation to fix a self-inflicted lockout. Refusing the last
     * removal is cheaper than every version of recovering from it.
     */
    LAST_ADMIN,
}

/**
 * The org / practice control plane: practices, their members, and those members' roles.
 *
 * Same posture as [com.daymark.companion.auth.AuthStore] and as the relationship blob index beside
 * it — SQLite, WAL, one connection under a `synchronized(lock)`, prepared statements everywhere,
 * strict charsets on anything that becomes part of a key. It differs from both in one respect that
 * is worth stating up front, because it is the reason this file is allowed to exist at all:
 *
 * **Nothing here is secret, and nothing here opens anything.** There is no hashed credential, no
 * seed, no token, no wrapped key, and no column that could hold one. The whole database is the
 * capability *graph* — who is in which practice, holding which role — which docs/
 * COMPANION_ACCESS_CONTROL.md says a multi-tenant-but-blind server may see. A dump of this file
 * tells an attacker the shape of a practice. It does not decrypt a single sentence, because there
 * is nothing in it that participates in decrypting anything.
 *
 * ## Tenant isolation is in the keys, not in a WHERE clause
 *
 * The obvious way to build this is `org_members(member_id PRIMARY KEY, org_id, role)` and a
 * discipline of always writing `AND org_id = ?`. That works right up until the one query where
 * somebody forgets, and the failure is silent: practice A's admin gets a row belonging to practice
 * B and nothing anywhere complains, because the query was valid, the row was real, and the only
 * thing wrong with it was whose it was.
 *
 * So the schema is arranged so that a membership row **has no name that does not contain its
 * practice**:
 *
 *  - `member_ref`, the primary key, is `BLAKE2b(org_id : member_id)`. It is not stored data that
 *    happens to combine the two; it is a value you cannot compute without both. There is no
 *    "look up member X" that a careless caller could write against the primary key, because the
 *    primary key is not derivable from X.
 *  - `UNIQUE (org_id, member_id)` states the same identity a second way, so the pair is a key in
 *    its own right and a duplicate membership is refused by SQLite rather than by a check somebody
 *    can delete.
 *  - `org_id` carries a foreign key to `orgs`, with `PRAGMA foreign_keys=ON` set at open — SQLite
 *    ignores the constraint entirely without it, which is the single most commonly-shipped SQLite
 *    bug there is — so a membership cannot name a practice that does not exist.
 *
 * And the API above it: every method that reaches a membership takes the practice id as its first
 * parameter. There is no overload that takes a member id alone, because there is no question about
 * a person that this store is willing to answer without being told which practice is asking.
 *
 * The honest limit, stated so nobody quotes the paragraph above as more than it is: SQL is still
 * SQL, and a determined future author can write `SELECT * FROM org_members` and get everything.
 * What the schema removes is the *easy* mistake — the single-column key that makes a cross-tenant
 * read the shortest thing to type. It cannot remove the deliberate one.
 *
 * ## A seat is an offer until the person takes it
 *
 * An admin may seat any id their charset allows, and that is correct: the server does not get a
 * vote in who a practice says works there, and checking the id against the credential table would
 * turn every add into a "does this person exist here?" oracle for anybody holding an admin seat
 * anywhere on the deployment. What the server DOES insist on is that a claim about somebody is not
 * authority over them. A fresh row is `accepted = 0`, and every act that reaches past the practice's
 * own roster and touches the PERSON — today that is the session cut on removal — is gated on the
 * person having accepted. See [Membership.accepted] and [acceptMembership] for what goes wrong
 * without it, which is a cross-tenant lever rather than an untidiness.
 */
class OrgStore(
    dataDir: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(root)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("org.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            // SQLite disables foreign keys by default, per connection, silently. Without this line
            // the REFERENCES clause below is documentation rather than a constraint and a
            // membership row can name a practice that was never created. It is set here, at open,
            // because there is no other place it can be set that every statement inherits.
            st.execute("PRAGMA foreign_keys=ON")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS orgs (
                    org_id     TEXT    NOT NULL PRIMARY KEY,
                    name       TEXT    NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS org_members (
                    member_ref TEXT    NOT NULL PRIMARY KEY,
                    org_id     TEXT    NOT NULL REFERENCES orgs(org_id),
                    member_id  TEXT    NOT NULL,
                    role       TEXT    NOT NULL,
                    added_at   INTEGER NOT NULL,
                    added_by   TEXT    NOT NULL,
                    -- 0 until the person themselves accepts the seat. DEFAULT 0 rather than
                    -- DEFAULT 1 so that the fail-closed value is the one a forgotten column,
                    -- a hand-written INSERT or a future migration lands on. See Membership.accepted.
                    accepted   INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (org_id, member_id)
                )
                """.trimIndent(),
            )
            // The roster read is the only query in this file that is not a point lookup, and it is
            // always by practice. Indexing it keeps that the cheap path, which matters because the
            // cheap path is the one people keep using instead of inventing a wider one.
            st.execute("CREATE INDEX IF NOT EXISTS idx_org_members_org ON org_members(org_id)")
        }
    }

    // ---- Practices ---------------------------------------------------------------

    /**
     * Create a practice and seat its first admin, atomically.
     *
     * The two halves are one transaction because a practice with no admin is the [OrgWrite.LAST_ADMIN]
     * state arrived at by a different route: nobody could add the missing admin, because adding
     * members is the thing admins do. A half-committed create would leave that condition permanently
     * on disk, so it is not allowed to be reachable even by a crash between two statements.
     *
     * [createdBy] is recorded as the seating authority for the first admin. It is the operator's
     * provisioning identity, not a member of anything — see the create route for why that is the
     * platform plane and why it is deliberately the only way a practice comes into being.
     */
    fun createOrg(orgId: String, name: String, firstAdminMemberId: String, createdBy: String): OrgWrite = synchronized(lock) {
        if (!isName(orgId) || !isName(firstAdminMemberId) || !isOrgName(name)) return OrgWrite.BAD_NAME
        if (orgLocked(orgId) != null) return OrgWrite.ALREADY_EXISTS
        val now = clock()
        conn.autoCommit = false
        try {
            conn.prepareStatement("INSERT INTO orgs(org_id, name, created_at) VALUES (?,?,?)").use { ps ->
                ps.setString(1, orgId); ps.setString(2, name); ps.setLong(3, now)
                ps.executeUpdate()
            }
            insertMemberLocked(orgId, firstAdminMemberId, OrgRole.ORG_ADMIN, now, createdBy)
            conn.commit()
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
        return OrgWrite.OK
    }

    fun org(orgId: String): Org? = synchronized(lock) { if (!isName(orgId)) null else orgLocked(orgId) }

    private fun orgLocked(orgId: String): Org? {
        conn.prepareStatement("SELECT name, created_at FROM orgs WHERE org_id=?").use { ps ->
            ps.setString(1, orgId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return Org(orgId, rs.getString(1), rs.getLong(2))
            }
        }
    }

    // ---- Membership --------------------------------------------------------------

    /**
     * The one authorization primitive: what standing does this person have **in this practice**?
     *
     * Every gate in the control plane is a call to this, made fresh on the request being gated.
     * Nothing caches it, and in particular a session never carries a role — which is what makes
     * removal take effect on the removed member's very next request rather than whenever their
     * session happened to lapse. A role cached at login is a role that outlives the decision to
     * take it away, and "revoked, effective in up to eight hours" is not revocation.
     *
     * Returns null — not an exception — for an id outside the charset, because the id it is usually
     * handed comes from a session and a malformed one must read as "not a member" rather than as a
     * server error. Fail closed and quietly.
     */
    fun membership(orgId: String, memberId: String): Membership? = synchronized(lock) {
        if (!isName(orgId) || !isName(memberId)) return null
        conn.prepareStatement(
            "SELECT org_id, member_id, role, added_at, added_by, accepted FROM org_members WHERE member_ref=?",
        ).use { ps ->
            ps.setString(1, memberRef(orgId, memberId))
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val role = OrgRole.fromWire(rs.getString(3)) ?: return null
                return Membership(rs.getString(1), rs.getString(2), role, rs.getLong(4), rs.getString(5), rs.getInt(6) != 0)
            }
        }
    }

    /** Everyone in one practice, and — because the query is keyed on the practice — nobody else. */
    fun roster(orgId: String): List<Membership> = synchronized(lock) {
        if (!isName(orgId)) return emptyList()
        val out = mutableListOf<Membership>()
        conn.prepareStatement(
            "SELECT member_id, role, added_at, added_by, accepted FROM org_members WHERE org_id=? ORDER BY member_id ASC",
        ).use { ps ->
            ps.setString(1, orgId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val role = OrgRole.fromWire(rs.getString(2)) ?: continue
                    out += Membership(orgId, rs.getString(1), role, rs.getLong(3), rs.getString(4), rs.getInt(5) != 0)
                }
            }
        }
        return out
    }

    /**
     * Seat a person in a practice with a role.
     *
     * Insert-only: an existing membership is never silently re-roled by this path, because "add"
     * and "change the role of" are different decisions and an add that quietly demoted the
     * practice's only admin would be the [OrgWrite.LAST_ADMIN] guard bypassed by a typo. Use
     * [changeRole], which checks.
     *
     * Adding somebody creates no read capability of any kind. It does not fetch anything for them,
     * it does not ask a patient anything on their behalf, and nothing they could not reach a moment
     * ago becomes reachable. What it changes is who is eligible to be *offered* a grant by a person
     * who decides that for themselves.
     *
     * And it creates no authority OVER them either: the seat lands unaccepted, so until the person
     * accepts it themselves, this row is the practice's claim about who works there and nothing
     * more. That is what keeps adding genuinely inert in both directions — an add that quietly
     * armed a later removal would make seating a stranger a way to reach their sessions, which is
     * the one thing a practice's control plane must never be able to do. See [acceptMembership].
     */
    fun addMember(orgId: String, memberId: String, role: OrgRole, addedBy: String): OrgWrite = synchronized(lock) {
        if (!isName(orgId) || !isName(memberId) || !isName(addedBy)) return OrgWrite.BAD_NAME
        if (orgLocked(orgId) == null) return OrgWrite.NO_SUCH_ORG
        if (existsLocked(orgId, memberId)) return OrgWrite.ALREADY_EXISTS
        insertMemberLocked(orgId, memberId, role, clock(), addedBy)
        return OrgWrite.OK
    }

    /**
     * The seated person says yes: mark their own membership accepted. Idempotent.
     *
     * This is the only mutation in the file whose subject is the caller rather than somebody the
     * caller administers, and that asymmetry is the whole reason it exists. An admin adds a row;
     * only the person named in the row can turn it into standing. There is deliberately no
     * `setAccepted(orgId, memberId, value)` an administrator could reach, no way back to false, and
     * no parameter for who is doing the accepting — the route passes the id it took from the
     * caller's own session, and a method shaped like this cannot be handed a different one by
     * accident.
     *
     * Why acceptance is worth a column at all, restated where a reader lands: removal cuts portal
     * sessions, and a portal session belongs to a CREDENTIAL rather than to a practice. Without a
     * fact that says this person consented to this practice, add-then-remove is a one-two an admin
     * could aim at any credential id they can guess — a clinician of another practice, mid-session,
     * with nothing about it in that practice's audit chain. See [Membership.accepted].
     *
     * NOT_A_MEMBER for a row that does not exist, which is also what a person who was removed
     * between fetching the page and clicking gets. Fail closed: there is no create-on-accept path,
     * so a stranger cannot seat themselves by accepting an invitation nobody issued.
     */
    fun acceptMembership(orgId: String, memberId: String): OrgWrite = synchronized(lock) {
        if (!isName(orgId) || !isName(memberId)) return OrgWrite.BAD_NAME
        if (roleLocked(orgId, memberId) == null) return OrgWrite.NOT_A_MEMBER
        conn.prepareStatement("UPDATE org_members SET accepted=1 WHERE member_ref=?").use { ps ->
            ps.setString(1, memberRef(orgId, memberId))
            ps.executeUpdate()
        }
        return OrgWrite.OK
    }

    /**
     * Change one member's role within one practice.
     *
     * Refuses to demote the practice's last admin — see [OrgWrite.LAST_ADMIN]. Promoting is
     * unrestricted here and gated where it belongs: the route charges step-up for it, because
     * changing who *can* be granted is the expensive direction in the annoyance budget.
     */
    fun changeRole(orgId: String, memberId: String, role: OrgRole): OrgWrite = synchronized(lock) {
        if (!isName(orgId) || !isName(memberId)) return OrgWrite.BAD_NAME
        val current = roleLocked(orgId, memberId) ?: return OrgWrite.NOT_A_MEMBER
        if (current == OrgRole.ORG_ADMIN && role != OrgRole.ORG_ADMIN && adminCountLocked(orgId) <= 1) {
            return OrgWrite.LAST_ADMIN
        }
        conn.prepareStatement("UPDATE org_members SET role=? WHERE member_ref=?").use { ps ->
            ps.setString(1, role.wire); ps.setString(2, memberRef(orgId, memberId))
            ps.executeUpdate()
        }
        return OrgWrite.OK
    }

    /**
     * Take a person out of a practice. The row is deleted, not flagged.
     *
     * A `removed_at` column would have kept the history, and the history is already kept — in the
     * hash-chained audit log, which is append-only, tamper-evident, and the place the spec puts it.
     * A soft-deleted membership row, by contrast, is a live row that every future query has to
     * remember to exclude, which is the same forgettable-WHERE-clause hazard the primary key is
     * shaped to avoid, applied to the one question where getting it wrong means a removed clinician
     * still reads as staff.
     *
     * What this does and does not accomplish is worth being exact about, because "removed" is a
     * word people hear as more than it is. What it accomplishes is that this person's standing IN
     * THIS PRACTICE ends immediately, effective on their next request, because nothing caches a
     * role. That is the whole of it, and the two things it is not are both larger than it:
     *
     * It is not the **cryptographic cutoff** — rotating the patient's data key and re-wrapping it
     * for whoever is still authorised — which this store not only does not do but *cannot* do: the
     * key is on the patient's device and the control plane has never held it.
     *
     * It is also not the end of the removed person's ACCESS. Their portal credential was created by
     * a patient's invitation, not by this practice, and nothing here touches it; whatever a patient
     * granted them, that patient is still the only one who can withdraw. A departing clinician who
     * still holds their authenticator signs in again and reads exactly what they read yesterday.
     * Both of those are the design working rather than gaps in it — an admin who could end a
     * patient's grant would be an admin with authority in the data plane — and the honest limit the
     * spec asks be said in-product is the same one: revocation stops future access, cannot un-read
     * what was already decrypted, and here does not even stop the reading. The route that calls
     * this says the same thing at the HTTP boundary, where a client author will read it.
     */
    fun removeMember(orgId: String, memberId: String): OrgWrite = synchronized(lock) {
        if (!isName(orgId) || !isName(memberId)) return OrgWrite.BAD_NAME
        val current = roleLocked(orgId, memberId) ?: return OrgWrite.NOT_A_MEMBER
        if (current == OrgRole.ORG_ADMIN && adminCountLocked(orgId) <= 1) return OrgWrite.LAST_ADMIN
        conn.prepareStatement("DELETE FROM org_members WHERE member_ref=?").use { ps ->
            ps.setString(1, memberRef(orgId, memberId))
            ps.executeUpdate()
        }
        return OrgWrite.OK
    }

    private fun insertMemberLocked(orgId: String, memberId: String, role: OrgRole, now: Long, addedBy: String) {
        conn.prepareStatement(
            "INSERT INTO org_members(member_ref, org_id, member_id, role, added_at, added_by) VALUES (?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, memberRef(orgId, memberId))
            ps.setString(2, orgId)
            ps.setString(3, memberId)
            ps.setString(4, role.wire)
            ps.setLong(5, now)
            ps.setString(6, addedBy)
            ps.executeUpdate()
        }
    }

    private fun existsLocked(orgId: String, memberId: String): Boolean = roleLocked(orgId, memberId) != null

    private fun roleLocked(orgId: String, memberId: String): OrgRole? {
        conn.prepareStatement("SELECT role FROM org_members WHERE member_ref=?").use { ps ->
            ps.setString(1, memberRef(orgId, memberId))
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return OrgRole.fromWire(rs.getString(1))
            }
        }
    }

    private fun adminCountLocked(orgId: String): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM org_members WHERE org_id=? AND role=?").use { ps ->
            ps.setString(1, orgId); ps.setString(2, OrgRole.ORG_ADMIN.wire)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        /**
         * The same strict charset every other path-ish identifier in this server takes.
         *
         * Applied to practice ids and to member ids alike. A member id is the portal credential id
         * a clinician signs in with, so this does constrain what a credential id may be if its
         * holder is ever to join a practice — deliberately, since the id is an input to a stored
         * key and an identifier that can contain anything is an identifier that eventually contains
         * a separator.
         */
        private val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** Practice names are shown to members. Bounded, and free of control characters. */
        private const val MAX_ORG_NAME = 120

        internal fun isName(s: String): Boolean = NAME.matches(s)

        internal fun isOrgName(s: String): Boolean =
            s.isNotBlank() && s.length <= MAX_ORG_NAME && s.none { it.isISOControl() }

        /**
         * The opaque handle a membership row is stored under: `BLAKE2b-256(orgId : memberId)`.
         *
         * The colon is unambiguous because [NAME] excludes it from both halves, so no pair of
         * distinct (practice, person) inputs can collide by shifting the separator — the encoding
         * is injective, which is the property that makes the digest a safe identity rather than a
         * convenient one.
         *
         * Same construction and same reasoning as `Secrets.relRefOf`, which derives an opaque
         * routing id from an inbox token: a digest, of non-secret inputs, used as an address. It is
         * not hiding anything (both inputs are stored in the clear in the row it keys), and it is
         * not a security control on its own. Its job is that the practice is *structurally* part of
         * the only name a membership has.
         */
        internal fun memberRef(orgId: String, memberId: String): String =
            Secrets.b64url(Secrets.blake2b("$orgId:$memberId".toByteArray(Charsets.UTF_8), 32))
    }
}
