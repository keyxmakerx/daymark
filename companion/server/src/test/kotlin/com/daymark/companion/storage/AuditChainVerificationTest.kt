package com.daymark.companion.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * AuditStore.verifyChain, exercised against the store's own database file.
 *
 * The tampering tests below edit rows through a SECOND JDBC connection rather than through any
 * store API, because the store has no API that can do it — append is insert-only and everything
 * else is a SELECT. That is the point: the adversary this check is for is whoever can reach the
 * SQLite file directly (a hostile operator, a bad restore, a corrupted disk), so the fixture has
 * to be that adversary, not a polite caller.
 *
 * Two of these tests assert that a DAMAGED history verifies without complaint — the truncated
 * tail and the pruned head — and both are deliberate. They pin the documented limit of a
 * server-computed chain (docs/COMPANION_SECURITY.md §9, R12: withholding is not detectable,
 * only tampering with what remains) so that a future edit cannot quietly promote this check into
 * a completeness claim it cannot keep. If either of those tests ever fails, the check has started
 * lying in the flattering direction, which is the worse direction.
 */
class AuditChainVerificationTest {

    private fun tmpDir() = Files.createTempDirectory("audit-chain-verify-test").toString()

    /**
     * A direct line to the same file the store has open. WAL journal mode allows the second
     * connection; each edit here is a single autocommitted statement, so the store's own
     * connection sees it on its next read.
     */
    private fun editDb(dir: String, sql: String, vararg params: Any) {
        DriverManager.getConnection("jdbc:sqlite:" + Path.of(dir).resolve("audit.db")).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p ->
                    when (p) {
                        is String -> ps.setString(i + 1, p)
                        is Long -> ps.setLong(i + 1, p)
                        is Int -> ps.setLong(i + 1, p.toLong())
                        else -> error("unsupported param type")
                    }
                }
                ps.executeUpdate()
            }
        }
    }

    /** Five entries with the shapes the real routes append: bare, objectRef'd, and meta'd. */
    private fun seed(store: AuditStore, relRef: String): List<AuditEvent> = listOf(
        store.append(relRef, AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS, meta = mapOf("credentialId" to "cred-1")),
        store.append(relRef, AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0"),
        store.append(relRef, AuditActor.OWNER, AuditAction.SHARE_REVOKE, objectRef = "lin"),
        // A meta value carrying every escaped character, so the recomputation is proven to hash
        // the STORED encoding rather than a re-encoding of a parse — the exact mistake
        // canonicalMeta's own comment warns a verifier against.
        store.append(relRef, AuditActor.THERAPIST, AuditAction.SHARE_DENIED, meta = mapOf("credentialId" to "a,b=c%25")),
        store.append(relRef, AuditActor.THERAPIST, AuditAction.SESSION_EXPIRED),
    )

    @Test
    fun `an untouched chain reports no break, and the same head twice`() {
        val store = AuditStore(tmpDir(), retentionSeconds = 0)
        val appended = seed(store, "relA")

        val first = store.verifyChain("relA")
        assertNull(first.firstBreakSeq, "an untouched chain must report no internal break")
        assertEquals(5L, first.entryCount)
        assertEquals(1L, first.oldestSeq)
        assertEquals(5L, first.headSeq)
        // The head the check reports is the newest entry's hash exactly as append computed it —
        // the value a person writes down. Not a recomputation of it: the same string.
        assertEquals(appended.last().entryHash, first.headHash)

        // Checked twice, identical both times, INCLUDING the head. A check that moved the head by
        // being run would defeat the write-it-down-and-compare use the head exists for; equality
        // of the whole result also proves the check appended nothing (seq 6 would change it).
        assertEquals(first, store.verifyChain("relA"))
    }

    @Test
    fun `verifying is read-only - the log holds exactly what it held before`() {
        val store = AuditStore(tmpDir(), retentionSeconds = 0)
        seed(store, "relA")
        store.verifyChain("relA")
        store.verifyChain("relA")
        // Still five entries, still ending at seq 5: the two checks wrote nothing.
        val listed = store.list("relA", limit = 50)
        assertEquals(5, listed.size)
        assertEquals(5L, listed.first().seq)
    }

    @Test
    fun `editing one row's payload in the database reports a break at that seq`() {
        val dir = tmpDir()
        val store = AuditStore(dir, retentionSeconds = 0)
        seed(store, "relA")

        // The realistic tamper: rewrite what happened, leave the stored hash alone. Done straight
        // against the SQLite file, which is the access a hostile operator actually has.
        editDb(dir, "UPDATE audit_events SET object_ref='lin:9' WHERE rel_ref=? AND seq=3", "relA")

        val v = store.verifyChain("relA")
        assertEquals(3L, v.firstBreakSeq, "the break must be located AT the edited entry")
        // The extent and head still describe what is served today — over damaged history.
        assertEquals(5L, v.entryCount)
        assertEquals(5L, v.headSeq)
        assertNotNull(v.headHash)
    }

    @Test
    fun `editing the stored meta string reports a break at that seq`() {
        // The other free-form column, edited at the raw encoding the hash actually covers.
        val dir = tmpDir()
        val store = AuditStore(dir, retentionSeconds = 0)
        seed(store, "relA")
        editDb(dir, "UPDATE audit_events SET meta='credentialId=mallory' WHERE rel_ref=? AND seq=1", "relA")
        assertEquals(1L, store.verifyChain("relA").firstBreakSeq)
    }

    @Test
    fun `tampering the first entry is caught against the genesis anchor`() {
        // Entry 1 has no stored predecessor; its hash chains off the fixed genesis constant. A
        // verifier that skipped the first entry "because there is nothing before it" would leave
        // the start of every chain freely rewritable — so the anchor is asserted to hold.
        val dir = tmpDir()
        val store = AuditStore(dir, retentionSeconds = 0)
        seed(store, "relA")
        editDb(dir, "UPDATE audit_events SET actor='owner' WHERE rel_ref=? AND seq=1", "relA")
        assertEquals(1L, store.verifyChain("relA").firstBreakSeq)
    }

    @Test
    fun `truncating the tail still reports no break - the documented limit, asserted on purpose`() {
        /*
         * THIS TEST PINS A WEAKNESS, NOT A FEATURE, and it must keep passing. Deleting the newest
         * entries leaves a shorter chain that is internally perfect — the surviving head was a
         * real head once, and everything beneath it still agrees. A server-computed chain cannot
         * detect its own truncation, only tampering with what remains; that is the honest
         * retraction in docs/COMPANION_SECURITY.md §9 (R12), and every user-facing surface of
         * this check is required to say so next to any result it renders. What catches a
         * truncation is the head hash compared against an anchor OUTSIDE the server — a person's
         * note, or the phone anchor planned in docs/PLAN_2026-08-COMPANION-NEXT.md 3.9.7. If this
         * test ever fails, verifyChain has started claiming to detect something it structurally
         * cannot, which would be the check lying in the flattering direction.
         */
        val dir = tmpDir()
        val store = AuditStore(dir, retentionSeconds = 0)
        val appended = seed(store, "relA")
        editDb(dir, "DELETE FROM audit_events WHERE rel_ref=? AND seq > 3", "relA")

        val v = store.verifyChain("relA")
        assertNull(v.firstBreakSeq, "truncation is invisible to an internal-consistency check, by construction")
        assertEquals(3L, v.entryCount)
        assertEquals(3L, v.headSeq)
        // And the head it reports is the old entry 3's hash — a value that WAS the served head
        // once, which is exactly why only an external anchor can tell today's from a replayed one.
        assertEquals(appended[2].entryHash, v.headHash)
    }

    @Test
    fun `a chain whose oldest entries were pruned by retention still reports no break`() {
        // Retention pruning is the one honest way entries leave this store, and it deletes
        // oldest-first, taking the pruned hashes with it. The oldest survivor then has nothing to
        // be recomputed against and must be taken as the walk's anchor — a verifier that insisted
        // on the genesis for it would report every aged chain broken.
        var now = 1_000_000L
        val store = AuditStore(tmpDir(), retentionSeconds = 100L, clock = { now })
        store.append("relA", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        store.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0")
        now += 200 // both existing entries age past the window; the next append prunes them
        val survivor = store.append("relA", AuditActor.THERAPIST, AuditAction.SESSION_EXPIRED)

        val v = store.verifyChain("relA")
        assertNull(v.firstBreakSeq)
        assertEquals(1L, v.entryCount)
        assertEquals(3L, v.oldestSeq, "seq keeps counting across pruning; the walk starts above 1")
        assertEquals(survivor.entryHash, v.headHash)
    }

    @Test
    fun `deleting a middle row reports a break at the entry after the hole`() {
        // A hole mid-run is not how pruning behaves and not something the insert-only API can
        // produce; it is direct interference with the file. The entry after the hole is the first
        // one the chain cannot account for, so that is where the break is named.
        val dir = tmpDir()
        val store = AuditStore(dir, retentionSeconds = 0)
        seed(store, "relA")
        editDb(dir, "DELETE FROM audit_events WHERE rel_ref=? AND seq = 3", "relA")
        assertEquals(4L, store.verifyChain("relA").firstBreakSeq)
    }

    @Test
    fun `an empty chain reports zero entries and no head`() {
        val store = AuditStore(tmpDir(), retentionSeconds = 0)
        seed(store, "relA")
        // A reference the store has never seen: zero and nulls, indistinguishable from a real
        // relationship nothing has happened in — deliberately, since existence-per-reference is
        // metadata this store does not volunteer.
        val v = store.verifyChain("relB")
        assertEquals(0L, v.entryCount)
        assertNull(v.oldestSeq)
        assertNull(v.headSeq)
        assertNull(v.headHash)
        assertNull(v.firstBreakSeq)
    }

    @Test
    fun `relationships verify independently - a break in one is silent in the other`() {
        val dir = tmpDir()
        val store = AuditStore(dir, retentionSeconds = 0)
        seed(store, "relA")
        seed(store, "relB")
        // Entry 2 really is a share.open in the fixture, so the rewrite below is a CHANGE — a
        // no-op edit here would leave the hash agreeing and this test asserting nothing.
        editDb(dir, "UPDATE audit_events SET action='gameplan.open' WHERE rel_ref=? AND seq=2", "relA")
        assertEquals(2L, store.verifyChain("relA").firstBreakSeq)
        assertNull(store.verifyChain("relB").firstBreakSeq, "relB's chain was not touched and must not inherit relA's break")
    }

    @Test
    fun `append refuses the field separator in objectRef and meta, so the hash recipe stays injective`() {
        // The chain hash joins its fields with '|', and canonicalMeta escapes ','/'='/'%' —
        // not '|'. A value carrying '|' would let a file-level tamper move the field boundary
        // while preserving the concatenation, and verifyChain (which faithfully rehashes the
        // stored bytes) would certify the edit. No current writer can produce such a value, so
        // the guard exists for the future call site that does not know the invariant: append
        // must refuse, not escape — escaping would fork the hash recipe under existing rows.
        val store = AuditStore(tmpDir(), retentionSeconds = 0)
        seed(store, "relA")
        val before = store.verifyChain("relA")

        assertFailsWith<IllegalArgumentException>("objectRef") {
            store.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin|0")
        }
        assertFailsWith<IllegalArgumentException>("meta key") {
            store.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, meta = mapOf("cred|Id" to "x"))
        }
        assertFailsWith<IllegalArgumentException>("meta value") {
            store.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, meta = mapOf("credentialId" to "a|b"))
        }

        // Refusal means REFUSAL: nothing landed, the chain still verifies, the head is where
        // it was. And a clean append afterwards still works — the guard rejects the character,
        // not the caller.
        val after = store.verifyChain("relA")
        assertEquals(before.headSeq, after.headSeq)
        assertEquals(before.headHash, after.headHash)
        assertNull(after.firstBreakSeq)
        store.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0")
        assertEquals(before.headSeq!! + 1, store.verifyChain("relA").headSeq)
    }
}
