package com.daymark.companion

import com.daymark.companion.auth.DeviceSignature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A signed request whose body arrives slowly (#186): on a real Netty engine, with the request written
 * by hand, so its body can stop halfway — framed by Content-Length and held, or chunked with the last
 * chunk held — exactly as a person on the path could send it. The server's clock is the test's, and
 * [DeviceServer.onClockRead] says when the server has read the request's time.
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
     * Send [headers] for a PUT of [body], stop after four bytes until the server has read the request's
     * time, then set the clock to [then] and finish the body: the server's answer, whenever it came.
     */
    private fun heldUntil(live: LiveServer, headers: Map<String, String>, chunked: Boolean, then: Long): RawResponse {
        val sawTime = CountDownLatch(1)
        live.server.onClockRead = { sawTime.countDown() }
        live.open().use { c ->
            c.write(LiveServer.head("PUT", target, headers, chunked, body.size.toLong()))
            c.write(part(chunked, 0, 4, last = false))
            assertTrue(sawTime.await(10, TimeUnit.SECONDS), "the server read the request's time")
            live.server.onClockRead = null
            live.server.now = then
            // A server that refused without the rest of the body has answered already.
            return c.answerWithin(300) ?: run {
                c.writeIfOpen(part(chunked, 4, body.size, last = true))
                c.readResponse()
            }
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
                assertEquals(unauthorized, replay.status to replay.body, if (chunked) "chunked, the last chunk held" else "the body held")
            }
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
}
