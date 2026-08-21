package com.daymark.companion.auth

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

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
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${root.resolve("auth.db")}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute(
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
            )
            st.execute(
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
            )
            // Databases created before last_used_step existed will not gain it from CREATE TABLE IF
            // NOT EXISTS. SQLite has no ADD COLUMN IF NOT EXISTS and errors on a duplicate column,
            // so the failure is swallowed deliberately: this is the additive-column idiom, not a
            // swallowed bug.
            runCatching {
                st.execute("ALTER TABLE totp ADD COLUMN last_used_step INTEGER NOT NULL DEFAULT 0")
            }
            // One credential per relationship: a second enroll attempt for a relRef that already
            // has a live credential is rejected (insert-only enroll), so an attacker cannot enroll
            // a second forged credential bound to the same relRef.
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_totp_rel_ref ON totp(rel_ref)")
            // Short-lived, single-use enrollment tickets minted at invite redemption. TOTP enroll
            // MUST consume a valid ticket that pins the relRef; this is what closes the
            // PENDING->REDEEMING->CONSUMED machine and stops unauthenticated enroll takeover.
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS enroll_tickets (
                    ticket_hash TEXT    NOT NULL PRIMARY KEY,
                    invite_id   TEXT    NOT NULL,
                    rel_ref     TEXT    NOT NULL,
                    scope       TEXT    NOT NULL,
                    expiry      INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // Per-source attempt windows for the surfaces whose security claim is stated in
            // attempts. See AttemptBudget for which budgets land here and which stay in memory;
            // see `allowAttempt` for why the source is stored as a digest rather than an address.
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS attempt_windows (
                    scope      TEXT    NOT NULL,
                    source_key TEXT    NOT NULL,
                    started_at INTEGER NOT NULL,
                    count      INTEGER NOT NULL,
                    PRIMARY KEY (scope, source_key)
                )
                """.trimIndent(),
            )
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
             * Note what is NOT hashed. Every other secret in this file is stored as an Argon2id or
             * BLAKE2b digest because the server has no business being able to read it back. These
             * are public keys — the point of storing them is to hand them back verbatim — so they
             * sit here in the clear, and that is correct rather than an oversight. They are also
             * not sensitive to this server in the way the rest of this table set is: knowing a
             * therapist's public key lets you seal something TO them, never open anything OF
             * theirs.
             *
             * And the server does not vouch for them. It took delivery of two strings from
             * whoever held a valid session for this relationship and it will hand the same two
             * strings back; it cannot tell the therapist's real key from a substituted one, and it
             * is not trying to. The check that catches a substitution is the owner reading the
             * fingerprint words back to their therapist on another channel before pinning. See the
             * route file for the full statement of that division of labour.
             */
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS therapist_keys (
                    rel_ref       TEXT    NOT NULL PRIMARY KEY,
                    box_pub_b64   TEXT    NOT NULL,
                    sign_pub_b64  TEXT    NOT NULL,
                    registered_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
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
            )
        }
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
    )

    /** Outcome of an explicit human report. Mirrors [RedeemStatus] so the routes read alike. */
    enum class ReportStatus { OK, WRONG_SECRET, LOCKED, GONE }
    data class ReportResult(val status: ReportStatus, val relRef: String? = null)

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
            conn.prepareStatement(
                "INSERT INTO enroll_tickets(ticket_hash, invite_id, rel_ref, scope, expiry) VALUES (?,?,?,?,?)",
            ).use { ins ->
                ins.setString(1, Secrets.tokenHash(ticket))
                ins.setString(2, inviteId)
                ins.setString(3, row.relRef)
                ins.setString(4, row.scope.joinToString(","))
                ins.setLong(5, now + ENROLL_TICKET_TTL_MS)
                ins.executeUpdate()
            }
            return RedeemResult(RedeemStatus.OK, row.relRef, row.scope, ticket)
        }
        // Wrong secret: bump fail count, apply capped backoff. Never consume the invite.
        applyWrongSecretBackoffLocked(inviteId, row, now, lockoutFails, lockoutBaseMs)
        return RedeemResult(RedeemStatus.WRONG_SECRET, row.relRef)
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
     */
    fun reportInvite(inviteId: String, secret: String, lockoutFails: Int, lockoutBaseMs: Long): ReportResult = synchronized(lock) {
        val now = clock()
        val row = readInviteLocked(inviteId) ?: return ReportResult(ReportStatus.GONE)
        if (!row.isReportable) return ReportResult(ReportStatus.GONE)
        if (now >= row.expiry) {
            setInviteStatus(inviteId, "EXPIRED")
            return ReportResult(ReportStatus.GONE)
        }
        if (row.lockedUntil > now) return ReportResult(ReportStatus.LOCKED, row.relRef)

        if (Secrets.verifySecret(secret, row.secretArgon2)) {
            killInviteLocked(inviteId)
            return ReportResult(ReportStatus.OK, row.relRef)
        }
        applyWrongSecretBackoffLocked(inviteId, row, now, lockoutFails, lockoutBaseMs)
        return ReportResult(ReportStatus.WRONG_SECRET, row.relRef)
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
     */
    private fun applyWrongSecretBackoffLocked(
        inviteId: String,
        row: InviteRow,
        now: Long,
        lockoutFails: Int,
        lockoutBaseMs: Long,
    ) {
        val priorFails = if (row.lockedUntil in 1..now) 0 else row.failCount
        val newFails = priorFails + 1
        val locked = if (newFails >= lockoutFails) {
            val backoff = lockoutBaseMs shl (newFails - lockoutFails).coerceAtMost(6) // cap the shift
            now + backoff.coerceAtMost(3_600_000L) // cap at 1h
        } else 0L
        conn.prepareStatement("UPDATE invites SET fail_count=?, locked_until=? WHERE invite_id=?").use { up ->
            up.setInt(1, newFails); up.setLong(2, locked); up.setString(3, inviteId); up.executeUpdate()
        }
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
     * [ATTEMPT_PRUNE_EVERY] attempts costs nothing by comparison, and what accumulates in between
     * is bounded by how many distinct sources can reach the server inside one window.
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

    // ---- TOTP --------------------------------------------------------------------

    enum class EnrollStatus { OK, NO_TICKET, ALREADY_ENROLLED }
    data class EnrollResult(val status: EnrollStatus, val relRef: String? = null)

    /**
     * Enrol a TOTP credential, gated on a valid single-use enrollment [ticket] minted at invite
     * redemption. The relRef is derived from the TICKET (not trusted from the caller); the ticket
     * is consumed and its invite driven to CONSUMED on success. Insert-only: if a credential
     * already exists for the credentialId OR the ticket's relRef, the enroll is REJECTED
     * (no silent INSERT OR REPLACE that could overwrite a live credential). Fail-closed.
     *
     * @return [EnrollResult] with the derived relRef on success, else the failure reason.
     */
    fun enrollTotp(ticket: String, credentialId: String, secretB64: String): EnrollResult = synchronized(lock) {
        val now = clock()
        // 1. Consume the ticket (single-use, unexpired). Derive relRef + invite from it.
        val ticketHash = Secrets.tokenHash(ticket)
        val (inviteId, relRef) = conn.prepareStatement(
            "SELECT invite_id, rel_ref, expiry FROM enroll_tickets WHERE ticket_hash=?",
        ).use { ps ->
            ps.setString(1, ticketHash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return EnrollResult(EnrollStatus.NO_TICKET)
                val inv = rs.getString(1)
                val rel = rs.getString(2)
                val expiry = rs.getLong(3)
                if (now >= expiry) {
                    // Expired ticket: delete and fail closed.
                    deleteEnrollTicket(ticketHash)
                    return EnrollResult(EnrollStatus.NO_TICKET)
                }
                inv to rel
            }
        }
        /*
         * 1b. THE TICKET IS NOT ENOUGH — the invite it came from must still be live.
         *
         * Found by the adversarial review of the report/burn change, and it is the reason the report
         * route was not the control it claimed to be. A ticket carries its own 10-minute expiry and
         * NOTHING here used to consult the invite, so a ticket outlived every way an invite can die:
         *
         *   attacker redeems (invite -> REDEEMING, ticket minted) -> owner reports it -> the report
         *   answers GONE and the owner believes the invitation is dead -> the attacker enrols anyway,
         *   for up to ten more minutes, and becomes the therapist.
         *
         * `killInviteLocked` does delete tickets, so the reported path was covered. The path that was
         * NOT is the one where the invite's own TTL had already lapsed when the report arrived: both
         * report functions flip the row to EXPIRED and return GONE *without* killing tickets, which
         * is precisely the window an attacker mid-enrolment is sitting in.
         *
         * Checking here rather than patching each kill path is deliberate: this is the single place
         * a ticket is spent, so a future third way to retire an invite cannot reopen the hole by
         * forgetting to sweep. Fail closed, and consume the ticket on the way out so a dead invite's
         * ticket cannot be retried.
         */
        val inviteLive = conn.prepareStatement("SELECT status FROM invites WHERE invite_id=?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> rs.next() && rs.getString(1) == "REDEEMING" }
        }
        if (!inviteLive) {
            deleteEnrollTicket(ticketHash)
            return EnrollResult(EnrollStatus.NO_TICKET)
        }
        // 2. Insert-only: reject if a credential already exists for this credentialId or relRef.
        val exists = conn.prepareStatement(
            "SELECT 1 FROM totp WHERE credential_id=? OR rel_ref=? LIMIT 1",
        ).use { ps ->
            ps.setString(1, credentialId); ps.setString(2, relRef)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (exists) return EnrollResult(EnrollStatus.ALREADY_ENROLLED)
        // 3. Insert the credential, consume the ticket, drive the invite to CONSUMED — atomically.
        conn.prepareStatement(
            "INSERT INTO totp(credential_id, rel_ref, secret_b64, fail_count, locked_until, created_at) VALUES (?,?,?,0,0,?)",
        ).use { ps ->
            ps.setString(1, credentialId); ps.setString(2, relRef); ps.setString(3, secretB64); ps.setLong(4, now)
            ps.executeUpdate()
        }
        deleteEnrollTicket(ticketHash)
        setInviteStatus(inviteId, "CONSUMED")
        return EnrollResult(EnrollStatus.OK, relRef)
    }

    private fun deleteEnrollTicket(ticketHash: String) {
        conn.prepareStatement("DELETE FROM enroll_tickets WHERE ticket_hash=?").use { ps ->
            ps.setString(1, ticketHash); ps.executeUpdate()
        }
    }

    data class TotpRecord(val relRef: String, val secretB64: String, val failCount: Int, val lockedUntil: Long)

    fun getTotp(credentialId: String): TotpRecord? = synchronized(lock) {
        conn.prepareStatement("SELECT rel_ref, secret_b64, fail_count, locked_until FROM totp WHERE credential_id=?").use { ps ->
            ps.setString(1, credentialId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                TotpRecord(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4))
            }
        }
    }

    /**
     * Atomically spend a TOTP step, returning false if it was already used.
     *
     * The compare and the write happen in ONE statement under the same lock every other totp
     * mutation takes. Doing it as read-then-write would leave a window in which two concurrent
     * requests both observe the old `last_used_step` and both succeed — which is precisely the
     * replay this is meant to stop, just narrower.
     *
     * `>` rather than `>=` is deliberate: the step 0 default means a freshly enrolled credential
     * has spent nothing, and steps only ever increase.
     */
    fun consumeTotpStep(credentialId: String, step: Long): Boolean = synchronized(lock) {
        conn.prepareStatement(
            "UPDATE totp SET last_used_step=? WHERE credential_id=? AND last_used_step < ?",
        ).use { ps ->
            ps.setLong(1, step); ps.setString(2, credentialId); ps.setLong(3, step)
            return ps.executeUpdate() > 0
        }
    }

    fun recordTotpSuccess(credentialId: String) = synchronized(lock) {
        conn.prepareStatement("UPDATE totp SET fail_count=0, locked_until=0 WHERE credential_id=?").use { ps ->
            ps.setString(1, credentialId); ps.executeUpdate()
        }
    }

    /** Bump the fail count and apply capped backoff lockout. Returns the new locked_until. */
    fun recordTotpFailure(credentialId: String, lockoutFails: Int, lockoutMs: Long): Long = synchronized(lock) {
        val now = clock()
        val rec = getTotp(credentialId) ?: return 0L
        // Once a lockout has expired, the counter starts again. Without this reset the count only
        // ever climbs, so every failure after the first lockout immediately re-armed another one —
        // a single bad code per window locked the credential out permanently, and `credentialId`
        // is a therapist-typed username, so anyone who knows it could keep them locked out for
        // free. Serving a lockout must clear the debt that caused it.
        val priorFails = if (rec.lockedUntil in 1..now) 0 else rec.failCount
        val newFails = priorFails + 1
        val locked = if (newFails >= lockoutFails) now + lockoutMs else 0L
        conn.prepareStatement("UPDATE totp SET fail_count=?, locked_until=? WHERE credential_id=?").use { ps ->
            ps.setInt(1, newFails); ps.setLong(2, locked); ps.setString(3, credentialId); ps.executeUpdate()
        }
        locked
    }

    // ---- Sessions ----------------------------------------------------------------

    data class NewSession(val sessionId: String, val csrfToken: String, val absoluteExpiry: Long)

    /** Create an opaque session. The raw [NewSession.sessionId] is returned once (cookie) and stored only hashed. */
    fun createSession(credentialId: String, relRef: String, idleSeconds: Long, absoluteSeconds: Long): NewSession = synchronized(lock) {
        val sessionId = Secrets.newToken()
        val csrf = Secrets.newToken()
        val now = clock()
        val absolute = now + absoluteSeconds * 1000
        conn.prepareStatement(
            "INSERT INTO sessions(session_id_hash, credential_id, rel_ref, csrf_token, created_at, last_seen, absolute_expiry, revoked) VALUES (?,?,?,?,?,?,?,0)",
        ).use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId))
            ps.setString(2, credentialId)
            ps.setString(3, relRef)
            ps.setString(4, csrf)
            ps.setLong(5, now)
            ps.setLong(6, now)
            ps.setLong(7, absolute)
            ps.executeUpdate()
        }
        NewSession(sessionId, csrf, absolute)
    }

    data class SessionRecord(val credentialId: String, val relRef: String, val csrfToken: String)

    enum class SessionCheck { OK, EXPIRED, REVOKED, MISSING, BAD_CSRF }
    data class SessionValidation(val check: SessionCheck, val record: SessionRecord? = null)

    /**
     * Validate a session by its raw id (hashed for lookup). Enforces idle + absolute timeouts;
     * on success touches last_seen. If [requireCsrf] is non-null it must equal the stored
     * anti-CSRF token (constant-time).
     */
    fun validateSession(sessionId: String, idleSeconds: Long, requireCsrf: String? = null): SessionValidation = synchronized(lock) {
        val now = clock()
        val hash = Secrets.tokenHash(sessionId)
        conn.prepareStatement(
            "SELECT credential_id, rel_ref, csrf_token, last_seen, absolute_expiry, revoked FROM sessions WHERE session_id_hash=?",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return SessionValidation(SessionCheck.MISSING)
                val credentialId = rs.getString(1)
                val relRef = rs.getString(2)
                val csrf = rs.getString(3)
                val lastSeen = rs.getLong(4)
                val absolute = rs.getLong(5)
                val revoked = rs.getInt(6) != 0
                if (revoked) return SessionValidation(SessionCheck.REVOKED)
                if (now >= absolute || now - lastSeen > idleSeconds * 1000) {
                    return SessionValidation(SessionCheck.EXPIRED)
                }
                if (requireCsrf != null && !Secrets.constantTimeEquals(requireCsrf, csrf)) {
                    return SessionValidation(SessionCheck.BAD_CSRF)
                }
                // Touch idle timer.
                conn.prepareStatement("UPDATE sessions SET last_seen=? WHERE session_id_hash=?").use { up ->
                    up.setLong(1, now); up.setString(2, hash); up.executeUpdate()
                }
                return SessionValidation(SessionCheck.OK, SessionRecord(credentialId, relRef, csrf))
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

    /** Hard-delete a session (logout / instant revoke). */
    fun revokeSession(sessionId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM sessions WHERE session_id_hash=?").use { ps ->
            ps.setString(1, Secrets.tokenHash(sessionId)); ps.executeUpdate()
        }
    }

    /**
     * Hard-delete EVERY session held by one credential. Returns how many were cut.
     *
     * The counterpart to [revokeSession], which needs the raw session id and is therefore only
     * usable by the person holding it — fine for logging yourself out, useless for the case this
     * exists for. When a practice removes a member, the removal has to land on sessions the
     * removing admin has never seen and could not name.
     *
     * It is a DELETE rather than a flag for the reason [validateSession] would otherwise make
     * awkward: a `revoked` column already exists there and is honoured, but a revoked row is a row
     * every future query has to remember to exclude, and the cost of forgetting once is a session
     * that outlives the decision to end it. Deleting removes the question.
     *
     * WHY IMMEDIACY IS THE WHOLE POINT. Without this, "removed from the practice" would mean
     * "removed at some point in the next eight hours, depending when they last clicked" — the
     * absolute session lifetime — and every minute of that is a person the practice believes it has
     * cut off who is still signed in. The spec is unambiguous that server-side cutoff is immediate,
     * and the annoyance budget insists the safe direction never be the expensive one, which has to
     * include *waiting*. A revocation you have to wait out is one people stop reaching for.
     *
     * WHAT IT DOES NOT DO, and the list is longer than it looks. It cuts sessions, never keys: a
     * clinician's own copies of whatever a patient already let them decrypt are on their machine
     * and beyond the reach of any statement in this file, and cryptographic cutoff is the patient's
     * device rotating a key and re-wrapping it, which this server has never held the material to
     * do. Less obviously, and more likely to be misread: IT DOES NOT END THE CREDENTIAL. The row in
     * `totp` is untouched, so the holder of that authenticator can sign in again immediately and be
     * issued a fresh session by the ordinary verify path. Ending the credential is not something
     * this server offers at all today, and a practice would not be the party to do it if it did —
     * the credential was enrolled against a patient's relationship, on the patient's invitation.
     * So this is a *sign-out*, and calling it a revocation anywhere a person can read would be a
     * promise the function does not keep. The caller in the org control plane says the same thing
     * at more length, because that is where somebody will look for it.
     *
     * The one threat it genuinely answers, stated so its value is not talked down either: a session
     * taken from a member — a stolen cookie, a machine left open — is not accompanied by the
     * authenticator, so cutting it ends that access and there is no way back in without the code.
     */
    fun revokeSessionsForCredential(credentialId: String): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM sessions WHERE credential_id=?").use { ps ->
            ps.setString(1, credentialId)
            return ps.executeUpdate()
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    companion object {
        /** Enrollment tickets are short-lived — a therapist enrolls TOTP immediately after redeeming. */
        const val ENROLL_TICKET_TTL_MS = 10 * 60 * 1000L

        /** How many attempts pass between sweeps of the attempt-window table. */
        private const val ATTEMPT_PRUNE_EVERY = 256
    }
}
