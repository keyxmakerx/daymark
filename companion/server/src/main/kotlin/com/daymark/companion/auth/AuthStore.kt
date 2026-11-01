package com.daymark.companion.auth

import com.daymark.companion.storage.Schema
import com.daymark.companion.storage.SchemaChange
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * SQLite-backed store for the therapist auth subsystem: single-use invites, TOTP
 * credentials, and opaque server-side sessions. Mirrors [com.daymark.companion.storage.BlobStore]'s
 * posture: WAL, no plaintext record columns, secrets stored only as Argon2id/BLAKE2b hashes
 * (the sole exception being the TOTP seed, which any TOTP verifier must hold to compute codes;
 * this is the honestly-weaker server-stored-authenticator path per COMPANION_SECURITY.md §5.2).
 *
 * Status machine for invites: PENDING -> REDEEMING -> CONSUMED, or -> EXPIRED, or -> REPORTED.
 * Redemption uses capped exponential backoff (NOT burn-after-N — that is a denial-of-enrollment
 * vector, COMPANION_THERAPIST.md §5.1): a wrong secret never permanently kills the invite.
 *
 * REPORTED is the one terminal state a *person* can reach on purpose, and it is deliberately not
 * reachable by any automatic rule. See [reportInviteByOwner] for the whole argument; the short form
 * is that a wrong code and an attacker's guess are indistinguishable by construction, so any rule
 * that kills an invite on a failed attempt is a denial-of-service primitive handed to whoever holds
 * the link. Only an explicit human report may destroy an invitation.
 */
class AuthStore(
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

    // ---- Invites -----------------------------------------------------------------

    data class MintedInvite(val inviteId: String, val secret: String, val expiresAt: Long)

    enum class RedeemStatus { OK, WRONG_SECRET, LOCKED, GONE }
    data class RedeemResult(
        val status: RedeemStatus,
        /**
         * The relationship this invite belongs to.
         *
         * Populated on the FAILURE statuses as well as on OK, and that is not an oversight: the
         * owner's audit log is keyed by relRef, so without it a failed pairing guess could not be
         * recorded against anything and would leave no trace at all. It is for the server-side
         * audit append ONLY — the route must never put it in a response body on a failure path,
         * because that would turn a wrong guess into a confirmation that the invite is real.
         */
        val relRef: String? = null,
        val scope: List<String> = emptyList(),
        /** Single-use enrollment ticket (plaintext, returned once) that /totp/enroll must consume. */
        val enrollTicket: String? = null,
        /**
         * True exactly when THIS wrong secret is the one that armed a lockout — the failure that
         * crossed the threshold, not any failure inside an episode and not a request that bounced
         * off a lockout already in force.
         *
         * It exists so the route can write the owner's one LOCKOUT audit row at the moment the
         * lockout begins, which is the only moment the server does any work to reach. Requests that
         * arrive while the invite is already locked answer [RedeemStatus.LOCKED] before the secret
         * is even hashed, so they are free to whoever holds the link; auditing each of them would
         * hand a link-holder unmetered, attacker-paced writes into the owner's audit chain. See the
         * LOCKED branch in TherapistAuthRoutes for the full statement of that trade.
         */
        val lockoutArmed: Boolean = false,
    )

    /** Outcome of an explicit human report. Mirrors [RedeemStatus] so the routes read alike. */
    enum class ReportStatus { OK, WRONG_SECRET, LOCKED, GONE }
    data class ReportResult(
        val status: ReportStatus,
        val relRef: String? = null,
        /** Same meaning as [RedeemResult.lockoutArmed]: redeem and report spend one shared fail
         *  counter, so either surface can be the one whose failure arms the lockout. */
        val lockoutArmed: Boolean = false,
    )

    /** Mint a single-use invite. The plaintext [MintedInvite.secret] is returned once (for the link) and never stored. */
    fun mintInvite(relRef: String, scope: List<String>, ttlSeconds: Long): MintedInvite = synchronized(lock) {
        val inviteId = Secrets.newToken()
        val secret = Secrets.newToken()
        val now = clock()
        val expiry = now + ttlSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO invites(invite_id, rel_ref, scope, secret_argon2, ttl_expiry, status, created_at) VALUES (?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.setString(2, relRef)
            ps.setString(3, scope.joinToString(","))
            ps.setString(4, Secrets.hashSecret(secret))
            ps.setLong(5, expiry)
            ps.setString(6, "PENDING")
            ps.setLong(7, now)
            ps.executeUpdate()
        }
        MintedInvite(inviteId, secret, expiry)
    }

    /**
     * Redeem an invite. Constant-time secret compare; capped exponential backoff on wrong
     * secrets (the invite is NEVER permanently burned by wrong guesses). On success the status
     * moves to REDEEMING so a second redemption of a single-use invite returns GONE.
     */
    fun redeemInvite(inviteId: String, secret: String, lockoutFails: Int, lockoutBaseMs: Long): RedeemResult = synchronized(lock) {
        val now = clock()
        val row = readInviteLocked(inviteId) ?: return RedeemResult(RedeemStatus.GONE)

        if (row.status != "PENDING") return RedeemResult(RedeemStatus.GONE)
        if (now >= row.expiry) {
            setInviteStatus(inviteId, "EXPIRED")
            return RedeemResult(RedeemStatus.GONE)
        }
        if (row.lockedUntil > now) return RedeemResult(RedeemStatus.LOCKED, row.relRef)

        if (Secrets.verifySecret(secret, row.secretArgon2)) {
            setInviteStatus(inviteId, "REDEEMING")
            // Mint a short-lived, single-use enrollment ticket pinning this invite's relRef.
            val ticket = Secrets.newToken()
            mintEnrollTicketLocked(inviteId, row, ticket, expiry = now + ENROLL_TICKET_TTL_MS)
            return RedeemResult(RedeemStatus.OK, row.relRef, row.scope, ticket)
        }
        // Wrong secret: bump fail count, apply capped backoff. Never consume the invite.
        val armed = applyWrongSecretBackoffLocked(inviteId, row, now, lockoutFails, lockoutBaseMs)
        return RedeemResult(RedeemStatus.WRONG_SECRET, row.relRef, lockoutArmed = armed)
    }

    /**
     * Verify an invite secret WITHOUT consuming the invite — the gate for the pairing-relay
     * touches (fetch / respond), which have to prove possession of the link on every request
     * while leaving the invite PENDING for however many protocol runs the pairing takes.
     *
     * Everything about failure is [redeemInvite]'s, not a parallel copy: the same
     * [applyWrongSecretBackoffLocked], the same shared fail counter, the same statuses. That is
     * the point — a relay touch, a redeem and a report all verify the SAME secret, so if any of
     * them kept its own counter an attacker would alternate surfaces and multiply their guess
     * budget by the number of routes. One secret, one counter, however many doors.
     *
     * What it deliberately does NOT do: move the status, mint a ticket, or write anything on
     * success. Proof of possession is a question, and questions leave no marks. (The one write
     * it shares with redeem is lazy expiry — an invite found past its time is marked EXPIRED —
     * which finalises a fact the clock already settled rather than answering the question.)
     *
     * [allowRedeeming] is for exactly one caller: the therapist's status poll after the owner has
     * approved, when the invite is REDEEMING by design and the therapist still has to learn that.
     * The touches that START a run (fetch, respond) keep the PENDING gate, so no new run can be
     * opened or answered once an approval exists — proof of the secret against a REDEEMING invite
     * yields a state word and nothing else.
     */
    fun checkInviteSecret(
        inviteId: String,
        secret: String,
        lockoutFails: Int,
        lockoutBaseMs: Long,
        allowRedeeming: Boolean = false,
    ): RedeemResult = synchronized(lock) {
        val now = clock()
        val row = readInviteLocked(inviteId) ?: return RedeemResult(RedeemStatus.GONE)
        val statusOk = row.status == "PENDING" || (allowRedeeming && row.status == "REDEEMING")
        if (!statusOk) return RedeemResult(RedeemStatus.GONE)
        if (now >= row.expiry) {
            setInviteStatus(inviteId, "EXPIRED")
            return RedeemResult(RedeemStatus.GONE)
        }
        if (row.lockedUntil > now) return RedeemResult(RedeemStatus.LOCKED, row.relRef)
        if (Secrets.verifySecret(secret, row.secretArgon2)) {
            return RedeemResult(RedeemStatus.OK, row.relRef, row.scope)
        }
        val armed = applyWrongSecretBackoffLocked(inviteId, row, now, lockoutFails, lockoutBaseMs)
        return RedeemResult(RedeemStatus.WRONG_SECRET, row.relRef, lockoutArmed = armed)
    }

    data class InviteMeta(val relRef: String, val status: String, val expiry: Long)

    /**
     * The owner-facing view of one invite row: whose it is, where it stands, when it dies.
     * For route code that must check an invite BELONGS to the caller's relationship before
     * acting on it (the pairing relay's open). Carries no secret material and never will.
     */
    fun inviteMetaFor(inviteId: String): InviteMeta? = synchronized(lock) {
        val row = readInviteLocked(inviteId) ?: return null
        InviteMeta(row.relRef, row.status, row.expiry)
    }

    /** The single place a ticket row is written; every minting path goes through it. */
    private fun mintEnrollTicketLocked(inviteId: String, row: InviteRow, ticket: String, expiry: Long) {
        conn.prepareStatement(
            "INSERT INTO enroll_tickets(ticket_hash, invite_id, rel_ref, scope, expiry) VALUES (?,?,?,?,?)",
        ).use { ins ->
            ins.setString(1, Secrets.tokenHash(ticket))
            ins.setString(2, inviteId)
            ins.setString(3, row.relRef)
            ins.setString(4, row.scope.joinToString(","))
            ins.setLong(5, expiry)
            ins.executeUpdate()
        }
    }

    enum class ApproveStatus { OK, GONE }
    data class ApproveResult(val status: ApproveStatus, val relRef: String? = null, val scope: List<String>? = null)

    /**
     * The owner approves a pairing run: the invite moves PENDING -> REDEEMING, and the enrolment
     * ticket THE THERAPIST CHOSE becomes the one ticket this invite will honour. That ticket
     * reached the owner sealed under the pairing key (COMPANION_PAIRING.md §4), so only someone
     * who typed the right code holds it; the owner forwards it here, and the server, as ever,
     * never sees the code — only a 32-byte value it will later compare a hash against.
     *
     * This is how a ticket comes to exist once the PAKE runs BEFORE redeem, and two things differ
     * from [redeemInvite] on purpose:
     *  - No secret is checked and `locked_until` is IGNORED. The owner presents the bearer token,
     *    not the invite secret. A link-holder's wrong guesses lock the secret's door, and must not
     *    be able to lock the owner out of approving the person who typed the right code.
     *  - The ticket lives until the INVITE expires, not ten minutes. The clock now starts at the
     *    owner's approval; the therapist learns of it by polling at most every 45 seconds; and a
     *    lockout raised by a link-holder can keep their status route shut for up to an hour. A
     *    ten-minute ticket would expire under an honest therapist's feet. What bounds exposure is
     *    what already bounds it: the invite's own TTL, [killInviteLocked] on a report, and
     *    [abandonRedeem] when the owner starts over.
     */
    fun approveRedeem(inviteId: String, ticket: String): ApproveResult = synchronized(lock) {
        val now = clock()
        val row = readInviteLocked(inviteId) ?: return ApproveResult(ApproveStatus.GONE)
        if (row.status != "PENDING") return ApproveResult(ApproveStatus.GONE)
        if (now >= row.expiry) {
            setInviteStatus(inviteId, "EXPIRED")
            return ApproveResult(ApproveStatus.GONE)
        }
        setInviteStatus(inviteId, "REDEEMING")
        mintEnrollTicketLocked(inviteId, row, ticket, expiry = row.expiry)
        return ApproveResult(ApproveStatus.OK, row.relRef, row.scope)
    }

    /**
     * The owner takes back an approval nobody finished: REDEEMING -> PENDING, and the invite's
     * unconsumed tickets go with it. Only from REDEEMING — a CONSUMED invite has a credential
     * behind it, and un-enrolling is a different act (revoking that credential), not this one.
     * Returns false when there was nothing to abandon.
     */
    fun abandonRedeem(inviteId: String): Boolean = synchronized(lock) {
        val row = readInviteLocked(inviteId) ?: return false
        if (row.status != "REDEEMING") return false
        conn.prepareStatement("DELETE FROM enroll_tickets WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId); ps.executeUpdate()
        }
        setInviteStatus(inviteId, "PENDING")
        true
    }

    data class InviteSummary(val inviteId: String, val status: String, val createdAt: Long, val expiry: Long, val failCount: Int)

    /**
     * Every invitation of one relationship, newest first, for the owner console's
     * waiting / in progress / finished / dead rendering. Reads only: an invite past its time is
     * reported here as EXPIRED without the row being written, so a listing is never a side
     * effect. `failCount` is how many wrong secrets have been presented against the invite — the
     * one signal the owner has that somebody is working on their link, shown as a count and never
     * as a verdict. Owner-authenticated callers only: to an anonymous caller this would be the
     * invite-status oracle [inviteStatusFor] refuses to be.
     */
    fun invitesFor(relRef: String, limit: Int = 50): List<InviteSummary> = synchronized(lock) {
        val now = clock()
        conn.prepareStatement(
            "SELECT invite_id, status, created_at, ttl_expiry, fail_count FROM invites WHERE rel_ref=? " +
                "ORDER BY created_at DESC, invite_id DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<InviteSummary>()
                while (rs.next()) {
                    val stored = rs.getString(2)
                    val expiry = rs.getLong(4)
                    val status = if (now >= expiry && (stored == "PENDING" || stored == "REDEEMING")) "EXPIRED" else stored
                    out += InviteSummary(rs.getString(1), status, rs.getLong(3), expiry, rs.getInt(5))
                }
                out
            }
        }
    }

    /**
     * The owner says "this wasn't me": drive the invite to the REPORTED terminal state at once.
     *
     * ## Why this is a separate verb, and why nothing automatic may call it
     *
     * An earlier draft of the pairing design said a refused confirmation should kill the invite.
     * That is backwards, and expensively so. With a PAKE, a wrong code and an attacker's guess are
     * **the same event** — being indistinguishable is the entire point of the construction — so
     * "burn on a failed attempt" hands anyone who has seen the invite link a cheap, repeatable veto
     * over the product's core flow: the owner mints, the attacker types one wrong character, the
     * invitation dies, and round again forever. The therapist never gets in and nobody can tell
     * why. It is equally hostile to the honest case, where a therapist mistypes a code read to them
     * down a phone line and loses their invitation for it.
     *
     * So a guess and a report are split, and only the second is a signal:
     *
     *  - Wrong code — could be a typo, could be an attacker, unknowable. Capped backoff, audited,
     *    never burned. That is [redeemInvite], and it has behaved this way since it was written.
     *  - A person says "this wasn't me" / "I didn't expect this" — unambiguous, because a human is
     *    reporting rather than a counter tripping. Burn immediately and require a fresh invite.
     *
     * REPORTED is deliberately distinct from CONSUMED and EXPIRED: those two say the invite did its
     * job or ran out of time, and this one says a person judged it hostile. Collapsing them would
     * throw away the only part of an invite's history the owner would actually want to read back.
     *
     * The invite's enrollment ticket dies with it. If an attacker has already redeemed and is
     * sitting on a valid ticket, the ticket — not the invite row — is what still enrols a
     * credential, so a report that left it alive would be theatre.
     *
     * Authority comes from the caller being the owner, proven by the owner bearer token at the
     * route. No secret is required or accepted here, because the owner is not the party who was
     * handed one.
     */
    fun reportInviteByOwner(inviteId: String): ReportResult = synchronized(lock) {
        val now = clock()
        val row = readInviteLocked(inviteId) ?: return ReportResult(ReportStatus.GONE)
        if (!row.isReportable) return ReportResult(ReportStatus.GONE)
        if (now >= row.expiry) {
            setInviteStatus(inviteId, "EXPIRED")
            return ReportResult(ReportStatus.GONE)
        }
        killInviteLocked(inviteId)
        return ReportResult(ReportStatus.OK, row.relRef)
    }

    /**
     * The invited party says "this wasn't me": the same terminal state as [reportInviteByOwner],
     * gated on presenting the CORRECT invite secret.
     *
     * ## Why requiring the right secret is not the denial of service this exists to avoid
     *
     * The distinction that makes burning safe here is not "a report is more serious than a guess" —
     * an attacker would happily click a serious-looking button. It is that **whoever can pass this
     * gate could already have consumed the invite instead.** Someone holding the correct secret can
     * redeem it, enrol their own authenticator and become the therapist; letting them close it
     * takes nothing away from them that they did not already have, and takes nothing away from the
     * owner that was not already lost. A wrong secret, by contrast, proves nothing at all about who
     * is asking, which is exactly why it must never burn anything.
     *
     * Wrong secrets on this route therefore take the SAME capped backoff, against the same counter,
     * as a wrong secret on redeem. Without that, this route would be a free oracle for guessing the
     * invite secret — unmetered attempts on a surface with no lockout of its own — and closing the
     * burn hole would have opened a guessing hole one route over.
     *
     * ## Why the secret is checked BEFORE the lockout, here and nowhere else
     *
     * Every other door asks "is this invitation locked?" first and answers a locked one without
     * ever looking at the secret. That is right for the doors a lockout exists to shut: they hand
     * something back — the owner's opening message, a state word — and what the lockout buys is
     * that a guesser learns nothing while it is in force.
     *
     * A report hands nothing back. It is a person saying "this wasn't me" about a link they were
     * sent, and the answer is the same flat acknowledgement whatever the truth of it (see the
     * route). So the lockout has nothing to protect here, and putting it in front of the secret
     * check bought exactly one outcome: the invitation somebody was guessing at — the one most
     * likely to be in hostile hands — became the one invitation its real holder could not close.
     *
     * So: verify first, and honour a correct report whether or not a lockout is in force. A WRONG
     * secret arriving while one is in force still writes NOTHING — no counter bump, no extension,
     * no audit row — which is the locked-door rule kept exactly as it is everywhere else
     * (`LockedInviteAuditTest`): a knock on a locked door is not a write, and a lockout must never
     * be extendable by the volume an attacker chooses to send.
     */
    fun reportInvite(inviteId: String, secret: String, lockoutFails: Int, lockoutBaseMs: Long): ReportResult = synchronized(lock) {
        val now = clock()
        val row = readInviteLocked(inviteId) ?: return ReportResult(ReportStatus.GONE)
        if (!row.isReportable) return ReportResult(ReportStatus.GONE)
        if (now >= row.expiry) {
            setInviteStatus(inviteId, "EXPIRED")
            return ReportResult(ReportStatus.GONE)
        }
        if (Secrets.verifySecret(secret, row.secretArgon2)) {
            killInviteLocked(inviteId)
            return ReportResult(ReportStatus.OK, row.relRef)
        }
        // Wrong, and the door is already shut: the knock is not a write.
        if (row.lockedUntil > now) return ReportResult(ReportStatus.LOCKED, row.relRef)
        val armed = applyWrongSecretBackoffLocked(inviteId, row, now, lockoutFails, lockoutBaseMs)
        return ReportResult(ReportStatus.WRONG_SECRET, row.relRef, lockoutArmed = armed)
    }

    /** One invite row, read whole so the several checks that follow share a single snapshot. */
    private data class InviteRow(
        val relRef: String,
        val scope: List<String>,
        val secretArgon2: String,
        val expiry: Long,
        val status: String,
        val failCount: Int,
        val lockedUntil: Long,
    ) {
        /**
         * REDEEMING is reportable as well as PENDING, and that is the case a report is FOR: the
         * therapist rings to say the page told them the link was already used, which means somebody
         * else redeemed it and is mid-enrolment. CONSUMED, EXPIRED and REPORTED are terminal — a
         * consumed invite's remedy is revoking the credential it produced, not re-killing the row.
         */
        val isReportable: Boolean get() = status == "PENDING" || status == "REDEEMING"
    }

    private fun readInviteLocked(inviteId: String): InviteRow? =
        conn.prepareStatement(
            "SELECT rel_ref, scope, secret_argon2, ttl_expiry, status, fail_count, locked_until FROM invites WHERE invite_id=?",
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    InviteRow(
                        relRef = rs.getString(1),
                        scope = rs.getString(2).split(',').filter { it.isNotEmpty() },
                        secretArgon2 = rs.getString(3),
                        expiry = rs.getLong(4),
                        status = rs.getString(5),
                        failCount = rs.getInt(6),
                        lockedUntil = rs.getLong(7),
                    )
                }
            }
        }

    /**
     * Bump the fail count and arm the capped backoff. Shared by redeem and report so that the two
     * surfaces spend one counter between them rather than one each.
     *
     * Serving a lockout clears the debt that caused it — the same reset `recordTotpFailure` does,
     * and for the same reason. Without it `fail_count` only ever climbs, so the FIRST failure after
     * any expired lockout immediately re-armed another one, and the backoff shift grew with it: one
     * mistyped character past the threshold escalated to the 1-hour cap and stayed there. The
     * therapist could not enrol at all, and the only remedy was the owner minting a fresh invite.
     *
     * @return true when this failure ARMED a lockout — crossed the threshold and set a future
     *   `locked_until`. That is a single event per lockout episode by construction: once armed,
     *   every later request answers LOCKED before reaching this function, and once the lockout has
     *   been served the counter resets, so it takes a full threshold of fresh failures to arm the
     *   next one. The routes use this to write exactly one LOCKOUT audit row per episode instead of
     *   one per request that bounces off it.
     */
    private fun applyWrongSecretBackoffLocked(
        inviteId: String,
        row: InviteRow,
        now: Long,
        lockoutFails: Int,
        lockoutBaseMs: Long,
    ): Boolean {
        val priorFails = if (row.lockedUntil in 1..now) 0 else row.failCount
        val newFails = priorFails + 1
        val locked = if (newFails >= lockoutFails) {
            val backoff = lockoutBaseMs shl (newFails - lockoutFails).coerceAtMost(6) // cap the shift
            now + backoff.coerceAtMost(3_600_000L) // cap at 1h
        } else 0L
        conn.prepareStatement("UPDATE invites SET fail_count=?, locked_until=? WHERE invite_id=?").use { up ->
            up.setInt(1, newFails); up.setLong(2, locked); up.setString(3, inviteId); up.executeUpdate()
        }
        return locked > now
    }

    /** Drive an invite to REPORTED and take its outstanding enrollment ticket with it. */
    private fun killInviteLocked(inviteId: String) {
        setInviteStatus(inviteId, "REPORTED")
        conn.prepareStatement("DELETE FROM enroll_tickets WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId); ps.executeUpdate()
        }
    }

    private fun setInviteStatus(inviteId: String, status: String) {
        conn.prepareStatement("UPDATE invites SET status=? WHERE invite_id=?").use { ps ->
            ps.setString(1, status); ps.setString(2, inviteId); ps.executeUpdate()
        }
    }

    /** Test/inspection helper: is the plaintext secret stored anywhere? (Always false by construction.) */
    fun rawSecretColumnFor(inviteId: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT secret_argon2 FROM invites WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * Test/inspection helper: the raw status word of an invite, or null if there is no such row.
     *
     * Deliberately NOT exposed on any unauthenticated route. The state machine is owner-facing
     * information (the owner console is to render waiting / in progress / finished / dead), and
     * handing the same distinction to an anonymous caller would turn a guessed invite id into an
     * oracle for whether an invitation exists and how far along it is.
     */
    fun inviteStatusFor(inviteId: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT status FROM invites WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** Test/inspection helper: how many enrollment tickets an invite still has outstanding. */
    fun enrollTicketCountFor(inviteId: String): Int = synchronized(lock) {
        conn.prepareStatement("SELECT COUNT(*) FROM enroll_tickets WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /**
     * Test/inspection helper: the raw `ticket_hash` column of an invite's live enrollment ticket,
     * or null if it has none. `ticket_hash` is the table's PRIMARY KEY (see the schema above) and
     * is always [Secrets.tokenHash] of whatever ticket was minted — this exists so a test can
     * check that directly, rather than trusting the property because nothing has ever measured it.
     */
    fun rawEnrollTicketHashFor(inviteId: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT ticket_hash FROM enroll_tickets WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    // ---- Durable per-source attempt windows ---------------------------------------

    /**
     * The persistent half of [PersistentAttemptLimiter]. Fixed-window, per-source, and deliberately
     * the same arithmetic as [AttemptLimiter] down to the edge cases — a refused attempt neither
     * increments the count nor extends the window — so the two implementations are interchangeable
     * at a call site and only durability differs. See [AttemptBudget] for which budgets belong here
     * and which are honestly fine in memory.
     *
     * ## The source is stored as a digest, and what that does and does not buy
     *
     * [source] is a client address. This table is on disk and outlives the process, so writing it
     * raw would create a durable record of who connected and when — precisely the record an
     * operator opts OUT of by leaving `DAYMARK_ACCESS_LOG_SOURCE_IP` off, which is the default
     * (COMPANION_SECURITY.md §9). A limiter only ever needs equality, never the address itself, so
     * what is stored is BLAKE2b-256 of it.
     *
     * Stated honestly, because the opposite claim is easy to drift into: this is **not**
     * anonymisation. IPv4 is 32 bits wide, so anyone holding this file can brute-force the
     * preimages in seconds. What it buys is that the column is not readable by eye, not grep-able
     * out of a backup, and not something a support dump quietly leaks — and rows age out with their
     * window, so the retention is minutes rather than forever. If the requirement ever becomes "an
     * attacker with the database learns nothing", this needs a keyed digest whose key lives outside
     * SQLite, and that is a different change than this one.
     */
    fun allowAttempt(scope: String, source: String, maxPerWindow: Int, windowMs: Long): Boolean = synchronized(lock) {
        val now = clock()
        val key = Secrets.tokenHash(source)
        pruneAttemptWindowsLocked(scope, now, windowMs)

        val existing = conn.prepareStatement(
            "SELECT started_at, count FROM attempt_windows WHERE scope=? AND source_key=?",
        ).use { ps ->
            ps.setString(1, scope); ps.setString(2, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getInt(2) else null }
        }
        val startedAt = existing?.first ?: now
        val count = existing?.second ?: 0

        // A source with no row yet is about to be given one, whichever branch below runs. That is
        // the moment the hard row cap is enforced — see [evictForAttemptCapLocked] for why a cap
        // exists at all and why the oldest window is the one that pays for it.
        if (existing == null) evictForAttemptCapLocked(scope)

        if (now - startedAt >= windowMs) {
            writeAttemptWindowLocked(scope, key, now, 1)
            return true
        }
        if (count < maxPerWindow) {
            writeAttemptWindowLocked(scope, key, startedAt, count + 1)
            return true
        }
        // Refused. The row is written back only when it did not exist yet, so a source that is
        // already over budget still gets a window on record to age out; an existing row is left
        // exactly as it was, which is what stops a refused attempt from extending its own lockout.
        if (existing == null) writeAttemptWindowLocked(scope, key, startedAt, count)
        return false
    }

    /**
     * How long until this source's window rolls, so a refusal can carry a `Retry-After` naming a
     * real time instead of the client assuming the server's window size.
     *
     * A source with no window on record is told the full window. That is the conservative
     * direction: the only way to have no row is to have spent nothing, in which case the caller is
     * not being refused by this budget at all and the number is never shown to anyone.
     */
    fun attemptRetryAfterMs(scope: String, source: String, windowMs: Long): Long = synchronized(lock) {
        val startedAt = conn.prepareStatement(
            "SELECT started_at FROM attempt_windows WHERE scope=? AND source_key=?",
        ).use { ps ->
            ps.setString(1, scope); ps.setString(2, Secrets.tokenHash(source))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        } ?: return windowMs
        return (startedAt + windowMs - clock()).coerceAtLeast(1L)
    }

    /** Clears a source's window — called on success, so legitimate use is never penalised. */
    fun resetAttempts(scope: String, source: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM attempt_windows WHERE scope=? AND source_key=?").use { ps ->
            ps.setString(1, scope); ps.setString(2, Secrets.tokenHash(source)); ps.executeUpdate()
        }
        Unit
    }

    private fun writeAttemptWindowLocked(scope: String, key: String, startedAt: Long, count: Int) {
        conn.prepareStatement(
            "INSERT INTO attempt_windows(scope, source_key, started_at, count) VALUES (?,?,?,?) " +
                "ON CONFLICT(scope, source_key) DO UPDATE SET started_at=excluded.started_at, count=excluded.count",
        ).use { ps ->
            ps.setString(1, scope); ps.setString(2, key); ps.setLong(3, startedAt); ps.setInt(4, count)
            ps.executeUpdate()
        }
    }

    /**
     * Drop windows that have aged out, so the table cannot grow without bound under a flood of
     * distinct sources.
     *
     * Amortised rather than checked on every call. The in-memory limiter can ask `windows.size` for
     * free and only walks the map once it is big enough to be worth walking; the equivalent here is
     * a COUNT query, which is not free and would run on every single unauthenticated request — the
     * exact shape of self-inflicted amplification a limiter exists to prevent. One DELETE per
     * [ATTEMPT_PRUNE_EVERY] attempts costs nothing by comparison.
     *
     * An earlier version of this comment claimed what accumulates between sweeps was "bounded by
     * how many distinct sources can reach the server inside one window" — which is no bound at
     * all, because distinct sources are the one thing an attacker mints for free. The actual bound
     * is [ATTEMPT_WINDOWS_MAX_PER_SCOPE], enforced at insert time by [evictForAttemptCapLocked];
     * this sweep's job is only to keep the table small in the HONEST case, so aged-out windows do
     * not sit around until the cap has to care about them.
     */
    private fun pruneAttemptWindowsLocked(scope: String, now: Long, windowMs: Long) {
        if (++attemptsSincePrune < ATTEMPT_PRUNE_EVERY) return
        attemptsSincePrune = 0
        conn.prepareStatement("DELETE FROM attempt_windows WHERE scope=? AND started_at <= ?").use { ps ->
            ps.setString(1, scope); ps.setLong(2, now - windowMs); ps.executeUpdate()
        }
    }

    /** Guarded by [lock] like every other mutation here; see [pruneAttemptWindowsLocked]. */
    private var attemptsSincePrune = 0

    /*
     * Hard ceiling on live rows per scope, enforced at insert time — the guarantee the amortised
     * prune above cannot give.
     *
     * WHY THE PRUNE IS NOT ENOUGH. The prune only ever removes windows that have AGED OUT, so
     * between sweeps the table holds every distinct source seen inside one window — and "distinct
     * source" is the attacker's cheapest thing to vary. A request flood that rotates addresses
     * turns each request into a fresh row, and SQLite's DELETE returns pages to the freelist
     * without ever shrinking the file, so a weekend of rotation converts into PERMANENT growth of
     * auth.db — the same file that holds the invites and the TOTP seeds. A limiter whose
     * bookkeeping is an unbounded write amplifier, in the credential store of all places, is the
     * self-inflicted wound it exists to prevent. The cap turns "how big can auth.db get" from a
     * function of attacker patience into a constant.
     *
     * WHY EVICTING THE OLDEST-STARTED WINDOW IS THE SAFE DIRECTION. Eviction forgets a window
     * early, which relaxes the budget for exactly one source — the one whose window started
     * longest ago, i.e. the source that has been quiet longest (an expired window sorts first of
     * all, and evicting one of those relaxes nothing). The cost is bounded and small: at worst
     * that source gets one fresh window, maxPerWindow attempts, sooner than it should have.
     * Against that, the attacker funding the evictions is paying one INSERT — one HTTP request
     * from one more distinct address — per row evicted. An attacker who commands N addresses
     * always had N * maxPerWindow attempts available by simply spending each address's own
     * budget, so churning the table to buy back an old source's window gains them nothing they
     * did not already hold; and the durable per-invite backoff, which is the counter that
     * actually guards the secret, is untouched by any of this. Evicting the NEWEST rows instead
     * would be the unsafe direction: it would forget the very sources currently spending, i.e.
     * the ones mid-attack.
     */
    private fun evictForAttemptCapLocked(scope: String) {
        // Counting per new-source insert is affordable precisely BECAUSE the cap holds: the
        // (scope, source_key) primary key serves the scan and the invariant keeps it to at most
        // ATTEMPT_WINDOWS_MAX_PER_SCOPE entries, so the cost is a small constant — unlike the
        // per-request COUNT the prune's comment rules out, it cannot grow with attack volume.
        val live = conn.prepareStatement("SELECT COUNT(*) FROM attempt_windows WHERE scope=?").use { ps ->
            ps.setString(1, scope)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (live < ATTEMPT_WINDOWS_MAX_PER_SCOPE) return
        val over = live - ATTEMPT_WINDOWS_MAX_PER_SCOPE + 1
        conn.prepareStatement(
            "DELETE FROM attempt_windows WHERE rowid IN (" +
                "SELECT rowid FROM attempt_windows WHERE scope=? ORDER BY started_at ASC LIMIT ?)",
        ).use { ps ->
            ps.setString(1, scope); ps.setInt(2, over); ps.executeUpdate()
        }
    }

    /** Test/inspection helper: how many live rows one scope holds in the attempt-window table. */
    fun attemptWindowCountFor(scope: String): Int = synchronized(lock) {
        conn.prepareStatement("SELECT COUNT(*) FROM attempt_windows WHERE scope=?").use { ps ->
            ps.setString(1, scope)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    // ---- Staff accounts (#314) ---------------------------------------------------

    /*
     * ONE ACCOUNT PER PERSON, AND RELATIONSHIPS ATTACH TO IT.
     *
     * An account is a sign-in name and its authenticator seed. It reaches a relationship only while
     * that relationship's newest row in `account_attachments` names it, the account is not closed and
     * the relationship has not ended. Every session-cookie route asks [reaches] (or [holds], for the
     * leave route) on every request, so an attachment that moves, an account that closes and a
     * relationship that ends all take effect on the next request, with no session to cut.
     *
     * The account adds no way to read. Reading still needs the relationship's own keys, which live
     * wrapped in the clinician's browser, and its inbox token (docs/COMPANION_ACCESS_CONTROL.md).
     */

    enum class EnrollStatus { OK, NO_TICKET, ALREADY_ENROLLED }
    data class EnrollResult(val status: EnrollStatus, val relRef: String? = null)

    /**
     * Make the account [credentialId] with the TOTP seed [secretB64], and attach the relationship its
     * enrolment [ticket] names: the invitation that creates a relationship also creates the invitee's
     * account when they have none here. The relRef comes from the TICKET, never from the caller; the
     * ticket is consumed and its invite driven to CONSUMED on success.
     *
     * Insert-only: an account that already exists is never overwritten, and a relationship that has
     * EVER been attached refuses a second invitation-made attachment ([attachByInvitation] shares
     * the rule), so a leftover invitation link cannot attach a relationship to a second account.
     * Both refusals are [EnrollStatus.ALREADY_ENROLLED] and leave the ticket where it was.
     */
    fun enrollTotp(ticket: String, credentialId: String, secretB64: String): EnrollResult = synchronized(lock) {
        val spent = liveTicketLocked(ticket) ?: return EnrollResult(EnrollStatus.NO_TICKET)
        if (accountExistsLocked(credentialId) || everAttachedLocked(spent.relRef)) {
            return EnrollResult(EnrollStatus.ALREADY_ENROLLED)
        }
        val now = clock()
        conn.prepareStatement(
            "INSERT INTO accounts(account_id, secret_b64, fail_count, locked_until, last_used_step, created_at) VALUES (?,?,0,0,0,?)",
        ).use { ps ->
            ps.setString(1, credentialId); ps.setString(2, secretB64); ps.setLong(3, now)
            ps.executeUpdate()
        }
        insertAttachmentLocked(spent.relRef, credentialId, ATTACHED_BY_INVITATION)
        deleteEnrollTicket(spent.ticketHash)
        setInviteStatus(spent.inviteId, "CONSUMED")
        EnrollResult(EnrollStatus.OK, spent.relRef)
    }

    /**
     * Attach the relationship [ticket] names to the account [accountId] that already exists: a
     * clinician with an account accepting a second patient's invitation while signed in. Same ticket
     * rules and the same insert-only refusal as [enrollTotp]; no seed is written. A closed account
     * attaches nothing.
     */
    fun attachByInvitation(ticket: String, accountId: String): EnrollResult = synchronized(lock) {
        if (!accountExistsLocked(accountId) || closedLocked(accountId)) return EnrollResult(EnrollStatus.NO_TICKET)
        val spent = liveTicketLocked(ticket) ?: return EnrollResult(EnrollStatus.NO_TICKET)
        if (everAttachedLocked(spent.relRef)) return EnrollResult(EnrollStatus.ALREADY_ENROLLED)
        insertAttachmentLocked(spent.relRef, accountId, ATTACHED_BY_INVITATION)
        deleteEnrollTicket(spent.ticketHash)
        setInviteStatus(spent.inviteId, "CONSUMED")
        EnrollResult(EnrollStatus.OK, spent.relRef)
    }

    private data class LiveTicket(val ticketHash: String, val inviteId: String, val relRef: String)

    /**
     * The enrolment ticket [ticket], when it is unexpired AND the invite it came from is still
     * REDEEMING; else null, with a dead ticket deleted on the way out so it cannot be retried.
     *
     * THE TICKET IS NOT ENOUGH — the invite must still be live. A ticket outlived every way an invite
     * can die until this checked: an attacker who redeemed, an owner who reported, and the invite's
     * own TTL lapsing while the report answered GONE without killing tickets. This is the single
     * place a ticket is spent, so a future way to retire an invite cannot reopen that by forgetting
     * to sweep (InviteBurnRuleTest).
     */
    private fun liveTicketLocked(ticket: String): LiveTicket? {
        val now = clock()
        val ticketHash = Secrets.tokenHash(ticket)
        val (inviteId, relRef, expiry) = conn.prepareStatement(
            "SELECT invite_id, rel_ref, expiry FROM enroll_tickets WHERE ticket_hash=?",
        ).use { ps ->
            ps.setString(1, ticketHash)
            ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) else null }
        } ?: return null
        if (now >= expiry) {
            deleteEnrollTicket(ticketHash)
            return null
        }
        val inviteLive = conn.prepareStatement("SELECT status FROM invites WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> rs.next() && rs.getString(1) == "REDEEMING" }
        }
        if (!inviteLive) {
            deleteEnrollTicket(ticketHash)
            return null
        }
        return LiveTicket(ticketHash, inviteId, relRef)
    }

    private fun deleteEnrollTicket(ticketHash: String) {
        conn.prepareStatement("DELETE FROM enroll_tickets WHERE ticket_hash=?").use { ps ->
            ps.setString(1, ticketHash); ps.executeUpdate()
        }
    }

    /**
     * Make an account with no relationship, for a member of staff joining a practice through its
     * staff invitation (a receptionist has no patient invitation to make one). Insert-only: false
     * when the name is taken, and the existing account is untouched. [step] is the TOTP step the
     * person just proved, recorded as spent.
     */
    fun createAccount(accountId: String, secretB64: String, step: Long): Boolean = synchronized(lock) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO accounts(account_id, secret_b64, fail_count, locked_until, last_used_step, created_at) VALUES (?,?,0,0,?,?)",
        ).use { ps ->
            ps.setString(1, accountId); ps.setString(2, secretB64); ps.setLong(3, step); ps.setLong(4, clock())
            ps.executeUpdate() > 0
        }
    }

    /** Whether the sign-in name [accountId] is taken on this server. For the join route's refusal only. */
    fun accountExists(accountId: String): Boolean = synchronized(lock) { accountExistsLocked(accountId) }

    private fun accountExistsLocked(accountId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM accounts WHERE account_id=?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { it.next() }
        }

    /** An account's authenticator state. [closed] accounts sign in nowhere. */
    data class AccountRecord(val accountId: String, val secretB64: String, val failCount: Int, val lockedUntil: Long, val closed: Boolean)

    fun account(accountId: String): AccountRecord? = synchronized(lock) {
        conn.prepareStatement("SELECT secret_b64, fail_count, locked_until FROM accounts WHERE account_id=?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                AccountRecord(accountId, rs.getString(1), rs.getInt(2), rs.getLong(3), closedLocked(accountId))
            }
        }
    }

    /**
     * Atomically spend a TOTP step, returning false if it was already used.
     *
     * The compare and the write happen in ONE statement under the same lock every other account
     * mutation takes, so two concurrent requests cannot both observe the old `last_used_step` and
     * both succeed (RFC 6238 §5.2). `>` rather than `>=`: step 0 means nothing has been spent.
     */
    fun consumeTotpStep(accountId: String, step: Long): Boolean = synchronized(lock) {
        conn.prepareStatement(
            "UPDATE accounts SET last_used_step=? WHERE account_id=? AND last_used_step < ?",
        ).use { ps ->
            ps.setLong(1, step); ps.setString(2, accountId); ps.setLong(3, step)
            return ps.executeUpdate() > 0
        }
    }

    fun recordTotpSuccess(accountId: String) = synchronized(lock) {
        conn.prepareStatement("UPDATE accounts SET fail_count=0, locked_until=0 WHERE account_id=?").use { ps ->
            ps.setString(1, accountId); ps.executeUpdate()
        }
    }

    /**
     * The store's opinion of now, for route code whose decisions must agree with what the store
     * wrote. The lockout is armed off this clock ([recordTotpFailure]), so the route asking "is it
     * still in force?" has to ask the same clock.
     */
    fun nowMs(): Long = clock()

    /**
     * Bump the fail count and arm the lockout once it crosses [lockoutFails]. Returns the new
     * locked_until.
     *
     * Once a lockout has been served the counter starts again. Without that reset every failure
     * after the first lockout immediately re-armed another one, and the account id is a typed
     * sign-in name, so anyone who knows it could keep its holder locked out for free.
     */
    fun recordTotpFailure(accountId: String, lockoutFails: Int, lockoutMs: Long): Long = synchronized(lock) {
        val now = clock()
        val rec = account(accountId) ?: return 0L
        val priorFails = if (rec.lockedUntil in 1..now) 0 else rec.failCount
        val newFails = priorFails + 1
        val locked = if (newFails >= lockoutFails) now + lockoutMs else 0L
        conn.prepareStatement("UPDATE accounts SET fail_count=?, locked_until=? WHERE account_id=?").use { ps ->
            ps.setInt(1, newFails); ps.setLong(2, locked); ps.setString(3, accountId); ps.executeUpdate()
        }
        locked
    }

    // ---- Attachments -------------------------------------------------------------

    /** How an attachment was made: by an invitation, by the v2 migration, or by a key proof. */
    data class Attachment(val relRef: String, val accountId: String, val attachedAt: Long, val how: String)

    /**
     * The relationship's current attachment: its newest row, or null when no account has ever held
     * it. The rows are insert-only, so "current" is the highest `attach_seq` and nothing else; a row
     * is never moved, rewritten or removed.
     */
    fun attachmentOf(relRef: String): Attachment? = synchronized(lock) { attachmentOfLocked(relRef) }

    private fun attachmentOfLocked(relRef: String): Attachment? =
        conn.prepareStatement(
            "SELECT account_id, attached_at, how FROM account_attachments WHERE rel_ref=? ORDER BY attach_seq DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs -> if (rs.next()) Attachment(relRef, rs.getString(1), rs.getLong(2), rs.getString(3)) else null }
        }

    private fun everAttachedLocked(relRef: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM account_attachments WHERE rel_ref=? LIMIT 1").use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { it.next() }
        }

    private fun insertAttachmentLocked(relRef: String, accountId: String, how: String) {
        conn.prepareStatement(
            "INSERT INTO account_attachments(rel_ref, account_id, attached_at, how) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef); ps.setString(2, accountId); ps.setLong(3, clock()); ps.setString(4, how)
            ps.executeUpdate()
        }
    }

    /**
     * Whether [accountId] holds [relRef]: the relationship's current attachment names it and the
     * account is open. Ended relationships are still held, so the leave route stays idempotent.
     */
    fun holds(accountId: String, relRef: String): Boolean = synchronized(lock) {
        attachmentOfLocked(relRef)?.accountId == accountId && !closedLocked(accountId)
    }

    /**
     * Whether a session of [accountId] may act on [relRef] at all: [holds], and the relationship has
     * not ended. The one question every data-plane route asks, on every request. An unattached
     * relationship and one that never existed answer the same false, so a refusal says nothing about
     * which it was.
     */
    fun reaches(accountId: String, relRef: String): Boolean = synchronized(lock) {
        attachmentOfLocked(relRef)?.accountId == accountId && !closedLocked(accountId) && endingLocked(relRef) == null
    }

    /**
     * The relationships [accountId] reaches, oldest attachment first: the clinician's own list, for
     * their console to find which of this browser's key records to open. Never a practice's list of
     * patients; there is no question here that takes a practice.
     */
    fun relationshipsOf(accountId: String): List<String> = synchronized(lock) {
        if (closedLocked(accountId)) return emptyList()
        conn.prepareStatement(
            "SELECT a.rel_ref FROM account_attachments a WHERE a.account_id=? " +
                "AND a.attach_seq = (SELECT MAX(b.attach_seq) FROM account_attachments b WHERE b.rel_ref = a.rel_ref) " +
                "AND NOT EXISTS (SELECT 1 FROM relationship_endings e WHERE e.rel_ref = a.rel_ref) " +
                "ORDER BY a.attach_seq",
        ).use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /**
     * Attach [relRef] to [accountId] for a session that has a fixture's word for it. Test fixtures
     * only: every route attaches through [enrollTotp], [attachByInvitation] or [attachByKeyProof],
     * and StaffAccountTest greps the main sources to say so.
     */
    internal fun attachForFixture(relRef: String, accountId: String) = synchronized(lock) {
        if (attachmentOfLocked(relRef)?.accountId != accountId) insertAttachmentLocked(relRef, accountId, "fixture")
    }

    data class AttachChallenge(val challengeId: String, val challenge: String, val expiresAt: Long)

    /**
     * A fresh challenge for [accountId] to sign with the clinician key [relRef]'s owner pinned, live
     * for [ttlMs] and good once. Bound to the account and the relationship as well as kept in the
     * signed message, so a challenge asked for one cannot be spent on another. Expired ones are swept
     * here.
     */
    fun issueAttachChallenge(accountId: String, relRef: String, ttlMs: Long): AttachChallenge = synchronized(lock) {
        val now = clock()
        conn.prepareStatement("DELETE FROM attach_challenges WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, now); ps.executeUpdate()
        }
        val challengeId = "ac_" + Secrets.newToken(9)
        val challenge = Secrets.newToken()
        conn.prepareStatement(
            "INSERT INTO attach_challenges(challenge_id, account_id, rel_ref, challenge, expires_at) VALUES (?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, challengeId); ps.setString(2, accountId); ps.setString(3, relRef)
            ps.setString(4, challenge); ps.setLong(5, now + ttlMs)
            ps.executeUpdate()
        }
        AttachChallenge(challengeId, challenge, now + ttlMs)
    }

    /**
     * Spend challenge [challengeId]: its text when it was issued to [accountId] for [relRef] and is
     * unexpired and unspent, else null. Deleted in the same step whatever the signature then says,
     * so no challenge is checked twice and a replay finds nothing.
     */
    fun spendAttachChallenge(challengeId: String, accountId: String, relRef: String): String? = synchronized(lock) {
        val row = conn.prepareStatement(
            "SELECT account_id, rel_ref, challenge, expires_at FROM attach_challenges WHERE challenge_id=?",
        ).use { ps ->
            ps.setString(1, challengeId)
            ps.executeQuery().use { rs ->
                if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4).toString()) else null
            }
        } ?: return null
        conn.prepareStatement("DELETE FROM attach_challenges WHERE challenge_id=?").use { ps ->
            ps.setString(1, challengeId); ps.executeUpdate()
        }
        if (row[0] != accountId || row[1] != relRef || row[3].toLong() <= clock()) return null
        row[2]
    }

    enum class KeyProofAttach { ATTACHED, ALREADY_HERE, NOT_ATTACHABLE }

    /**
     * Attach [relRef] to [accountId] once the route has checked a signature over a spent challenge
     * with the relationship's registered clinician signing key. Inserts one row; the account that
     * held it before reaches it no more from its next request.
     *
     * NOT_ATTACHABLE when the relationship was never attached (no enrolment, so no key to have
     * proved), when it has ended (an ended relationship stays ended; the way back is a fresh
     * invitation), or when [accountId] is closed or does not exist. ALREADY_HERE writes nothing.
     */
    fun attachByKeyProof(relRef: String, accountId: String): KeyProofAttach = synchronized(lock) {
        if (!accountExistsLocked(accountId) || closedLocked(accountId)) return KeyProofAttach.NOT_ATTACHABLE
        val current = attachmentOfLocked(relRef) ?: return KeyProofAttach.NOT_ATTACHABLE
        if (endingLocked(relRef) != null) return KeyProofAttach.NOT_ATTACHABLE
        if (current.accountId == accountId) return KeyProofAttach.ALREADY_HERE
        insertAttachmentLocked(relRef, accountId, ATTACHED_BY_KEY_PROOF)
        KeyProofAttach.ATTACHED
    }

    // ---- Closing a lost account --------------------------------------------------

    /**
     * Close [accountId]: one insert-only row, and every session it holds deleted. Returns true only
     * for the call that closed it, so the caller writes its one audit row once.
     *
     * A closed account signs in nowhere and reaches nothing; its attachments stay as rows, so the
     * relationships it held are reached by nobody until the clinician re-attaches each one with a key
     * proof or a fresh invitation. Nobody re-issues another person's sign-in: there is no route that
     * gives a closed account a new seed, and no route that reopens one.
     */
    fun closeAccount(accountId: String): Boolean = synchronized(lock) {
        if (!accountExistsLocked(accountId)) return false
        val closed = conn.prepareStatement(
            "INSERT OR IGNORE INTO account_closures(account_id, closed_at) VALUES (?,?)",
        ).use { ps ->
            ps.setString(1, accountId); ps.setLong(2, clock())
            ps.executeUpdate() > 0
        }
        conn.prepareStatement("DELETE FROM account_sessions WHERE account_id=?").use { ps ->
            ps.setString(1, accountId); ps.executeUpdate()
        }
        closed
    }

    fun isClosed(accountId: String): Boolean = synchronized(lock) { closedLocked(accountId) }

    private fun closedLocked(accountId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM account_closures WHERE account_id=?").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { it.next() }
        }

    // ---- Staff invitations -------------------------------------------------------

    data class StaffInvite(val orgId: String, val role: String, val issuedBy: String)

    /**
     * Mint a staff invitation: a seat in practice [orgId] with role [role], taken by whoever holds
     * the secret before [ttlMs] runs out. The plaintext secret is returned once and stored only as
     * its BLAKE2b digest, which is also the row's key, so there is no invitation id to guess at and
     * a wrong secret finds nothing at all.
     */
    fun mintStaffInvite(orgId: String, role: String, issuedBy: String, ttlMs: Long): Pair<String, Long> = synchronized(lock) {
        val now = clock()
        conn.prepareStatement("DELETE FROM staff_invites WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, now); ps.executeUpdate()
        }
        val secret = Secrets.newToken()
        conn.prepareStatement(
            "INSERT INTO staff_invites(secret_hash, org_id, role, issued_by, created_at, expires_at) VALUES (?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(secret)); ps.setString(2, orgId); ps.setString(3, role)
            ps.setString(4, issuedBy); ps.setLong(5, now); ps.setLong(6, now + ttlMs)
            ps.executeUpdate()
        }
        secret to now + ttlMs
    }

    /** Spend a staff invitation: what it seats, when it is unexpired and unspent, else null. Good once. */
    fun spendStaffInvite(secret: String): StaffInvite? = synchronized(lock) {
        val hash = Secrets.tokenHash(secret)
        val row = conn.prepareStatement(
            "SELECT org_id, role, issued_by, expires_at FROM staff_invites WHERE secret_hash=?",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> if (rs.next()) StaffInvite(rs.getString(1), rs.getString(2), rs.getString(3)) to rs.getLong(4) else null }
        } ?: return null
        conn.prepareStatement("DELETE FROM staff_invites WHERE secret_hash=?").use { ps ->
            ps.setString(1, hash); ps.executeUpdate()
        }
        if (row.second <= clock()) null else row.first
    }

    /** Whether a staff invitation is live, without spending it: the join route checks before it asks for anything else. */
    fun staffInviteLive(secret: String): Boolean = synchronized(lock) {
        conn.prepareStatement("SELECT expires_at FROM staff_invites WHERE secret_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(secret))
            ps.executeQuery().use { rs -> rs.next() && rs.getLong(1) > clock() }
        }
    }

    // ---- Sessions ----------------------------------------------------------------

    data class NewSession(val sessionId: String, val csrfToken: String, val absoluteExpiry: Long)

    /**
     * Create an opaque session for an account. The raw [NewSession.sessionId] is returned once (the
     * cookie) and stored only hashed. A session names an account, never a relationship: which
     * relationships it reaches is asked fresh on every request ([reaches]).
     */
    fun createSession(accountId: String, idleSeconds: Long, absoluteSeconds: Long): NewSession = synchronized(lock) {
        val sessionId = Secrets.newToken()
        val csrf = Secrets.newToken()
        val now = clock()
        val absolute = now + absoluteSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO account_sessions(session_id_hash, account_id, csrf_token, created_at, last_seen, absolute_expiry) VALUES (?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId))
            ps.setString(2, accountId)
            ps.setString(3, csrf)
            ps.setLong(4, now)
            ps.setLong(5, now)
            ps.setLong(6, absolute)
            ps.executeUpdate()
        }
        NewSession(sessionId, csrf, absolute)
    }

    data class SessionRecord(val accountId: String, val csrfToken: String)

    enum class SessionCheck { OK, EXPIRED, REVOKED, MISSING, BAD_CSRF }
    data class SessionValidation(val check: SessionCheck, val record: SessionRecord? = null)

    /**
     * Validate a session by its raw id (hashed for lookup). Enforces idle + absolute timeouts and
     * refuses a closed account's session as REVOKED; on success touches last_seen. If [requireCsrf]
     * is non-null it must equal the stored anti-CSRF token (constant-time).
     */
    fun validateSession(sessionId: String, idleSeconds: Long, requireCsrf: String? = null): SessionValidation = synchronized(lock) {
        val now = clock()
        val hash = Secrets.tokenHash(sessionId)
        conn.prepareStatement(
            "SELECT account_id, csrf_token, last_seen, absolute_expiry FROM account_sessions WHERE session_id_hash=?",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return SessionValidation(SessionCheck.MISSING)
                val accountId = rs.getString(1)
                val csrf = rs.getString(2)
                val lastSeen = rs.getLong(3)
                val absolute = rs.getLong(4)
                if (closedLocked(accountId)) return SessionValidation(SessionCheck.REVOKED)
                if (now >= absolute || now - lastSeen > idleSeconds * 1000) {
                    return SessionValidation(SessionCheck.EXPIRED)
                }
                if (requireCsrf != null && !Secrets.constantTimeEquals(requireCsrf, csrf)) {
                    return SessionValidation(SessionCheck.BAD_CSRF)
                }
                conn.prepareStatement("UPDATE account_sessions SET last_seen=? WHERE session_id_hash=?").use { up ->
                    up.setLong(1, now); up.setString(2, hash); up.executeUpdate()
                }
                return SessionValidation(SessionCheck.OK, SessionRecord(accountId, csrf))
            }
        }
    }

    // ---- Therapist public keys ---------------------------------------------------

    /**
     * The therapist's two public halves, exactly as their browser published them: the X25519 key
     * the owner seals shares to, and the Ed25519 key the owner verifies signatures with.
     *
     * [registeredAt] is epoch MILLISECONDS, because [clock] is. (The audit log next door counts in
     * seconds; the two have always disagreed, and this follows the store it lives in rather than
     * quietly introducing a third convention.)
     */
    data class TherapistKeys(val boxPubB64: String, val signPubB64: String, val registeredAt: Long)

    enum class KeyRegistration { OK, ALREADY_REGISTERED }

    /**
     * Record the therapist's public keys for a relationship. INSERT-ONLY: a relationship that
     * already has keys keeps the ones it has, and this returns [KeyRegistration.ALREADY_REGISTERED]
     * without touching the stored row.
     *
     * `INSERT OR IGNORE` rather than SELECT-then-INSERT so the refusal is SQLite's, not this
     * function's. The distinction is not stylistic: a read followed by a write is only atomic for
     * as long as every writer happens to take the same in-process [lock], and this store is one
     * connection today by accident of deployment rather than by any guarantee. Under OR IGNORE the
     * primary key does the refusing inside the statement, so a second process, a second connection
     * or a future concurrent caller cannot slip between the check and the write.
     *
     * A zero row count therefore means precisely one thing here — a row for this rel_ref already
     * exists. It cannot mean a NOT NULL violation: every column is NOT NULL and every value comes
     * from a non-null Kotlin parameter, so there is no other constraint left to fire. If a nullable
     * column is ever added to this table, that reasoning stops holding and this needs to become an
     * explicit conflict check.
     */
    fun registerTherapistKeys(relRef: String, boxPubB64: String, signPubB64: String): KeyRegistration = synchronized(lock) {
        val now = clock()
        conn.prepareStatement(
            "INSERT OR IGNORE INTO therapist_keys(rel_ref, box_pub_b64, sign_pub_b64, registered_at) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setString(2, boxPubB64)
            ps.setString(3, signPubB64)
            ps.setLong(4, now)
            return if (ps.executeUpdate() > 0) KeyRegistration.OK else KeyRegistration.ALREADY_REGISTERED
        }
    }

    /** The registered keys for a relationship, or null if the therapist has not published any. */
    fun therapistKeys(relRef: String): TherapistKeys? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT box_pub_b64, sign_pub_b64, registered_at FROM therapist_keys WHERE rel_ref=?",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                TherapistKeys(rs.getString(1), rs.getString(2), rs.getLong(3))
            }
        }
    }

    data class OwnerKeys(val signPubB64: String, val boxPubB64: String, val registeredAt: Long)

    /**
     * Record the owner's public keys for a relationship. INSERT-ONLY, for the reasons set out on
     * [registerTherapistKeys] — the same reasoning applies unchanged, and deliberately so: two
     * tables enforcing one rule differently is how one of them quietly stops enforcing it.
     */
    fun registerOwnerKeys(relRef: String, signPubB64: String, boxPubB64: String): KeyRegistration = synchronized(lock) {
        val now = clock()
        conn.prepareStatement(
            "INSERT OR IGNORE INTO owner_keys(rel_ref, box_pub_b64, sign_pub_b64, registered_at) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setString(2, boxPubB64)
            ps.setString(3, signPubB64)
            ps.setLong(4, now)
            return if (ps.executeUpdate() > 0) KeyRegistration.OK else KeyRegistration.ALREADY_REGISTERED
        }
    }

    /** The owner's registered keys for a relationship, or null if they have not published any. */
    fun ownerKeys(relRef: String): OwnerKeys? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT box_pub_b64, sign_pub_b64, registered_at FROM owner_keys WHERE rel_ref=?",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                OwnerKeys(signPubB64 = rs.getString(2), boxPubB64 = rs.getString(1), registeredAt = rs.getLong(3))
            }
        }
    }

    /** Hard-delete a session (logout / instant revoke). */
    fun revokeSession(sessionId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM account_sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.executeUpdate()
        }
    }

    /**
     * Hard-delete EVERY session held by one account. Returns how many were cut.
     *
     * The counterpart to [revokeSession], which needs the raw session id and so serves only the
     * person holding it. When a practice removes a member, the removal has to land on sessions the
     * removing admin has never seen and could not name, and at once: a removal you have to wait out
     * for up to the eight-hour absolute lifetime is one people stop reaching for.
     *
     * A DELETE rather than a flag, so no later query has to remember to exclude a revoked row.
     *
     * WHAT IT DOES NOT DO. It cuts sessions, never keys: a clinician's copies of what a patient
     * already let them decrypt are on their own machine. And IT DOES NOT CLOSE THE ACCOUNT: its holder
     * can sign in again at once. Closing a lost account is [closeAccount], a separate act with its own
     * audit row. So this is a sign-out, and nothing a person reads may call it a revocation.
     */
    fun revokeSessionsForCredential(accountId: String): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM account_sessions WHERE account_id=?").use { ps ->
            ps.setString(1, accountId)
            return ps.executeUpdate()
        }
    }

    // ---- A clinician ending their own access (issue #91) -------------------------

    data class RelationshipEnding(val relRef: String, val credentialId: String, val endedAt: Long)

    /** Whether this call is the one that ended it, or whether it was already over. */
    enum class EndingWrite { RECORDED, ALREADY_ENDED }

    /**
     * Record that the clinician holding [credentialId] ended relationship [relRef].
     *
     * INSERT-ONLY by primary key, by the same OR IGNORE argument set out on [registerTherapistKeys]:
     * the constraint refuses inside the statement rather than in a check a second connection could
     * slip past, and a zero row count can mean only "already ended" because every column is NOT NULL
     * and every value is a non-null parameter.
     *
     * The return value is not decoration. A second call must do the same thing as the first — the
     * route is idempotent on purpose, because a clinician who clicks twice, or whose browser retries
     * a request whose answer never arrived, must not meet an error for an act that already
     * succeeded. But the AUDIT line must be written once, on the ending itself, not once per call:
     * this is the same rule the lockout path follows, and for the same reason. An audit row per
     * probe is how the one row that matters gets buried.
     *
     * WHAT THIS DOES NOT DO, said here because the function name is broader than the act. It writes
     * one row. It touches no key, no blob, no grant, and nothing at all belonging to the owner —
     * their entries, the material they shared, and their record of having shared it are exactly as
     * they were. It does not delete the clinician's credential either; see the table's header for
     * why closing it and deleting it are opposites rather than degrees of the same thing.
     */
    fun endRelationship(relRef: String, credentialId: String): EndingWrite = synchronized(lock) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO relationship_endings(rel_ref, credential_id, ended_at) VALUES (?,?,?)",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.setString(2, credentialId)
            ps.setLong(3, clock())
            return if (ps.executeUpdate() > 0) EndingWrite.RECORDED else EndingWrite.ALREADY_ENDED
        }
    }

    /**
     * The ending for a relationship, or null while it is live.
     *
     * Read on the sign-in path, so it is one indexed lookup by primary key. Null is the ordinary
     * answer and is not an error: almost every relationship is live.
     */
    fun relationshipEnding(relRef: String): RelationshipEnding? = synchronized(lock) { endingLocked(relRef) }

    private fun endingLocked(relRef: String): RelationshipEnding? =
        conn.prepareStatement(
            "SELECT rel_ref, credential_id, ended_at FROM relationship_endings WHERE rel_ref=?",
        ).use { ps ->
            ps.setString(1, relRef)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                RelationshipEnding(rs.getString(1), rs.getString(2), rs.getLong(3))
            }
        }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        /**
         * auth.db, version by version (#193). [Schema] says what a version is, and how a database
         * written by an earlier release is brought to [Schema.current] before it is served.
         */
        internal val SCHEMA = Schema(
            "auth.db",
            listOf(
                // Version 1: the structure as it stood when versions began to be kept.
                listOf(
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS invites (
                            invite_id     TEXT    NOT NULL PRIMARY KEY,
                            rel_ref       TEXT    NOT NULL,
                            scope         TEXT    NOT NULL,
                            secret_argon2 TEXT    NOT NULL,
                            ttl_expiry    INTEGER NOT NULL,
                            status        TEXT    NOT NULL,
                            fail_count    INTEGER NOT NULL DEFAULT 0,
                            locked_until  INTEGER NOT NULL DEFAULT 0,
                            created_at    INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS totp (
                            credential_id TEXT    NOT NULL PRIMARY KEY,
                            rel_ref       TEXT    NOT NULL,
                            secret_b64    TEXT    NOT NULL,
                            fail_count    INTEGER NOT NULL DEFAULT 0,
                            locked_until  INTEGER NOT NULL DEFAULT 0,
                            created_at    INTEGER NOT NULL,
                            -- Highest TOTP step already spent by this credential. RFC 6238 5.2: a code must
                            -- be accepted at most once. Without this a code stays replayable for the whole
                            -- +/-90s window, and every acceptance mints an independent 8h session that
                            -- outlives the victim logging out.
                            last_used_step INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    ),
                    // A database from before last_used_step existed has a totp table without it, which
                    // CREATE TABLE IF NOT EXISTS leaves alone; the column is added, and reads 0 for the
                    // credentials already there.
                    SchemaChange.Column("totp", "last_used_step", "INTEGER NOT NULL DEFAULT 0"),
                    // One credential per relationship: a second enroll attempt for a relRef that already
                    // has a live credential is rejected (insert-only enroll), so an attacker cannot enroll
                    // a second forged credential bound to the same relRef.
                    SchemaChange.Index("CREATE UNIQUE INDEX IF NOT EXISTS idx_totp_rel_ref ON totp(rel_ref)"),
                    // Short-lived, single-use enrollment tickets minted at invite redemption. TOTP enroll
                    // MUST consume a valid ticket that pins the relRef; this is what closes the
                    // PENDING->REDEEMING->CONSUMED machine and stops unauthenticated enroll takeover.
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS enroll_tickets (
                            ticket_hash TEXT    NOT NULL PRIMARY KEY,
                            invite_id   TEXT    NOT NULL,
                            rel_ref     TEXT    NOT NULL,
                            scope       TEXT    NOT NULL,
                            expiry      INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    // Per-source attempt windows for the surfaces whose security claim is stated in
                    // attempts. See AttemptBudget for which budgets land here and which stay in memory;
                    // see `allowAttempt` for why the source is stored as a digest rather than an address.
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS attempt_windows (
                            scope      TEXT    NOT NULL,
                            source_key TEXT    NOT NULL,
                            started_at INTEGER NOT NULL,
                            count      INTEGER NOT NULL,
                            PRIMARY KEY (scope, source_key)
                        )
                        """.trimIndent(),
                    ),
                    /*
                     * The therapist's PUBLIC keys, on their way to the owner.
                     *
                     * This is the one link the relationship never had. The owner's console already seals
                     * every share to a therapist's X25519 key and already refuses to seal to a key it has
                     * not pinned, and the therapist's browser already generates and wraps the keypair —
                     * but there was no path by which the public halves could travel from one to the other,
                     * so the pin had nothing to be taken against and the seal had nothing to aim at.
                     * These four columns are that path.
                     *
                     * `rel_ref` is the PRIMARY KEY, and that is the entire enforcement of insert-only.
                     * The alternative — a SELECT before the INSERT, in the route or here — is a rule that
                     * lives in a line of code somebody can later delete, reorder, or forget on a second
                     * write path; this one lives in the schema, so every future caller inherits it whether
                     * or not they know it exists. It matters more here than in most places because the
                     * failure mode of a silent overwrite is not a lost row: it is the owner's next journal
                     * share sealed to whatever key was written last, which is exactly the substitution the
                     * pinning was built to catch.
                     *
                     * Note what is NOT hashed. Every other secret in this file but the TOTP seed (see the
                     * header) and the per-session CSRF token (handed back to the session holder) is stored
                     * as an Argon2id or BLAKE2b digest, because the server has no business being able to
                     * read it back. These are public keys — the point of storing them is to hand them back
                     * verbatim — so they sit here in the clear, and that is correct rather than an
                     * oversight. They are also not sensitive to this server in the way the rest of this
                     * table set is: knowing a therapist's public key lets you seal something TO them, never
                     * open anything OF theirs.
                     *
                     * And the server does not vouch for them. It took delivery of two strings from
                     * whoever held a valid session for this relationship and it will hand the same two
                     * strings back; it cannot tell the therapist's real key from a substituted one, and it
                     * is not trying to. The check that catches a substitution is the owner reading the
                     * fingerprint words back to their therapist on another channel before pinning. See the
                     * route file for the full statement of that division of labour.
                     */
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS therapist_keys (
                            rel_ref       TEXT    NOT NULL PRIMARY KEY,
                            box_pub_b64   TEXT    NOT NULL,
                            sign_pub_b64  TEXT    NOT NULL,
                            registered_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    /*
                     * The OWNER's public keys, on their way to the therapist.
                     *
                     * The mirror of `therapist_keys`, and it was missing for the same reason that one was:
                     * each side had a use for the other's public halves and no path to carry them. The
                     * consequence was visible on the sign-in form, which asked a clinician to paste the
                     * owner's signing and encryption keys by hand on every visit — two of the nine fields
                     * that made that screen unusable.
                     *
                     * Same rules as its counterpart. `rel_ref` is the PRIMARY KEY, so insert-only is the
                     * schema's job rather than a check a later edit can drop; a silent overwrite here would
                     * repoint the key a clinician verifies shares against, which is exactly the substitution
                     * the pinning exists to catch. Stored in the clear because these are public keys and
                     * handing them back verbatim is the entire point.
                     *
                     * The server does not vouch for these either. It relays them, and what catches a
                     * substituted key is the clinician comparing the fingerprint against what the owner
                     * reads aloud — the same out-of-band step, pointing the other way.
                     */

                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS owner_keys (
                            rel_ref       TEXT    NOT NULL PRIMARY KEY,
                            box_pub_b64   TEXT    NOT NULL,
                            sign_pub_b64  TEXT    NOT NULL,
                            registered_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    /*
                     * THE CLINICIAN PUT THIS RELATIONSHIP DOWN (issue #91).
                     *
                     * One row per ended relationship, and the row is the whole mechanism. It does two jobs
                     * that look separate and are not:
                     *
                     *   1. It CLOSES THE RELATIONSHIP. Every session-cookie route asks this table on every
                     *      request ([reaches]), so a clinician who left reaches it from nowhere — not from a
                     *      second browser, not from a saved copy of their key record, not with the
                     *      authenticator still on their phone — while their account carries on for their
                     *      other relationships (version 2, #314). That is what makes leaving an off switch
                     *      rather than a per-browser act.
                     *   2. It TELLS THE OWNER, in the only two places the fact can actually help them: a
                     *      line in this relationship's access log, and a refusal the next time they try to
                     *      share here. The owner is never messaged; see COMPANION_THERAPIST.md.
                     *
                     * WHY IT IS A ROW HERE RATHER THAN A DELETE OR AN UPDATE ON `totp`. The credential table
                     * is insert-only by design — its whole safety property is that a row, once written, is
                     * never removed or rewritten, so no bug and no stolen session can substitute a
                     * credential for a relationship that already has one. A "disabled" column on `totp`
                     * would be an UPDATE path into exactly that table, and a DELETE would be worse: the
                     * unique index on `rel_ref` is what stops a second enrolment, and removing the row would
                     * hand an attacker who holds the invitation link a way to enrol a fresh credential
                     * against a relationship somebody has already left. So the closure is a SEPARATE
                     * insert-only table, read on the way in, and `totp` is never touched by any of this.
                     *
                     * `rel_ref` IS THE PRIMARY KEY, which is the entire enforcement of insert-only and of
                     * idempotence at once: a second leave writes nothing, reports that it wrote nothing, and
                     * the audit line is therefore appended once — on the ending, not once per probe.
                     *
                     * KEYED ON THE RELATIONSHIP RATHER THAN THE CREDENTIAL, and the two are the same thing
                     * here: `idx_totp_rel_ref` makes a credential per-relationship, so closing one closes
                     * exactly one relationship and reaches no other work the clinician holds. Keying on the
                     * relationship is what makes that a property of the schema rather than a coincidence a
                     * later change could break. It also means the ending survives any future re-enrolment
                     * path: a relationship that was ended stays ended, and the way back is a fresh
                     * invitation — which is a fresh relationship — exactly as the clinician was told.
                     *
                     * `credential_id` is the account that ended it, stored so a later reader asking "whose
                     * was this" need not join through attachments. It is a
                     * therapist-typed username, not a secret, and nothing here is a key.
                     */
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS relationship_endings (
                            rel_ref       TEXT    NOT NULL PRIMARY KEY,
                            credential_id TEXT    NOT NULL,
                            ended_at      INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS sessions (
                            session_id_hash TEXT    NOT NULL PRIMARY KEY,
                            credential_id   TEXT    NOT NULL,
                            rel_ref         TEXT    NOT NULL,
                            csrf_token      TEXT    NOT NULL,
                            created_at      INTEGER NOT NULL,
                            last_seen       INTEGER NOT NULL,
                            absolute_expiry INTEGER NOT NULL,
                            revoked         INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    ),
                ),
                /*
                 * Version 2: staff accounts (#314). One account per person, with relationships
                 * attached to it, replaces one TOTP credential per relationship.
                 *
                 * NOTHING IS RELEASED YET, so the migration is the simplest one that keeps every
                 * credential working: each `totp` row becomes its own account under the same name and
                 * seed, with its one relationship attached ("migrated"). A clinician with thirty such
                 * credentials then has thirty accounts, signs in with any one of them, and attaches
                 * the other relationships to it by key proof (StaffAccountRoutes). No merge logic.
                 *
                 * `totp` and `sessions` stay as version 1 left them, because no version deletes a row
                 * (Schema). Nothing reads either after this version: a session made before it simply
                 * stops being recognised, which signs the clinician out once.
                 */
                listOf(
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS accounts (
                            account_id     TEXT    NOT NULL PRIMARY KEY,
                            secret_b64     TEXT    NOT NULL,
                            fail_count     INTEGER NOT NULL DEFAULT 0,
                            locked_until   INTEGER NOT NULL DEFAULT 0,
                            last_used_step INTEGER NOT NULL DEFAULT 0,
                            created_at     INTEGER NOT NULL
                        )
                        """.trimIndent(),
                        seed = "INSERT INTO accounts(account_id, secret_b64, fail_count, locked_until, last_used_step, created_at) " +
                            "SELECT credential_id, secret_b64, fail_count, locked_until, last_used_step, created_at FROM totp",
                    ),
                    /*
                     * Which account holds which relationship. INSERT-ONLY: the current holder of a
                     * relationship is its row with the highest `attach_seq`, and a row is never
                     * updated or deleted. A relationship that has any row at all refuses an
                     * invitation-made attachment, which is the job `idx_totp_rel_ref` did, so a
                     * leftover invitation link can never attach it to a second account. The only
                     * way a newer row is written for a relationship already held is a key proof
                     * made with the clinician key its owner pinned.
                     */
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS account_attachments (
                            attach_seq  INTEGER PRIMARY KEY,
                            rel_ref     TEXT    NOT NULL,
                            account_id  TEXT    NOT NULL,
                            attached_at INTEGER NOT NULL,
                            how         TEXT    NOT NULL
                        )
                        """.trimIndent(),
                        seed = "INSERT INTO account_attachments(rel_ref, account_id, attached_at, how) " +
                            "SELECT rel_ref, credential_id, created_at, '$ATTACHED_BY_MIGRATION' FROM totp ORDER BY created_at, credential_id",
                    ),
                    SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_attachments_rel ON account_attachments(rel_ref, attach_seq)"),
                    SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_attachments_account ON account_attachments(account_id)"),
                    // A closed account: insert-only by primary key, never reopened.
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS account_closures (
                            account_id TEXT    NOT NULL PRIMARY KEY,
                            closed_at  INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS account_sessions (
                            session_id_hash TEXT    NOT NULL PRIMARY KEY,
                            account_id      TEXT    NOT NULL,
                            csrf_token      TEXT    NOT NULL,
                            created_at      INTEGER NOT NULL,
                            last_seen       INTEGER NOT NULL,
                            absolute_expiry INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_account_sessions_account ON account_sessions(account_id)"),
                    // Challenges a key-proof attachment signs, each good once, for one account and one relationship.
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS attach_challenges (
                            challenge_id TEXT    NOT NULL PRIMARY KEY,
                            account_id   TEXT    NOT NULL,
                            rel_ref      TEXT    NOT NULL,
                            challenge    TEXT    NOT NULL,
                            expires_at   INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                    // A practice's staff invitations, keyed by the digest of their secret; spent once.
                    SchemaChange.Table(
                        """
                        CREATE TABLE IF NOT EXISTS staff_invites (
                            secret_hash TEXT    NOT NULL PRIMARY KEY,
                            org_id      TEXT    NOT NULL,
                            role        TEXT    NOT NULL,
                            issued_by   TEXT    NOT NULL,
                            created_at  INTEGER NOT NULL,
                            expires_at  INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        /** The words in `account_attachments.how`: what made each attachment. */
        const val ATTACHED_BY_INVITATION = "invitation"
        const val ATTACHED_BY_MIGRATION = "migrated"
        const val ATTACHED_BY_KEY_PROOF = "key_proof"

        /** Enrollment tickets are short-lived — a therapist enrolls TOTP immediately after redeeming. */
        const val ENROLL_TICKET_TTL_MS = 10 * 60 * 1000L

        /** How many attempts pass between sweeps of the attempt-window table. */
        private const val ATTEMPT_PRUNE_EVERY = 256

        /**
         * The most live rows one scope may hold in `attempt_windows` — the number behind
         * [evictForAttemptCapLocked], sized the same way `PAIR_MAX_PER_WINDOW` is: against what
         * honest use can possibly look like, then with headroom that costs nothing.
         *
         * An honest source occupies exactly ONE row per window regardless of how many attempts it
         * spends, so reaching this cap requires 4096 DISTINCT client addresses touching the pairing
         * surface inside one five-minute window. This server fronts one household or one practice;
         * its honest pairing traffic is a therapist following a link they were sent, which is a
         * single source per invitation. `PAIR_MAX_PER_WINDOW`'s comment reasons that twelve
         * attempts is "nobody's honest afternoon" — by the same reasoning, four thousand
         * simultaneous distinct sources is nobody's honest anything, and a clinic's worth of NAT'd
         * therapists is a handful of rows. The gap between "a handful" and 4096 is deliberate
         * slack: it means eviction, with its documented budget-relaxing side effect, can only ever
         * fire while a rotation flood is actually in progress.
         *
         * The other side of the sizing is what the cap costs when full: a row is one scope word,
         * one 64-hex source digest and two integers — order of 100 bytes — so the table's
         * permanent worst case is a few hundred kilobytes per scope. Bounded, and small against
         * the database it shares a file with, which is the point: the number needs only to be
         * simultaneously far above honest use and far below "meaningful growth of auth.db", and
         * the whole range between satisfies both.
         *
         * `internal` so the tests assert against the production number instead of restating it.
         */
        internal const val ATTEMPT_WINDOWS_MAX_PER_SCOPE = 4096
    }
}
