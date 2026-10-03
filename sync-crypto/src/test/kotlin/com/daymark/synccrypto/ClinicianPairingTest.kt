package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The phone runs the owner's half of pairing (#174) against [FakeServer] as the relay and a clinician
 * played by the same CPace and envelope code the vectors pin to the web. What each test pins: every
 * request is signed by the phone's key; a matching code opens the offer and the clinician opens the
 * owner's keys; a wrong code is a null offer and nothing is approved; a server that changes its story
 * after the key exists is refused.
 */
class ClinicianPairingTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val server = FakeServer(sodium)
    private val key = DeviceKey.generate(sodium).also { server.know(it.publicKey) }
    private val paired = PairedServer(FakeServer.ADDRESS, key)
    private val pairing = ClinicianPairing(sodium, server, FakeClock())
    private val cpace = CpaceCrypto(sodium)
    private val env = PairingEnvelope(sodium)

    private val relRef = "rel-a"
    private val inviteId = "inv-1"
    private val code = ClinicianCode.of("ABCDEFG")
    private val ownerKeys = PairingPayloads.ownerKeysOf(SyncCrypto(sodium).ownerIdentityFromMaster(ByteArray(32) { it.toByte() }))
    private val offer = PairingPayloads.TherapistOffer(
        boxPubB64 = SyncCrypto.toBase64(ByteArray(32) { 1 }),
        signPubB64 = SyncCrypto.toBase64(ByteArray(32) { 2 }),
        displayName = "Dr Example",
        enrolTicketB64 = SyncCrypto.toBase64(ByteArray(32) { 3 }),
    )

    /** A relay that keeps one run, and a clinician who answers it with [clinicianCode]. */
    private inner class Relay(val clinicianCode: String = code.canonical) {
        var state = "OPEN"
        var sidB64: String? = null
        var msgA: ByteArray? = null
        var msgBB64: String? = null
        var e1B64: String? = null
        var clinicianIsk: ByteArray? = null
        var approved: Map<*, *>? = null
        var approveStatus = 204

        fun clinicianAnswers() {
            val sid = SyncCrypto.fromBase64(sidB64!!)
            val responded = cpace.respond(
                clinicianCode.toByteArray(), CpaceCrypto.channelIdentifier(relRef, inviteId), sid, msgA!!,
                "therapist".toByteArray(),
            )
            clinicianIsk = responded.isk
            msgBB64 = SyncCrypto.toBase64(responded.msgB)
            val offerJson = """{"v":1,"boxPubB64":"${offer.boxPubB64}","signPubB64":"${offer.signPubB64}","displayName":"${offer.displayName}","enrolTicketB64":"${offer.enrolTicketB64}"}"""
            e1B64 = SyncCrypto.toBase64(env.seal(responded.isk, sidB64!!, PairingEnvelope.Direction.THERAPIST_TO_OWNER, offerJson.toByteArray()))
            state = "RESPONDED"
        }

        init {
            server.answer = { request ->
                assertEquals("${request.method} ${request.target} is not signed by the phone", key.keyId, request.signedBy)
                val run = "/v1/relations/$relRef/pairing/ex-1"
                when ("${request.method} ${request.target}") {
                    "POST /v1/relations/$relRef/pairing" -> {
                        val body = Answers.jsonObject(request.body)!!
                        assertEquals(inviteId, body["inviteId"])
                        sidB64 = body["sidB64"] as String
                        msgA = SyncCrypto.fromBase64(body["msgAB64"] as String)
                        FakeServer.answer(201, """{"exchangeId":"ex-1"}""")
                    }
                    "GET $run" -> {
                        val reply = if (msgBB64 == null) "" else ""","msgBB64":"$msgBB64","envB64":"$e1B64""""
                        FakeServer.answer(200, """{"exchangeId":"ex-1","state":"$state"$reply}""")
                    }
                    "POST $run/approve" -> {
                        approved = Answers.jsonObject(request.body)
                        if (approveStatus == 204) state = "CLOSED"
                        FakeServer.answer(approveStatus)
                    }
                    "POST $run/cancel" -> FakeServer.answer(if (state == "CANCELLED") 410 else 204).also { state = "CANCELLED" }
                    else -> throw AssertionError("unexpected ${request.method} ${request.target}")
                }
            }
        }
    }

    private fun opened(): ClinicianPairing.OwnerRun =
        (pairing.open(paired, relRef, inviteId, code) as? ClinicianPairing.Opened.Running ?: throw AssertionError("not opened")).run

    private fun complete(run: ClinicianPairing.OwnerRun) =
        pairing.collect(paired, run) as? ClinicianPairing.Collected.Complete ?: throw AssertionError("not complete")

    @Test
    fun `a matching code opens the clinician's offer, and the clinician opens the owner's keys`() {
        val relay = Relay()
        val run = opened()
        assertEquals(ClinicianPairing.Collected.Waiting, pairing.collect(paired, run))
        relay.clinicianAnswers()
        val done = complete(run)
        assertEquals(offer, done.offer)
        assertEquals(relay.msgBB64, done.run.pinnedMsgBB64)

        assertEquals(ClinicianPairing.Approval.Approved, pairing.approve(paired, done, ownerKeys))
        assertEquals(offer.enrolTicketB64, relay.approved!!["enrolTicketB64"])
        val e2 = env.open(relay.clinicianIsk!!, relay.sidB64!!, PairingEnvelope.Direction.OWNER_TO_THERAPIST,
            SyncCrypto.fromBase64(relay.approved!!["envB64"] as String))
        assertEquals("""{"v":1,"boxPubB64":"${ownerKeys.boxPubB64}","signPubB64":"${ownerKeys.signPubB64}"}""", e2!!.toString(Charsets.UTF_8))
        // The key is not kept once approval has used it.
        assertTrue(done.isk.all { it == 0.toByte() })
    }

    @Test
    fun `a wrong code is a null offer, and approving it sends nothing`() {
        val relay = Relay(clinicianCode = ClinicianCode.of("ABCDEFH").canonical)
        assertNotEquals(code.canonical, relay.clinicianCode)
        val run = opened()
        relay.clinicianAnswers()
        val done = complete(run)
        assertNull(done.offer)
        val sentBefore = server.requests.size
        assertEquals(ClinicianPairing.Stop.REFUSED, (pairing.approve(paired, done, ownerKeys) as ClinicianPairing.Approval.Stopped).stop)
        assertEquals(sentBefore, server.requests.size)
        assertNull(relay.approved)
    }

    @Test
    fun `the offer opens only in the clinician's direction`() {
        val relay = Relay()
        val run = opened()
        relay.clinicianAnswers()
        // The clinician's own E1, sealed the other way: what a relay reflecting the owner's words would send.
        val o2t = env.seal(relay.clinicianIsk!!, relay.sidB64!!, PairingEnvelope.Direction.OWNER_TO_THERAPIST,
            env.open(relay.clinicianIsk!!, relay.sidB64!!, PairingEnvelope.Direction.THERAPIST_TO_OWNER, SyncCrypto.fromBase64(relay.e1B64!!))!!)
        relay.e1B64 = SyncCrypto.toBase64(o2t)
        assertNull(complete(run).offer)
    }

    @Test
    fun `once the key exists, a different reply or a run back to waiting is refused`() {
        val relay = Relay()
        val run = opened()
        relay.clinicianAnswers()
        val pinned = complete(run).run
        // Control: the same reply read again is the same run.
        assertEquals(offer, complete(pinned).offer)

        val first = relay.msgBB64
        relay.clinicianAnswers()
        assertNotEquals(first, relay.msgBB64)
        assertEquals(ClinicianPairing.Stop.CONTRADICTED, (pairing.collect(paired, pinned) as ClinicianPairing.Collected.Stopped).stop)

        relay.msgBB64 = first
        for (regressed in listOf("OPEN", "SUPERSEDED")) {
            relay.state = regressed
            assertEquals(ClinicianPairing.Stop.CONTRADICTED, (pairing.collect(paired, pinned) as ClinicianPairing.Collected.Stopped).stop)
        }
        // Cancelling after the key is a legal step, not a contradiction.
        relay.state = "CANCELLED"
        assertEquals(ClinicianPairing.Collected.Cancelled, pairing.collect(paired, pinned))
    }

    @Test
    fun `opening refusals are named, and ids the server should not mint are never sent`() {
        for ((status, stop) in listOf(404 to ClinicianPairing.Stop.NO_OPEN_INVITATION, 409 to ClinicianPairing.Stop.RUNS_USED, 500 to ClinicianPairing.Stop.REFUSED)) {
            server.answer = { FakeServer.answer(status, "{}") }
            assertEquals(stop, (pairing.open(paired, relRef, inviteId, code) as ClinicianPairing.Opened.Stopped).stop)
        }
        server.answer = { FakeServer.answer(201, """{"exchangeId":"../x"}""") }
        assertEquals(ClinicianPairing.Stop.UNEXPECTED, (pairing.open(paired, relRef, inviteId, code) as ClinicianPairing.Opened.Stopped).stop)
        server.answer = { throw IOException("down") }
        assertEquals(ClinicianPairing.Stop.UNREACHABLE, (pairing.open(paired, relRef, inviteId, code) as ClinicianPairing.Opened.Stopped).stop)

        val sent = server.requests.size
        for ((r, i) in listOf("rel/../a" to inviteId, relRef to "inv?x=1", "" to inviteId)) {
            assertEquals(ClinicianPairing.Stop.UNEXPECTED, (pairing.open(paired, r, i, code) as ClinicianPairing.Opened.Stopped).stop)
        }
        assertEquals(sent, server.requests.size)
    }

    @Test
    fun `an approval the run moved under is read again, and cancel says whether it withdrew`() {
        val relay = Relay()
        val run = opened()
        relay.clinicianAnswers()
        relay.approveStatus = 409
        assertEquals(ClinicianPairing.Stop.RUN_CHANGED, (pairing.approve(paired, complete(run), ownerKeys) as ClinicianPairing.Approval.Stopped).stop)
        assertEquals(true, pairing.cancel(paired, run))
        assertEquals(false, pairing.cancel(paired, run))
    }

    @Test
    fun `the code is seven symbols and the web's check symbol, and never shown by accident`() {
        assertEquals("ABCDEFGV", ClinicianCode.of("ABCDEFG").canonical)
        assertEquals("2345678N", ClinicianCode.of("2345678").canonical)
        assertEquals("ZZZZZZZ5", ClinicianCode.of("ZZZZZZZ").canonical)
        assertEquals("ABCD-EFGV", ClinicianCode.of("ABCDEFG").display)
        val drawn = ClinicianCode.draw(sodium)
        assertEquals(8, drawn.canonical.length)
        assertTrue(drawn.canonical.all { it in PairingCode.ALPHABET })
        assertFalse(drawn.toString().contains(drawn.canonical))
        assertNotEquals(ClinicianCode.draw(sodium).canonical + ClinicianCode.draw(sodium).canonical, drawn.canonical + drawn.canonical)
    }

    @Test
    fun `a run that finds no reply waits, and nothing is derived`() {
        Relay()
        val run = opened()
        assertEquals(ClinicianPairing.Collected.Waiting, pairing.collect(paired, run))
        assertNull(run.pinnedMsgBB64)
    }
}
