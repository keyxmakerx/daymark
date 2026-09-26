package com.daymark.companion.auth

import com.daymark.companion.storage.Schema
import com.daymark.companion.storage.SchemaChange
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Store-and-forward relay state for the CPace pairing exchange (COMPANION_PAIRING.md §4).
 *
 * The exchange this carries is one round trip that never happens in real time: the owner posts
 * their opening message, the therapist fetches it and posts the reply whenever they open the
 * link, and the owner collects the reply next time they look. Three touches, none simultaneous.
 * This store is the parcel shelf between them, and a parcel shelf is ALL it is allowed to be:
 *
 *  - THE BLOBS ARE OPAQUE. `msg_a`, `msg_b`, `env_to_owner` and `env_to_therapist` are stored and
 *    returned byte-for-byte, never parsed, never validated beyond a size cap. The server cannot
 *    participate in the exchange — it does not have the pairing code, and the code never reaches
 *    it in any form (COMPANION_PAIRING.md §5) — so there is nothing it could legitimately do with
 *    the contents.
 *    A relay that started reading its parcels would learn nothing and become a thing worth
 *    compromising. One envelope goes each way: the therapist's offer (their public keys, a name,
 *    the enrol ticket they chose) travels with the reply, and the owner's own public keys travel
 *    with the approval. Both are sealed under keys only a right code derives; to this store they
 *    are two more opaque columns.
 *
 *  - ROWS ARE INSERT-ONLY; only `state`, `responded_at` and the envelope columns ever change, along
 *    the one path OPEN → RESPONDED → CLOSED (the owner's approve), or → CANCELLED from any of
 *    those (the owner's cancel; from CLOSED it is an abandon, which also puts the invitation back),
 *    or OPEN → SUPERSEDED when the owner opens a fresh run. A wrong-code protocol run is retried
 *    by the owner opening a FRESH exchange — CPace gives one guess per run by construction, and a
 *    fresh run needs fresh randomness on both sides, so reuse is not an optimisation, it is a
 *    vulnerability. AT MOST ONE OPEN EXCHANGE PER INVITE, as an invariant kept by [open]: the
 *    run it replaces is retired in the same block that inserts the new one, so nothing stale is
 *    ever served to a fetch or answerable by a respond. (Before the 2026-09-01 audit this held
 *    only while the newest row stayed OPEN — the moment it was answered or cancelled, the
 *    abandoned older row, whose scalar the owner's device had already discarded, resurfaced as
 *    "newest", and a holder of the link could answer it into a key nobody would ever hold.)
 *    Retired rows keep their history.
 *
 *  - EXCHANGES ARE CAPPED PER INVITE ([MAX_EXCHANGES_PER_INVITE]) because each open is a free
 *    insert to the bearer token that mints it, and unbounded server-side growth from a bounded
 *    human ceremony is the attempt_windows lesson over again.
 *
 * Expiry rides on the INVITE's expiry, passed in at open: an exchange has no life of its own
 * beyond the invitation it serves, and a second clock would let the two disagree.
 */
class PairingStore(
    dataDir: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val root: Path = Path.of(dataDir).toAbsolutePath().normalize()
    private val lock = Any()
    private val conn: Connection

    init {
        Files.createDirectories(root)
        conn = SCHEMA.open(root)
        conn.createStatement().use { st -> st.execute("PRAGMA synchronous=NORMAL") }
    }

    enum class State { OPEN, RESPONDED, CLOSED, CANCELLED, SUPERSEDED }

    data class Exchange(
        val exchangeId: String,
        val inviteId: String,
        val relRef: String,
        val sidB64: String,
        val msgAB64: String,
        val msgBB64: String?,
        /** The therapist's sealed offer, therapist → owner. Null until answered, and null forever on rows older than the column. */
        val envToOwnerB64: String?,
        /** The owner's sealed keys, owner → therapist. Null until approved, and null forever on rows older than the column. */
        val envToTherapistB64: String?,
        val state: State,
        val createdAt: Long,
        val expiry: Long,
    )

    enum class OpenStatus { OK, TOO_MANY, INVITE_DEAD }
    data class OpenResult(val status: OpenStatus, val exchangeId: String? = null)

    /**
     * The owner opens a fresh exchange. [expiry] is the INVITE's expiry — see the class KDoc —
     * and it is checked HERE because invite expiry is lazy elsewhere: an invite nothing has
     * touched still reads PENDING in its row after its time has passed, so a route trusting the
     * status word alone would happily open an exchange nobody can ever answer.
     */
    fun open(inviteId: String, relRef: String, sidB64: String, msgAB64: String, expiry: Long): OpenResult = synchronized(lock) {
        if (expiry <= clock()) return OpenResult(OpenStatus.INVITE_DEAD)
        val count = conn.prepareStatement("SELECT COUNT(*) FROM pairing_exchanges WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        if (count >= MAX_EXCHANGES_PER_INVITE) return OpenResult(OpenStatus.TOO_MANY)
        // Retire the run this one replaces — after the cap check, so a refused open retires
        // nothing, and inside the same lock as the insert, so "at most one OPEN per invite" is
        // never observably false. Not the owner's Cancel (no audit line): the owner did not
        // withdraw, they started over, and the audit already records the new opening.
        conn.prepareStatement("UPDATE pairing_exchanges SET state=? WHERE invite_id=? AND state=?").use { ps ->
            ps.setString(1, State.SUPERSEDED.name)
            ps.setString(2, inviteId)
            ps.setString(3, State.OPEN.name)
            ps.executeUpdate()
        }
        val exchangeId = Secrets.newToken()
        conn.prepareStatement(
            "INSERT INTO pairing_exchanges(exchange_id, invite_id, rel_ref, sid, msg_a, state, created_at, expiry) VALUES (?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, exchangeId)
            ps.setString(2, inviteId)
            ps.setString(3, relRef)
            ps.setString(4, sidB64)
            ps.setString(5, msgAB64)
            ps.setString(6, State.OPEN.name)
            ps.setLong(7, clock())
            ps.setLong(8, expiry)
            ps.executeUpdate()
        }
        OpenResult(OpenStatus.OK, exchangeId)
    }

    private val columns =
        "exchange_id, invite_id, rel_ref, sid, msg_a, msg_b, env_to_owner, env_to_therapist, state, created_at, expiry"

    /**
     * The live OPEN exchange for an invite — what a therapist fetch sees. There is at most one
     * ([open] retires the run it replaces), and a row past its invite's expiry is not it. The
     * ordering stays as belt-and-braces for a database written before the invariant existed.
     */
    fun openExchangeFor(inviteId: String): Exchange? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT $columns FROM pairing_exchanges WHERE invite_id=? AND state=? AND expiry>? ORDER BY created_at DESC, exchange_id DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.setString(2, State.OPEN.name)
            ps.setLong(3, clock())
            ps.executeQuery().use { rs -> if (rs.next()) rowFrom(rs) else null }
        }
    }

    /** The newest exchange for an invite in ANY state — the owner console's "where does this invitation stand". */
    fun latestExchangeFor(inviteId: String): Exchange? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT $columns FROM pairing_exchanges WHERE invite_id=? ORDER BY created_at DESC, exchange_id DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rowFrom(rs) else null }
        }
    }

    enum class RespondStatus { OK, GONE }

    /**
     * The therapist answers ONE exchange, once: the CPace reply and, sealed beside it, their
     * offer. [inviteId] must be the invite the exchange belongs to — a mismatch is GONE, not an
     * error detail, because the caller only ever proves possession of one invite and must learn
     * nothing about any other's exchanges. The `state = OPEN` predicate is what makes "once"
     * true: there is no second write into a run, so nothing sealed can ever be replaced.
     */
    fun respond(exchangeId: String, inviteId: String, msgBB64: String, envToOwnerB64: String?): RespondStatus = synchronized(lock) {
        val now = clock()
        val updated = conn.prepareStatement(
            "UPDATE pairing_exchanges SET state=?, msg_b=?, env_to_owner=?, responded_at=? " +
                "WHERE exchange_id=? AND invite_id=? AND state=? AND expiry>?",
        ).use { ps ->
            ps.setString(1, State.RESPONDED.name)
            ps.setString(2, msgBB64)
            ps.setString(3, envToOwnerB64)
            ps.setLong(4, now)
            ps.setString(5, exchangeId)
            ps.setString(6, inviteId)
            ps.setString(7, State.OPEN.name)
            ps.setLong(8, now)
            ps.executeUpdate()
        }
        if (updated == 1) RespondStatus.OK else RespondStatus.GONE
    }

    /** Owner-side read of one exchange. relRef must match — a miss is null, non-enumerating. */
    fun exchangeFor(exchangeId: String, relRef: String): Exchange? = synchronized(lock) {
        conn.prepareStatement("SELECT $columns FROM pairing_exchanges WHERE exchange_id=? AND rel_ref=?").use { ps ->
            ps.setString(1, exchangeId)
            ps.setString(2, relRef)
            ps.executeQuery().use { rs -> if (rs.next()) rowFrom(rs) else null }
        }
    }

    /** Therapist-side read of one exchange, keyed by the invite they proved. A miss is null, non-enumerating. */
    fun exchangeForInvite(exchangeId: String, inviteId: String): Exchange? = synchronized(lock) {
        conn.prepareStatement("SELECT $columns FROM pairing_exchanges WHERE exchange_id=? AND invite_id=?").use { ps ->
            ps.setString(1, exchangeId)
            ps.setString(2, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rowFrom(rs) else null }
        }
    }

    enum class TransitionStatus { OK, GONE }

    /**
     * The owner approves the reply; RESPONDED → CLOSED, and their sealed keys land in the same
     * statement that closes the run. The invitation side of the same act (REDEEMING, the ticket)
     * lives in AuthStore and the route sequences the two.
     *
     * ONE WRITE, so there is no instant where a run is CLOSED with no envelope behind it: the
     * therapist's status poll is allowed to return the envelope precisely BECAUSE the run is
     * closed, and a second UPDATE would open a window where that reasoning is false. The
     * `state = RESPONDED` predicate keeps it once-only, as respond's does for the other direction:
     * an approved run cannot have its envelope replaced by anything, including a second approval.
     */
    fun approve(exchangeId: String, relRef: String, envToTherapistB64: String?): TransitionStatus = synchronized(lock) {
        val updated = conn.prepareStatement(
            "UPDATE pairing_exchanges SET state=?, env_to_therapist=? WHERE exchange_id=? AND rel_ref=? AND state=?",
        ).use { ps ->
            ps.setString(1, State.CLOSED.name)
            ps.setString(2, envToTherapistB64)
            ps.setString(3, exchangeId)
            ps.setString(4, relRef)
            ps.setString(5, State.RESPONDED.name)
            ps.executeUpdate()
        }
        if (updated == 1) TransitionStatus.OK else TransitionStatus.GONE
    }

    /** Owner cancels; OPEN or RESPONDED → CANCELLED (COMPANION_PAIRING.md §8). */
    fun cancel(exchangeId: String, relRef: String): TransitionStatus =
        transition(exchangeId, relRef, from = listOf(State.OPEN, State.RESPONDED), to = State.CANCELLED)

    /**
     * Owner abandons an approved run nobody finished; CLOSED → CANCELLED. The route only calls
     * this after AuthStore has put the invitation back to PENDING and taken the ticket, so a row
     * that reads CANCELLED here never has a live ticket behind it.
     */
    fun abandon(exchangeId: String, relRef: String): TransitionStatus =
        transition(exchangeId, relRef, from = listOf(State.CLOSED), to = State.CANCELLED)

    private fun transition(exchangeId: String, relRef: String, from: List<State>, to: State): TransitionStatus = synchronized(lock) {
        val placeholders = from.joinToString(",") { "?" }
        val updated = conn.prepareStatement(
            "UPDATE pairing_exchanges SET state=? WHERE exchange_id=? AND rel_ref=? AND state IN ($placeholders)",
        ).use { ps ->
            ps.setString(1, to.name)
            ps.setString(2, exchangeId)
            ps.setString(3, relRef)
            from.forEachIndexed { i, s -> ps.setString(4 + i, s.name) }
            ps.executeUpdate()
        }
        if (updated == 1) TransitionStatus.OK else TransitionStatus.GONE
    }

    /** How many exchanges an invite has used of its [MAX_EXCHANGES_PER_INVITE]; the owner's "6 of 8 left". */
    fun exchangeCountFor(inviteId: String): Long = synchronized(lock) {
        conn.prepareStatement("SELECT COUNT(*) FROM pairing_exchanges WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun rowFrom(rs: java.sql.ResultSet) = Exchange(
        exchangeId = rs.getString(1),
        inviteId = rs.getString(2),
        relRef = rs.getString(3),
        sidB64 = rs.getString(4),
        msgAB64 = rs.getString(5),
        msgBB64 = rs.getString(6),
        envToOwnerB64 = rs.getString(7),
        envToTherapistB64 = rs.getString(8),
        state = State.valueOf(rs.getString(9)),
        createdAt = rs.getLong(10),
        expiry = rs.getLong(11),
    )

    override fun close() {
        synchronized(lock) { conn.close() }
    }

    companion object {
        /**
         * pairing.db, version by version (#193). [Schema] says what a version is, and how a database
         * written by an earlier release is brought to [Schema.current] before it is served.
         */
        internal val SCHEMA = Schema(
            "pairing.db",
            listOf(
                // Version 1: the structure as it stood when versions began to be kept.
                listOf(
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS pairing_exchanges (
                            exchange_id  TEXT    PRIMARY KEY,
                            invite_id    TEXT    NOT NULL,
                            rel_ref      TEXT    NOT NULL,
                            sid          TEXT    NOT NULL,
                            msg_a        TEXT    NOT NULL,
                            msg_b        TEXT,
                            state        TEXT    NOT NULL,
                            created_at   INTEGER NOT NULL,
                            responded_at INTEGER,
                            expiry       INTEGER NOT NULL,
                            env_to_owner TEXT,
                            env_to_therapist TEXT
                        )
                        """.trimIndent(),
                    ),
                    // A database from before these columns existed has a pairing_exchanges table
                    // without them, which CREATE TABLE IF NOT EXISTS leaves alone; they are added. A
                    // reply stored before env_to_owner existed reads back with no envelope, and the
                    // owner's client treats that as a reply without an offer; an approval stored before
                    // env_to_therapist existed reads back the same way, and the clinician's client
                    // treats it as an approval that proved no owner keys.
                    SchemaChange.Column("pairing_exchanges", "env_to_owner", "TEXT"),
                    SchemaChange.Column("pairing_exchanges", "env_to_therapist", "TEXT"),
                    SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_pairing_invite ON pairing_exchanges(invite_id, created_at)"),
                ),
            ),
        )

        /**
         * Eight, not eighty: an honest ceremony uses one exchange, a mistyped code a second,
         * a bad phone line maybe a third. The cap bounds what an owner token can grow the
         * database by per invite; hitting it is a signal to mint a fresh invitation, which is
         * cheap and also rotates the link an apparent attacker has been working on.
         */
        const val MAX_EXCHANGES_PER_INVITE = 8
    }
}
