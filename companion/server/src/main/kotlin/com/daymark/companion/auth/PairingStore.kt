package com.daymark.companion.auth

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Store-and-forward relay state for the CPace pairing exchange (plan §3.7.3).
 *
 * The exchange this carries is one round trip that never happens in real time: the owner posts
 * their opening message, the therapist fetches it and posts the reply whenever they open the
 * link, and the owner collects the reply next time they look. Three touches, none simultaneous.
 * This store is the parcel shelf between them, and a parcel shelf is ALL it is allowed to be:
 *
 *  - THE BLOBS ARE OPAQUE. `msg_a` and `msg_b` are stored and returned byte-for-byte, never
 *    parsed, never validated beyond a size cap. The server cannot participate in the exchange —
 *    it does not have the pairing code, and the code never reaches it in any form (§3.7.4) — so
 *    there is nothing it could legitimately do with the contents. A relay that started reading
 *    its parcels would learn nothing and become a thing worth compromising.
 *
 *  - ROWS ARE INSERT-ONLY; only `state` and `responded_at` ever change, along the one path
 *    OPEN → RESPONDED → CLOSED (or → CANCELLED from either pre-terminal state). A wrong-code
 *    protocol run is retried by the owner opening a FRESH exchange — CPace gives one guess per
 *    run by construction, and a fresh run needs fresh randomness on both sides, so reuse is not
 *    an optimisation, it is a vulnerability. The newest OPEN exchange is the one a therapist
 *    fetch sees; superseded rows keep their history.
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
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("pairing.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS pairing_exchanges (
                    exchange_id  TEXT    PRIMARY KEY,
                    invite_id    TEXT    NOT NULL,
                    rel_ref      TEXT    NOT NULL,
                    sid          TEXT    NOT NULL,
                    msg_a        TEXT    NOT NULL,
                    msg_b        TEXT,
                    -- The sealed negotiation halves (plan §3.7.3 step 5). Each side can only
                    -- seal AFTER it holds the CPace key, so payload_b rides with the response
                    -- and payload_a arrives when the owner closes. Opaque here in the strongest
                    -- sense: they are ciphertext under a key derived from a code this server
                    -- has never seen and cannot obtain.
                    payload_a    TEXT,
                    payload_b    TEXT,
                    state        TEXT    NOT NULL,
                    created_at   INTEGER NOT NULL,
                    responded_at INTEGER,
                    expiry       INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_pairing_invite ON pairing_exchanges(invite_id, created_at)")
        }
    }

    enum class State { OPEN, RESPONDED, CLOSED, CANCELLED }

    data class Exchange(
        val exchangeId: String,
        val inviteId: String,
        val relRef: String,
        val sidB64: String,
        val msgAB64: String,
        val msgBB64: String?,
        /** The owner's sealed negotiation half, present once they have closed. */
        val payloadAB64: String?,
        /** The clinician's sealed negotiation half, present once they have responded. */
        val payloadBB64: String?,
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

    /**
     * The newest live OPEN exchange for an invite — what a therapist fetch sees. Rows an owner
     * has superseded by re-opening, and rows past their invite's expiry, are simply not it.
     */
    fun openExchangeFor(inviteId: String): Exchange? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT exchange_id, invite_id, rel_ref, sid, msg_a, msg_b, payload_a, payload_b, state, created_at, expiry " +
                "FROM pairing_exchanges WHERE invite_id=? AND state=? AND expiry>? ORDER BY created_at DESC, exchange_id DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.setString(2, State.OPEN.name)
            ps.setLong(3, clock())
            ps.executeQuery().use { rs -> if (rs.next()) rowFrom(rs) else null }
        }
    }

    enum class RespondStatus { OK, GONE }

    /**
     * The therapist answers ONE exchange, once. [inviteId] must be the invite the exchange
     * belongs to — a mismatch is GONE, not an error detail, because the caller only ever proves
     * possession of one invite and must learn nothing about any other's exchanges.
     *
     * [payloadBB64] is their sealed negotiation half, written in the SAME statement as the
     * message that makes the key derivable. One UPDATE, not two: a reply that landed without
     * its payload would leave the owner holding a key and an empty envelope, in a state no
     * later request could distinguish from "the clinician has not finished typing".
     */
    fun respond(exchangeId: String, inviteId: String, msgBB64: String, payloadBB64: String?): RespondStatus = synchronized(lock) {
        val now = clock()
        val updated = conn.prepareStatement(
            "UPDATE pairing_exchanges SET state=?, msg_b=?, payload_b=?, responded_at=? " +
                "WHERE exchange_id=? AND invite_id=? AND state=? AND expiry>?",
        ).use { ps ->
            ps.setString(1, State.RESPONDED.name)
            ps.setString(2, msgBB64)
            if (payloadBB64 != null) ps.setString(3, payloadBB64) else ps.setNull(3, java.sql.Types.VARCHAR)
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
        conn.prepareStatement(
            "SELECT exchange_id, invite_id, rel_ref, sid, msg_a, msg_b, payload_a, payload_b, state, created_at, expiry " +
                "FROM pairing_exchanges WHERE exchange_id=? AND rel_ref=?",
        ).use { ps ->
            ps.setString(1, exchangeId)
            ps.setString(2, relRef)
            ps.executeQuery().use { rs -> if (rs.next()) rowFrom(rs) else null }
        }
    }

    enum class TransitionStatus { OK, GONE }

    /**
     * Owner acknowledges the reply and publishes their own sealed half; RESPONDED → CLOSED.
     * Anything else is GONE.
     *
     * Closing IS publishing, in one statement, for the reason [respond] gives about its own
     * payload: a CLOSED exchange with no payload_a is a state the clinician's collect could not
     * tell apart from "the owner has not looked yet", and the difference matters — one is a
     * ceremony still in progress and the other is one that silently lost a step.
     */
    fun close(exchangeId: String, relRef: String, payloadAB64: String?): TransitionStatus = synchronized(lock) {
        val updated = conn.prepareStatement(
            "UPDATE pairing_exchanges SET state=?, payload_a=? WHERE exchange_id=? AND rel_ref=? AND state=?",
        ).use { ps ->
            ps.setString(1, State.CLOSED.name)
            if (payloadAB64 != null) ps.setString(2, payloadAB64) else ps.setNull(2, java.sql.Types.VARCHAR)
            ps.setString(3, exchangeId)
            ps.setString(4, relRef)
            ps.setString(5, State.RESPONDED.name)
            ps.executeUpdate()
        }
        if (updated == 1) TransitionStatus.OK else TransitionStatus.GONE
    }

    /**
     * The clinician collects the owner's sealed half — the fourth and last touch of the
     * ceremony. Gated on the invite, like their other touches, and deliberately answering the
     * same way for "not closed yet" as for "no such exchange": until the owner has closed there
     * is nothing to hand over, and which of those two it is, is not this route's to disclose.
     */
    fun ownerPayloadFor(exchangeId: String, inviteId: String): String? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT payload_a FROM pairing_exchanges WHERE exchange_id=? AND invite_id=? AND state=? AND expiry>?",
        ).use { ps ->
            ps.setString(1, exchangeId)
            ps.setString(2, inviteId)
            ps.setString(3, State.CLOSED.name)
            ps.setLong(4, clock())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Owner cancels; OPEN or RESPONDED → CANCELLED. The 4.0a owner Cancel. */
    fun cancel(exchangeId: String, relRef: String): TransitionStatus =
        transition(exchangeId, relRef, from = listOf(State.OPEN, State.RESPONDED), to = State.CANCELLED)

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

    /** Test/inspection helper, same family as AuthStore.inviteStatusFor. */
    fun exchangeCountFor(inviteId: String): Long = synchronized(lock) {
        conn.prepareStatement("SELECT COUNT(*) FROM pairing_exchanges WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Column order matches the two SELECTs above, which are the only callers. */
    private fun rowFrom(rs: java.sql.ResultSet) = Exchange(
        exchangeId = rs.getString(1),
        inviteId = rs.getString(2),
        relRef = rs.getString(3),
        sidB64 = rs.getString(4),
        msgAB64 = rs.getString(5),
        msgBB64 = rs.getString(6),
        payloadAB64 = rs.getString(7),
        payloadBB64 = rs.getString(8),
        state = State.valueOf(rs.getString(9)),
        createdAt = rs.getLong(10),
        expiry = rs.getLong(11),
    )

    override fun close() {
        synchronized(lock) { conn.close() }
    }

    companion object {
        /**
         * Eight, not eighty: an honest ceremony uses one exchange, a mistyped code a second,
         * a bad phone line maybe a third. The cap bounds what an owner token can grow the
         * database by per invite; hitting it is a signal to mint a fresh invitation, which is
         * cheap and also rotates the link an apparent attacker has been working on.
         */
        const val MAX_EXCHANGES_PER_INVITE = 8
    }
}
