package com.daymark.companion.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Who performed the logged action.
 *
 * The first two are the two ends of a patient/clinician relationship and key entries in the
 * relationship log. The last three act in the ORG control plane and key entries in the practice log
 * — a physically separate database (see [AuditStore]'s `dbName`), so a practice's history and a
 * patient's history cannot be interleaved by any choice of identifier.
 */
enum class AuditActor(val wire: String) {
    OWNER("owner"),
    THERAPIST("therapist"),

    /** A practice member exercising membership authority, inside the one practice they hold it in. */
    ORG_ADMIN("org_admin"),

    /**
     * A practice member without the authority for the act they attempted.
     *
     * Distinct from [ORG_ADMIN] so that a refusal is legible as a refusal at a glance. A front-desk
     * account trying to change roles is either a broken client or somebody testing where the wall
     * is, and both are things the practice's admin should be able to see without decoding the
     * action name.
     */
    ORG_MEMBER("org_member"),

    /**
     * The operator's provisioning identity, which creates practices and seats their first admin.
     *
     * The platform plane: runs the server, holds no grant, appears in exactly one action. It is
     * listed here rather than folded into [OWNER] because conflating "the person who runs the
     * infrastructure" with "the person whose data it is" is the exact confusion the three-plane
     * rule exists to prevent, and an audit log that made them the same word would be arguing for it.
     */
    PLATFORM("platform"),
}

/**
 * The audit action taxonomy (COMPANION_SECURITY.md §9 / COMPANION_THERAPIST.md §10),
 * extend as needed. Every value names an EVENT, never content.
 */
enum class AuditAction(val wire: String) {
    AUTH_SUCCESS("auth.success"),
    AUTH_FAIL("auth.fail"),
    LOCKOUT("lockout"),
    ENROL_OK("enrol.ok"),
    SHARE_OPEN("share.open"),
    GAMEPLAN_OPEN("gameplan.open"),
    ASSIGNMENT_PUBLISH("assignment.publish"),
    GAMEPLAN_PUBLISH("gameplan.publish"),
    SESSION_EXPIRED("session.expired"),

    /** The owner withdrew a share lineage. Recorded so withdrawal is never a silent operation. */
    SHARE_REVOKE("share.revoke"),

    /**
     * A read of an expired or withdrawn share was refused.
     *
     * The owner's log distinguishes expiry from revocation; the therapist's 410 does not. That
     * asymmetry is the point — the owner is entitled to know what their own access control did.
     */
    SHARE_DENIED("share.denied"),

    /**
     * A pairing attempt presented the wrong invite secret.
     *
     * This is the event that is deliberately NOT allowed to destroy the invitation. A wrong code
     * and an attacker's guess are indistinguishable by construction, so the server cannot tell a
     * mistyped character from a hostile probe and must not act as though it can — it takes the
     * capped backoff and writes this line. The line matters precisely because the automatic
     * response is so restrained: a burst of these against one relationship is the only thing that
     * tells the owner somebody is working on their invite, and it is the evidence a person needs
     * before deciding to report it. A failed pairing attempt that left no trace would be an attack
     * that left no trace.
     *
     * Carries no code, no guess and no fragment of either — the event, not its content.
     */
    PAIR_GUESS_FAILED("pair.guess_failed"),

    /**
     * A person explicitly reported an invitation as unexpected, and it was killed on the spot.
     *
     * The counterpart to [PAIR_GUESS_FAILED], and the reason the two must never be one action: a
     * failed guess is unknowable and gets patience, while a human saying "this wasn't me" is
     * unambiguous and gets the terminal state. [AuditActor] records which side reported — the owner
     * seeing an invitation they did not expect, or the invited party who was handed a link they
     * never asked for — because the two say quite different things about what went wrong.
     */
    INVITE_REPORTED("invite.reported"),

    /**
     * The therapist published their public keys for this relationship, and the server took them.
     *
     * Read the wording carefully, because the natural reading of an audit line is that the server
     * checked something and approved it, and here it did not. All this records is that two 32-byte
     * public keys arrived from a caller holding a valid session and were stored insert-only. The
     * server cannot tell the therapist's real key from one a hostile operator or a stolen session
     * substituted — it relays, it does not attest — so this line is a receipt for a delivery, not a
     * statement that the right keys arrived. What decides that is the owner comparing fingerprint
     * words with their therapist on another channel before pinning.
     *
     * The line still earns its place: this is the moment the owner can start sealing shares to a
     * particular key, so it is the timestamp they will want if that key is ever in question.
     *
     * Carries no key material, no fingerprint and no fragment of either — the event, not its
     * content, like every other value here.
     */
    THERAPIST_KEY_REGISTERED("therapist_key.registered"),

    /**
     * A second registration arrived for a relationship that already had keys, and was refused.
     *
     * The counterpart to [THERAPIST_KEY_REGISTERED], recorded separately for the same reason
     * [SHARE_DENIED] is not folded into [SHARE_REVOKE]: the refusal is the interesting half. A
     * successful registration is routine and happens once. A refused one means something tried to
     * replace the key the owner may already have pinned and sealed to — a therapist who re-keyed
     * and does not yet know they need the owner's rotate-pin path, a client retrying a request it
     * thinks failed, or a session in the wrong hands attempting exactly the substitution the
     * insert-only rule exists to stop. The server cannot tell those apart and must not pretend to;
     * it refuses all three identically and writes this line so the owner can ask.
     *
     * The 409 the caller sees says nothing about which of those it was, and neither does this.
     */
    THERAPIST_KEY_REFUSED("therapist_key.refused"),

    /**
     * The owner collected the therapist's registered public keys.
     *
     * The read half, logged for the same reason [GAMEPLAN_OPEN] is: the log is a record of what
     * moved between the two parties, and a half of it that only ever recorded writes would show
     * the owner things arriving and never being picked up. It is also the entry that dates the
     * owner's opportunity to pin, which is the fact that matters if a key is later disputed.
     */
    THERAPIST_KEY_FETCHED("therapist_key.fetched"),

    /**
     * The owner published their own public keys for a relationship, so the clinician's browser can
     * stop asking a human to paste them.
     *
     * The mirror of [THERAPIST_KEY_REGISTERED] and, like it, a receipt for a DELIVERY rather than a
     * statement that the right keys arrived. The server does not vouch for either side's keys; what
     * catches a substitution is the fingerprint compared out of band, and an audit line is exactly
     * the sort of thing that reads like approval if it is not said plainly.
     */
    OWNER_KEY_REGISTERED("owner_key.registered"),

    /** A second publish for a relationship that already has owner keys. Refused, never overwritten. */
    OWNER_KEY_REFUSED("owner_key.refused"),

    /** A clinician read the owner's published keys. */
    OWNER_KEY_FETCHED("owner_key.fetched"),

    /**
     * A practice was created and its first admin was seated.
     *
     * The genesis entry of a practice's chain, and the only one the operator's provisioning
     * identity ever writes. Carries the practice id, never the practice's name: the id is what the
     * rest of the chain is keyed on and therefore has to be here, while the name is a human-
     * readable label that would sit in a log forever for no operational gain. "Minimize and don't
     * over-log" applies to metadata too, and a practice name is exactly the kind of metadata that
     * identifies a real clinic in a leaked file.
     */
    ORG_CREATED("org.created"),

    /**
     * Somebody was seated in a practice with a role.
     *
     * Read this for what it is not. It records a change to who *may be offered* read capability; it
     * does not record read capability being created, because adding a member creates none. The
     * grant that would is minted on a patient's device, authorised by that patient, and recorded in
     * that patient's own log — a different chain in a different database, which is the arrangement
     * that keeps "the practice added a clinician" and "I let someone read my notes" from ever
     * looking like the same event.
     */
    ORG_MEMBER_ADDED("org.member_added"),

    /** A member's role changed within one practice. Carries the roles, which are membership metadata. */
    ORG_MEMBER_ROLE_CHANGED("org.member_role_changed"),

    /**
     * The seated person accepted their own seat.
     *
     * The only entry in the practice's chain written by the member about themselves, and the reason
     * it is worth a line rather than a silent column flip: acceptance is what gives the practice
     * standing to end that person's portal sessions when they leave, so the moment they granted it
     * is exactly the moment a later dispute turns on. Recorded as a membership event and nothing
     * more — an id, a role, a timestamp — because that is all acceptance is.
     */
    ORG_MEMBER_ACCEPTED("org.member_accepted"),

    /**
     * A member was removed from a practice.
     *
     * The entry an admin goes looking for when they need to prove *when* somebody stopped being a
     * member of the practice, so it is written on the removal itself rather than inferred from an
     * absence later. Its `sessionsCut` annotation says how many live portal sessions went with it,
     * which is zero for a seat the person never accepted — the practice has no standing over a
     * credential nobody agreed to bring.
     *
     * Read the entry for what it is: the end of a membership, not the end of an access. No key was
     * rotated, because none was ever here to rotate; no credential was disabled, because this
     * practice never issued one; and a removed clinician who still holds their authenticator can
     * sign in again and read whatever a patient still grants them. Only that patient can change
     * that, and when they do it is recorded in their chain rather than in this one.
     */
    ORG_MEMBER_REMOVED("org.member_removed"),

    /**
     * A member of the practice attempted a control-plane act their role does not carry, and was
     * refused.
     *
     * Written for the same reason [SHARE_DENIED] and [THERAPIST_KEY_REFUSED] are, and it is the
     * more informative half of this group: successful membership changes are routine, while a
     * front-desk account repeatedly trying to add members is either a client bug or someone
     * probing, and the server cannot tell those apart and should not pretend to. It refuses both
     * identically and writes this line so a person can ask.
     *
     * Only ever written against a practice the caller is genuinely a member of. A stranger's
     * attempt is not recorded, because the practice id on that request is an unverified path
     * parameter and auditing it would let anyone holding any session seed another practice's chain
     * with entries of their own choosing — the same reasoning that keeps a missed public-key read
     * out of the relationship log.
     */
    ORG_ACTION_DENIED("org.action_denied"),
}

data class AuditEvent(
    val seq: Long,
    val ts: Long,
    val relRef: String,
    val actor: String,
    val action: String,
    val objectRef: String?,
    val meta: Map<String, String>?,
    val entryHash: String,
)

/**
 * Owner-readable, append-only, metadata-only audit log of relationship access
 * (COMPANION_SECURITY.md §9 / COMPANION_THERAPIST.md §10). Callers must NEVER pass
 * plaintext, decrypted content, keys, TOTP codes, or which individual record was viewed —
 * only the event type, an opaque [objectRef] (channel-scoped lineage/version), and small
 * fixed non-content [meta] annotations (e.g. an acting credential id, or the source IP when
 * the operator opted in).
 *
 * Each entry is hash-chained: `entryHash = SHA-256(prevHash ‖ seq ‖ ts ‖ relRef ‖ actor ‖
 * action ‖ objectRef ‖ meta)`. This makes a stored entry's tampering/reordering detectable
 * (every later entry's hash would no longer verify), but it is **server-computed, not
 * therapist-signed** — it does not add non-repudiation, and it does NOT stop a hostile
 * server from simply never appending an event, or truncating the chain and serving a
 * shorter-but-internally-consistent history. See the honest retraction in
 * docs/COMPANION_SECURITY.md §9 (R12) — completeness is not provable, only internal
 * consistency of whatever is returned.
 */
class AuditStore(
    dataDir: String,
    private val retentionSeconds: Long = DEFAULT_RETENTION_SECONDS,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Which database file this chain lives in. Defaults to the relationship log.
     *
     * The org control plane opens a SECOND instance on its own file, and the separation is
     * structural rather than tidy-minded. Both logs key their chains on an opaque identifier out of
     * the same charset — a relationship's `relRef` is a 43-character base64url digest, a practice
     * id is a minted token of the same alphabet — so a single shared table would be one unlucky
     * collision away from a practice's membership history being chained into a patient's access
     * history, or an org admin's audit read paging into rows that were never theirs. Two files
     * makes that unreachable rather than unlikely: the identifier spaces never meet, because the
     * tables never meet.
     *
     * Everything else is identical by construction — same chain, same escaping, same retention, same
     * metadata-only contract — because they are the same class. That is the point of the parameter
     * rather than a second copy of this file.
     */
    dbName: String = "audit.db",
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(root)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve(dbName)}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    rel_ref    TEXT    NOT NULL,
                    seq        INTEGER NOT NULL,
                    ts         INTEGER NOT NULL,
                    actor      TEXT    NOT NULL,
                    action     TEXT    NOT NULL,
                    object_ref TEXT,
                    meta       TEXT,
                    entry_hash TEXT    NOT NULL,
                    PRIMARY KEY (rel_ref, seq)
                )
                """.trimIndent(),
            )
        }
    }

    /** Append one entry, chained off this relationship's latest entry. Insert-only. */
    fun append(
        relRef: String,
        actor: AuditActor,
        action: AuditAction,
        objectRef: String? = null,
        meta: Map<String, String>? = null,
    ): AuditEvent = synchronized(lock) {
        requireName(relRef)
        val now = clock()
        val prevHash = lastHashLocked(relRef)
        val seq = lastSeqLocked(relRef) + 1
        val metaEncoded = meta?.let { canonicalMeta(it) }
        val entryHash = chainHash(prevHash, seq, now, relRef, actor.wire, action.wire, objectRef, metaEncoded)
        conn.prepareStatement(
            "INSERT INTO audit_events(rel_ref, seq, ts, actor, action, object_ref, meta, entry_hash) VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setLong(2, seq)
            ps.setLong(3, now)
            ps.setString(4, actor.wire)
            ps.setString(5, action.wire)
            if (objectRef != null) ps.setString(6, objectRef) else ps.setNull(6, java.sql.Types.VARCHAR)
            if (metaEncoded != null) ps.setString(7, metaEncoded) else ps.setNull(7, java.sql.Types.VARCHAR)
            ps.setString(8, entryHash)
            ps.executeUpdate()
        }
        pruneExpiredLocked(relRef, now)
        AuditEvent(seq, now, relRef, actor.wire, action.wire, objectRef, meta, entryHash)
    }

    /** List newest-first. When [beforeSeq] is set, only entries strictly older than it. */
    fun list(relRef: String, beforeSeq: Long? = null, limit: Int = 50): List<AuditEvent> = synchronized(lock) {
        requireName(relRef)
        val cap = limit.coerceIn(1, MAX_PAGE_SIZE)
        val sql = if (beforeSeq != null) {
            "SELECT seq, ts, actor, action, object_ref, meta, entry_hash FROM audit_events " +
                "WHERE rel_ref=? AND seq<? ORDER BY seq DESC LIMIT ?"
        } else {
            "SELECT seq, ts, actor, action, object_ref, meta, entry_hash FROM audit_events " +
                "WHERE rel_ref=? ORDER BY seq DESC LIMIT ?"
        }
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, relRef)
            if (beforeSeq != null) ps.setLong(idx++, beforeSeq)
            ps.setInt(idx, cap)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AuditEvent>()
                while (rs.next()) {
                    out += AuditEvent(
                        seq = rs.getLong(1),
                        ts = rs.getLong(2),
                        relRef = relRef,
                        actor = rs.getString(3),
                        action = rs.getString(4),
                        objectRef = rs.getString(5),
                        meta = rs.getString(6)?.let(::parseMeta),
                        entryHash = rs.getString(7),
                    )
                }
                out
            }
        }
    }

    private fun lastSeqLocked(relRef: String): Long {
        conn.prepareStatement("SELECT MAX(seq) FROM audit_events WHERE rel_ref=?").use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val v = rs.getLong(1)
                    if (!rs.wasNull()) return v
                }
                return 0L
            }
        }
    }

    private fun lastHashLocked(relRef: String): String {
        conn.prepareStatement("SELECT entry_hash FROM audit_events WHERE rel_ref=? ORDER BY seq DESC LIMIT 1").use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else GENESIS_HASH }
        }
    }

    /** Best-effort prune of entries past the retention window for this relationship. */
    private fun pruneExpiredLocked(relRef: String, now: Long) {
        if (retentionSeconds <= 0) return
        conn.prepareStatement("DELETE FROM audit_events WHERE rel_ref=? AND ts < ?").use { ps ->
            ps.setString(1, relRef)
            ps.setLong(2, now - retentionSeconds)
            ps.executeUpdate()
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        const val DEFAULT_RETENTION_SECONDS = 90L * 24 * 3600
        const val MAX_PAGE_SIZE = 200

        /** Fixed genesis hash (64 hex zeros) chained ahead of the first entry for a relationship. */
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        private val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

        private fun requireName(name: String) {
            require(NAME.matches(name)) { "invalid rel_ref" }
        }

        /**
         * Deterministic, order-stable, **injective** encoding of a small flat meta map.
         *
         * The separators used to be raw, and that let a caller-supplied value forge entries. A
         * `credentialId` of `alice,sourceIp=10.0.0.1` encoded to
         * `credentialId=alice,sourceIp=10.0.0.1`, which [parseMeta] reads back as *two* fields —
         * one of them invented. And because [chainHash] hashes the already-encoded string, a
         * verifier that re-canonicalises the parsed map computes the identical hash: the tamper-
         * evidence machinery certifies the forgery instead of catching it. On a log whose whole
         * purpose is holding the therapist accountable, with a field the therapist supplies.
         *
         * Percent-escaping the three meaningful characters makes the mapping one-to-one, so
         * `parseMeta(canonicalMeta(m)) == m` for every map. Values containing none of them encode
         * byte-identically to before, so existing rows and their hashes still verify — only the
         * inputs that were already ambiguous change, and those were never trustworthy.
         */
        private fun canonicalMeta(meta: Map<String, String>): String =
            meta.toSortedMap().entries.joinToString(",") { (k, v) -> "${escapeMeta(k)}=${escapeMeta(v)}" }

        private fun parseMeta(encoded: String): Map<String, String> =
            if (encoded.isEmpty()) {
                emptyMap()
            } else {
                encoded.split(",").associate {
                    val parts = it.split("=", limit = 2)
                    unescapeMeta(parts[0]) to unescapeMeta(parts.getOrElse(1) { "" })
                }
            }

        // '%' must be escaped first and unescaped last, or the escape character is not itself
        // escapable and a value containing "%2C" would decode to a comma it never had.
        private fun escapeMeta(s: String): String =
            s.replace("%", "%25").replace(",", "%2C").replace("=", "%3D")

        private fun unescapeMeta(s: String): String =
            s.replace("%3D", "=").replace("%2C", ",").replace("%25", "%")

        private fun chainHash(
            prevHash: String,
            seq: Long,
            ts: Long,
            relRef: String,
            actor: String,
            action: String,
            objectRef: String?,
            metaEncoded: String?,
        ): String {
            val canonical = listOf(
                prevHash, seq.toString(), ts.toString(), relRef, actor, action,
                objectRef ?: "", metaEncoded ?: "",
            ).joinToString("|")
            return BlobStore.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
        }
    }
}
