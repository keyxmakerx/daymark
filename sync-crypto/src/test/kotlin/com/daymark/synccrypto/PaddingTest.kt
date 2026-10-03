package com.daymark.synccrypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [Padding] against the reference, `companion/web/src/lib/padding.test.ts`: the same bucket rule,
 * the same boundaries, the same refusals and the same vectors (#316). A change to any expected
 * value below is a change to the wire format, never a refactor.
 */
class PaddingTest {

    private val mib = 1L shl 20

    /** A length the padding could have produced for some input: the buckets, and nothing else. */
    private fun isBucket(len: Long): Boolean = len >= Padding.MIN_PADDED && Padding.paddedLength(len) == len

    private fun bytes(n: Int, seed: Int = 7): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun assertRefused(padded: ByteArray) {
        try {
            Padding.unpad(padded)
            fail("expected unpad to refuse the item")
        } catch (_: Padding.PaddingException) {
            // expected
        }
    }

    // ---- the bucket rule (#315) -------------------------------------------------------------

    @Test
    fun is4KiBUpTo4KiBThenTheNextPowerOfTwoUpTo1MiB() {
        assertEquals(4096L, Padding.paddedLength(0))
        assertEquals(4096L, Padding.paddedLength(1))
        assertEquals(4096L, Padding.paddedLength(4096))
        assertEquals(8192L, Padding.paddedLength(4097))
        assertEquals(8192L, Padding.paddedLength(8192))
        assertEquals(16384L, Padding.paddedLength(8193))
        assertEquals(mib, Padding.paddedLength(mib - 1))
        assertEquals(mib, Padding.paddedLength(mib))
    }

    @Test
    fun isThePadmeLengthAbove1MiBNeverMoreThan12PercentLarger() {
        assertEquals(Padding.padme(mib + 1), Padding.paddedLength(mib + 1))
        assertEquals(1_081_344L, Padding.padme(mib + 1))
        var l = mib + 1
        while (l < 64 * mib) {
            val p = Padding.paddedLength(l)
            assertTrue("L=$l", p >= l)
            assertTrue("L=$l", (p - l).toDouble() / l <= 0.12)
            assertEquals("a bucket pads to itself, L=$l", p, Padding.paddedLength(p))
            l += 104_729
        }
    }

    @Test
    fun neverShrinksAnItemAndEveryResultIsABucket() {
        for (x in longArrayOf(0, 5, 4095, 4096, 4097, 70_000, mib - 5, mib, mib + 1, 3 * mib + 17, 25 * mib)) {
            assertTrue("x=$x", Padding.paddedLength(x) >= x)
            assertTrue("x=$x", isBucket(Padding.paddedLength(x)))
        }
    }

    @Test
    fun theBucketCheckCanFail_mostLengthsAreNotBuckets() {
        // Positive control for isBucket above.
        for (len in longArrayOf(4097, 5000, 12_345, mib + 1, 2 * mib + 3)) {
            assertFalse("len=$len", isBucket(len))
        }
    }

    // ---- pad and unpad ----------------------------------------------------------------------

    @Test
    fun roundTripsOneByteUnderExactlyOnAndOneByteOverEachBoundary() {
        // The 4-byte prefix counts toward the bucket, so each boundary sits 4 bytes below it:
        // 4091, 4092, 4093 around 4 KiB and 1 MiB - 5, - 4, - 3 around 1 MiB.
        val min = Padding.MIN_PADDED
        val limit = Padding.POWER_OF_TWO_LIMIT
        val edges = intArrayOf(0, 1, min - 5, min - 4, min - 3, 8188, 8189, limit - 5, limit - 4, limit - 3)
        for (n in edges) {
            val plain = bytes(n, n)
            val padded = Padding.pad(plain)
            assertTrue("n=$n", isBucket(padded.size.toLong()))
            assertEquals("n=$n", Padding.paddedLength(4L + n), padded.size.toLong())
            assertArrayEquals("n=$n", plain, Padding.unpad(padded))
        }
        // Exactly on a boundary is the last size in its bucket; one byte over is the next bucket.
        assertEquals(4096, Padding.pad(bytes(min - 4)).size)
        assertEquals(8192, Padding.pad(bytes(min - 3)).size)
        assertEquals(1 shl 20, Padding.pad(bytes(limit - 4)).size)
        assertEquals(1_081_344, Padding.pad(bytes(limit - 3)).size)
    }

    @Test
    fun thePaddedLengthSaysNothingFinerThanTheBucket() {
        assertEquals(Padding.pad(bytes(10)).size, Padding.pad(bytes(4000)).size)
        assertEquals(Padding.pad(bytes(5000)).size, Padding.pad(bytes(8000)).size)
    }

    @Test
    fun refusesANonZeroByteInThePadding() {
        val padded = Padding.pad(bytes(10))
        assertArrayEquals(bytes(10), Padding.unpad(padded)) // the unchanged item unpads
        val last = padded.size - 1
        assertEquals(0.toByte(), padded[last]) // so setting it to 1 really is a change
        padded[last] = 1
        assertRefused(padded)
    }

    @Test
    fun refusesAPrefixThatUnderstatesThePlaintext() {
        // The plaintext bytes past the stated length are then read as padding, and they are not zero.
        val plain = bytes(10)
        val padded = Padding.pad(plain)
        assertTrue(plain.copyOfRange(5, 10).all { it != 0.toByte() })
        padded[3] = 5
        assertNotEquals(plain.size.toByte(), padded[3])
        assertRefused(padded)
    }

    @Test
    fun refusesALengthThatIsNotTheBucketForItsPrefix() {
        val padded = Padding.pad(bytes(10))
        assertArrayEquals(bytes(10), Padding.unpad(padded))
        assertRefused(padded.copyOf(padded.size + 1))
        assertRefused(padded.copyOf(padded.size - 1))
    }

    @Test
    fun refusesAPrefixThatOverrunsTheItemAndAnItemTooShortToHoldOne() {
        val padded = Padding.pad(bytes(10))
        assertArrayEquals(bytes(10), Padding.unpad(padded))
        val size = padded.size
        padded[0] = (size ushr 24).toByte()
        padded[1] = (size ushr 16).toByte()
        padded[2] = (size ushr 8).toByte()
        padded[3] = size.toByte()
        assertRefused(padded)

        assertEquals(0, Padding.unpad(ByteArray(4096)).size) // a zero prefix and zeros: the empty item
        assertRefused(ByteArray(3))
    }

    @Test
    fun readsAPrefixWithTheTopBitSetAsTheLargeLengthItIs() {
        // A u32 of 2^31 or more is a length far past the item, never a negative Int that would
        // pass the overrun check.
        for (prefix in listOf(byteArrayOf(0x80.toByte(), 0, 0, 0), byteArrayOf(-1, -1, -1, -1))) {
            val padded = Padding.pad(bytes(10))
            assertArrayEquals(bytes(10), Padding.unpad(padded))
            prefix.copyInto(padded)
            assertRefused(padded)
        }
    }

    // ---- the vector the phone is held to (#316) ---------------------------------------------

    @Test
    fun padsDaymarkTo4096Bytes_lengthPrefixTheTextThenZeros() {
        val padded = Padding.pad("daymark".toByteArray(Charsets.UTF_8))
        assertEquals(4096, padded.size)
        assertEquals("00000007" + "6461796d61726b", hex(padded.copyOfRange(0, 11))) // n = 7, then UTF-8
        assertTrue(padded.copyOfRange(11, padded.size).all { it == 0.toByte() })
    }

    @Test
    fun mapsThesePlaintextLengthsToThesePaddedLengths() {
        // The table in padding.test.ts, which was computed from the paper's bit-mask form of Padmé.
        val table = listOf(
            0L to 4096L,
            4092L to 4096L,
            4093L to 8192L,
            mib - 4 to mib,
            mib - 3 to 1_081_344L,
            5_000_000L to 5_111_808L,
            25 * mib to 26_738_688L,
        )
        for ((n, expected) in table) assertEquals("n=$n", expected, Padding.paddedLength(4 + n))
    }
}
