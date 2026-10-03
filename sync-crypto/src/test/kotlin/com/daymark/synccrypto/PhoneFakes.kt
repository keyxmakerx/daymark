package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium

/**
 * A server for the phone's protocol tests (#432) that checks what the real one checks before it answers
 * anything a test scripts: every signed request is verified over the message the server rebuilds from
 * the request as it arrives (the target from the URL, the body's hash, the time, the nonce and the five
 * signed-header lines), against the keys it has been told of, and a nonce is taken once. What it saw,
 * and what it answered, is kept in [requests].
 */
internal class FakeServer(private val sodium: LazySodium, val address: String = ADDRESS) : Transport {

    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray) {
        /** The id of the known key whose signature over this request verified, with a nonce never seen before. */
        var signedBy: String? = null

        /** What was answered: a status, or -1 for no answer. */
        var status: Int = -1

        val isSigned: Boolean get() = DeviceSignature.HEADERS.any { it in headers }
    }

    val requests = ArrayList<Request>()
    private val keys = HashMap<String, ByteArray>()
    private val nonces = HashSet<String>()

    /** The answer to each request, once its signature has been checked. May throw an IOException for none. */
    var answer: (Request) -> TransportAnswer = { throw AssertionError("no answer scripted for ${it.method} ${it.target}") }

    /** Takes [publicKey]'s signatures from now on, as a registered or pending key's. */
    fun know(publicKey: ByteArray) {
        keys[DeviceKey.keyIdOf(sodium, publicKey)] = publicKey.copyOf()
    }

    fun targets(): List<String> = requests.map { "${it.method} ${it.target}" }

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray): TransportAnswer {
        if (!url.startsWith("$address/v1/")) throw AssertionError("a request to another address, or not under /v1/")
        val request = Request(method, url.substring(address.length), LinkedHashMap(headers), body.copyOf())
        requests += request
        request.signedBy = verify(request)
        val answered = answer(request)
        request.status = answered.status
        return answered
    }

    private fun verify(request: Request): String? {
        val keyId = request.headers[DeviceSignature.KEY_HEADER] ?: return null
        val time = request.headers[DeviceSignature.TIME_HEADER] ?: return null
        val nonce = request.headers[DeviceSignature.NONCE_HEADER] ?: return null
        val signature = request.headers[DeviceSignature.SIGNATURE_HEADER] ?: return null
        val publicKey = keys[keyId] ?: return null
        // The signed headers as the request carries them, whatever the case of their names.
        val values = DeviceSignature.SIGNED_HEADERS.map { name ->
            request.headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }
        val message = DeviceSignature.requestMessage(
            request.method,
            request.target,
            DeviceKey.bodyHash(sodium, request.body),
            time,
            nonce,
            values,
        )
        val bytes = SyncCrypto.fromBase64(signature)
        if (!sodium.cryptoSignVerifyDetached(bytes, message, message.size, publicKey)) return null
        if (!nonces.add(nonce)) return null
        return keyId
    }

    companion object {
        const val ADDRESS = "https://daymark.test"

        fun answer(status: Int, json: String = "", vararg headers: Pair<String, String>): TransportAnswer =
            TransportAnswer(status, headers.associate { it.first to listOf(it.second) }, json.toByteArray(Charsets.UTF_8))
    }
}

/** A clock a test moves: a pause moves both clocks by exactly its length, and can be called off. */
internal class FakeClock(var now: Long = 1_790_000_000_000L, var elapsed: Long = 50_000L) : PhoneClock {
    val pauses = ArrayList<Long>()

    /** The pause at this index (from 0) is called off, as a screen that closed calls it off. */
    var callOffPause: Int = Int.MAX_VALUE

    override fun nowMillis(): Long = now

    override fun elapsedMillis(): Long = elapsed

    override fun pause(millis: Long) {
        if (pauses.size == callOffPause) throw InterruptedException()
        pauses += millis
        now += millis
        elapsed += millis
    }
}
