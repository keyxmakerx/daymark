package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone keeps about its clinicians (#174): the bytes read back exactly and nothing else reads
 * at all; key records are insert-only; a run is handed to storage before the ceremony goes on; and no
 * secret is named by [Any.toString].
 */
class KeptCliniciansTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val invites = ClinicianInvites(sodium, { _, _, _, _ -> throw AssertionError("no network here") })
    private val clock = FakeClock()

    private fun offer(box: Byte, sign: Byte) = PairingPayloads.TherapistOffer(
        SyncCrypto.toBase64(ByteArray(32) { box }), SyncCrypto.toBase64(ByteArray(32) { sign }), "Dr Example",
        SyncCrypto.toBase64(ByteArray(32) { 3 }),
    )

    private fun run(relRef: String, pinned: String? = null) = ClinicianPairing.OwnerRun(
        relRef, "inv-1", "ex-1", SyncCrypto.toBase64(ByteArray(16) { 4 }),
        CpaceCrypto.StartResult(ByteArray(32) { 5 }, ByteArray(40) { 6 }), pinned,
    )

    /** Two clinicians, the first with a key record and an answered run. */
    private fun sample(): KeptClinicians {
        val (one, lee) = KeptClinicians.EMPTY.adding(sodium, "Dr Lee", invites.newInboxToken())
        val (two, _) = one.adding(sodium, "My GP ✓ Ünïcode", invites.newInboxToken())
        val book = KeptClinicians.Book(two, lee.id, clock) {}
        assertTrue(book.pin(offer(1, 2)))
        book.saveRun(run(invites.relRefOf(lee.inboxToken), pinned = SyncCrypto.toBase64(ByteArray(40) { 7 })))
        return book.now
    }

    @Test
    fun `the bytes read back exactly`() {
        val kept = sample()
        val back = KeptClinicians.decode(kept.encode())!!
        assertEquals(kept.clinicians.map { it.id }, back.clinicians.map { it.id })
        assertEquals(listOf("Dr Lee", "My GP ✓ Ünïcode"), back.clinicians.map { it.displayName })
        assertEquals(kept.clinicians.map { it.inboxToken }, back.clinicians.map { it.inboxToken })
        val lee = back.clinicians[0]
        assertArrayEquals(ByteArray(32) { 1 }, lee.current!!.boxPub)
        assertArrayEquals(ByteArray(32) { 2 }, lee.current!!.signPub)
        assertEquals(clock.nowMillis(), lee.current!!.pinnedAt)
        val r = lee.run!!
        assertEquals(invites.relRefOf(lee.inboxToken), r.relRef)
        assertEquals("inv-1", r.inviteId)
        assertEquals("ex-1", r.exchangeId)
        assertArrayEquals(ByteArray(32) { 5 }, r.start.ya)
        assertArrayEquals(ByteArray(40) { 6 }, r.start.msgA)
        assertEquals(SyncCrypto.toBase64(ByteArray(40) { 7 }), r.pinnedMsgBB64)
        assertNull(back.clinicians[1].run)
        assertNull(back.clinicians[1].current)
        assertArrayEquals(kept.encode(), back.encode())
    }

    @Test
    fun `anything but those bytes exactly is nothing kept`() {
        val bytes = sample().encode()
        assertNotNull(KeptClinicians.decode(bytes))
        assertNull(KeptClinicians.decode(bytes + 0))
        assertNull(KeptClinicians.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(KeptClinicians.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(KeptClinicians.decode(bytes.copyOf().also { it[4] = 2 }))
        assertNull(KeptClinicians.decode(ByteArray(0)))
        // Every single-byte truncation is refused, not read as less.
        for (n in 0 until bytes.size) assertNull("read $n of ${bytes.size} bytes", KeptClinicians.decode(bytes.copyOf(n)))
        assertEquals(0, KeptClinicians.decode(KeptClinicians.EMPTY.encode())!!.clinicians.size)
    }

    @Test
    fun `key records are insert-only, and the newest is the one in use`() {
        val (kept, lee) = KeptClinicians.EMPTY.adding(sodium, "Dr Lee", invites.newInboxToken())
        val book = KeptClinicians.Book(kept, lee.id, clock) {}
        assertTrue(book.pin(offer(1, 2)))
        assertTrue(book.pin(offer(1, 2)))
        assertEquals("the same keys again record nothing", 1, book.now.find(lee.id)!!.keys.size)
        clock.now += 1000
        assertTrue(book.pin(offer(8, 9)))
        val records = book.now.find(lee.id)!!.keys
        assertEquals(2, records.size)
        assertArrayEquals("the replaced record stays", ByteArray(32) { 1 }, records[0].boxPub)
        assertArrayEquals(ByteArray(32) { 8 }, book.held()!!.boxPub)
        assertFalse(book.pin(offer(1, 2).copy(boxPubB64 = "not-a-key")))
    }

    @Test
    fun `another relationship's keys are offered for the check, with the owner's name, and this one's are not`() {
        val (one, lee) = KeptClinicians.EMPTY.adding(sodium, "Dr Lee", invites.newInboxToken())
        val (two, gp) = one.adding(sodium, "My GP", invites.newInboxToken())
        KeptClinicians.Book(two, gp.id, clock) {}.also { it.pin(offer(1, 2)) }.now.let { withGp ->
            val leeBook = KeptClinicians.Book(withGp, lee.id, clock) {}
            assertEquals(listOf("My GP"), leeBook.others().map { it.displayName })
            assertNull(leeBook.held())
            assertTrue(ClinicianKeys.check(offer(1, 2), leeBook.held(), leeBook.others()) is ClinicianKeys.Check.OtherPerson)
            // Control: the GP's own book sees its keys as held, and nobody else's.
            val gpBook = KeptClinicians.Book(withGp, gp.id, clock) {}
            assertTrue(gpBook.others().isEmpty())
            assertEquals(ClinicianKeys.Check.Unchanged, ClinicianKeys.check(offer(1, 2), gpBook.held(), gpBook.others()))
        }
    }

    @Test
    fun `a run is handed to storage before the call returns`() {
        val (kept, lee) = KeptClinicians.EMPTY.adding(sodium, "Dr Lee", invites.newInboxToken())
        var stored: KeptClinicians? = null
        val book = KeptClinicians.Book(kept, lee.id, clock) { stored = it }
        book.saveRun(run(invites.relRefOf(lee.inboxToken)))
        assertEquals("ex-1", KeptClinicians.decode(stored!!.encode())!!.find(lee.id)!!.run!!.exchangeId)
        book.forgetRun()
        assertNull(stored!!.find(lee.id)!!.run)
    }

    @Test
    fun `names are the owner's, kept to the same rule as the clinician's`() {
        assertTrue(KeptClinicians.validName("Dr Lee"))
        assertFalse(KeptClinicians.validName(""))
        assertFalse(KeptClinicians.validName("   "))
        assertFalse(KeptClinicians.validName("Dr‮Lee"))
        assertFalse(KeptClinicians.validName("x".repeat(65)))
        assertTrue(KeptClinicians.validName("x".repeat(64)))
    }

    @Test
    fun `no secret is named by toString`() {
        val kept = sample()
        val lee = kept.clinicians[0]
        for (text in listOf(kept.toString(), lee.toString(), lee.run.toString())) {
            assertFalse(text, text.contains(lee.inboxToken))
            assertFalse(text, text.contains("Dr Lee"))
        }
        // Control: the token is findable as text where it really is, in the bytes kept.
        assertTrue(String(kept.encode(), Charsets.ISO_8859_1).contains(lee.inboxToken))
    }
}
