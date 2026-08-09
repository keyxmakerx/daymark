package com.daymark.companion.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a failed `put` must leave behind: nothing.
 *
 * The old implementation wrote the blob to its final path first and inserted the index row
 * afterwards, outside any transaction, and never cleaned up its temp file. Three defects fell out
 * of that, all of them worst on the disk-full path the code is named for:
 *
 *  - a failing INSERT left bytes at the final path with no row pointing at them;
 *  - the append-only guard reads the *index*, so the client's retry passed `exists()` and then
 *    silently overwrote a file it believed it was creating;
 *  - every failure orphaned a temp file on `/data/tmp` — which is on the persistent volume, so
 *    the orphans survived restarts and each disk-full failure consumed more of the full disk.
 *
 * Failure is induced by making the lineage directory unwritable, so the `Files.move` fails after
 * the temp file exists and after the row has been inserted. That is precisely the window the old
 * code could not survive.
 */
class BlobStoreDurabilityTest {

    private val dir: File = Files.createTempDirectory("blobstore").toFile()
    private var store: BlobStore? = null

    private fun open(): BlobStore =
        BlobStore(dir.absolutePath, maxBlobBytes = 1 shl 20, maxVersions = 10, perTokenQuotaBytes = 1 shl 24)
            .also { store = it }

    @AfterTest
    fun cleanup() {
        store?.close()
        dir.walkBottomUp().forEach { it.setWritable(true, false); it.delete() }
    }

    private fun tempFiles(): List<String> =
        File(dir, "tmp").list()?.toList() ?: emptyList()

    private fun isRoot() = System.getProperty("user.name") == "root"

    @Test
    fun `a successful put leaves no temp file behind`() {
        val s = open()
        s.put("lineage", 1, ByteArray(64) { 7 })
        assertEquals(emptyList<String>(), tempFiles(), "temp dir should be empty after a clean put")
        assertEquals(64, s.fetch("lineage", 1).size)
    }

    @Test
    fun `a put that fails mid-write leaves neither a temp file nor an index row`() {
        if (isRoot()) {
            println("SKIPPED (running as root — permission bits do not apply)")
            return
        }
        val s = open()
        s.put("lineage", 1, ByteArray(16))

        // Block the rename into the lineage directory: the temp file will be written, the row
        // inserted, and then the move will fail — the exact ordering the fix exists for.
        val lineageDir = File(File(dir, "blobs"), "lineage")
        assertTrue(lineageDir.isDirectory, "precondition: lineage dir exists")
        assertTrue(lineageDir.setWritable(false, false), "could not drop write permission")

        try {
            assertFailsWith<BlobStoreException> { s.put("lineage", 2, ByteArray(16)) }

            assertEquals(emptyList<String>(), tempFiles(), "the failed put orphaned a temp file")
        } finally {
            lineageDir.setWritable(true, false)
        }

        // The index must not carry a row for a blob that was never stored. If it does, the
        // append-only guard will reject the client's legitimate retry of version 2 forever.
        assertTrue(
            s.listVersions("lineage").none { it.version == 2L },
            "a rolled-back put left an index row: ${s.listVersions("lineage").map { it.version }}",
        )
    }

    @Test
    fun `after a failed put the same version can still be stored`() {
        // The user-visible consequence of the rollback: a retry works. Under the old code the
        // orphaned row (or orphaned file) made version 2 permanently unusable for this lineage.
        if (isRoot()) {
            println("SKIPPED (running as root — permission bits do not apply)")
            return
        }
        val s = open()
        s.put("lineage", 1, ByteArray(16))
        val lineageDir = File(File(dir, "blobs"), "lineage")
        lineageDir.setWritable(false, false)
        runCatching { s.put("lineage", 2, ByteArray(16)) }
        lineageDir.setWritable(true, false)

        s.put("lineage", 2, ByteArray(32))
        assertEquals(32, s.fetch("lineage", 2).size)
        assertEquals(emptyList<String>(), tempFiles())
    }

    @Test
    fun `putKeyparams reports a disk failure as a store exception, not a raw IOException`() {
        // It had no try/catch at all, so the identical failure that maps to 507 one method up
        // surfaced here as a bare 500.
        if (isRoot()) {
            println("SKIPPED (running as root — permission bits do not apply)")
            return
        }
        val s = open()
        s.putKeyparams("""{"salt":"a"}""".toByteArray())
        assertTrue(dir.setWritable(false, false), "could not drop write permission")
        try {
            assertFailsWith<BlobStoreException> { s.putKeyparams("""{"salt":"b"}""".toByteArray()) }
        } finally {
            dir.setWritable(true, false)
        }
    }
}
