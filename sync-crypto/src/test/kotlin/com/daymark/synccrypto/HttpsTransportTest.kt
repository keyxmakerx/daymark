package com.daymark.synccrypto

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections

/**
 * [HttpsTransport], the code the phone sends with, against a local HTTP server that records exactly what
 * arrives (#432). The phone's own constructor opens only https; here the https URL is opened at the local
 * server's http address instead, which is the one thing the test changes, after the transport's own
 * https check has run.
 */
class HttpsTransportTest {

    private class Arrived(val method: String, val path: String, val headers: Map<String, List<String>>, val body: ByteArray)

    private val arrived = Collections.synchronizedList(ArrayList<Arrived>())
    private val local: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }
    private val port = local.address.port
    private var reply: (HttpExchange) -> Unit = { it.sendResponseHeaders(204, -1) }

    /** Opens the phone's https URL at the local server, path and query as they are. */
    private val transport = HttpsTransport { url -> URI("http://127.0.0.1:$port${url.file}").toURL().openConnection() as HttpURLConnection }

    private fun handle(exchange: HttpExchange) {
        val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }
        arrived += Arrived(exchange.requestMethod, exchange.requestURI.rawPath, headers, exchange.requestBody.readBytes())
        // Every request of the phone's asks for its connection to be closed after it. Android's client
        // honours that; the JDK's drops the header as one it restricts, so the answer says it instead,
        // and no test here sends a request over a connection an earlier one left open.
        exchange.responseHeaders.add("Connection", "close")
        reply(exchange)
        exchange.close()
    }

    @After
    fun stop() = local.stop(0)

    @Test
    fun anAddressThatIsNotHttpsIsRefusedBeforeAnyConnection() {
        for (url in listOf("http://127.0.0.1:$port/v1/devices/redeem", "HTTP://127.0.0.1:$port/v1/x", "ftp://127.0.0.1/v1/x", "127.0.0.1:$port/v1/x")) {
            try {
                transport.send("POST", url, emptyMap(), "{}".toByteArray())
                fail("$url was sent")
            } catch (_: IOException) {
                // refused
            }
        }
        assertEquals("a refused URL reached the server", 0, arrived.size)
        // The control: the same request to an https URL arrives.
        assertEquals(204, transport.send("POST", "https://daymark.test/v1/devices/redeem", emptyMap(), "{}".toByteArray()).status)
        assertEquals(1, arrived.size)
    }

    @Test
    fun aBodyGoesWithItsLength_neverChunked() {
        val body = ByteArray(70_000) { (it % 251).toByte() }
        transport.send("PUT", "https://daymark.test/v1/snapshots/phone_x/0", mapOf("Content-Type" to "application/octet-stream"), body)
        val put = arrived.single()
        assertEquals("PUT", put.method)
        assertEquals(listOf(body.size.toString()), put.headers["content-length"])
        assertNull("the body was sent chunked", put.headers["transfer-encoding"])
        assertArrayEquals(body, put.body)
        // A request with no body sends none, and states no chunking either.
        transport.send("GET", "https://daymark.test/v1/keydoc", emptyMap(), ByteArray(0))
        val get = arrived[1]
        assertEquals(0, get.body.size)
        assertNull(get.headers["transfer-encoding"])
    }

    @Test
    fun theRequestCarriesTheHeadersGiven_andNoConditionalOneOfItsOwn() {
        val given = mapOf(
            "X-Device-Key" to "DpNOAhg_l-DNg60Q1sS-Wg",
            "X-Device-Time" to "1790000000",
            "X-Device-Nonce" to "YGFiY2RlZmdoaWprbG1ubw",
            "X-Device-Signature" to "sig",
        )
        repeat(2) { transport.send("GET", "https://daymark.test/v1/keydoc", given, ByteArray(0)) }
        for (request in arrived) {
            for ((name, value) in given) assertEquals(name, listOf(value), request.headers[name.lowercase()])
            for (conditional in listOf("if-none-match", "if-match", "if-modified-since", "x-rel-token", "x-setting-key", "x-share-meta")) {
                assertNull("$conditional joined the request by itself", request.headers[conditional])
            }
            assertEquals("/v1/keydoc", request.path)
        }
    }

    @Test
    fun aRedirectIsNeverFollowed() {
        reply = { exchange ->
            if (exchange.requestURI.rawPath.startsWith("/v1/")) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/elsewhere")
                exchange.sendResponseHeaders(if (exchange.requestMethod == "GET") 302 else 307, -1)
            } else {
                exchange.sendResponseHeaders(204, -1)
            }
        }
        // A GET is the request a client follows a redirect for by default; a POST carrying a code is the
        // one it would cost most to follow.
        assertEquals(302, transport.send("GET", "https://daymark.test/v1/keydoc", emptyMap(), ByteArray(0)).status)
        val answer = transport.send("POST", "https://daymark.test/v1/devices/redeem", mapOf("Content-Type" to "application/json"), "{\"code\":\"x\"}".toByteArray())
        assertEquals(307, answer.status)
        assertEquals(listOf("/v1/keydoc", "/v1/devices/redeem"), arrived.map { it.path })
    }

    @Test
    fun anAnswerIsReadWhateverItsStatus_withItsHeaders() {
        reply = { exchange ->
            val body = """{"error":"no such pairing code"}""".toByteArray()
            exchange.responseHeaders.add("ETag", "\"abc\"")
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.write(body)
        }
        val answer = transport.send("POST", "https://daymark.test/v1/devices/redeem", emptyMap(), "{}".toByteArray())
        assertEquals(404, answer.status)
        assertEquals("""{"error":"no such pairing code"}""", String(answer.body))
        assertEquals("\"abc\"", answer.header("etag"))
        assertEquals("\"abc\"", answer.header("ETag"))
    }

    @Test
    fun anAnswerLongerThanThePhoneReadsIsNoAnswer() {
        reply = { exchange ->
            exchange.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024)
            repeat(HttpsTransport.MAX_ANSWER_BYTES / chunk.size + 1) { exchange.responseBody.write(chunk) }
        }
        try {
            transport.send("GET", "https://daymark.test/v1/keydoc", emptyMap(), ByteArray(0))
            fail("an answer over the cap was read")
        } catch (_: IOException) {
            // refused
        }
        // The control: one exactly at the cap is read.
        reply = { exchange ->
            exchange.sendResponseHeaders(200, HttpsTransport.MAX_ANSWER_BYTES.toLong())
            exchange.responseBody.write(ByteArray(HttpsTransport.MAX_ANSWER_BYTES))
        }
        assertEquals(HttpsTransport.MAX_ANSWER_BYTES, transport.send("GET", "https://daymark.test/v1/keydoc", emptyMap(), ByteArray(0)).body.size)
    }

    @Test
    fun thePhonesOwnConstructorRefusesHttpToo() {
        val phone = HttpsTransport()
        try {
            phone.send("GET", "http://127.0.0.1:$port/v1/keydoc", emptyMap(), ByteArray(0))
            fail("http was sent")
        } catch (_: IOException) {
            // refused
        }
        assertEquals(0, arrived.size)
    }
}
