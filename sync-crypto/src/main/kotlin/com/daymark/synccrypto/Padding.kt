package com.daymark.synccrypto

/**
 * Padding before encryption: the Kotlin mirror of `companion/web/src/lib/padding.ts`, which is the
 * reference and carries the rationale (#214, #315). It MUST produce byte-identical output; the
 * web's vectors are pinned in PaddingTest.
 *
 * The server sees the exact size of every item it stores, and a size is information derived from
 * content, so an item is padded on the device, inside the encryption, to one of a small set of
 * sizes: up to 1 MiB the next power of two, never less than 4 KiB; above 1 MiB the Padmé length,
 * never more than about 12% larger. Padding hides how much, never when.
 *
 * Layout (a wire contract, #316):
 *
 *   padded = u32 big-endian n || the n plaintext bytes || zero bytes
 *   size(padded) = paddedLength(4 + n)
 *
 * Unpadding is strict: the prefix fits, the total is exactly the bucket for that prefix, and every
 * byte after the plaintext is zero. Whatever carries a padded item also authenticates it, and
 * unpadding runs only after that, so strictness is not the security boundary: it makes a writer
 * that pads wrongly fail loudly instead of writing items only it can read.
 *
 * Lengths are [Long] here where the web has a JavaScript number, so that `4 + n` and its bucket
 * are exact for every size a [ByteArray] can have, and a length prefix of 2^31 or more reads as
 * the large unsigned number it is rather than as a negative [Int].
 */
object Padding {

    class PaddingException(message: String) : Exception(message)

    const val MIN_PADDED = 4096
    const val POWER_OF_TWO_LIMIT = 1 shl 20 // 1 MiB

    private const val PREFIX = 4

    /** The web's lengths are JavaScript safe integers; nothing outside that range is a length there. */
    private const val MAX_SAFE_INTEGER = (1L shl 53) - 1

    /** Characters in x's binary form, as the web counts them: 1 -> 1, 2 -> 2, 4 -> 3, and 0 -> 1. */
    private fun bitLength(x: Long): Int = x.toString(2).length

    /**
     * The Padmé length for [length] (Nikitin et al., "Reducing Metadata Leakage from Encrypted
     * Files and Communication with PURBs", PETS 2019): keep the top bits of its binary form and
     * round the rest up, so at most log2(log2(L)) + 1 significant bits remain.
     */
    fun padme(length: Long): Long {
        require(length in 1..MAX_SAFE_INTEGER) { "padme: bad length $length" }
        val e = bitLength(length) - 1 // floor(log2 L)
        val s = bitLength(e.toLong()) // floor(log2 E) + 1
        val lastBits = e - s
        if (lastBits <= 0) return length
        val step = 1L shl lastBits
        return (length + step - 1) / step * step
    }

    /** The size an item of [x] bytes (prefix included) is padded to. */
    fun paddedLength(x: Long): Long {
        require(x in 0..MAX_SAFE_INTEGER) { "paddedLength: bad length $x" }
        if (x <= MIN_PADDED) return MIN_PADDED.toLong()
        if (x <= POWER_OF_TWO_LIMIT) return 1L shl bitLength(x - 1)
        return padme(x)
    }

    /** Pad a plaintext before it is encrypted. */
    fun pad(plaintext: ByteArray): ByteArray {
        val n = plaintext.size
        val total = paddedLength(PREFIX + n.toLong())
        if (total > Int.MAX_VALUE) throw PaddingException("item too large to pad")
        val out = ByteArray(total.toInt()) // zero-filled
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        plaintext.copyInto(out, PREFIX)
        return out
    }

    /** Undo [pad]. Throws [PaddingException] on anything [pad] could not have produced. */
    fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < PREFIX) throw PaddingException("padded item too short")
        val n = ((padded[0].toLong() and 0xff) shl 24) or
            ((padded[1].toLong() and 0xff) shl 16) or
            ((padded[2].toLong() and 0xff) shl 8) or
            (padded[3].toLong() and 0xff)
        val end = PREFIX + n
        if (end > padded.size) throw PaddingException("padded item length prefix overruns the item")
        if (padded.size.toLong() != paddedLength(end)) throw PaddingException("padded item is not its bucket size")
        for (i in end.toInt() until padded.size) {
            if (padded[i] != 0.toByte()) throw PaddingException("padded item has non-zero padding")
        }
        return padded.copyOfRange(PREFIX, end.toInt())
    }
}
