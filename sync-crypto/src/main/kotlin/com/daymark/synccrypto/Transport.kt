package com.daymark.synccrypto

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * How the phone's side of the protocol reaches its server (#432): one request out, its whole answer
 * back. [PhonePairing] and [PhoneSync] speak only through it, so every rule they keep runs on a plain
 * JVM, against a transport a test controls and against the real server through [HttpsTransport].
 */
fun interface Transport {
    /**
     * Sends one request and reads its whole answer. [url] is the server's address followed by the
     * request target. The request carries [headers] exactly, and nothing that adds a header an owner
     * route acts on ([DeviceSignature.SIGNED_HEADERS]) by itself. A non-empty [body] is sent with its
     * length stated, never chunked; an empty one is sent as no body. Throws [IOException] when no
     * answer arrives, and then nothing is known about whether the request arrived.
     */
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray): TransportAnswer
}

/** One answer: its status, its headers, and its body. A header's name is found in any case. */
class TransportAnswer(val status: Int, headers: Map<String, List<String>>, val body: ByteArray) {
    private val byName: Map<String, List<String>> =
        headers.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, lists) -> lists.flatten() }

    /** The header's one value; null when the answer carries it not at all, or more than once. */
    fun header(name: String): String? = byName[name.lowercase()]?.singleOrNull()
}

/**
 * The phone's [Transport], over `HttpURLConnection`, which Android and the JVM both have (#432).
 *
 * - ONLY HTTPS. A URL that is not `https://` is refused with an [IOException] before any connection is
 *   made, whatever the caller checked: the pairing verdict declines an http address before anything is
 *   sent, and this is the depth behind it. A redirect is never followed. A signed request sent on to
 *   another URL is refused there and counts toward the lockout, and a redemption sent on hands its code
 *   to wherever the redirect points.
 * - EXACTLY THE HEADERS GIVEN. No response cache is used ([HttpURLConnection.setUseCaches] is false and
 *   nothing here installs one), so no `If-None-Match` or `If-Modified-Since` joins a request by itself:
 *   the server refuses a signed request carrying a header its signature does not cover, and the refusal
 *   counts toward the lockout of the phone's whole network address.
 * - A BODY STATES ITS LENGTH. It goes out in fixed-length streaming mode, with its `Content-Length`,
 *   never chunked, which a signed request may not be (docs/SYNC_PROTOCOL.md §2.1). A request with no
 *   body sends none. Every request asks for its connection to be closed after it, which Android's
 *   client honours, so no request is sent again by itself on a pooled connection that turned out to be
 *   stale: a signed request sent twice is refused the second time, for its nonce.
 * - TIMEOUTS: [CONNECT_TIMEOUT_MS] to connect, and [READ_TIMEOUT_MS] between the bytes of an answer.
 * - AN ANSWER is read to at most [MAX_ANSWER_BYTES]: every answer the phone reads is a small JSON object
 *   or nothing. A longer one is an [IOException].
 * - NOTHING IS LOGGED, and no exception from here repeats the URL, a header or a body.
 */
class HttpsTransport internal constructor(private val open: (URL) -> HttpURLConnection) : Transport {

    /** The phone's: every URL opened as the https connection it must be. */
    constructor() : this({ url -> url.openConnection() as HttpsURLConnection })

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray): TransportAnswer {
        if (!url.startsWith(HTTPS)) throw IOException(NOT_HTTPS)
        val connection = try {
            open(URI(url).toURL())
        } catch (_: URISyntaxException) {
            throw IOException(NOT_A_URL)
        } catch (_: IllegalArgumentException) {
            throw IOException(NOT_A_URL)
        } catch (_: ClassCastException) {
            throw IOException(NOT_HTTPS)
        }
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("Connection", "close")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body.isNotEmpty()) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size.toLong())
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status < 100) throw IOException(NOT_AN_ANSWER)
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val answer = stream?.use { readAtMost(it) } ?: ByteArray(0)
            val fields = HashMap<String, List<String>>()
            for ((name, values) in connection.headerFields) if (name != null) fields[name] = values
            return TransportAnswer(status, fields, answer)
        } finally {
            connection.disconnect()
        }
    }

    private fun readAtMost(stream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_ANSWER_BYTES) throw IOException(TOO_LONG)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_ANSWER_BYTES = 1024 * 1024

        private const val HTTPS = "https://"
        private const val NOT_HTTPS = "the phone sends only over https"
        private const val NOT_A_URL = "not a URL the phone sends to"
        private const val NOT_AN_ANSWER = "not an HTTP answer"
        private const val TOO_LONG = "an answer longer than the phone reads"
    }
}
