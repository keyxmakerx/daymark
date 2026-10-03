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
 * The owner's pairing ceremony on the phone (#174), against [FakeServer] as the relay and a clinician
 * played by the CPace and envelope code the vectors pin to the web. What each test pins is the order of
 * effects and what a tap may and may not send: the run is kept before its code is shown; a reply that
 * does not open sends nothing and records nothing; an approval records before it forwards and forwards
 * nothing when the record refuses; only a person's Stop reports an invitation; a restarted run has no
 * code to show.
 */
class ClinicianCeremonyTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val server = FakeServer(sodium)
    private val key = DeviceKey.generate(sodium).also { server.know(it.publicKey) }
    private val paired = PairedServer(FakeServer.ADDRESS, key)
    private val clock = FakeClock()
    private val invites = ClinicianInvites(sodium, server, clock)
    private val cpace = CpaceCrypto(sodium)
    private val env = PairingEnvelope(sodium)
    private val relRef = invites.relRefOf("inbox-token-example")
    private val ownerKeys = PairingPayloads.ownerKeysOf(SyncCrypto(sodium).ownerIdentityFromMaster(ByteArray(32) { it.toByte() }))

    private val box = ByteArray(32) { 1 }
    private val sign = ByteArray(32) { 2 }

    /** The phone's keeping, watched: what was kept, and in what order everything happened. */
    private inner class Kept : ClinicianCeremony.Keep {
        var run: ClinicianPairing.OwnerRun? = null
        var held: ClinicianKeys.Held? = null
        var others: List<ClinicianKeys.Named> = emptyList()
        var refusePin = false
        val pins = ArrayList<PairingPayloads.TherapistOffer>()
        val events = ArrayList<String>()

        override fun saveRun(run: ClinicianPairing.OwnerRun) { this.run = run; events += "save" }
        override fun loadRun() = run
        override fun forgetRun() { run = null; events += "forget" }
        override fun held() = held
        override fun others() = others
        override fun pin(offer: PairingPayloads.TherapistOffer): Boolean {
            events += "pin"
            if (refusePin) return false
            pins += offer
            return true
        }
    }

    /** One invitation, its runs, and a clinician who answers the newest with [clinicianCode]. */
    private inner class Relay(private val kept: Kept) {
        val inviteId = "inv-1"
        var status = "PENDING"
        var clinicianCode: String? = null
        var runs = 0
        val state = HashMap<String, String>()
        val sid = HashMap<String, String>()
        val msgA = HashMap<String, ByteArray>()
        val msgB = HashMap<String, String>()
        val e1 = HashMap<String, String>()
        var approved: Map<*, *>? = null
        var reported = false
        var down = false

        val latest get() = "ex-$runs"

        fun clinicianAnswers(boxPub: ByteArray = box, signPub: ByteArray = sign) {
            val ex = latest
            val responded = cpace.respond(
                clinicianCode!!.toByteArray(), CpaceCrypto.channelIdentifier(relRef, inviteId), SyncCrypto.fromBase64(sid[ex]!!), msgA[ex]!!,
                "therapist".toByteArray(),
            )
            msgB[ex] = SyncCrypto.toBase64(responded.msgB)
            val offer = """{"v":1,"boxPubB64":"${SyncCrypto.toBase64(boxPub)}","signPubB64":"${SyncCrypto.toBase64(signPub)}","displayName":"Dr Example","enrolTicketB64":"${SyncCrypto.toBase64(ByteArray(32) { 3 })}"}"""
            e1[ex] = SyncCrypto.toBase64(env.seal(responded.isk, sid[ex]!!, PairingEnvelope.Direction.THERAPIST_TO_OWNER, offer.toByteArray()))
            state[ex] = "RESPONDED"
        }

        init {
            server.answer = { request ->
                if (down) throw IOException("no answer")
                assertEquals("${request.method} ${request.target} is not signed by the phone", key.keyId, request.signedBy)
                val base = "/v1/relations/$relRef"
                val path = "${request.method} ${request.target}"
                when {
                    path == "POST /v1/invite" -> {
                        val body = Answers.jsonObject(request.body)!!
                        assertEquals(relRef, body["relRef"])
                        assertEquals(listOf("read.share"), body["scope"])
                        FakeServer.answer(201, """{"inviteId":"$inviteId","link":"https://daymark.test/t/accept#i=$inviteId&s=secret","expiresAt":1790600000000}""")
                    }
                    path == "GET $base/invites" -> {
                        val latestPart = if (runs == 0) "" else ""","latestExchange":{"exchangeId":"$latest","state":"${state[latest]}"}"""
                        FakeServer.answer(200, """[{"inviteId":"$inviteId","status":"$status","createdAt":1,"expiresAt":1790600000000,"failCount":0,"exchangeCount":$runs$latestPart}]""")
                    }
                    path == "POST /v1/invite/$inviteId/report" -> { reported = true; status = "REPORTED"; FakeServer.answer(204) }
                    path == "POST $base/pairing" -> {
                        if (runs >= 8) FakeServer.answer(409) else {
                        if (runs > 0 && state[latest] == "OPEN") state[latest] = "SUPERSEDED"
                        runs++
                        kept.events += "open"
                        val body = Answers.jsonObject(request.body)!!
                        sid[latest] = body["sidB64"] as String
                        msgA[latest] = SyncCrypto.fromBase64(body["msgAB64"] as String)
                        state[latest] = "OPEN"
                        FakeServer.answer(201, """{"exchangeId":"$latest"}""")
                        }
                    }
                    request.method == "GET" && request.target.startsWith("$base/pairing/") -> {
                        val ex = request.target.substringAfterLast('/')
                        val reply = msgB[ex]?.let { ""","msgBB64":"$it","envB64":"${e1[ex]}"""" } ?: ""
                        FakeServer.answer(200, """{"exchangeId":"$ex","state":"${state[ex]}"$reply}""")
                    }
                    request.method == "POST" && request.target.endsWith("/approve") -> {
                        kept.events += "approve"
                        approved = Answers.jsonObject(request.body)
                        state[request.target.split('/')[5]] = "CLOSED"
                        status = "REDEEMING"
                        FakeServer.answer(204)
                    }
                    request.method == "POST" && request.target.endsWith("/cancel") -> {
                        val ex = request.target.split('/')[5]
                        kept.events += "cancel"
                        FakeServer.answer(if (state[ex] == "CANCELLED") 410 else 204).also { state[ex] = "CANCELLED" }
                    }
                    else -> throw AssertionError("unexpected $path")
                }
            }
        }
    }

    private var drawn = ArrayList<ClinicianCode>()
    private fun ceremony(kept: Kept) = ClinicianCeremony(
        paired, relRef, ClinicianPairing(sodium, server, clock), invites, ownerKeys, kept,
    ) { ClinicianCode.draw(sodium).also { drawn += it } }

    /** Mint, open a run, and have the clinician answer it with the code on screen (or [typed]). */
    private fun answered(kept: Kept, relay: Relay, ceremony: ClinicianCeremony, typed: ((String) -> String)? = null): ClinicianCeremony.Step {
        val invited = ceremony.startInvitation().phase
        val waiting = ceremony.openRun(invited).phase as ClinicianCeremony.Phase.Waiting
        relay.clinicianCode = typed?.invoke(waiting.code.canonical) ?: waiting.code.canonical
        relay.clinicianAnswers()
        return ceremony.checkForReply(waiting)
    }

    /** A wrong code derived from the right one, so it is wrong whatever was drawn. */
    private fun wrongFor(right: String): String {
        val a = ClinicianCode.of("2345678").canonical
        return if (right == a) ClinicianCode.of("ABCDEFG").canonical else a
    }

    @Test
    fun `the relationship reference is the web's, byte for byte`() {
        // Pinned with the same value in companion/web/src/lib/sync/portal.test.ts.
        assertEquals("PmoLo2aBLjo0CAzUlM2MhtNp2fCKQvpN17Qgod-1w2s", invites.relRefOf("inbox-token-example"))
        val token = invites.newInboxToken()
        assertEquals(32, SyncCrypto.fromBase64(token).size)
        assertNotEquals(token, invites.newInboxToken())
    }

    @Test
    fun `minting draws no code and opens no run`() {
        val kept = Kept()
        Relay(kept)
        val step = ceremony(kept).startInvitation()
        val invited = step.phase as ClinicianCeremony.Phase.Invited
        assertNull(step.halt)
        assertEquals("https://daymark.test/t/accept#i=inv-1&s=secret", invited.invite.link)
        assertEquals(8, invited.attemptsLeft)
        assertTrue(drawn.isEmpty())
        assertEquals(listOf("POST /v1/invite"), server.requests.map { "${it.method} ${it.target}" })
    }

    @Test
    fun `the run is kept before its code is shown, and the code lives only while it waits`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        val waiting = c.openRun(c.startInvitation().phase).phase as ClinicianCeremony.Phase.Waiting
        assertEquals(listOf("open", "save"), kept.events)
        assertEquals(waiting.run.exchangeId, kept.run!!.exchangeId)
        assertEquals(7, waiting.attemptsLeft)
        relay.clinicianCode = waiting.code.canonical
        relay.clinicianAnswers()
        val answered = c.checkForReply(waiting).phase
        assertTrue(answered is ClinicianCeremony.Phase.Answered)
        // No phase after a reply has a code to show; the class has no field for one.
        assertFalse(ClinicianCeremony.Phase.Answered::class.java.declaredFields.any { it.type == ClinicianCode::class.java })
        assertFalse(ClinicianCeremony.Phase.Mismatch::class.java.declaredFields.any { it.type == ClinicianCode::class.java })
        assertFalse(ClinicianCeremony.Phase.Resumed::class.java.declaredFields.any { it.type == ClinicianCode::class.java })
        // Positive control: the reflection sees the field where there is one.
        assertTrue(ClinicianCeremony.Phase.Waiting::class.java.declaredFields.any { it.type == ClinicianCode::class.java })
    }

    @Test
    fun `checking for a reply is one read, before an answer and after a mismatch alike`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        val waiting = c.openRun(c.startInvitation().phase).phase
        val before = server.requests.size
        assertTrue(c.checkForReply(waiting).phase === waiting)
        assertEquals(before + 1, server.requests.size)
        relay.clinicianCode = wrongFor((waiting as ClinicianCeremony.Phase.Waiting).code.canonical)
        assertNotEquals(waiting.code.canonical, relay.clinicianCode)
        relay.clinicianAnswers()
        val mismatch = c.checkForReply(waiting).phase
        val after = server.requests.size
        c.checkForReply(mismatch)
        assertEquals(after + 1, server.requests.size)
    }

    @Test
    fun `a reply that does not open records nothing, approves nothing and ends nothing`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        val step = answered(kept, relay, c, ::wrongFor)
        assertNotEquals(drawn.single().canonical, relay.clinicianCode)
        val mismatch = step.phase as ClinicianCeremony.Phase.Mismatch
        assertTrue(kept.pins.isEmpty())
        assertNull(relay.approved)
        assertFalse(relay.reported)
        assertEquals("PENDING", relay.status)
        // Approving a mismatch is refused without a request.
        val sent = server.requests.size
        assertEquals(ClinicianCeremony.Halt.REFUSED, c.approve(mismatch).halt)
        assertEquals(sent, server.requests.size)
        // Keeping the invitation sends nothing either.
        val kept2 = c.keepInvitation(mismatch)
        assertTrue(kept2.phase is ClinicianCeremony.Phase.Invited)
        assertEquals(sent, server.requests.size)
    }

    @Test
    fun `an approval records the keys before it forwards, and seals the owner's keys back`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        val answered = answered(kept, relay, c).phase as ClinicianCeremony.Phase.Answered
        assertEquals(ClinicianKeys.Check.First, answered.keys)
        kept.events.clear()
        val approved = c.approve(answered).phase as ClinicianCeremony.Phase.Approved
        assertEquals(listOf("pin", "approve", "forget"), kept.events)
        assertFalse(approved.replaced)
        assertEquals(SyncCrypto.toBase64(sign), kept.pins.single().signPubB64)
        assertNotEquals(null, relay.approved!!["envB64"])
    }

    @Test
    fun `a record that refuses forwards nothing`() {
        val kept = Kept().apply { refusePin = true }
        val relay = Relay(kept)
        val c = ceremony(kept)
        val answered = answered(kept, relay, c).phase
        val step = c.approve(answered)
        assertEquals(ClinicianCeremony.Halt.NOT_RECORDED, step.halt)
        assertNull(relay.approved)
        // Positive control: the same answer approves once the record takes it.
        kept.refusePin = false
        assertTrue(c.approve(step.phase).phase is ClinicianCeremony.Phase.Approved)
        assertNotEquals(null, relay.approved)
    }

    @Test
    fun `keys already held for someone else are named by the owner's name and never recorded`() {
        val kept = Kept().apply { others = listOf(ClinicianKeys.Named("My GP", ByteArray(32) { 9 }, sign)) }
        val relay = Relay(kept)
        val c = ceremony(kept)
        val answered = answered(kept, relay, c).phase as ClinicianCeremony.Phase.Answered
        assertEquals("My GP", (answered.keys as ClinicianKeys.Check.OtherPerson).displayName)
        assertEquals(ClinicianCeremony.Halt.OTHER_PERSON, c.approve(answered).halt)
        assertTrue(kept.pins.isEmpty())
        assertNull(relay.approved)
    }

    @Test
    fun `different keys for the same person are approvable and say they replace`() {
        val kept = Kept().apply { held = ClinicianKeys.Held(ByteArray(32) { 7 }, ByteArray(32) { 8 }) }
        val relay = Relay(kept)
        val c = ceremony(kept)
        val answered = answered(kept, relay, c).phase as ClinicianCeremony.Phase.Answered
        assertEquals(ClinicianKeys.Check.Supersedes, answered.keys)
        assertTrue((c.approve(answered).phase as ClinicianCeremony.Phase.Approved).replaced)
        // Control: the same keys on file are unchanged, not a replacement.
        assertEquals(
            ClinicianKeys.Check.Unchanged,
            ClinicianKeys.check(answered.offer, ClinicianKeys.Held(box, sign), emptyList()),
        )
    }

    @Test
    fun `only Stop reports the invitation`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        var phase = c.startInvitation().phase
        phase = c.openRun(phase).phase
        phase = c.newCode(phase).phase
        assertEquals(2, relay.runs)
        assertEquals("CANCELLED", relay.state["ex-1"])
        assertFalse(relay.reported)
        val ended = c.stopInvitation(phase).phase as ClinicianCeremony.Phase.Ended
        assertEquals(ClinicianCeremony.EndReason.REPORTED, ended.reason)
        assertTrue(relay.reported)
        assertNull(kept.run)
    }

    @Test
    fun `a restarted run can be finished but has no code to show`() {
        val kept = Kept()
        val relay = Relay(kept)
        val first = ceremony(kept)
        val waiting = first.openRun(first.startInvitation().phase).phase as ClinicianCeremony.Phase.Waiting
        relay.clinicianCode = waiting.code.canonical

        // The app restarts: a new ceremony over the same keeping.
        val restored = ceremony(kept).restore().phase
        assertTrue(restored is ClinicianCeremony.Phase.Resumed)
        assertEquals(waiting.run.exchangeId, (restored as ClinicianCeremony.Phase.Resumed).run.exchangeId)
        assertNull(restored.invite.link)
        relay.clinicianAnswers()
        assertTrue(ceremony(kept).checkForReply(restored).phase is ClinicianCeremony.Phase.Answered)
    }

    @Test
    fun `a kept run that is not the invitation's newest is forgotten, not shown`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        val phase = c.openRun(c.startInvitation().phase).phase
        val stale = kept.run!!
        c.openRun(phase)
        kept.run = stale
        val restored = ceremony(kept).restore().phase
        assertTrue(restored is ClinicianCeremony.Phase.Invited)
        assertNull(kept.run)
        assertEquals(6, (restored as ClinicianCeremony.Phase.Invited).attemptsLeft)
        assertEquals(2, relay.runs)
    }

    @Test
    fun `no answer on Stop leaves the invitation and says so`() {
        val kept = Kept()
        val relay = Relay(kept)
        val c = ceremony(kept)
        val invited = c.startInvitation().phase
        relay.down = true
        val step = c.stopInvitation(invited)
        assertEquals(ClinicianCeremony.Halt.NO_ANSWER, step.halt)
        assertTrue(step.phase === invited)
    }

    @Test
    fun `an invitation's runs run out at eight, and the ceremony ends it as capped`() {
        val kept = Kept()
        Relay(kept)
        val c = ceremony(kept)
        var phase = c.startInvitation().phase
        repeat(8) { phase = c.openRun(phase).phase }
        assertEquals(0, (phase as ClinicianCeremony.Phase.Waiting).attemptsLeft)
        val ended = c.openRun(phase).phase as ClinicianCeremony.Phase.Ended
        assertEquals(ClinicianCeremony.EndReason.CAPPED, ended.reason)
    }
}
