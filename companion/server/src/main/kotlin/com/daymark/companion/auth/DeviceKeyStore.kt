package com.daymark.companion.auth

import com.daymark.companion.storage.SchemaChange
import java.sql.Connection

/**
 * The owner's one id, the phones registered to it, and the codes that pair them (#186, #189): what
 * version 2 of `owner-account.db` adds, so it is there in every shape that syncs.
 *
 * INSERT-ONLY BY PRIMARY KEY, every table but the nonces'. A device key is one row in `device_keys`,
 * written when the owner console confirms it and never changed; revoking it is a row beside it in
 * `device_key_revocations`, never a flag on it. A pairing code is taken by the first row for its id in
 * `pairing_redemptions`, which also holds the key waiting for the console's confirmation. A code and a
 * waiting key lapse by their timestamps, so nothing is updated when they do. `seen_nonces` is the one
 * table rows leave: a nonce is kept until a request carrying it would be too old to take anyway, and
 * no longer. It answers "has this request been made before", never "is this key revoked".
 *
 * NO VERDICT IS CACHED. [registeredKey] reads the key and its revocation from the database on every
 * call, so a revocation holds from the next request on.
 *
 * It works on `owner-account.db`'s connection, under its lock: [com.daymark.companion.mail.OwnerAccountStore]
 * opens the file, brings it to its version, and makes re-issuing the token and revoking every device
 * of the owner one transaction.
 */
class DeviceKeyStore internal constructor(
    private val conn: Connection,
    private val lock: Any,
    private val clock: () -> Long,
) {

    /**
     * This server's owner (#186): a random id minted into the single owner row the first time the
     * database is opened, and never changed. Every check authorises on it, never on the token's digest,
     * so an owner account (#324) can take this same id and no device row moves.
     */
    val ownerId: String = synchronized(lock) { loadOrMintOwner() }

    /** The store's clock, in milliseconds: the one a request's time is judged against. */
    fun now(): Long = clock()

    /** A registered key and, when it has one, its revocation. */
    class RegisteredKey(val keyId: String, val ownerId: String, val publicKey: ByteArray, val pairedAt: Long, val revokedAt: Long?)

    /** A key a redeemed code is holding for the console's confirmation, until [confirmBy]. */
    class PendingKey(val keyId: String, val publicKey: ByteArray, val confirmBy: Long)

    /** One line of the device list: when it was paired, its key, and when it was revoked, if it was. No name. */
    data class Device(val keyId: String, val publicKeyB64: String, val pairedAt: Long, val revokedAt: Long?)

    /** A code as minted: the code itself goes to the console once and is never stored. */
    class MintedCode(val code: String, val codeId: String, val expiresAt: Long)

    /** Where one of the owner's codes stands, as the console reads it. A code that has lapsed has none. */
    sealed interface CodeState {
        data class Waiting(val expiresAt: Long) : CodeState
        data class Redeemed(val keyId: String, val publicKeyB64: String, val confirmBy: Long) : CodeState
        data class Registered(val keyId: String, val pairedAt: Long) : CodeState
    }

    sealed interface Redemption {
        data class Pending(val keyId: String, val confirmBy: Long) : Redemption
        data object Refused : Redemption
    }

    sealed interface Confirmation {
        /** [fresh] when this confirmation wrote the row; false when an earlier one already had. */
        data class Registered(val keyId: String, val pairedAt: Long, val fresh: Boolean) : Confirmation
        data object Refused : Confirmation
    }

    /** The key [keyId] names, registered, with its revocation if it has one; null when none is registered. */
    fun registeredKey(keyId: String): RegisteredKey? = synchronized(lock) { registeredKeyLocked(keyId) }

    /**
     * The key a redeemed code of this owner is holding under [keyId], while the console may still
     * confirm it; null once it is registered, and once its confirmation window has passed.
     */
    fun pendingKey(keyId: String): PendingKey? = synchronized(lock) {
        val redemption = redemptionWhere("key_id", keyId) ?: return null
        if (keyIsKnownLocked(keyId)) return null
        val code = codeRow(redemption.codeId) ?: return null
        if (code.ownerId != ownerId) return null
        val confirmBy = redemption.redeemedAt + CONFIRM_WINDOW_MS
        if (clock() >= confirmBy) return null
        PendingKey(keyId, decodeKey(redemption.publicKeyB64), confirmBy)
    }

    /**
     * Record that [keyId] has used [nonce], to be remembered until [keepUntil]; false when it already
     * had, which makes the request a replay. Nonces whose time has passed are forgotten first, in the
     * same transaction: a request carrying one is refused by its time alone.
     */
    fun rememberNonce(keyId: String, nonce: String, keepUntil: Long): Boolean = synchronized(lock) {
        conn.inTransaction {
            conn.prepareStatement("DELETE FROM seen_nonces WHERE keep_until < ?").use { ps ->
                ps.setLong(1, clock())
                ps.executeUpdate()
            }
            conn.prepareStatement("INSERT OR IGNORE INTO seen_nonces(key_id, nonce, keep_until) VALUES (?,?,?)").use { ps ->
                ps.setString(1, keyId)
                ps.setString(2, nonce)
                ps.setLong(3, keepUntil)
                ps.executeUpdate() == 1
            }
        }
    }

    /** A fresh code for [ownerId], good for [CODE_TTL_MS]. Only its id is stored. */
    fun mintCode(ownerId: String): MintedCode = synchronized(lock) {
        val now = clock()
        repeat(MINT_DRAWS) {
            val code = PairingCode.generate()
            val codeId = DeviceSignature.codeIdOf(code)
            val inserted = conn.prepareStatement(
                "INSERT OR IGNORE INTO pairing_codes(code_id, owner_id, created_at, expires_at) VALUES (?,?,?,?)",
            ).use { ps ->
                ps.setString(1, codeId)
                ps.setString(2, ownerId)
                ps.setLong(3, now)
                ps.setLong(4, now + CODE_TTL_MS)
                ps.executeUpdate() == 1
            }
            // A code equal to one minted before is drawn again: its id is taken, and a code is used once.
            if (inserted) return MintedCode(code, codeId, now + CODE_TTL_MS)
        }
        error("no unused pairing code in $MINT_DRAWS draws")
    }

    /** Where [ownerId]'s code [codeId] stands; null for a code that is not theirs, not known, or lapsed. */
    fun codeState(ownerId: String, codeId: String): CodeState? = synchronized(lock) {
        val code = codeRow(codeId) ?: return null
        if (code.ownerId != ownerId) return null
        val now = clock()
        val redemption = redemptionWhere("code_id", codeId)
            ?: return if (now < code.expiresAt) CodeState.Waiting(code.expiresAt) else null
        val registered = registeredKeyLocked(redemption.keyId)
        if (registered != null) {
            return if (registered.revokedAt == null) CodeState.Registered(registered.keyId, registered.pairedAt) else null
        }
        val confirmBy = redemption.redeemedAt + CONFIRM_WINDOW_MS
        if (now < confirmBy) CodeState.Redeemed(redemption.keyId, redemption.publicKeyB64, confirmBy) else null
    }

    /**
     * Take the code [codeId] names for [publicKey], which the caller has shown it holds. The key waits
     * for the console's confirmation and authenticates nothing until then.
     *
     * One refusal for everything that is not a live code this key may take: a code never minted, one
     * that has lapsed, one another key took, and a key that was ever registered or has redeemed a code
     * before. The same key taking the same code again is answered as the first time, so a phone whose
     * answer was lost can ask again.
     */
    fun redeem(codeId: String, publicKey: ByteArray): Redemption = synchronized(lock) {
        val keyId = DeviceSignature.keyIdOf(publicKey)
        val now = clock()
        val code = codeRow(codeId) ?: return Redemption.Refused
        if (code.ownerId != ownerId || now >= code.expiresAt) return Redemption.Refused
        if (keyIsKnownLocked(keyId)) return Redemption.Refused
        val taken = conn.prepareStatement(
            "INSERT OR IGNORE INTO pairing_redemptions(code_id, key_id, public_key, redeemed_at) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, codeId)
            ps.setString(2, keyId)
            ps.setString(3, DeviceSignature.b64url(publicKey))
            ps.setLong(4, now)
            ps.executeUpdate() == 1
        }
        if (taken) return Redemption.Pending(keyId, now + CONFIRM_WINDOW_MS)
        val earlier = redemptionWhere("code_id", codeId)
        if (earlier != null && earlier.keyId == keyId) Redemption.Pending(keyId, earlier.redeemedAt + CONFIRM_WINDOW_MS) else Redemption.Refused
    }

    /**
     * The owner console's confirmation that [keyId], which redeemed [ownerId]'s code [codeId], is the
     * phone whose words the person compared: the only thing that writes a `device_keys` row. A second
     * confirmation of the same key is answered as the first and writes nothing.
     */
    fun confirm(ownerId: String, codeId: String, keyId: String): Confirmation = synchronized(lock) {
        val code = codeRow(codeId) ?: return Confirmation.Refused
        if (code.ownerId != ownerId) return Confirmation.Refused
        val redemption = redemptionWhere("code_id", codeId) ?: return Confirmation.Refused
        if (redemption.keyId != keyId) return Confirmation.Refused
        registeredKeyLocked(keyId)?.let { registered ->
            return if (registered.revokedAt == null) {
                Confirmation.Registered(keyId, registered.pairedAt, fresh = false)
            } else {
                Confirmation.Refused
            }
        }
        val now = clock()
        if (now >= redemption.redeemedAt + CONFIRM_WINDOW_MS) return Confirmation.Refused
        conn.prepareStatement("INSERT INTO device_keys(key_id, owner_id, public_key, created_at) VALUES (?,?,?,?)").use { ps ->
            ps.setString(1, keyId)
            ps.setString(2, ownerId)
            ps.setString(3, redemption.publicKeyB64)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        Confirmation.Registered(keyId, now, fresh = true)
    }

    /** Every device registered to [ownerId], oldest first, revoked ones included with the date they were. */
    fun devices(ownerId: String): List<Device> = synchronized(lock) {
        conn.prepareStatement(
            "SELECT d.key_id, d.public_key, d.created_at, r.revoked_at FROM device_keys d " +
                "LEFT JOIN device_key_revocations r ON r.key_id = d.key_id WHERE d.owner_id=? ORDER BY d.created_at, d.rowid",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val revokedAt = rs.getLong(4).takeUnless { rs.wasNull() }
                        add(Device(rs.getString(1), rs.getString(2), rs.getLong(3), revokedAt))
                    }
                }
            }
        }
    }

    /**
     * Revoke [ownerId]'s device [keyId] from the next request on: true when this call revoked it,
     * false when it already was, null when the owner has no such device.
     */
    fun revoke(ownerId: String, keyId: String): Boolean? = synchronized(lock) {
        val registered = registeredKeyLocked(keyId) ?: return null
        if (registered.ownerId != ownerId) return null
        insertRevocationLocked(keyId, clock())
    }

    /**
     * Revoke every device of [ownerId] that is not revoked yet, returning their ids. The caller holds
     * the lock and the transaction the re-issued token is written in (#186): a new token disconnects
     * every paired phone.
     */
    internal fun revokeAllLocked(ownerId: String): List<String> {
        val live = conn.prepareStatement(
            "SELECT d.key_id FROM device_keys d WHERE d.owner_id=? AND NOT EXISTS " +
                "(SELECT 1 FROM device_key_revocations r WHERE r.key_id = d.key_id) ORDER BY d.created_at, d.rowid",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        val now = clock()
        return live.filter { insertRevocationLocked(it, now) }
    }

    // ---- Rows ----------------------------------------------------------------------------------

    private class CodeRow(val ownerId: String, val expiresAt: Long)
    private class RedemptionRow(val codeId: String, val keyId: String, val publicKeyB64: String, val redeemedAt: Long)

    private fun loadOrMintOwner(): String {
        conn.prepareStatement("SELECT owner_id FROM owner ORDER BY created_at, rowid LIMIT 1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) return rs.getString(1) }
        }
        val id = Secrets.newToken(16)
        conn.prepareStatement("INSERT INTO owner(owner_id, created_at) VALUES (?,?)").use { ps ->
            ps.setString(1, id)
            ps.setLong(2, clock())
            ps.executeUpdate()
        }
        return id
    }

    private fun registeredKeyLocked(keyId: String): RegisteredKey? =
        conn.prepareStatement(
            "SELECT d.owner_id, d.public_key, d.created_at, r.revoked_at FROM device_keys d " +
                "LEFT JOIN device_key_revocations r ON r.key_id = d.key_id WHERE d.key_id=?",
        ).use { ps ->
            ps.setString(1, keyId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val revokedAt = rs.getLong(4).takeUnless { rs.wasNull() }
                RegisteredKey(keyId, rs.getString(1), decodeKey(rs.getString(2)), rs.getLong(3), revokedAt)
            }
        }

    /** Whether [keyId] was ever registered, or revoked: such a key never waits for a confirmation again. */
    private fun keyIsKnownLocked(keyId: String): Boolean =
        conn.prepareStatement(
            "SELECT 1 FROM device_keys WHERE key_id=? UNION ALL SELECT 1 FROM device_key_revocations WHERE key_id=?",
        ).use { ps ->
            ps.setString(1, keyId)
            ps.setString(2, keyId)
            ps.executeQuery().use { it.next() }
        }

    private fun insertRevocationLocked(keyId: String, now: Long): Boolean =
        conn.prepareStatement("INSERT OR IGNORE INTO device_key_revocations(key_id, revoked_at) VALUES (?,?)").use { ps ->
            ps.setString(1, keyId)
            ps.setLong(2, now)
            ps.executeUpdate() == 1
        }

    private fun codeRow(codeId: String): CodeRow? =
        conn.prepareStatement("SELECT owner_id, expires_at FROM pairing_codes WHERE code_id=?").use { ps ->
            ps.setString(1, codeId)
            ps.executeQuery().use { rs -> if (rs.next()) CodeRow(rs.getString(1), rs.getLong(2)) else null }
        }

    /** The redemption whose [column] (`code_id` or `key_id`, both unique) is [value]. */
    private fun redemptionWhere(column: String, value: String): RedemptionRow? {
        require(column == "code_id" || column == "key_id")
        return conn.prepareStatement(
            "SELECT code_id, key_id, public_key, redeemed_at FROM pairing_redemptions WHERE $column=?",
        ).use { ps ->
            ps.setString(1, value)
            ps.executeQuery().use { rs ->
                if (rs.next()) RedemptionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null
            }
        }
    }

    private fun decodeKey(b64: String): ByteArray =
        requireNotNull(DeviceSignature.decodeCanonical(b64, DeviceSignature.PUBLIC_KEY_BYTES)) { "a stored key is 32 bytes" }

    companion object {
        /** How long a minted code can be redeemed: about two minutes (#189). */
        const val CODE_TTL_MS = 120_000L

        /**
         * How long, after a phone redeems a code, the console may confirm its key: long enough to compare
         * the words on two screens and press once. After it, the key authenticated nothing and never will.
         */
        const val CONFIRM_WINDOW_MS = 120_000L

        /** Draws before [mintCode] gives up; two codes out of 31^9 meeting is what a second draw is for. */
        private const val MINT_DRAWS = 8

        /**
         * What version 2 of `owner-account.db` adds (#186, #189). Every table is written by primary key
         * and only `seen_nonces` ever loses a row; see this class.
         */
        internal val TABLES: List<SchemaChange> = listOf(
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS owner (
                    owner_id   TEXT    NOT NULL PRIMARY KEY,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS device_keys (
                    key_id     TEXT    NOT NULL PRIMARY KEY,
                    owner_id   TEXT    NOT NULL,
                    public_key TEXT    NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS device_key_revocations (
                    key_id     TEXT    NOT NULL PRIMARY KEY,
                    revoked_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS pairing_codes (
                    code_id    TEXT    NOT NULL PRIMARY KEY,
                    owner_id   TEXT    NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS pairing_redemptions (
                    code_id     TEXT    NOT NULL PRIMARY KEY,
                    key_id      TEXT    NOT NULL UNIQUE,
                    public_key  TEXT    NOT NULL,
                    redeemed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            SchemaChange.Table(
                """
                CREATE TABLE IF NOT EXISTS seen_nonces (
                    key_id     TEXT    NOT NULL,
                    nonce      TEXT    NOT NULL,
                    keep_until INTEGER NOT NULL,
                    PRIMARY KEY (key_id, nonce)
                )
                """.trimIndent(),
            ),
            SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_seen_nonces_keep_until ON seen_nonces(keep_until)"),
            SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_device_keys_owner ON device_keys(owner_id)"),
        )
    }
}

/**
 * Run [block] as one transaction on this connection, committing when it returns and rolling back when
 * it throws. The caller holds the lock the connection is used under.
 */
internal fun <T> Connection.inTransaction(block: () -> T): T {
    val wasAuto = autoCommit
    autoCommit = false
    try {
        val result = block()
        commit()
        return result
    } catch (e: Throwable) {
        runCatching { rollback() }
        throw e
    } finally {
        autoCommit = wasAuto
    }
}
