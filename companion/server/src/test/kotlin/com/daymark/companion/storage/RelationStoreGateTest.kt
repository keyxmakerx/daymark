package com.daymark.companion.storage

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The access gate on the blob read paths.
 *
 * Before this, `shares` read authorization was: you hold the inbox token, and a live session bound
 * to it. No expiry, no revocation flag, no grant. Both were enforced only inside the therapist's own
 * browser — the party being restricted supplied the clock and served themselves the `{#if}`.
 *
 * The gate lives in the store rather than the routes so that any future route built on `fetch` /
 * `fetchCurrent` inherits it. The bug this whole area keeps producing is a guard applied to *some*
 * of the doors.
 */
class RelationStoreGateTest {

    private var now = 1_000_000L

    private fun store() = RelationStore(
        dataDir = createTempDirectory("relgate").toString(),
        maxBlobBytes = 1_000_000,
        maxVersions = 50,
        perRelQuotaBytes = 10_000_000,
        clock = { now },
    )

    private val rel = "relref0000000000"
    private val body = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `a live share is served — the gate is not simply refusing everything`() {
        // Non-vacuity: every refusal below is worthless if this fails.
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 10_000)
        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "share").second.contentEquals(body))
        assertTrue(s.fetch(rel, Channel.SHARES, "share", 0).contentEquals(body))
    }

    @Test
    fun `an expired share is refused on both read paths`() {
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 10_000)
        now += 10_000 // exactly the deadline: refusal is at >=, matching the client's `now < expiry`
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetchCurrent(rel, Channel.SHARES, "share") }.kind,
        )
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetch(rel, Channel.SHARES, "share", 0) }.kind,
            "gating /current alone would leave the by-version route as a second door to the same bytes",
        )
    }

    @Test
    fun `revoking marks every retained version, not just the newest`() {
        /*
         * Prior versions stay on disk up to the retention window and are individually fetchable, so
         * withdrawing only the head would leave the previous share readable — the same partial-guard
         * shape as the original defect.
         */
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)
        s.put(rel, Channel.SHARES, "share", 1, body, null, expiry = now + 100_000)

        assertEquals(2, s.revokeLineage(rel, Channel.SHARES, "share"))

        for (v in 0L..1L) {
            assertEquals(
                RelationStoreException.Kind.GONE,
                assertFailsWith<RelationStoreException> { s.fetch(rel, Channel.SHARES, "share", v) }.kind,
                "version $v stayed readable after withdrawal",
            )
        }
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetchCurrent(rel, Channel.SHARES, "share") }.kind,
        )
    }

    @Test
    fun `revoking is idempotent and does not double-count`() {
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)
        assertEquals(1, s.revokeLineage(rel, Channel.SHARES, "share"))
        assertEquals(0, s.revokeLineage(rel, Channel.SHARES, "share"), "already-withdrawn rows must not be re-counted")
    }

    @Test
    fun `republishing after a withdrawal is live again`() {
        // Withdrawing marks the versions that exist at that moment. If it poisoned the lineage
        // forever, an owner could never share with that person again — which is not what a person
        // means by "stop showing them this".
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)
        s.revokeLineage(rel, Channel.SHARES, "share")
        val fresh = byteArrayOf(9, 9)
        s.put(rel, Channel.SHARES, "share", 1, fresh, null, expiry = now + 100_000)
        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "share").second.contentEquals(fresh))
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetch(rel, Channel.SHARES, "share", 0) }.kind,
            "the withdrawn version stays withdrawn",
        )
    }

    @Test
    fun `a null expiry never expires — the grandfather rule`() {
        /*
         * Rows written before the expiry column existed have NULL, and locking every already-published
         * share out on upgrade would be an outage delivered as a security fix. Asserted on the SHARES
         * channel specifically, because that is where the upgrade case actually lives — testing it on
         * a channel that never carries an expiry would pass against an implementation that special-cased
         * shares-with-null into GONE, i.e. against the exact outage it claims to rule out.
         */
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = null)
        now += 10L * 365 * 24 * 60 * 60 * 1000
        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "share").second.contentEquals(body))
    }

    @Test
    fun `withdrawal is scoped to its lineage, channel and relationship`() {
        val s = store()
        val other = "relref1111111111"
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)
        s.put(rel, Channel.SHARES, "second", 0, body, null, expiry = now + 100_000)
        s.put(rel, Channel.GRANTS, "grant", 0, body, null, expiry = null)
        s.put(other, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)

        s.revokeLineage(rel, Channel.SHARES, "share")

        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "second").second.contentEquals(body), "other lineage")
        assertTrue(s.fetchCurrent(rel, Channel.GRANTS, "grant").second.contentEquals(body), "other channel")
        assertTrue(s.fetchCurrent(other, Channel.SHARES, "share").second.contentEquals(body), "other relationship")
    }

    @Test
    fun `other channels are unaffected by expiry they never carry`() {
        val s = store()
        s.put(rel, Channel.ASSIGNMENTS, "a", 0, body, null, expiry = null)
        now += 10_000_000
        assertTrue(s.fetchCurrent(rel, Channel.ASSIGNMENTS, "a").second.contentEquals(body))
    }

    @Test
    fun `a missing blob is still NOT_FOUND, not GONE`() {
        val s = store()
        assertEquals(
            RelationStoreException.Kind.NOT_FOUND,
            assertFailsWith<RelationStoreException> { s.fetchCurrent(rel, Channel.SHARES, "nope") }.kind,
        )
    }
}
