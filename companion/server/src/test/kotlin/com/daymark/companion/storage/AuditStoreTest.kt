package com.daymark.companion.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditStoreTest {

    private fun tmpDir() = Files.createTempDirectory("audit-store-test").toString()

    /**
     * Meta encoding must be injective, because the therapist controls one of the values that goes
     * into it. `credentialId` is not validated anywhere before it reaches here, and the old
     * encoding joined pairs with a raw `,` and `=`, so a value carrying those characters split
     * into extra fields on the way back out. The hash is computed over the *encoded* string, so a
     * verifier re-canonicalising the parsed map got the same hash — the tamper-evidence certified
     * the forgery rather than catching it. On the log that exists to hold the therapist to
     * account.
     */
    @Test
    fun `a meta value containing the separators cannot forge extra fields`() {
        val store = AuditStore(tmpDir(), retentionSeconds = 0)
        val forged = mapOf("credentialId" to "alice,sourceIp=10.0.0.1")
        store.append("rel1", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, meta = forged)

        val read = store.list("rel1").single().meta
        assertEquals(forged, read, "the map must round-trip exactly, with no invented fields")
        assertEquals(1, read!!.size, "a value with a comma must not become two entries")
        assertNull(read["sourceIp"], "sourceIp was never recorded and must not appear")
    }

    @Test
    fun `percent, comma and equals all survive a round trip in keys and values`() {
        val store = AuditStore(tmpDir(), retentionSeconds = 0)
        // '%' is the escape character, so a value that already contains an escape sequence is the
        // case that breaks a naive implementation: escape '%' last and "%2C" decodes to a comma
        // the caller never wrote.
        val awkward = mapOf(
            "a=b" to "100%",
            "c,d" to "%2C",
            "plain" to "",
            "%25" to "x=y,z",
        )
        store.append("rel1", AuditActor.OWNER, AuditAction.SHARE_OPEN, meta = awkward)
        assertEquals(awkward, store.list("rel1").single().meta)
    }

    @Test
    fun `ordinary values encode exactly as before, so existing rows still verify`() {
        // The escaping must be backward compatible: any meta that contained none of the three
        // characters hashes identically to the pre-fix encoding, or every historical entry's
        // chain hash would break on upgrade.
        var now = 1_000_000L
        val store = AuditStore(tmpDir(), retentionSeconds = 0, clock = { now })
        val e = store.append(
            "rel1", AuditActor.THERAPIST, AuditAction.SHARE_OPEN,
            objectRef = "obj-1",
            meta = mapOf("credentialId" to "alice", "channel" to "notes"),
        )
        // Recomputed by hand against the documented format: sorted pairs joined with ',' and '='.
        val expectedMeta = "channel=notes,credentialId=alice"
        val canonical = listOf(
            "0".repeat(64), "1", now.toString(), "rel1",
            AuditActor.THERAPIST.wire, AuditAction.SHARE_OPEN.wire, "obj-1", expectedMeta,
        ).joinToString("|")
        assertEquals(BlobStore.sha256Hex(canonical.toByteArray()), e.entryHash)
    }

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
