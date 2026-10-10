package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a clinician sends, opened on the phone (#177): the web's own sealed bytes open here; every one
 * of the six conditions in [ClinicianItems]'s header refuses on its own; a grant is believed only from
 * the owner for the pinned clinician; and fetching sends the relationship's token signed.
 */
class ClinicianItemsTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val items = ClinicianItems(sodium, { _, _, _, _ -> throw AssertionError("no network here") })

    private fun seed(b: Int) = ByteArray(32) { b.toByte() }

    private fun boxPair(b: Int): ClinicianItems.OwnerBox {
        val pub = ByteArray(Box.PUBLICKEYBYTES)
        val sec = ByteArray(Box.SECRETKEYBYTES)
        assertTrue(sodium.cryptoBoxSeedKeypair(pub, sec, seed(b)))
        return ClinicianItems.OwnerBox(pub, sec)
    }

    private fun signPair(b: Int): Pair<ByteArray, ByteArray> {
        val pub = ByteArray(32)
        val sec = ByteArray(64)
        assertTrue(sodium.cryptoSignSeedKeypair(pub, sec, seed(b)))
        return pub to sec
    }

    private val owner = boxPair(1)
    private val ownerSign = signPair(2)
    private val clinician = signPair(3)
    private val stranger = signPair(9)

    private fun hex(h: String) = sodium.sodiumHex2Bin(h)

    /** Seals [payloadJson] as the web does: signed, enveloped, padded, sealed to [to]. */
    private fun seal(payloadJson: String, signer: Pair<ByteArray, ByteArray> = clinician, to: ByteArray = owner.publicKey, padded: Boolean = true): ByteArray {
        val message = payloadJson.toByteArray(Charsets.UTF_8)
        val sig = ByteArray(64)
        assertTrue(sodium.cryptoSignDetached(sig, message, message.size.toLong(), signer.second))
        val env = """{"payloadJson":${quote(payloadJson)},"sigB64":"${SyncCrypto.toBase64(sig)}"}""".toByteArray(Charsets.UTF_8)
        val inner = if (padded) Padding.pad(env) else env
        val out = ByteArray(inner.size + Box.SEALBYTES)
        assertTrue(sodium.cryptoBoxSeal(out, inner, inner.size.toLong(), to))
        return out
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val ownerFp get() = items.fingerprint(owner.publicKey)
    private val clinicianFp get() = items.fingerprint(clinician.first)

    private fun assignmentJson(
        context: String = ClinicianItems.ASSIGNMENT_CONTEXT,
        recipient: String = ownerFp,
        lineage: String = "as-9",
        version: Long = 4,
        author: String = clinicianFp,
    ) = """{"context":"$context","recipientOwnerFp":"$recipient","assignment":{"assignmentId":"x","lineageId":"$lineage",""" +
        """"version":$version,"type":"goal","capability":"assign.goal","payload":{"title":"Walk","activityId":null},""" +
        """"issuedAt":1790000000000,"authorFingerprint":"$author"}}"""

    private fun refusalOf(o: ClinicianItems.Opening) = (o as? ClinicianItems.Opening.Refused)?.refusal

    @Test
    fun `the web's own assignment, game plan and grant open here`() {
        assertEquals(ClinicianItemsVector.CLINICIAN_FINGERPRINT, clinicianFp)

        val a = items.openAssignment(ClinicianItems.Fetched("as-1", 3, hex(ClinicianItemsVector.ASSIGNMENT_HEX)), owner, clinician.first)
        a as ClinicianItems.Opening.Assignment
        assertEquals("reminder", a.type)
        assertEquals("assign.reminder", a.capability)
        assertEquals(mapOf("every" to "week", "count" to 2L), a.payload)
        assertEquals("Try twice a week", a.note)
        assertEquals(clinicianFp, a.authorFingerprint)

        val g = items.openGamePlan(ClinicianItems.Fetched("gp-1", 2, hex(ClinicianItemsVector.GAME_PLAN_HEX)), owner, clinician.first)
        g as ClinicianItems.Opening.GamePlan
        assertEquals(1L, g.supersedes)
        assertEquals("active", g.status)
        assertEquals(listOf("Walk", "Breathe"), g.items.map { it.title })
        assertEquals(3, g.items[0].targetPerWeek)
        assertEquals("Ten minutes", g.items[0].detail)
        assertNull(g.items[1].detail)
        assertEquals("week", g.reviewEvery)
        assertEquals(2, g.reviewCount)

        val grant = items.verifyGrant(hex(ClinicianItemsVector.GRANT_HEX), ownerSign.first, clinician.first)
        grant as ClinicianItems.GrantReading.Found
        assertTrue(grant.grant.capabilities["assign.reminder"]!!.granted)
        assertEquals("auto", grant.grant.capabilities["assign.reminder"]!!.apply)
        assertEquals(false, grant.grant.capabilities["assign.goal"]!!.granted)
    }

    @Test
    fun `an assignment sealed and signed as the web does opens, padded or not`() {
        for (padded in listOf(true, false)) {
            val o = items.openAssignment(ClinicianItems.Fetched("as-9", 4, seal(assignmentJson(), padded = padded)), owner, clinician.first)
            assertTrue("padded=$padded", o is ClinicianItems.Opening.Assignment)
        }
    }

    @Test
    fun `each condition refuses on its own`() {
        val good = seal(assignmentJson())
        // The control for every case below: the same item, unchanged, opens.
        assertTrue(items.openAssignment(ClinicianItems.Fetched("as-9", 4, good), owner, clinician.first) is ClinicianItems.Opening.Assignment)

        // 1. Sealed to someone else, or altered after sealing.
        val other = seal(assignmentJson(), to = boxPair(7).publicKey)
        assertEquals(ClinicianItems.Refusal.NOT_OPENED, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 4, other), owner, clinician.first)))
        val altered = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertTrue(!altered.contentEquals(good))
        assertEquals(ClinicianItems.Refusal.NOT_OPENED, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 4, altered), owner, clinician.first)))

        // 3. Signed by anyone but the pinned clinician, even with the right fingerprint inside.
        val forged = seal(assignmentJson(), signer = stranger)
        assertEquals(ClinicianItems.Refusal.NOT_THEIRS, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 4, forged), owner, clinician.first)))

        // 3b. Signed by the pinned clinician, but naming another clinician as its author.
        val strangerFp = items.fingerprint(stranger.first)
        assertNotEquals(clinicianFp, strangerFp)
        val borrowed = seal(assignmentJson(author = strangerFp))
        assertEquals(ClinicianItems.Refusal.NOT_THEIRS, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 4, borrowed), owner, clinician.first)))

        // 4. Another kind's context.
        val replayed = seal(assignmentJson(context = ClinicianItems.GAMEPLAN_CONTEXT))
        assertEquals(ClinicianItems.Refusal.MISDIRECTED, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 4, replayed), owner, clinician.first)))

        // 5. Addressed to another owner, though sealed to this one.
        val elsewhere = seal(assignmentJson(recipient = items.fingerprint(boxPair(7).publicKey)))
        assertNotEquals(ownerFp, items.fingerprint(boxPair(7).publicKey))
        assertEquals(ClinicianItems.Refusal.MISDIRECTED, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 4, elsewhere), owner, clinician.first)))

        // 6. Filed under another lineage or version than the one signed.
        assertEquals(ClinicianItems.Refusal.MISDIRECTED, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-9", 5, good), owner, clinician.first)))
        assertEquals(ClinicianItems.Refusal.MISDIRECTED, refusalOf(items.openAssignment(ClinicianItems.Fetched("as-8", 4, good), owner, clinician.first)))

        // An assignment is not a game plan, whatever the channel says.
        assertEquals(ClinicianItems.Refusal.MISDIRECTED, refusalOf(items.openGamePlan(ClinicianItems.Fetched("as-9", 4, good), owner, clinician.first)))
    }

    @Test
    fun `a withdrawn plan carries no items, and a plan's items are read strictly`() {
        fun plan(status: String, items: String) = """{"context":"daymark.gameplan.v1","lineageId":"gp-2","version":1,"supersedes":0,""" +
            """"recipientOwnerFp":"$ownerFp","status":"$status","items":$items,"authorFingerprint":"$clinicianFp","issuedAt":1}"""
        val item = """{"itemRef":"a","kind":"goal","title":"Walk"}"""
        fun open(json: String) = items.openGamePlan(ClinicianItems.Fetched("gp-2", 1, seal(json)), owner, clinician.first)
        assertTrue(open(plan("withdrawn", "[]")) is ClinicianItems.Opening.GamePlan)
        assertTrue(open(plan("active", "[$item]")) is ClinicianItems.Opening.GamePlan)
        assertEquals(ClinicianItems.Refusal.MALFORMED, refusalOf(open(plan("withdrawn", "[$item]"))))
        assertEquals(ClinicianItems.Refusal.MALFORMED, refusalOf(open(plan("paused", "[]"))))
        assertEquals(ClinicianItems.Refusal.MALFORMED, refusalOf(open(plan("active", "[$item,$item]"))))
        assertEquals(ClinicianItems.Refusal.MALFORMED, refusalOf(open(plan("active", """[{"itemRef":"a","kind":"diagnosis","title":"x"}]"""))))
        // Signed by the pinned clinician, but naming another as its author.
        val borrowed = plan("active", "[$item]").replace(clinicianFp, items.fingerprint(stranger.first))
        assertNotEquals(plan("active", "[$item]"), borrowed)
        assertEquals(ClinicianItems.Refusal.NOT_THEIRS, refusalOf(open(borrowed)))
    }

    @Test
    fun `a grant is believed only from the owner, for the pinned clinician`() {
        val good = hex(ClinicianItemsVector.GRANT_HEX)
        assertTrue(items.verifyGrant(good, ownerSign.first, clinician.first) is ClinicianItems.GrantReading.Found)
        // Checked against another owner's key: not this owner's grant.
        assertEquals(ClinicianItems.GrantReading.NotBelieved, items.verifyGrant(good, stranger.first, clinician.first))
        // For another clinician: names someone else.
        assertEquals(ClinicianItems.GrantReading.NotBelieved, items.verifyGrant(good, ownerSign.first, stranger.first))
        // One character of the signed text changed: derived from the text, so it is a real change.
        val text = String(good, Charsets.UTF_8)
        val at = text.indexOf("true")
        assertTrue(at > 0)
        val changed = text.replaceRange(at, at + 4, "TRUE")
        assertNotEquals(text, changed)
        assertEquals(ClinicianItems.GrantReading.NotBelieved, items.verifyGrant(changed.toByteArray(), ownerSign.first, clinician.first))
    }

    @Test
    fun `fetching signs the relationship's token, and counts what it could not read`() {
        val server = FakeServer(sodium)
        val key = DeviceKey.generate(sodium).also { server.know(it.publicKey) }
        val paired = PairedServer(FakeServer.ADDRESS, key)
        val token = ClinicianInvites(sodium, server).newInboxToken()
        val relRef = ClinicianInvites(sodium, server).relRefOf(token)
        val blob = seal(assignmentJson())
        server.answer = { r ->
            when (r.target) {
                "/v1/rel/$relRef/assignments" -> FakeServer.answer(200, """{"lineages":["as-9","gone-1","bad lineage"]}""")
                "/v1/rel/$relRef/assignments/as-9/current" -> TransportAnswer(200, mapOf("X-Version" to listOf("4")), blob)
                "/v1/rel/$relRef/assignments/gone-1/current" -> FakeServer.answer(410)
                else -> throw AssertionError("asked ${r.target}")
            }
        }
        val listing = ClinicianItems(sodium, server, FakeClock()).fetch(paired, token, ClinicianItems.Channel.ASSIGNMENTS)
        listing as ClinicianItems.Listing.Items
        assertEquals(listOf("as-9"), listing.items.map { it.lineage })
        assertEquals(1, listing.gone)
        assertEquals(1, listing.unreadable)
        // Every request carried the token and was signed by the phone's key over it.
        assertEquals(3, server.requests.size)
        for (r in server.requests) {
            assertEquals(token, r.headers["X-Rel-Token"])
            assertEquals(key.keyId, r.signedBy)
        }
        // And the token is one of the headers the signature covers, so it cannot be swapped in transit.
        assertTrue(DeviceSignature.SIGNED_HEADERS.any { it.equals("X-Rel-Token", ignoreCase = true) })

        server.answer = { FakeServer.answer(404) }
        val empty = ClinicianItems(sodium, server, FakeClock()).fetch(paired, token, ClinicianItems.Channel.GAME_PLANS)
        assertEquals(0, (empty as ClinicianItems.Listing.Items).items.size)
    }
}
