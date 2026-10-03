package com.daymark.companion.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep that deletes the stored copy of every relationship item that has ended (#338).
 *
 * A rule applied only when someone reads never reaches the items nobody opens, which are the ones
 * most likely to be left lying around. The sweep removes the bytes and keeps the row, so version
 * numbers keep counting and a read answers GONE, never NOT_FOUND — the clinician's screen reads a
 * 404 as "nothing was ever shared". The per-relationship quota counts only bytes still held.
 */
class RelationStoreSweepTest {

    private val day = 24L * 60 * 60 * 1000
    private var now = 1_800_000_000_000L
    private val rel = "relref0000000000"
    private val body = byteArrayOf(1, 2, 3, 4)

    private class Fixture(val dir: String, val store: RelationStore)

    private fun fixture(quota: Long = 10_000_000, maxBlob: Long = 1_000_000): Fixture {
        val dir = createTempDirectory("relsweep").toString()
        return Fixture(dir, RelationStore(dir, maxBlob, 50, quota, clock = { now }))
    }

    private fun Fixture.file(channel: Channel, lineage: String, version: Long, relRef: String = rel): Path =
        Path.of(dir, "rel", relRef, channel.wire, lineage, "$version.blob")

    private fun gone(block: () -> Unit) =
        assertEquals(RelationStoreException.Kind.GONE, assertFailsWith<RelationStoreException> { block() }.kind)

    @Test
    fun `after an item's end, one sweep removes its file and keeps its row, and a read answers GONE`() {
        val f = fixture()
        val s = f.store
        s.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        s.put(rel, Channel.SHARES, "share", 1, body, null, expiry = now + 30 * day)
        assertTrue(Files.exists(f.file(Channel.ASSIGNMENTS, "task", 1)), "precondition: the copy is on the volume")
        now += 91 * day

        val outcome = s.sweepEnded()

        assertEquals(2, outcome.removed)
        assertEquals(0, outcome.notRemoved)
        assertFalse(Files.exists(f.file(Channel.ASSIGNMENTS, "task", 1)), "the assignment's copy is gone")
        assertFalse(Files.exists(f.file(Channel.SHARES, "share", 1)), "the share's copy is gone")
        // The rows stay: listed, still holding their numbers, and still answered GONE.
        assertEquals(listOf(1L), s.listVersions(rel, Channel.ASSIGNMENTS, "task").map { it.version })
        assertEquals(
            RelationStoreException.Kind.CONFLICT,
            assertFailsWith<RelationStoreException> { s.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null) }.kind,
            "version 1 is still taken, so numbering keeps counting",
        )
        gone { s.fetch(rel, Channel.ASSIGNMENTS, "task", 1) }
        gone { s.fetchCurrent(rel, Channel.SHARES, "share") }
        // A second sweep finds nothing left to do.
        assertEquals(RelationStore.SweepOutcome(0, 0, 0), s.sweepEnded())
    }

    @Test
    fun `a live item's file is untouched (positive control)`() {
        val f = fixture()
        val s = f.store
        s.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        s.put(rel, Channel.GAMEPLANS, "plan", 1, body, null, expiry = null)
        s.put(rel, Channel.SHARES, "share", 1, body, null, expiry = now + 200 * day)
        s.put(rel, Channel.GRANTS, "grant", 1, body, null, expiry = null)
        now += 89 * day

        assertEquals(RelationStore.SweepOutcome(0, 0, 0), s.sweepEnded())

        for ((channel, lineage) in listOf(
            Channel.ASSIGNMENTS to "task", Channel.GAMEPLANS to "plan", Channel.SHARES to "share", Channel.GRANTS to "grant",
        )) {
            assertTrue(Files.exists(f.file(channel, lineage, 1)), "$channel copy kept on day 89")
            assertContentEquals(body, s.fetch(rel, channel, lineage, 1), "$channel still served on day 89")
        }
        // A grant has no end: day 400 changes nothing for it.
        now += 311 * day
        s.sweepEnded()
        assertTrue(Files.exists(f.file(Channel.GRANTS, "grant", 1)), "the grant's copy on day 400")
        assertContentEquals(body, s.fetch(rel, Channel.GRANTS, "grant", 1))
    }

    @Test
    fun `a removed copy is answered GONE even when the clock steps back`() {
        // A wall clock can be set backwards. The rule would then call the item live again, but its
        // bytes are gone, and a 404 would tell the clinician nothing was ever shared.
        val f = fixture()
        val s = f.store
        s.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        now += 91 * day
        assertEquals(1, s.sweepEnded().removed)
        now -= 41 * day // day 50: inside the 90 days again

        gone { s.fetch(rel, Channel.ASSIGNMENTS, "task", 1) }
        gone { s.fetchCurrent(rel, Channel.ASSIGNMENTS, "task") }
    }

    @Test
    fun `a replaced share version's copy is removed, and the newest is kept`() {
        val f = fixture()
        val s = f.store
        s.put(rel, Channel.SHARES, "share", 1, byteArrayOf(1), null, expiry = now + 30 * day)
        s.put(rel, Channel.SHARES, "share", 2, byteArrayOf(2), null, expiry = now + 30 * day)

        assertEquals(1, s.sweepEnded().removed)

        assertFalse(Files.exists(f.file(Channel.SHARES, "share", 1)))
        assertTrue(Files.exists(f.file(Channel.SHARES, "share", 2)))
        gone { s.fetch(rel, Channel.SHARES, "share", 1) }
        assertContentEquals(byteArrayOf(2), s.fetchCurrent(rel, Channel.SHARES, "share").second)
    }

    @Test
    fun `a file that cannot be deleted is counted, and a later sweep removes it`() {
        /*
         * A non-empty directory where the blob should be is the portable way to make a delete throw:
         * the test may run as root, so permissions cannot be relied on to refuse. The real copy is
         * moved aside into it, so the bytes are still on the volume while the delete keeps failing.
         */
        val f = fixture()
        val s = f.store
        s.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        val blob = f.file(Channel.ASSIGNMENTS, "task", 1)
        val aside = Files.createTempFile(Path.of(f.dir), "aside", ".tmp")
        Files.move(blob, aside, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.createDirectories(blob)
        Files.move(aside, blob.resolve("stuck"))
        now += 91 * day

        val first = s.sweepEnded()
        assertEquals(RelationStore.SweepOutcome(removed = 0, alreadyGone = 0, notRemoved = 1), first)
        assertTrue(Files.exists(blob), "still on the volume")
        gone { s.fetch(rel, Channel.ASSIGNMENTS, "task", 1) }

        // The obstruction clears; the next sweep tries again and succeeds.
        Files.delete(blob.resolve("stuck"))
        val second = s.sweepEnded()
        assertEquals(RelationStore.SweepOutcome(removed = 1, alreadyGone = 0, notRemoved = 0), second)
        assertFalse(Files.exists(blob))
        gone { s.fetch(rel, Channel.ASSIGNMENTS, "task", 1) }
    }

    @Test
    fun `a publish that fits only if deleted bytes are not counted succeeds`() {
        // quota 400_000: the clinician's quarter is 100_000, two 50 KB items.
        val f = fixture(quota = 400_000, maxBlob = 60_000)
        val s = f.store
        s.put(rel, Channel.ASSIGNMENTS, "one", 1, ByteArray(50_000), null, expiry = null)
        s.put(rel, Channel.GAMEPLANS, "two", 1, ByteArray(50_000), null, expiry = null)
        // Control: the budget is full while both are held, so the success below is the sweep's doing.
        assertEquals(
            RelationStoreException.Kind.QUOTA,
            assertFailsWith<RelationStoreException> { s.put(rel, Channel.ASSIGNMENTS, "three", 1, ByteArray(50_000), null, expiry = null) }.kind,
        )
        now += 91 * day
        assertEquals(2, s.sweepEnded().removed)

        s.put(rel, Channel.ASSIGNMENTS, "three", 1, ByteArray(50_000), null, expiry = null)
        s.put(rel, Channel.GAMEPLANS, "four", 1, ByteArray(50_000), null, expiry = null)
        // The per-direction budget is exactly as it was: a third live item does not fit.
        assertEquals(
            RelationStoreException.Kind.QUOTA,
            assertFailsWith<RelationStoreException> { s.put(rel, Channel.ASSIGNMENTS, "five", 1, ByteArray(50_000), null, expiry = null) }.kind,
        )
    }

    @Test
    fun `withdrawn bytes stop counting at once, without waiting for a sweep`() {
        // quota 400_000: the owner's three quarters are 300_000, six 50 KB shares.
        val f = fixture(quota = 400_000, maxBlob = 60_000)
        val s = f.store
        repeat(6) { i -> s.put(rel, Channel.SHARES, "share$i", 1, ByteArray(50_000), null, expiry = now + 30 * day) }
        assertEquals(
            RelationStoreException.Kind.QUOTA,
            assertFailsWith<RelationStoreException> { s.put(rel, Channel.GRANTS, "grant", 1, ByteArray(50_000), null, expiry = null) }.kind,
        )
        assertEquals(1, s.revokeLineage(rel, Channel.SHARES, "share0").deleted)

        s.put(rel, Channel.GRANTS, "grant", 1, ByteArray(50_000), null, expiry = null)
    }

    @Test
    fun `a copy that would not delete still counts against the quota`() {
        // Bytes still on the volume are still held: counting only what the server holds means
        // counting these too.
        val f = fixture(quota = 400_000, maxBlob = 60_000)
        val s = f.store
        s.put(rel, Channel.ASSIGNMENTS, "one", 1, ByteArray(50_000), null, expiry = null)
        s.put(rel, Channel.ASSIGNMENTS, "two", 1, ByteArray(50_000), null, expiry = null)
        val stuck = f.file(Channel.ASSIGNMENTS, "two", 1)
        Files.delete(stuck)
        Files.createDirectories(stuck.resolve("child"))
        now += 91 * day

        assertEquals(RelationStore.SweepOutcome(removed = 1, alreadyGone = 0, notRemoved = 1), s.sweepEnded())

        s.put(rel, Channel.ASSIGNMENTS, "three", 1, ByteArray(50_000), null, expiry = null) // the freed half
        assertEquals(
            RelationStoreException.Kind.QUOTA,
            assertFailsWith<RelationStoreException> { s.put(rel, Channel.ASSIGNMENTS, "four", 1, ByteArray(50_000), null, expiry = null) }.kind,
            "the row whose copy would not delete is still counted",
        )
    }

    @Test
    fun `the gate and the sweep agree, day by day`() {
        /*
         * One table of items and the day each one ends, independent of the store's own rule. Each day
         * the sweep runs, and then every item is either served with its copy on the volume, or
         * refused as GONE with its copy removed — never NOT_FOUND, and never early or late.
         */
        val f = fixture()
        val s = f.store
        data class Item(val channel: Channel, val lineage: String, val version: Long, val endsOnDay: Long?)
        val t0 = now
        s.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        s.put(rel, Channel.GAMEPLANS, "plan", 1, body, null, expiry = null)
        s.put(rel, Channel.SHARES, "short", 1, body, null, expiry = t0 + 30 * day)
        s.put(rel, Channel.SHARES, "long", 1, body, null, expiry = t0 + 200 * day)
        s.put(rel, Channel.SHARES, "noend", 1, body, null, expiry = null)
        s.put(rel, Channel.GRANTS, "grant", 1, body, null, expiry = null)
        val items = mutableListOf(
            Item(Channel.ASSIGNMENTS, "task", 1, 90),
            Item(Channel.GAMEPLANS, "plan", 1, 90),
            Item(Channel.SHARES, "short", 1, 30),
            Item(Channel.SHARES, "long", 1, 90),
            Item(Channel.SHARES, "noend", 1, 90),
            Item(Channel.GRANTS, "grant", 1, null),
        )
        for (d in 0L..120L) {
            now = t0 + d * day
            if (d == 10L) {
                // Published on day 10, it would run its 90 days to day 100...
                s.put(rel, Channel.SHARES, "replaced", 1, body, null, expiry = now + 200 * day)
                items += Item(Channel.SHARES, "replaced", 1, 100)
            }
            if (d == 20L) {
                // ...but a newer version replaces it on day 20, and the new one runs its own 90 days.
                s.put(rel, Channel.SHARES, "replaced", 2, body, null, expiry = now + 200 * day)
                items[items.indexOfFirst { it.lineage == "replaced" }] = Item(Channel.SHARES, "replaced", 1, 20)
                items += Item(Channel.SHARES, "replaced", 2, 110)
            }
            s.sweepEnded()
            for (item in items) {
                val ended = item.endsOnDay != null && d >= item.endsOnDay
                val file = f.file(item.channel, item.lineage, item.version)
                if (ended) {
                    gone { s.fetch(rel, item.channel, item.lineage, item.version) }
                    assertFalse(Files.exists(file), "day $d: ${item.lineage} v${item.version} has ended, so its copy is gone")
                } else {
                    assertContentEquals(body, s.fetch(rel, item.channel, item.lineage, item.version), "day $d: ${item.lineage} v${item.version} is live")
                    assertTrue(Files.exists(file), "day $d: ${item.lineage} v${item.version} is live, so its copy is kept")
                }
            }
        }
    }

    @Test
    fun `rows an older build stored follow the rule on upgrade`() {
        /*
         * An index written before the store tracked which bytes it holds: no `held` column, one row
         * withdrawn (its file already deleted, as withdrawal always did), one written 100 days ago,
         * and one written yesterday. Opening the store adds the column; the first sweep removes the
         * old copy, clears the withdrawn row, and leaves yesterday's alone.
         */
        val dir = createTempDirectory("relsweep-upgrade").toString()
        DriverManager.getConnection("jdbc:sqlite:${Path.of(dir, "rel-index.db")}").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE rel_blobs (rel_ref TEXT NOT NULL, channel TEXT NOT NULL, lineage TEXT NOT NULL, " +
                        "version INTEGER NOT NULL, size INTEGER NOT NULL, content_hash TEXT NOT NULL, setting_key TEXT, " +
                        "created_at INTEGER NOT NULL, expiry INTEGER, revoked INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY (rel_ref, channel, lineage, version))",
                )
                st.execute("INSERT INTO rel_blobs VALUES ('$rel','shares','withdrawn',1,4,'h',NULL,${now - 5 * day},${now + 20 * day},1)")
                st.execute("INSERT INTO rel_blobs VALUES ('$rel','assignments','old',1,4,'h',NULL,${now - 100 * day},NULL,0)")
                st.execute("INSERT INTO rel_blobs VALUES ('$rel','assignments','recent',1,4,'h',NULL,${now - day},NULL,0)")
            }
        }
        for (lineage in listOf("old", "recent")) {
            val p = Path.of(dir, "rel", rel, "assignments", lineage, "1.blob")
            Files.createDirectories(p.parent)
            Files.write(p, body)
        }
        val s = RelationStore(dir, 1_000_000, 50, 10_000_000, clock = { now })

        assertEquals(RelationStore.SweepOutcome(removed = 1, alreadyGone = 1, notRemoved = 0), s.sweepEnded())

        assertFalse(Files.exists(Path.of(dir, "rel", rel, "assignments", "old", "1.blob")))
        assertContentEquals(body, s.fetch(rel, Channel.ASSIGNMENTS, "recent", 1))
        gone { s.fetch(rel, Channel.ASSIGNMENTS, "old", 1) }
        gone { s.fetch(rel, Channel.SHARES, "withdrawn", 1) }
    }

    @Test
    fun `a sync snapshot is untouched`() {
        // The sync API holds the owner's own backups, in another store under the same data
        // directory. The sweep has no business there; the relationship copy removed in the same
        // sweep is the control that the sweep did run.
        val f = fixture()
        val snapshots = BlobStore(f.dir, 1_000_000, 200, 10_000_000)
        val backup = byteArrayOf(5, 5, 5)
        snapshots.put("phone", 1, backup)
        f.store.put(rel, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        now += 400 * day

        assertEquals(1, f.store.sweepEnded().removed)

        assertFalse(Files.exists(f.file(Channel.ASSIGNMENTS, "task", 1)), "control: the sweep ran")
        assertContentEquals(backup, snapshots.fetch("phone", 1))
        assertTrue(Files.exists(Path.of(f.dir, "blobs", "phone", "1.blob")))
    }
}
