package com.daymark.companion

import com.daymark.companion.auth.DeviceSignature
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom

/**
 * A phone, as the tests play one (#186, #189): a fresh Ed25519 key and the signing DeviceSignature
 * describes, done independently of the server's own checks so that a test signs what a phone would.
 */
class TestPhone(val seed: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {
    val publicKey: ByteArray = ByteArray(DeviceSignature.PUBLIC_KEY_BYTES).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
    val publicKeyB64: String = DeviceSignature.b64url(publicKey)
    val keyId: String = DeviceSignature.keyIdOf(publicKey)

    fun sign(message: ByteArray): ByteArray =
        ByteArray(DeviceSignature.SIGNATURE_BYTES).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }

    /** The proof a redemption of [code] carries: the signature over the code's id and this key. */
    fun redeemProof(code: String): String =
        DeviceSignature.b64url(sign(DeviceSignature.redeemMessage(DeviceSignature.codeIdOf(code), publicKeyB64)))

    /** The JSON body of a redemption of [code] by this phone. */
    fun redeemBody(code: String, proof: String = redeemProof(code)): String =
        """{"code":"$code","publicKey":"$publicKeyB64","signature":"$proof"}"""

    /** The four headers a request to [target] with [body] carries, signed at [timeSeconds] with [nonce]. */
    fun headers(
        method: String,
        target: String,
        body: ByteArray = ByteArray(0),
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        nonce: String = freshNonce(),
    ): Map<String, String> {
        val time = timeSeconds.toString()
        val message = DeviceSignature.requestMessage(method, target, DeviceSignature.bodyHash(body), time, nonce)
        return mapOf(
            DeviceSignature.KEY_HEADER to keyId,
            DeviceSignature.TIME_HEADER to time,
            DeviceSignature.NONCE_HEADER to nonce,
            DeviceSignature.SIGNATURE_HEADER to DeviceSignature.b64url(sign(message)),
        )
    }

    companion object {
        private val rng = SecureRandom()

        fun freshNonce(): String = DeviceSignature.b64url(ByteArray(DeviceSignature.NONCE_BYTES).also { rng.nextBytes(it) })
    }
}

/** Attach [headers], a signed request's, to this request. */
fun HttpRequestBuilder.signedWith(headers: Map<String, String>) {
    headers.forEach { (name, value) -> header(name, value) }
}
