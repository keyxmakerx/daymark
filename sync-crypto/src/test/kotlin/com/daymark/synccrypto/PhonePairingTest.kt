package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The phone's half of pairing (#432), against [FakeServer], which checks every signature as the server
 * does. What each test pins: nothing is sent for an address or a code the phone can refuse itself; the
 * redemption is sent once; the poll asks about every two seconds, stops at the first refusal, and never
 * asks after its deadline; and only `registered` hands the key back to be kept.
 */
class PhonePairingTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val server = FakeServer(sodium)
    private val clock = FakeClock()
    private val pairing = PhonePairing(sodium, server, clock)

    /** A code with a right check symbol, as the console shows it. */
    private val code = "K7M4R-D96QA"

    private fun ready(): PhonePairing.Reading.Ready =
        pairing.read(FakeServer.ADDRESS, code) as? PhonePairing.Reading.Ready ?: throw AssertionError("the code was not read")

    /** Redeems at the fake, which takes the proof as the server does and answers 202 for [confirmIn] ms. */
    private fun redeemed(confirmIn: Long = PhonePairing.CONFIRM_WINDOW_MS): PendingPairing {
        server.answer = { request ->
            val fields = Answers.jsonObject(request.body)!!
            val publicKey = SyncCrypto.fromBase64(fields["publicKey"] as String)
            val codeId = DeviceKey.codeIdOf(sodium, PairingCode.parse(fields["code"] as String))
            val proof = SyncCrypto.fromBase64(fields["signature"] as String)
            val message = DeviceSignature.redeemMessage(codeId, fields["publicKey"] as String)
            assertTrue("the proof does not verify", sodium.cryptoSignVerifyDetached(proof, message, message.size, publicKey))
            server.know(publicKey)
            FakeServer.answer(202, """{"keyId":"${DeviceKey.keyIdOf(sodium, publicKey)}","confirmBy":${clock.now + confirmIn}}""")
        }
        val redemption = pairing.redeem(ready())
        return (redemption as? PhonePairing.Redemption.Waiting)?.pending ?: throw AssertionError("not waiting: $redemption")
    }

    private fun wordsOf(redemption: PhonePairing.Redemption) = (redemption as PhonePairing.Redemption.Stopped).words
    private fun wordsOf(registration: PhonePairing.Registration) = (registration as PhonePairing.Registration.Stopped).words

    @Test
    fun anHttpAddressIsDeclinedBeforeAnythingIsSent() {
        for (typed in listOf("http://daymark.test", "HTTP://daymark.test", " http://192.168.1.20:8080 ")) {
            val reading = pairing.read(typed, code)
            assertEquals(typed, PhoneWords.HTTP_ADDRESS, (reading as PhonePairing.Reading.Declined).words)
        }
        val pasted = "daymark-pair:v1?server=http%3A%2F%2Fdaymark.test&code=K7M4RD96QA"
        assertEquals(PhoneWords.HTTP_ADDRESS, (pairing.read(pasted, "") as PhonePairing.Reading.Declined).words)
        assertEquals("a request went out", 0, server.requests.size)
        // The control: the same address over https is read, and still nothing is sent by reading.
        val https = pairing.read("https://daymark.test", code) as PhonePairing.Reading.Ready
        assertEquals("https://daymark.test", https.address)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun anAddressThatIsNotOneIsToldHowToTypeIt() {
        for (typed in listOf("daymark.test", "https://me@daymark.test", "https://daymark.test/?x=1", "https://", "ftp://daymark.test")) {
            val reading = pairing.read(typed, code)
            assertEquals(typed, PhoneWords.NOT_AN_ADDRESS, (reading as PhonePairing.Reading.Declined).words)
        }
        for (pasted in listOf("daymark-pair:v2?server=https%3A%2F%2Fdaymark.test&code=K7M4RD96QA", "daymark-pair:v1?server=https://daymark.test&code=K7M4RD96QA")) {
            assertEquals(pasted, PhoneWords.NOT_PAIRING_TEXT, (pairing.read(pasted, "") as PhonePairing.Reading.Declined).words)
        }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun aCodeThatDoesNotCheckOutIsDeclinedBeforeAnythingIsSent() {
        val canonical = code.replace("-", "")
        // The check symbol changed to another symbol, derived from the code so the change is never a no-op.
        val last = canonical.last()
        val other = PairingCode.ALPHABET.first { it != last }
        val wrong = canonical.dropLast(1) + other
        assertNotEquals(canonical, wrong)
        for (typed in listOf(wrong, canonical.take(9), canonical + "2", "K7M4R-D96Q0", "")) {
            val reading = pairing.read(FakeServer.ADDRESS, typed)
            assertEquals(typed, PhoneWords.CODE_DOES_NOT_CHECK_OUT, (reading as PhonePairing.Reading.Declined).words)
        }
        assertEquals(0, server.requests.size)
        // The control: the code as the console shows it, and typed back in lower case, is read.
        assertTrue(pairing.read(FakeServer.ADDRESS, code) is PhonePairing.Reading.Ready)
        assertTrue(pairing.read(FakeServer.ADDRESS, code.lowercase()) is PhonePairing.Reading.Ready)
    }

    @Test
    fun pastedTextIsReadAsTypingItsTwoValuesIs() {
        val pasted = "daymark-pair:v1?server=https%3A%2F%2Fdaymark.test&code=K7M4RD96QA\n"
        val fromText = pairing.read(pasted, "whatever is in the code field") as PhonePairing.Reading.Ready
        val typed = ready()
        assertEquals(typed.address, fromText.address)
        assertEquals(typed.code, fromText.code)
    }

    @Test
    fun theRedemptionCarriesTheCodeANewKeyAndItsProof_once() {
        val pending = redeemed()
        assertEquals(listOf("POST /v1/devices/redeem"), server.targets())
        val sent = server.requests.single()
        assertEquals("application/json", sent.headers["Content-Type"])
        assertTrue("a redemption carries no signature headers", !sent.isSigned)
        val fields = Answers.jsonObject(sent.body)!!
        assertEquals("K7M4RD96QA", fields["code"])
        assertEquals(pending.key.publicKeyB64, fields["publicKey"])
        assertEquals(pending.key.words(), pending.words)
        assertEquals(6, pending.words.size)
        // A second redemption makes a second key: a key is never used for two codes.
        val second = redeemed()
        assertNotEquals(pending.key.keyId, second.key.keyId)
    }

    @Test
    fun aRedemptionTheServerDoesNotTakeIsSentOnceAndSaysSo() {
        val cases = listOf(
            404 to PhoneWords.CODE_NOT_TAKEN,
            400 to PhoneWords.CODE_NOT_TAKEN,
            429 to PhoneWords.PAUSED,
            500 to PhoneWords.UNREACHABLE,
            502 to PhoneWords.UNREACHABLE,
        )
        for ((status, words) in cases) {
            val before = server.requests.size
            server.answer = { FakeServer.answer(status, """{"error":"no such pairing code"}""") }
            assertEquals("$status", words, wordsOf(pairing.redeem(ready())))
            assertEquals("$status was sent again", before + 1, server.requests.size)
        }
        val before = server.requests.size
        server.answer = { throw IOException("no route") }
        assertEquals(PhoneWords.UNREACHABLE, wordsOf(pairing.redeem(ready())))
        assertEquals(before + 1, server.requests.size)
    }

    @Test
    fun anAnswerAboutAnotherKeyIsNoAnswerAboutThisOne() {
        val other = DeviceKey.generate(sodium)
        server.answer = { FakeServer.answer(202, """{"keyId":"${other.keyId}","confirmBy":${clock.now + 120_000}}""") }
        assertEquals(PhoneWords.CODE_NOT_TAKEN, wordsOf(pairing.redeem(ready())))
        server.answer = { FakeServer.answer(202, """{"keyId":"x"}""") }
        assertEquals(PhoneWords.CODE_NOT_TAKEN, wordsOf(pairing.redeem(ready())))
        server.answer = { FakeServer.answer(202, "not json") }
        assertEquals(PhoneWords.CODE_NOT_TAKEN, wordsOf(pairing.redeem(ready())))
    }

    @Test
    fun thePollAsksAboutEveryTwoSeconds_signed_untilRegistered() {
        val pending = redeemed()
        var polls = 0
        server.answer = { request ->
            assertEquals("GET /v1/devices/registration", "${request.method} ${request.target}")
            assertEquals("the poll is not signed by the pending key over what was sent", pending.key.keyId, request.signedBy)
            assertEquals((clock.now / 1000).toString(), request.headers[DeviceSignature.TIME_HEADER])
            polls++
            if (polls < 3) {
                FakeServer.answer(202, """{"state":"pending","confirmBy":${clock.now + 60_000}}""")
            } else {
                FakeServer.answer(200, """{"state":"registered"}""")
            }
        }
        val registration = pairing.awaitRegistration(pending)
        val registered = registration as? PhonePairing.Registration.Registered ?: throw AssertionError("not registered: $registration")
        assertEquals(pending.key.keyId, registered.server.key.keyId)
        assertEquals(FakeServer.ADDRESS, registered.server.address)
        assertEquals(3, polls)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), clock.pauses)
        val nonces = server.requests.drop(1).map { it.headers.getValue(DeviceSignature.NONCE_HEADER) }
        assertEquals("a nonce was used twice", nonces.size, nonces.toSet().size)
    }

    @Test
    fun thePollStopsAtTheFirstRefusal_andIsNotSentAgain() {
        for ((status, words) in listOf(401 to PhoneWords.TIME_RAN_OUT, 429 to PhoneWords.PAUSED, 403 to PhoneWords.CODE_NOT_TAKEN)) {
            val pending = redeemed()
            val before = server.requests.size
            server.answer = { FakeServer.answer(status, """{"error":"refused"}""") }
            assertEquals("$status", words, wordsOf(pairing.awaitRegistration(pending)))
            assertEquals("$status was asked again", before + 1, server.requests.size)
        }
        // A 200 that does not say registered is not taken as registered.
        val pending = redeemed()
        server.answer = { FakeServer.answer(200, """{"state":"pending"}""") }
        assertEquals(PhoneWords.CODE_NOT_TAKEN, wordsOf(pairing.awaitRegistration(pending)))
    }

    @Test
    fun thePollNeverAsksAfterItsDeadline() {
        val pending = redeemed()
        val redeemedAt = clock.elapsed
        val askedAt = ArrayList<Long>()
        server.answer = {
            askedAt += clock.elapsed - redeemedAt
            FakeServer.answer(202, """{"state":"pending","confirmBy":${pending.confirmBy}}""")
        }
        assertEquals(PhoneWords.TIME_RAN_OUT, wordsOf(pairing.awaitRegistration(pending)))
        // Two minutes from the redemption, less a second for a question still on its way: written out
        // here rather than read from the constants, so a change to either is a change this test sees.
        assertEquals((2_000L..118_000L step 2_000L).toList(), askedAt)
        assertTrue("it waited past its deadline", clock.elapsed - redeemedAt <= 119_000L)
        assertTrue(server.requests.drop(1).all { it.status == 202 })
    }

    @Test
    fun theLastSecondBeforeTheDeadlineIsNeverAskedIn() {
        // A window of 4.5 s: asked at 2 s; at 4 s only half a second would be left, inside the margin.
        val pending = redeemed(confirmIn = 4_500)
        val redeemedAt = clock.elapsed
        val askedAt = ArrayList<Long>()
        server.answer = {
            askedAt += clock.elapsed - redeemedAt
            FakeServer.answer(202, """{"state":"pending","confirmBy":${pending.confirmBy}}""")
        }
        assertEquals(PhoneWords.TIME_RAN_OUT, wordsOf(pairing.awaitRegistration(pending)))
        assertEquals(listOf(2_000L), askedAt)
    }

    @Test
    fun aPhoneWhoseClockRunsAheadGivesUpEarly_neverLate() {
        // The server says confirmBy 30 s from now by the phone's clock: a phone whose clock is 90 s
        // ahead of the server's reads its two minutes as thirty seconds, and asks for thirty.
        val pending = redeemed(confirmIn = 30_000)
        val redeemedAt = clock.elapsed
        server.answer = { FakeServer.answer(202, """{"state":"pending","confirmBy":${pending.confirmBy}}""") }
        assertEquals(PhoneWords.TIME_RAN_OUT, wordsOf(pairing.awaitRegistration(pending)))
        assertTrue(clock.elapsed <= redeemedAt + 30_000 - PhonePairing.LAST_ASK_MARGIN_MS)
        assertEquals(14, server.requests.size - 1)
        // And a confirmBy already past asks nothing at all.
        val lapsed = redeemed(confirmIn = -5_000)
        val before = server.requests.size
        assertEquals(PhoneWords.TIME_RAN_OUT, wordsOf(pairing.awaitRegistration(lapsed)))
        assertEquals(before, server.requests.size)
    }

    @Test
    fun aPollWithNoAnswerIsAskedAgain_andEndsSayingSo() {
        val pending = redeemed()
        server.answer = { throw IOException("no route") }
        assertEquals(PhoneWords.UNREACHABLE, wordsOf(pairing.awaitRegistration(pending)))
        assertTrue("it gave up at the first unanswered poll", server.requests.size > 10)
        // A 5xx is the server not answering for itself either: asked again, and said the same way.
        val again = redeemed()
        server.answer = { FakeServer.answer(503) }
        assertEquals(PhoneWords.UNREACHABLE, wordsOf(pairing.awaitRegistration(again)))
        // And an unanswered poll followed by pending ends as the time running out.
        val third = redeemed()
        var n = 0
        server.answer = { if (n++ == 0) throw IOException("blip") else FakeServer.answer(202, """{"state":"pending","confirmBy":1}""") }
        assertEquals(PhoneWords.TIME_RAN_OUT, wordsOf(pairing.awaitRegistration(third)))
    }

    @Test
    fun aPauseCalledOffEndsThePoll_withNothingHandedBack() {
        val pending = redeemed()
        server.answer = { FakeServer.answer(202, """{"state":"pending","confirmBy":${pending.confirmBy}}""") }
        clock.callOffPause = 2
        try {
            pairing.awaitRegistration(pending)
            fail("the poll went on after its pause was called off")
        } catch (_: InterruptedException) {
            // what a screen that closed sees
        }
        assertEquals("two questions, then the pause was called off", 3, server.requests.size)
    }
}
