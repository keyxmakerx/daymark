package com.daymark.synccrypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A server this phone is paired with (#432): its address, `https://` and a host with no trailing
 * slash, as [PairingVerdict] reads it, and the key the server registered for this phone. Every request
 * goes to the address followed by a target from `/v1/`, and the target is what is signed.
 */
class PairedServer(val address: String, val key: DeviceKey) {
    override fun toString(): String = "PairedServer($address, ${key.keyId})"
}

/** The phone's clocks and its pause between polls, as the protocol reads them (#432). */
interface PhoneClock {
    /** Wall time, in milliseconds: what every request's X-Device-Time is made from. */
    fun nowMillis(): Long

    /** A clock that never jumps, in milliseconds: what the poll's pace and its end are measured on. */
    fun elapsedMillis(): Long

    /** Waits [millis]. A wait called off throws [InterruptedException], which ends the poll. */
    @Throws(InterruptedException::class)
    fun pause(millis: Long)

    companion object {
        /** The device's own. */
        val SYSTEM: PhoneClock = object : PhoneClock {
            override fun nowMillis(): Long = System.currentTimeMillis()
            override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
            override fun pause(millis: Long) = Thread.sleep(millis)
        }
    }
}

/**
 * Every signed request the phone sends (docs/SYNC_PROTOCOL.md §2.1): signed by [server]'s key over the
 * target as sent, at the phone's time, under a nonce drawn for that request and no other, so a request
 * sent again is a new request. The URL is the address and the target, and the target is what is signed:
 * behind a proxy that takes a prefix off the address, that is the target the server receives.
 */
internal class SignedRequests(private val server: PairedServer, private val transport: Transport, private val clock: PhoneClock) {

    fun get(target: String): TransportAnswer = send("GET", target, ByteArray(0), emptyMap())

    fun put(target: String, body: ByteArray): TransportAnswer =
        send("PUT", target, body, mapOf("Content-Type" to "application/octet-stream"))

    /** A JSON body, or none at all when [json] is null. */
    fun post(target: String, json: ByteArray?): TransportAnswer =
        if (json == null) send("POST", target, ByteArray(0), emptyMap())
        else send("POST", target, json, mapOf("Content-Type" to "application/json"))

    private fun send(method: String, target: String, body: ByteArray, plain: Map<String, String>): TransportAnswer {
        val headers = LinkedHashMap<String, String>(plain)
        headers.putAll(server.key.signRequest(method, target, body, clock.nowMillis() / 1000))
        return transport.send(method, server.address + target, headers, body)
    }
}

/** The small JSON answers the phone reads, read strictly and never shown. */
internal object Answers {
    private val WHOLE_NUMBER = Regex("0|[1-9][0-9]{0,17}")

    /** The body as one JSON object, or null for anything else, bytes that are not UTF-8 included. */
    fun jsonObject(body: ByteArray): Map<*, *>? {
        val text = utf8(body) ?: return null
        return try {
            StrictJson.parse(text) as? Map<*, *>
        } catch (_: StrictJson.JsonException) {
            null
        }
    }

    /** The body as text, when it is UTF-8 and nothing else. */
    fun utf8(body: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** A member that is a whole number, 0 or more, written as one: no sign, fraction or exponent. */
    fun wholeNumber(value: Any?): Long? {
        val number = value as? StrictJson.Number ?: return null
        return if (WHOLE_NUMBER.matches(number.lexeme)) number.lexeme.toLong() else null
    }
}
