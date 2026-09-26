package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Sign

/**
 * The phone's own key for its Companion server (#186, #189, #432): a fresh Ed25519 pair, what it signs
 * and the words it shows.
 *
 * - FRESH, NEVER DERIVED. [generate] draws the pair from libsodium's CSPRNG, in memory. It is not
 *   derived from the owner's master: a stolen master must not also be server access, and revoking a
 *   device must never touch the owner's pairing identity (#186). The phone keeps the key only once the
 *   registration poll answers `registered` (#432).
 * - ITS ID ([keyId]), which every signed request names, is base64url of BLAKE2b-128 of the 32-byte public
 *   key: 22 characters, as the server's `DeviceSignature.keyIdOf` makes it.
 * - A REQUEST ([signRequest]) is signed over [DeviceSignature]'s eleven lines. Its nonce is 16 bytes from
 *   libsodium's CSPRNG, drawn by that call and no other, so every attempt, a retry included, is a new
 *   request with a new nonce. No public call takes a nonce.
 * - A REDEMPTION ([redeemProof]) carries the signature over the code's id and this public key, which
 *   proves the phone holds the key it asks to have registered, for that code and no other.
 * - ITS WORDS ([words]) are the six the person compares with the console's ([DeviceWords]).
 *
 * The secret key never leaves this object: no property returns it and [toString] shows only the key id,
 * which is not a secret. [sodium] is the shared abstract [LazySodium], as in [SyncCrypto]:
 * lazysodium-java in the tests, lazysodium-android on the phone.
 */
class DeviceKey private constructor(
    private val sodium: LazySodium,
    publicKey: ByteArray,
    private val secretKey: ByteArray,
) {
    private val publicKeyBytes: ByteArray = publicKey.copyOf()

    /** The 32-byte Ed25519 public key; a copy. */
    val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    /** The public key as a redemption carries it: base64url without padding, 43 characters. */
    val publicKeyB64: String = SyncCrypto.toBase64(publicKeyBytes)

    /** The key's id, which every signed request names. */
    val keyId: String = keyIdOf(sodium, publicKeyBytes)

    /**
     * The headers a request must carry to be signed by this key, at [timeSeconds] of the phone's clock:
     * the four of [DeviceSignature.HEADERS], in that order, then each of [signedHeaders] once, under the
     * server's spelling of its name. The phone sends exactly these and adds none of
     * [DeviceSignature.SIGNED_HEADERS] of its own.
     *
     * [target] is the request target as it is sent to the server's `/v1`, query included. [body] is the
     * whole body, whose length the request states; an empty array for none. [signedHeaders] are the
     * signed headers the request carries, if any, each once and never empty. A request the server would
     * read differently from how it was signed is not signed: [DeviceSignatureException].
     */
    fun signRequest(
        method: String,
        target: String,
        body: ByteArray,
        timeSeconds: Long,
        signedHeaders: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> =
        signRequestWithNonce(method, target, body, timeSeconds, signedHeaders, SyncCrypto.toBase64(sodium.randomBytesBuf(DeviceSignature.NONCE_BYTES)))

    /** [signRequest] under a nonce the caller gives, so the vector can be reproduced. Never for a request that is sent. */
    internal fun signRequestWithNonce(
        method: String,
        target: String,
        body: ByteArray,
        timeSeconds: Long,
        signedHeaders: List<Pair<String, String>>,
        nonce: String,
    ): Map<String, String> {
        DeviceSignature.requireMethod(method)
        DeviceSignature.requireTarget(target)
        val time = DeviceSignature.timeLine(timeSeconds)
        val values = DeviceSignature.signedHeaderValues(signedHeaders)
        val message = DeviceSignature.requestMessage(method, target, bodyHash(sodium, body), time, nonce, values)
        val headers = LinkedHashMap<String, String>()
        headers[DeviceSignature.KEY_HEADER] = keyId
        headers[DeviceSignature.TIME_HEADER] = time
        headers[DeviceSignature.NONCE_HEADER] = nonce
        headers[DeviceSignature.SIGNATURE_HEADER] = SyncCrypto.toBase64(sign(message))
        for ((name, value) in DeviceSignature.SIGNED_HEADERS.zip(values)) {
            if (value != null) headers[name] = value
        }
        return headers
    }

    /** The proof a redemption of [code] carries, base64url: this key's signature over the code's id and this key. */
    fun redeemProof(code: PairingCode): String =
        SyncCrypto.toBase64(sign(DeviceSignature.redeemMessage(codeIdOf(sodium, code), publicKeyB64)))

    /** The six words this phone shows while the person compares them with the console's. */
    fun words(): List<String> = DeviceWords.of(sodium, publicKeyB64)

    override fun toString(): String = "DeviceKey($keyId)"

    private fun sign(message: ByteArray): ByteArray {
        val signature = ByteArray(Sign.BYTES)
        if (!sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)) {
            throw SyncCrypto.SyncCryptoException("Ed25519 signing failed")
        }
        return signature
    }

    companion object {
        /** A fresh key pair from libsodium's CSPRNG, derived from nothing. */
        fun generate(sodium: LazySodium): DeviceKey {
            val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
            val secretKey = ByteArray(Sign.SECRETKEYBYTES)
            if (!sodium.cryptoSignKeypair(publicKey, secretKey)) throw SyncCrypto.SyncCryptoException("Ed25519 key generation failed")
            return DeviceKey(sodium, publicKey, secretKey)
        }

        /** The key pair of a 32-byte seed, for the vector. A key the phone uses comes from [generate]. */
        internal fun fromSeed(sodium: LazySodium, seed: ByteArray): DeviceKey {
            require(seed.size == Sign.SEEDBYTES) { "an Ed25519 seed is ${Sign.SEEDBYTES} bytes" }
            val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
            val secretKey = ByteArray(Sign.SECRETKEYBYTES)
            if (!sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)) throw SyncCrypto.SyncCryptoException("Ed25519 key derivation failed")
            return DeviceKey(sodium, publicKey, secretKey)
        }

        /** A key's id: base64url of BLAKE2b-128 of the public key. */
        internal fun keyIdOf(sodium: LazySodium, publicKey: ByteArray): String =
            SyncCrypto.toBase64(blake2b(sodium, publicKey, DeviceSignature.KEY_ID_BYTES))

        /** The body's line: base64url of BLAKE2b-256 of its bytes. */
        internal fun bodyHash(sodium: LazySodium, body: ByteArray): String =
            SyncCrypto.toBase64(blake2b(sodium, body, DeviceSignature.HASH_BYTES))

        /** A pairing code's id: base64url of BLAKE2b-256 of `daymark-pairing-code-v1`, LF, and the code. */
        internal fun codeIdOf(sodium: LazySodium, code: PairingCode): String =
            SyncCrypto.toBase64(blake2b(sodium, DeviceSignature.codeIdMessage(code), DeviceSignature.HASH_BYTES))

        /** Unkeyed BLAKE2b (`crypto_generichash`) of [input], [outBytes] long. */
        internal fun blake2b(sodium: LazySodium, input: ByteArray, outBytes: Int): ByteArray {
            val out = ByteArray(outBytes)
            if (!sodium.cryptoGenericHash(out, out.size, input, input.size.toLong())) throw SyncCrypto.SyncCryptoException("BLAKE2b failed")
            return out
        }
    }
}
