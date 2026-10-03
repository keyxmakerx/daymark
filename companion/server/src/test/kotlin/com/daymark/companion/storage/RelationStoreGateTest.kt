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
         * Version 0 is already refused as replaced once version 1 exists, but its file is still on
         * the volume, and a withdrawal takes the whole lineage back now: both rows are marked and
         * both files removed. `marked == 2` is the assertion that sees the difference.
         */
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)
        s.put(rel, Channel.SHARES, "share", 1, body, null, expiry = now + 100_000)

        val outcome = s.revokeLineage(rel, Channel.SHARES, "share")
        assertEquals(2, outcome.marked)
        assertEquals(2, outcome.deleted, "both ciphertext files should be gone from the volume")
        assertEquals(0, outcome.undeletable)

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
        assertEquals(1, s.revokeLineage(rel, Channel.SHARES, "share").marked)
        assertEquals(0, s.revokeLineage(rel, Channel.SHARES, "share").marked, "already-withdrawn rows must not be re-counted")
    }

    @Test
    fun `a ciphertext copy that will not delete is counted, not swallowed`() {
        /*
         * The previous revokeLineage wrapped every delete in runCatching and returned the marked
         * count alone, so "withdrawn" read as complete while the bytes stayed on the volume. A
         * non-empty directory whose name ends in .blob is the portable way to make deleteIfExists
         * throw: the test process may be root, so permissions cannot be relied on to refuse.
         */
        val dir = createTempDirectory("relgate").toString()
        val s = RelationStore(dataDir = dir, maxBlobBytes = 1_000_000, maxVersions = 50, perRelQuotaBytes = 10_000_000, clock = { now })
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = now + 100_000)
        val lineageDir = java.nio.file.Path.of(dir, "rel", rel, Channel.SHARES.wire, "share")
        assertTrue(java.nio.file.Files.isDirectory(lineageDir), "the lineage directory should exist where the store writes blobs")
        val stuck = lineageDir.resolve("9.blob")
        java.nio.file.Files.createDirectories(stuck.resolve("child"))

        val outcome = s.revokeLineage(rel, Channel.SHARES, "share")

        assertEquals(1, outcome.marked)
        assertEquals(1, outcome.deleted, "the real blob is removed")
        assertEquals(1, outcome.undeletable, "the copy that refused deletion is reported")
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetch(rel, Channel.SHARES, "share", 0) }.kind,
            "the share is refused regardless of what is left on the volume",
        )
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
    fun `a share with no recorded expiry ends 90 days after it was written, not never (#332)`() {
        /*
         * An older build could store a share with no end at all. Such a row now ends at the same
         * ceiling as everything else, measured from when it was written. Asserted on the SHARES
         * channel specifically, because that is where the upgrade case actually lives. The day-89
         * read is the control: without it, an implementation that refused every NULL-expiry share
         * outright would pass, which is an outage rather than the rule.
         */
        val day = 24L * 60 * 60 * 1000
        val s = store()
        s.put(rel, Channel.SHARES, "share", 0, body, null, expiry = null)
        now += 89 * day
        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "share").second.contentEquals(body), "served on day 89")
        now += day
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetchCurrent(rel, Channel.SHARES, "share") }.kind,
            "refused on day 90",
        )
        assertEquals(
            RelationStoreException.Kind.GONE,
            assertFailsWith<RelationStoreException> { s.fetch(rel, Channel.SHARES, "share", 0) }.kind,
            "and by version",
        )
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
    fun `an item with no chosen end is served inside the 90 days`() {
        // The NULL is not read as epoch 0: an item that carries no end is not refused as if it had
        // ended in 1970.
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
