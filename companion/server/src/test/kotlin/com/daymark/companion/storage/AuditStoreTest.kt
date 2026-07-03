package com.daymark.companion.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditStoreTest {

    private fun tmpDir() = Files.createTempDirectory("audit-store-test").toString()

    @Test
    fun `append assigns monotonic seq per relationship and chains hashes`() {
        var now = 1_000_000L
        val store = AuditStore(tmpDir(), clock = { now })
        val e1 = store.append("relA", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        now += 5
        val e2 = store.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0")
        // Independent relationship starts its own chain at seq 1.
        val eB = store.append("relB", AuditActor.OWNER, AuditAction.GAMEPLAN_OPEN)

        assertEquals(1L, e1.seq)
        assertEquals(2L, e2.seq)
        assertEquals(1L, eB.seq)
        assertNotEquals(e1.entryHash, e2.entryHash)
        // Changing any field changes the resulting hash (order-sensitive chain).
        assertNotEquals(e1.entryHash, eB.entryHash)
    }

    @Test
    fun `tampering an earlier entry breaks the chain for everything after it`() {
        val store = AuditStore(tmpDir())
        store.append("rel", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        val e2 = store.append("rel", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0")
        // Recompute what e2's hash SHOULD be if e1 had been different — a verifier who
        // recomputes the chain from a tampered e1 would get a different e2 hash, so tampering
        // is detectable (this test exercises the hash function's sensitivity directly).
        val recomputed = AuditStore(tmpDir()).let {
            it.append("rel", AuditActor.THERAPIST, AuditAction.LOCKOUT) // different action than the real e1
            it.append("rel", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:0")
        }
        assertNotEquals(e2.entryHash, recomputed.entryHash)
    }

    @Test
    fun `list is newest-first and paginates with a beforeSeq cursor`() {
        val store = AuditStore(tmpDir())
        repeat(5) { store.append("rel", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:$it") }

        val page1 = store.list("rel", limit = 2)
        assertEquals(listOf(5L, 4L), page1.map { it.seq })

        val page2 = store.list("rel", beforeSeq = page1.last().seq, limit = 2)
        assertEquals(listOf(3L, 2L), page2.map { it.seq })

        val page3 = store.list("rel", beforeSeq = page2.last().seq, limit = 2)
        assertEquals(listOf(1L), page3.map { it.seq })
    }

    @Test
    fun `list never leaks content beyond the fixed event shape`() {
        val store = AuditStore(tmpDir())
        store.append(
            "rel", AuditActor.THERAPIST, AuditAction.SHARE_OPEN,
            objectRef = "lin1:3", meta = mapOf("credentialId" to "cred-abc"),
        )
        val events = store.list("rel")
        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals("share.open", ev.action)
        assertEquals("therapist", ev.actor)
        assertEquals("lin1:3", ev.objectRef)
        assertEquals(mapOf("credentialId" to "cred-abc"), ev.meta)
        // The event shape has no field that could carry decrypted content — objectRef/meta are
        // the only free-form-ish slots, and callers are the ones responsible for keeping them
        // metadata-only (enforced by review of the route call sites, not by this store).
    }

    @Test
    fun `entries older than the retention window are pruned on the next append`() {
        var now = 1_000_000L
        val store = AuditStore(tmpDir(), retentionSeconds = 100L, clock = { now })
        store.append("rel", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        now += 200 // past the 100s retention window
        store.append("rel", AuditActor.THERAPIST, AuditAction.SHARE_OPEN)

        val events = store.list("rel", limit = 50)
        assertEquals(1, events.size)
        assertEquals("share.open", events[0].action)
    }

    @Test
    fun `retention disabled with a non-positive window keeps every entry`() {
        var now = 1_000_000L
        val store = AuditStore(tmpDir(), retentionSeconds = 0L, clock = { now })
        store.append("rel", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        now += 10_000_000
        store.append("rel", AuditActor.THERAPIST, AuditAction.SHARE_OPEN)
        assertEquals(2, store.list("rel", limit = 50).size)
    }

    @Test
    fun `relationships are isolated from each other`() {
        val store = AuditStore(tmpDir())
        store.append("relA", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS)
        assertTrue(store.list("relB").isEmpty())
        assertNull(store.list("relB").firstOrNull())
    }
}
