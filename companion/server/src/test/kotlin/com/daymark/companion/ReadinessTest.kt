package com.daymark.companion

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/healthz` returning 200 never proved the server could accept a write, and the three ways this
 * deployment actually breaks — a read-only `/data` mount, a volume owned by the wrong UID, a full
 * disk — all leave HTTP working perfectly. These tests pin the behaviour that replaced it.
 */
class ReadinessTest {

    private fun tempDir(): File = Files.createTempDirectory("readyz").toFile().also { it.deleteOnExit() }

    /**
     * Root ignores the permission bits, so the "unwritable" tests would silently assert nothing.
     * Skipping loudly beats passing dishonestly; CI's `server` job runs as a normal user, so these
     * do execute where it counts.
     */
    private fun makeUnwritable(dir: File): Boolean {
        if (System.getProperty("user.name") == "root") {
            println("SKIPPED (running as root — permission bits do not apply)")
            return false
        }
        return dir.setWritable(false, false)
    }

    @Test
    fun `a writable directory is ready, and the probe leaves nothing behind`() {
        val dir = tempDir()
        val r = Readiness(dir.absolutePath).check()
        assertTrue(r.ready, "expected ready, got ${r.reason}")
        assertNull(r.reason)
        // A probe that mints a file per call is a probe that orphans one whenever it dies between
        // create and delete — on the one volume the operator cannot afford to fill.
        assertEquals(0, dir.list()!!.size, "probe left files behind: ${dir.list()!!.toList()}")
    }

    @Test
    fun `an unwritable directory is not ready`() {
        val dir = tempDir()
        if (!makeUnwritable(dir)) return
        try {
            val r = Readiness(dir.absolutePath).check()
            assertFalse(r.ready, "an unwritable /data must not report ready")
            val reason = r.reason
            assertNotNull(reason)
            assertTrue(reason.contains(dir.absolutePath), "the log reason must name the path: $reason")
        } finally {
            dir.setWritable(true, false)
        }
    }

    @Test
    fun `a missing directory is not ready`() {
        val gone = File(tempDir(), "does-not-exist")
        val r = Readiness(gone.absolutePath).check()
        assertFalse(r.ready)
        assertNotNull(r.reason)
    }

    @Test
    fun `results are cached, so an anonymous caller cannot force one disk write per request`() {
        // This endpoint is unauthenticated and it touches the disk. Without the cache, a GET flood
        // becomes a write+fsync flood on the same volume that holds every snapshot.
        val dir = tempDir()
        var now = 1_000L
        val r = Readiness(dir.absolutePath, ttlMs = 5_000) { now }

        assertTrue(r.check().ready)
        if (!makeUnwritable(dir)) return
        try {
            assertTrue(r.check().ready, "within the TTL the cached result must be reused")
            now += 5_001
            assertFalse(r.check().ready, "past the TTL it must probe again and see the truth")
        } finally {
            dir.setWritable(true, false)
        }
    }

    @Test
    fun `recovery is noticed once the TTL expires`() {
        val dir = tempDir()
        var now = 0L
        val r = Readiness(dir.absolutePath, ttlMs = 1_000) { now }
        if (!makeUnwritable(dir)) return
        assertFalse(r.check().ready)
        dir.setWritable(true, false)
        now += 1_001
        assertTrue(r.check().ready, "a recovered volume must clear the failure, not latch it")
    }
}
