package com.daymark.companion.storage

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Each writing direction has its own storage budget (RelationStore). With one budget shared by
 * both, a clinician's own assignments and game plans could fill it, and the owner's next grant or
 * share would be refused, including a grant written to narrow what that clinician may do.
 */
class RelationStoreQuotaTest {

    private val quota = 400_000L // the clinician's quarter is 100_000; the owner keeps 300_000
    private val rel = "relref0000000000"
    private val other = "relref0000000001"
    private val farFuture = Long.MAX_VALUE / 2

    private fun store() = RelationStore(
        dataDir = createTempDirectory("relquota").toString(),
        maxBlobBytes = 60_000,
        maxVersions = 50,
        perRelQuotaBytes = quota,
    )

    private fun blob(n: Int) = ByteArray(n) { 7 }

    /** Write 50 KB items on [channel] under fresh lineages until refused; return how many fitted. */
    private fun fill(s: RelationStore, relRef: String, channel: Channel): Int {
        var written = 0
        val refused = assertFailsWith<RelationStoreException> {
            while (true) {
                val expiry = if (channel == Channel.SHARES) farFuture else null
                s.put(relRef, channel, "fill$written", 0, blob(50_000), null, expiry = expiry)
                written++
            }
        }
        assertEquals(RelationStoreException.Kind.QUOTA, refused.kind)
        return written
    }

    @Test
    fun `a clinician who fills their budget cannot block the owner's grant or share`() {
        val s = store()
        assertEquals(2, fill(s, rel, Channel.ASSIGNMENTS)) // 100_000 is theirs: two 50 KB items
        // Game plans draw on the same clinician budget, so they are refused too.
        val plan = assertFailsWith<RelationStoreException> {
            s.put(rel, Channel.GAMEPLANS, "plan", 0, blob(1), null, expiry = null)
        }
        assertEquals(RelationStoreException.Kind.QUOTA, plan.kind)
        // The owner is untouched: a grant narrowing that clinician, and a share, both go through.
        s.put(rel, Channel.GRANTS, "grant", 0, blob(2_000), null, expiry = null)
        s.put(rel, Channel.SHARES, "share", 0, blob(50_000), null, expiry = farFuture)
    }

    @Test
    fun `the owner's budget is enforced too, and filling it does not block the clinician (positive control)`() {
        val s = store()
        assertEquals(6, fill(s, rel, Channel.SHARES)) // 300_000 is the owner's
        val grant = assertFailsWith<RelationStoreException> {
            s.put(rel, Channel.GRANTS, "grant", 0, blob(1), null, expiry = null)
        }
        assertEquals(RelationStoreException.Kind.QUOTA, grant.kind)
        s.put(rel, Channel.ASSIGNMENTS, "assignment", 0, blob(50_000), null, expiry = null)
        s.put(rel, Channel.GAMEPLANS, "plan", 0, blob(50_000), null, expiry = null)
    }

    @Test
    fun `the two budgets add up to the configured quota, and each relationship has its own`() {
        val s = store()
        val clinician = fill(s, rel, Channel.GAMEPLANS)
        val owner = fill(s, rel, Channel.SHARES)
        assertEquals(quota, (clinician + owner) * 50_000L)
        // A full relationship leaves another relationship's budgets alone.
        s.put(other, Channel.ASSIGNMENTS, "assignment", 0, blob(50_000), null, expiry = null)
        s.put(other, Channel.GRANTS, "grant", 0, blob(50_000), null, expiry = null)
    }

    @Test
    fun `every channel names its writer, and the directions are the ones the routes enforce`() {
        assertEquals(Writer.OWNER, Channel.GRANTS.writer)
        assertEquals(Writer.OWNER, Channel.SHARES.writer)
        assertEquals(Writer.CLINICIAN, Channel.ASSIGNMENTS.writer)
        assertEquals(Writer.CLINICIAN, Channel.GAMEPLANS.writer)
    }
}
