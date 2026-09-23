package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Hash
import com.goterl.lazysodium.interfaces.Ristretto255

/**
 * Daymark Companion — CPace, the balanced PAKE for pairing (`docs/COMPANION_PAIRING.md` §3). Kotlin
 * mirror of `companion/web/src/lib/pairing/cpace.ts`; that file's header carries the design
 * rationale.
 *
 * CPACE-RISTRETTO255-SHA512, implemented directly against draft-irtf-cfrg-cpace and pinned
 * byte-for-byte to the CFRG working group's published test vectors in CpaceCryptoTest. The
 * TypeScript side is pinned to the same vectors, so the two implementations agree with each
 * other transitively — neither side is the reference for the other, the standard is the
 * reference for both. That agreement is what `docs/COMPANION_PAIRING.md` §3 rests on: a JVM party
 * (the phone, eventually) and a browser party derive the same key from the same short human code.
 *
 * Like the TS side, this needed lazysodium's Ristretto255 binding, which exists on the JVM
 * only from lazysodium-java 5.2.0 (the 5.1.0 artifact had none — the finding that reopened
 * the version pin). LazySodiumParityTest proves the android artifact carries the same surface.
 *
 * WRONG CODE ≠ ERROR: a mismatched code yields two different ISKs and no signal here. The
 * mismatch surfaces as an AEAD failure at the next layer, where a human decides what it means
 * — a wrong code is indistinguishable from an attacker's guess BY CONSTRUCTION, which is why
 * it must never burn an invite by itself (`docs/COMPANION_PAIRING.md` §6, §7).
 *
 * [sodium] is typed as the shared abstract [LazySodium] for the same reason SyncCrypto's is:
 * host tests run LazySodiumJava, the phone runs LazySodiumAndroid, and this class cannot tell.
 */
class CpaceCrypto(private val sodium: LazySodium) {

    class CpaceException(message: String) : Exception(message)

    data class StartResult(
        /** Secret scalar; keep until [finish], then discard. */
        val ya: ByteArray,
        /** lv_cat(Ya, ADa) — the bytes to relay to the other party. */
        val msgA: ByteArray,
    )

    data class RespondResult(
        /** lv_cat(Yb, ADb) — the bytes to relay back. */
        val msgB: ByteArray,
        /** The 64-byte intermediate session key. Equal on both sides iff PRS/CI/sid matched. */
        val isk: ByteArray,
    )

    /** Party A, step 1. [scalar] is for test vectors only — production omits it. */
    fun start(prs: ByteArray, ci: ByteArray, sid: ByteArray, ada: ByteArray, scalar: ByteArray? = null): StartResult {
        requireSid(sid)
        val g = calculateGenerator(prs, ci, sid)
        val ya = scalar ?: randomScalar()
        val yaPoint = scalarMultVfy(ya, g)
        return StartResult(ya, lvCat(yaPoint, ada))
    }

    /** Party B: consume MSGa, produce MSGb and the key. Throws on an invalid or identity point. */
    fun respond(prs: ByteArray, ci: ByteArray, sid: ByteArray, msgA: ByteArray, adb: ByteArray, scalar: ByteArray? = null): RespondResult {
        requireSid(sid)
        val yaPoint = parseLv(msgA, 2)[0]
        if (yaPoint.size != POINT_BYTES) throw CpaceException("bad point length")
        val g = calculateGenerator(prs, ci, sid)
        val yb = scalar ?: randomScalar()
        val ybPoint = scalarMultVfy(yb, g)
        // scalar_mult_vfy: fails when Ya is not a valid encoding or the product is the
        // identity — the draft's mandatory abort, not a condition to soften.
        val k = scalarMultVfy(yb, yaPoint)
        val msgB = lvCat(ybPoint, adb)
        return RespondResult(msgB, deriveIsk(sid, k, msgA, msgB))
    }

    /** Party A, final step: consume MSGb, produce the key. Throws on an invalid or identity point. */
    fun finish(sid: ByteArray, start: StartResult, msgB: ByteArray): ByteArray {
        requireSid(sid)
        val ybPoint = parseLv(msgB, 2)[0]
        if (ybPoint.size != POINT_BYTES) throw CpaceException("bad point length")
        val k = scalarMultVfy(start.ya, ybPoint)
        return deriveIsk(sid, k, start.msgA, msgB)
    }

    // ---- generator derivation (draft: calculate_generator) --------------------------------

    /**
     * The generator string. The zero-pad length puts DSI, PRS and the pad in exactly one
     * SHA-512 input block — the draft's defence against structure sharing between PRS values.
     */
    fun generatorString(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val lenZpad = maxOf(0, S_IN_BYTES - 1 - prependLen(prs).size - prependLen(DSI).size)
        return lvCat(DSI, prs, ByteArray(lenZpad), ci, sid)
    }

    /** hash-to-group: g = ristretto255_from_hash(SHA-512(generator_string)). */
    fun calculateGenerator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val hash = sha512(generatorString(prs, ci, sid))
        val point = ByteArray(POINT_BYTES)
        if (!(sodium as Ristretto255.Native).cryptoCoreRistretto255FromHash(point, hash)) {
            throw CpaceException("ristretto255_from_hash failed")
        }
        return point
    }

    // ---- ISK ------------------------------------------------------------------------------

    /**
     * ISK = SHA-512( lv_cat(DSI_ISK, sid, K) || transcript_ir ), transcript_ir being
     * MSGa || MSGb — the initiator/responder ordering. Our flow always has distinguishable
     * roles (the owner initiates, `docs/COMPANION_PAIRING.md` §3), so the parallel-execution ("oc")
     * transcript variant is deliberately not implemented.
     */
    private fun deriveIsk(sid: ByteArray, k: ByteArray, msgA: ByteArray, msgB: ByteArray): ByteArray =
        sha512(lvCat(DSI_ISK, sid, k) + msgA + msgB)

    // ---- primitives -----------------------------------------------------------------------

    private fun sha512(input: ByteArray): ByteArray {
        val out = ByteArray(64)
        if (!(sodium as Hash.Native).cryptoHashSha512(out, input, input.size.toLong())) {
            throw CpaceException("sha512 failed")
        }
        return out
    }

    private fun randomScalar(): ByteArray {
        val scalar = ByteArray(SCALAR_BYTES)
        (sodium as Ristretto255.Native).cryptoCoreRistretto255ScalarRandom(scalar)
        return scalar
    }

    private fun scalarMultVfy(scalar: ByteArray, point: ByteArray): ByteArray {
        val out = ByteArray(POINT_BYTES)
        if (!(sodium as Ristretto255.Native).cryptoScalarmultRistretto255(out, scalar, point)) {
            throw CpaceException("invalid point or identity result")
        }
        return out
    }

    private fun requireSid(sid: ByteArray) {
        if (sid.size != SID_BYTES) throw CpaceException("sid must be 16 bytes")
    }

    companion object {
        private val DSI = "CPaceRistretto255".toByteArray(Charsets.US_ASCII)
        private val DSI_ISK = "CPaceRistretto255_ISK".toByteArray(Charsets.US_ASCII)

        /** SHA-512's input block size — the zero-pad target for the generator string. */
        private const val S_IN_BYTES = 128
        const val SID_BYTES = 16
        const val POINT_BYTES = 32
        const val SCALAR_BYTES = 32
        const val ISK_BYTES = 64

        // ---- lv encoding (draft: prepend_len / lv_cat), exact mirror of cpace.ts ----------

        /** LEB128 length prefix + data. 7 bits per byte, least significant first. */
        fun prependLen(data: ByteArray): ByteArray {
            var length = data.size
            val enc = ArrayList<Byte>(2)
            while (true) {
                enc.add(if (length < 128) length.toByte() else ((length and 0x7f) + 0x80).toByte())
                length = length ushr 7
                if (length == 0) break
            }
            return enc.toByteArray() + data
        }

        /** Concatenation of length-prefixed items. */
        fun lvCat(vararg items: ByteArray): ByteArray {
            var out = ByteArray(0)
            for (item in items) out += prependLen(item)
            return out
        }

        /**
         * Parse exactly [count] lv items and require the input fully consumed — a message
         * with bytes to spare is refused, because unparsed bytes are where a protocol grows
         * a second meaning.
         */
        fun parseLv(data: ByteArray, count: Int): List<ByteArray> {
            val out = ArrayList<ByteArray>(count)
            var off = 0
            repeat(count) {
                var length = 0
                var shift = 0
                while (true) {
                    if (off >= data.size) throw CpaceException("truncated message")
                    val b = data[off++].toInt() and 0xff
                    length = length or ((b and 0x7f) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                    if (shift > 28) throw CpaceException("absurd length")
                }
                if (off + length > data.size) throw CpaceException("truncated message")
                out.add(data.copyOfRange(off, off + length))
                off += length
            }
            if (off != data.size) throw CpaceException("trailing bytes")
            return out
        }
    }
}
