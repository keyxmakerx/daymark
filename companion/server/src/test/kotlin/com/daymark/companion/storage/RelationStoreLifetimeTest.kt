package com.daymark.companion.storage

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How long the server serves what the owner and the clinician send each other (#332): nothing but a
 * grant for more than 90 days after it was written, and no share version once a newer one exists.
 *
 * The sealing has no forward secrecy (COMPANION_SECURITY.md §11), so time is the only limit on what a
 * stolen clinician key could open. Every refusal is the one GONE kind, so the clinician cannot tell
 * an elapsed end from a replacement or a withdrawal.
 *
 * The rows here are written straight into the store with ends the routes would clamp, which is what
 * rows an older build stored look like: they follow the new rule on upgrade.
 */
class RelationStoreLifetimeTest {

    private val day = 24L * 60 * 60 * 1000
    private var now = 1_800_000_000_000L
    private val rel = "relref0000000000"
    private val body = byteArrayOf(1, 2, 3, 4)

    private fun store() = RelationStore(
        dataDir = createTempDirectory("rellife").toString(),
        maxBlobBytes = 1_000_000,
        maxVersions = 50,
        perRelQuotaBytes = 10_000_000,
        clock = { now },
    )

    private fun gone(block: () -> Unit) =
        assertEquals(RelationStoreException.Kind.GONE, assertFailsWith<RelationStoreException> { block() }.kind)

    @Test
    fun `the lifetime is 90 days`() {
        // Pinned as a number: the owner console's tests pin the same 90 days (#339), and a change to
        // either side must fail a test on both.
        assertEquals(90L * 24 * 60 * 60 * 1000, RelationStore.ITEM_LIFETIME_MS)
    }

    @Test
    fun `a share stored with a 200-day end is served on day 89 and refused on day 91`() {
        val s = store()
        s.put(rel, Channel.SHARES, "share", 1, body, null, expiry = now + 200 * day)
        now += 89 * day
        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "share").second.contentEquals(body), "day 89")
        assertTrue(s.fetch(rel, Channel.SHARES, "share", 1).contentEquals(body), "day 89, by version")
        now += 2 * day
        gone { s.fetchCurrent(rel, Channel.SHARES, "share") }
        gone { s.fetch(rel, Channel.SHARES, "share", 1) }
    }

    @Test
    fun `a share's own earlier end is kept`() {
        // The ceiling caps an end; it does not replace one the owner chose inside it.
        val s = store()
        s.put(rel, Channel.SHARES, "share", 1, body, null, expiry = now + 30 * day)
        now += 30 * day - 1
        assertTrue(s.fetchCurrent(rel, Channel.SHARES, "share").second.contentEquals(body), "just before its end")
        now += 1
        gone { s.fetchCurrent(rel, Channel.SHARES, "share") }
    }

    @Test
    fun `after version 2 is published, version 1 is refused and current is version 2`() {
        val s = store()
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)
        s.put(rel, Channel.SHARES, "share", 1, first, null, expiry = now + 30 * day)
        // Control: version 1 is served while it is the newest, so its refusal below is the
        // replacement's doing.
        assertTrue(s.fetch(rel, Channel.SHARES, "share", 1).contentEquals(first))

        s.put(rel, Channel.SHARES, "share", 2, second, null, expiry = now + 30 * day)

        gone { s.fetch(rel, Channel.SHARES, "share", 1) }
        val (version, bytes) = s.fetchCurrent(rel, Channel.SHARES, "share")
        assertEquals(2L, version)
        assertTrue(bytes.contentEquals(second))
        // Replacement is not deletion: both rows are still listed, so version numbers keep counting.
        assertEquals(listOf(1L, 2L), s.listVersions(rel, Channel.SHARES, "share").map { it.version })
    }

    @Test
    fun `replacement is per lineage, and another share lineage is untouched`() {
        val s = store()
        s.put(rel, Channel.SHARES, "first", 1, body, null, expiry = now + 30 * day)
        s.put(rel, Channel.SHARES, "second", 1, body, null, expiry = now + 30 * day)
        s.put(rel, Channel.SHARES, "second", 2, body, null, expiry = now + 30 * day)
        assertTrue(s.fetch(rel, Channel.SHARES, "first", 1).contentEquals(body))
        gone { s.fetch(rel, Channel.SHARES, "second", 1) }
    }

    @Test
    fun `an assignment and a game plan are served on day 89 and refused on day 91`() {
        val s = store()
        s.put(rel, Channel.ASSIGNMENTS, "assignment", 1, body, null, expiry = null)
        s.put(rel, Channel.GAMEPLANS, "plan", 1, body, null, expiry = null)
        now += 89 * day
        assertTrue(s.fetchCurrent(rel, Channel.ASSIGNMENTS, "assignment").second.contentEquals(body))
        assertTrue(s.fetchCurrent(rel, Channel.GAMEPLANS, "plan").second.contentEquals(body))
        now += 2 * day
        gone { s.fetchCurrent(rel, Channel.ASSIGNMENTS, "assignment") }
        gone { s.fetchCurrent(rel, Channel.GAMEPLANS, "plan") }
    }

    @Test
    fun `a newer assignment does not end an older one, since replacement is for shares only`() {
        val s = store()
        s.put(rel, Channel.ASSIGNMENTS, "assignment", 1, body, null, expiry = null)
        s.put(rel, Channel.ASSIGNMENTS, "assignment", 2, body, null, expiry = null)
        assertTrue(s.fetch(rel, Channel.ASSIGNMENTS, "assignment", 1).contentEquals(body))
    }

    @Test
    fun `a grant is still served on day 400, older versions included (positive control)`() {
        // The clinician needs the current grant for as long as the relationship lasts, and it is
        // signed, not sealed. Were the ceiling or the replacement rule applied to every channel,
        // this is the test that would say so.
        val s = store()
        s.put(rel, Channel.GRANTS, "grant", 1, body, null, expiry = null)
        s.put(rel, Channel.GRANTS, "grant", 2, body, null, expiry = null)
        now += 400 * day
        assertTrue(s.fetchCurrent(rel, Channel.GRANTS, "grant").second.contentEquals(body))
        assertTrue(s.fetch(rel, Channel.GRANTS, "grant", 1).contentEquals(body))
    }
}
