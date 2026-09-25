package com.daymark.companion.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keep-last-N ends an old version the way everything else ends (#374, #338): the row stays and a
 * read answers GONE, never NOT_FOUND, and a copy that will not delete is counted and retried by
 * the sweep instead of being left on the volume with no row pointing at it.
 */
class RelationStorePruneTest {

    private val now = 1_800_000_000_000L
    private val rel = "relref0000000000"
    private val keep = 3

    private class Fixture(val dir: String, val store: RelationStore)

    private fun fixture(quota: Long = 10_000_000): Fixture {
        val dir = createTempDirectory("relprune").toString()
        return Fixture(dir, RelationStore(dir, 1_000_000, keep, quota, clock = { now }))
    }

    private fun Fixture.file(version: Long): Path = Path.of(dir, "rel", rel, Channel.ASSIGNMENTS.wire, "task", "$version.blob")

    private fun Fixture.publish(version: Long, size: Int = 4) =
        store.put(rel, Channel.ASSIGNMENTS, "task", version, ByteArray(size) { version.toByte() }, null, expiry = null)

    private fun kindOf(block: () -> Unit) = assertFailsWith<RelationStoreException> { block() }.kind

    @Test
    fun `within the limit every version is served (positive control)`() {
        val f = fixture()
        for (v in 1L..keep) f.publish(v)
        for (v in 1L..keep) assertContentEquals(ByteArray(4) { v.toByte() }, f.store.fetch(rel, Channel.ASSIGNMENTS, "task", v))
    }

    @Test
    fun `a version past the limit answers GONE, its copy is removed, and its number stays taken`() {
        val f = fixture()
        for (v in 1L..keep + 1) f.publish(v)
        assertEquals(RelationStoreException.Kind.GONE, kindOf { f.store.fetch(rel, Channel.ASSIGNMENTS, "task", 1) })
        assertFalse(Files.exists(f.file(1)), "the pruned copy is off the volume")
        assertEquals(RelationStoreException.Kind.CONFLICT, kindOf { f.publish(1) }, "the row still holds its number")
        assertContentEquals(ByteArray(4) { 2 }, f.store.fetch(rel, Channel.ASSIGNMENTS, "task", 2))
    }

    @Test
    fun `a pruned copy that will not delete is still counted, and the sweep removes it later`() {
        val f = fixture(quota = 4 * 60_000L) // the clinician's quarter is 60_000
        f.publish(1, size = 20_000)
        val stuck = f.file(1)
        Files.delete(stuck)
        Files.createDirectories(stuck.resolve("child"))
        for (v in 2L..keep + 1) f.publish(v, size = 10_000) // prunes version 1, whose "file" will not delete

        assertEquals(RelationStoreException.Kind.GONE, kindOf { f.store.fetch(rel, Channel.ASSIGNMENTS, "task", 1) })
        assertTrue(Files.exists(stuck), "precondition: the copy is still on the volume")
        // Still counted: 3 x 10_000 held, plus 20_000 that would not delete, leaves under 10_001.
        assertEquals(RelationStoreException.Kind.QUOTA, kindOf { f.store.put(rel, Channel.ASSIGNMENTS, "more", 1, ByteArray(10_001), null, expiry = null) })

        assertEquals(RelationStore.SweepOutcome(removed = 0, alreadyGone = 0, notRemoved = 1), f.store.sweepEnded())
        Files.delete(stuck.resolve("child"))
        assertEquals(RelationStore.SweepOutcome(removed = 1, alreadyGone = 0, notRemoved = 0), f.store.sweepEnded())
        assertFalse(Files.exists(stuck))
        f.store.put(rel, Channel.ASSIGNMENTS, "more", 1, ByteArray(10_001), null, expiry = null) // no longer counted
    }
}
