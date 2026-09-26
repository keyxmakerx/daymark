package com.daymark.companion

import com.daymark.companion.auth.DeviceSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A signed request whose body arrives slowly (#186): on a real Netty engine, with the request written
 * by hand, so its body can stop halfway — framed by Content-Length and held, or chunked with the last
 * chunk held — exactly as a person on the path could send it. The server's clock is the test's. A
 * request the server is still holding open after [HELD_MS] is one waiting for the rest of its body.
 */
class HeldRequestTest {

    private val unauthorized = 401 to """{"error":"unauthorized"}"""
    private val body = ByteArray(16) { (it * 7 + 3).toByte() }
    private val target = "/v1/snapshots/devA/1"

    /** The first [n] bytes of [body], or the rest after them, framed for a chunked body or not. */
    private fun part(chunked: Boolean, from: Int, to: Int, last: Boolean): ByteArray {
        val bytes = body.copyOfRange(from, to)
        return if (!chunked) bytes else LiveServer.chunk(bytes) + (if (last) LiveServer.LAST_CHUNK else ByteArray(0))
    }

    /**
     * Send [headers] for a PUT of [body] and stop after four bytes. A server that refuses without the
     * rest of the body has answered within [HELD_MS]; one still holding the request open then has its
     * clock set to [then] and the body finished: the server's answer, whenever it came.
     */
    private fun heldUntil(live: LiveServer, headers: Map<String, String>, chunked: Boolean, then: Long): RawResponse =
        live.open().use { c ->
            c.write(LiveServer.head("PUT", target, headers, chunked, body.size.toLong()))
            c.write(part(chunked, 0, 4, last = false))
            c.answerWithin(HELD_MS) ?: run {
                live.server.now = then
                c.writeIfOpen(part(chunked, 4, body.size, last = true))
                c.readResponse()
            }
        }

    @Test
    fun `a captured request replayed with its body held past its window is refused`() {
        for (chunked in listOf(false, true)) {
            val server = DeviceServer()
            server.startNetty().use { live ->
                val phone = TestPhone()
                live.pair(phone)
                val sentAt = server.now
                val captured = phone.headers("PUT", target, body, server.seconds)
                assertEquals(201, live.send("PUT", target, captured, body).status, "control: the phone's own request is taken")

                // Sent again 100 s later, its body finished a millisecond after the window closed.
                server.now = sentAt + 100_000
                val replay = heldUntil(live, captured, chunked, then = sentAt + DeviceSignature.WINDOW_MS + 1)
                // Chunked, it is refused for not stating its length, before its key or nonce is looked at.
                val refused = if (chunked) 411 to """{"error":"a signed request must state its length"}""" else unauthorized
                assertEquals(refused, replay.status to replay.body, if (chunked) "chunked, the last chunk held" else "the body held")
            }
        }
    }

    @Test
    fun `a phone revoked while its request's body is on the way is refused, and nothing is stored`() {
        val server = DeviceServer()
        server.startNetty().use { live ->
            val phone = TestPhone()
            live.pair(phone)
            val headers = phone.headers("PUT", target, body, server.seconds)
            live.open().use { c ->
                c.write(LiveServer.head("PUT", target, headers, chunked = false, length = body.size.toLong()))
                c.write(body.copyOfRange(0, 4))
                // The server holds the request open, waiting for the rest of its body.
                assertNull(c.answerWithin(HELD_MS), "the server waits for the body")
                assertEquals(204, live.owner("POST", "/v1/devices/${phone.keyId}/revoke").status, "the console's Revoke")
                c.write(body.copyOfRange(4, body.size))
                val answer = c.readResponse()
                assertEquals(unauthorized, answer.status to answer.body)
            }
            val stored = live.owner("GET", "/v1/snapshots/devA")
            assertEquals(200 to """{"lineage":"devA","versions":[]}""", stored.status to stored.body, "nothing was stored")
        }
    }

    /** A PUT by [phone] of [bytes], framed by Content-Length or chunked: the answer, early or after the body. */
    private fun put(live: LiveServer, phone: TestPhone, bytes: ByteArray, chunked: Boolean): RawResponse = live.open().use { c ->
        val headers = phone.headers("PUT", target, bytes, live.server.seconds)
        c.write(LiveServer.head("PUT", target, headers, chunked, bytes.size.toLong()))
        c.answerWithin(HELD_MS) ?: run {
            c.writeIfOpen(if (chunked) LiveServer.chunk(bytes) + LiveServer.LAST_CHUNK else bytes)
            c.readResponse()
        }
    }

    @Test
    fun `a body too large, or of a length it does not state, gets the same answer whatever key it names`() {
        val server = DeviceServer(maxRequestBytes = 4_096)
        server.startNetty().use { live ->
            val paired = TestPhone()
            live.pair(paired)
            val stranger = TestPhone() // a key nobody paired
            val tooLarge = ByteArray(4_097) { it.toByte() }
            for (chunked in listOf(false, true)) {
                val byPaired = put(live, paired, tooLarge, chunked)
                val byStranger = put(live, stranger, tooLarge, chunked)
                assertEquals(byPaired, byStranger, "chunked=$chunked: a live key and a key nobody paired get the same answer")
            }
            assertEquals(RawResponse(413, """{"error":"request body too large"}"""), put(live, stranger, tooLarge, chunked = false))
            // A signed body that does not state its length is refused whatever its size and its key.
            val small = ByteArray(16) { 1 }
            assertEquals(RawResponse(411, """{"error":"a signed request must state its length"}"""), put(live, paired, small, chunked = true))
            // Controls: the paired phone's small body, stating its length, is taken; the stranger's is refused.
            assertEquals(201, put(live, paired, small, chunked = false).status)
            assertEquals(RawResponse(401, """{"error":"unauthorized"}"""), put(live, stranger, small, chunked = false))
        }
    }

    @Test
    fun `a request whose body is finished after its window is refused, whatever its nonce`() {
        val server = DeviceServer()
        server.startNetty().use { live ->
            val phone = TestPhone()
            live.pair(phone)
            val sentAt = server.now
            // Never sent before: its nonce is fresh. Begun inside its window, finished outside it.
            val held = phone.headers("PUT", target, body, server.seconds)
            server.now = sentAt + 250_000
            val late = heldUntil(live, held, chunked = false, then = sentAt + DeviceSignature.WINDOW_MS + 1)
            assertEquals(unauthorized, late.status to late.body)

            // Control: the same kind of request, finished inside its window, is taken.
            val fresh = phone.headers("PUT", "/v1/snapshots/devA/2", body, server.seconds)
            val prompt = live.send("PUT", "/v1/snapshots/devA/2", fresh, body)
            assertEquals(201, prompt.status, prompt.body)
        }
    }

    @Test
    fun `a body held back gets no answer before it is whole, whatever key the request names, and then the same one`() {
        val server = DeviceServer()
        server.startNetty().use { live ->
            val paired = TestPhone()
            live.pair(paired)
            val revoked = TestPhone()
            live.pair(revoked)
            assertEquals(204, live.owner("POST", "/v1/devices/${revoked.keyId}/revoke").status)
            val stranger = TestPhone() // a key nobody paired

            // Each request is forged: signed by the stranger's key, naming the key given.
            val named = mapOf("a live key" to paired.keyId, "a revoked key" to revoked.keyId, "a key nobody paired" to stranger.keyId)
            val answers = named.mapValues { (what, keyId) ->
                val forged = stranger.headers("PUT", target, body, server.seconds) + (DeviceSignature.KEY_HEADER to keyId)
                live.open().use { c ->
                    c.write(LiveServer.head("PUT", target, forged, chunked = false, length = body.size.toLong()))
                    c.write(body.copyOfRange(0, 4))
                    assertNull(c.answerWithin(HELD_MS), "$what: no answer while the body is held back")
                    c.write(body.copyOfRange(4, body.size))
                    c.readResponse()
                }
            }
            assertEquals(named.mapValues { RawResponse(401, """{"error":"unauthorized"}""") }, answers, "the same answer for each, once its body is whole")

            // Control: the live key's own request, its body whole, is taken.
            assertEquals(201, live.send("PUT", target, paired.headers("PUT", target, body, server.seconds), body).status)
        }
    }

    @Test
    fun `a request whose own time is outside the window is refused before its body is read`() {
        val server = DeviceServer()
        server.startNetty().use { live ->
            val phone = TestPhone()
            live.pair(phone)
            for (offset in listOf(DeviceSignature.WINDOW_SECONDS + 1, -(DeviceSignature.WINDOW_SECONDS + 1))) {
                val headers = phone.headers("PUT", target, body, server.seconds + offset)
                live.open().use { c ->
                    c.write(LiveServer.head("PUT", target, headers, chunked = false, length = body.size.toLong()))
                    c.write(body.copyOfRange(0, 4))
                    assertEquals(RawResponse(401, """{"error":"unauthorized"}"""), c.answerWithin(HELD_MS), "$offset s: answered with its body held back")
                }
            }
            // Control: the same request timed now waits for its body, and is taken once it is whole.
            live.open().use { c ->
                c.write(LiveServer.head("PUT", target, phone.headers("PUT", target, body, server.seconds), chunked = false, length = body.size.toLong()))
                c.write(body.copyOfRange(0, 4))
                assertNull(c.answerWithin(HELD_MS), "timed now, it waits for its body")
                c.write(body.copyOfRange(4, body.size))
                assertEquals(201, c.readResponse().status)
            }
        }
    }

    private companion object {
        /** How long a request is held open, part of its body sent, before the test takes it that the server waits for the rest. */
        const val HELD_MS = 1_000L
    }
}
