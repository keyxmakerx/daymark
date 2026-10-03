package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD

/**
 * The pairing envelope (docs/COMPANION_PAIRING.md §4 and §13.3): what the CPace key is for. Kotlin
 * mirror of `companion/web/src/lib/pairing/envelope.ts`, whose header carries the reasoning;
 * OwnerPairingVectorTest pins both directional keys and one sealed envelope to the bytes that file
 * produces.
 *
 * Two directional keys, BLAKE2b keyed with the whole 64-byte ISK over a versioned label, so an
 * envelope sealed one way cannot be reflected back and opened the other. The envelope is
 * `0x01 | nonce(24) | XChaCha20-Poly1305` under the AAD `daymark/pairing/env/v1|sid|direction`, so
 * a ciphertext moved between exchanges, versions or directions does not open even under the
 * right key.
 *
 * WHERE A WRONG CODE IS FINALLY SEEN. CPace says nothing on a mismatch; the first difference is
 * an envelope that does not open. [open] answers null for every failure, because an AEAD failure
 * is one bit: a typo and an attacker look the same, and only a person may decide what it means
 * (§7). Nothing here burns, reports or retries.
 */
class PairingEnvelope(private val sodium: LazySodium) {

    /** Owner to clinician, or clinician to owner. The two never share a key. */
    enum class Direction(val wire: String) {
        OWNER_TO_THERAPIST("owner-to-therapist"),
        THERAPIST_TO_OWNER("therapist-to-owner"),
    }

    /** The sealing key for one direction. Refuses an ISK of any length but 64. */
    fun directionKey(isk: ByteArray, direction: Direction): ByteArray {
        if (isk.size != ISK_BYTES) throw SyncCrypto.SyncCryptoException("ISK must be $ISK_BYTES bytes")
        val label = "daymark/pairing/key/v1|${direction.wire}".toByteArray(Charsets.UTF_8)
        val out = ByteArray(KEY_BYTES)
        if (!sodium.cryptoGenericHash(out, out.size, label, label.size.toLong(), isk, isk.size)) {
            throw SyncCrypto.SyncCryptoException("BLAKE2b failed")
        }
        return out
    }

    /** Seal one payload under a fresh nonce. */
    fun seal(isk: ByteArray, sidB64: String, direction: Direction, payload: ByteArray): ByteArray =
        sealWithNonce(isk, sidB64, direction, payload, sodium.randomBytesBuf(NONCE_BYTES))

    internal fun sealWithNonce(isk: ByteArray, sidB64: String, direction: Direction, payload: ByteArray, nonce: ByteArray): ByteArray {
        if (nonce.size != NONCE_BYTES) throw SyncCrypto.SyncCryptoException("nonce must be $NONCE_BYTES bytes")
        val key = directionKey(isk, direction)
        try {
            val aad = aad(sidB64, direction)
            val cipher = ByteArray(payload.size + TAG_BYTES)
            val cipherLen = LongArray(1)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                cipher, cipherLen, payload, payload.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
            )
            if (!ok) throw SyncCrypto.SyncCryptoException("AEAD encryption failed")
            return byteArrayOf(VERSION) + nonce + cipher.copyOf(cipherLen[0].toInt())
        } finally {
            key.fill(0)
        }
    }

    /** The payload, or null for every failure alike. */
    fun open(isk: ByteArray, sidB64: String, direction: Direction, envelope: ByteArray): ByteArray? {
        if (isk.size != ISK_BYTES) return null
        if (envelope.size < 1 + NONCE_BYTES + TAG_BYTES || envelope[0] != VERSION) return null
        val nonce = envelope.copyOfRange(1, 1 + NONCE_BYTES)
        val sealed = envelope.copyOfRange(1 + NONCE_BYTES, envelope.size)
        val key = directionKey(isk, direction)
        try {
            val aad = aad(sidB64, direction)
            val out = ByteArray(sealed.size - TAG_BYTES)
            val outLen = LongArray(1)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                out, outLen, null, sealed, sealed.size.toLong(), aad, aad.size.toLong(), nonce, key,
            )
            return if (ok) out.copyOf(outLen[0].toInt()) else null
        } finally {
            key.fill(0)
        }
    }

    private fun aad(sidB64: String, direction: Direction): ByteArray =
        "daymark/pairing/env/v1|$sidB64|${direction.wire}".toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION: Byte = 0x01
        const val ISK_BYTES = 64
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
        private const val TAG_BYTES = AEAD.XCHACHA20POLY1305_IETF_ABYTES
    }
}
