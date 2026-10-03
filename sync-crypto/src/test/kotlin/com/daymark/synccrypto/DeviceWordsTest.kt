package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The six words of a device's key (#189, #432), which the console shows beside the phone's (#431). The
 * vectors are shared with the web: the same key gives the same six words on both sides, or the person
 * compares two screens that can never match.
 */
class DeviceWordsTest {

    private val sodium = LazySodiumJava(SodiumJava())

    @Test
    fun theVectors() {
        // The server vector's key: the phone's own key from the seed 0x40..0x5f.
        val vectorKey = "JUO5L_EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0"
        val vectorWords = listOf("hotel", "cedar", "earth", "coral", "nacho", "hotel")
        assertEquals(vectorWords, DeviceWords.of(sodium, vectorKey))
        val phone = DeviceKey.fromSeed(sodium, ByteArray(32) { (0x40 + it).toByte() })
        assertEquals(vectorKey, phone.publicKeyB64)
        assertEquals(vectorWords, phone.words())
        // Thirty-two zero bytes.
        assertEquals(listOf("inlet", "arrow", "cedar", "cobra", "crisp", "fault"), DeviceWords.of(sodium, "A".repeat(43)))
    }

    @Test
    fun theListHas256DistinctWords_oneForEachByte() {
        assertEquals(256, DeviceWords.WORDS.size)
        assertEquals(256, DeviceWords.WORDS.toSet().size)
        for (word in DeviceWords.WORDS) assertTrue(word, word.all { it in 'a'..'z' })
    }

    /** One key, one spelling: a key spelled another way would show other words for the same key. */
    @Test
    fun aKeySpelledAnyOtherWayHasNoWords() {
        val key = "JUO5L_EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0"
        assertEquals("control", 6, DeviceWords.of(sodium, key).size)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        // The last of 43 characters carries four bits of the key; the two below them must be zero.
        val strayBits = key.dropLast(1) + alphabet[alphabet.indexOf(key.last()) or 1]
        assertNotEquals(key, strayBits)
        val standard = key.replace('_', '/').replace('-', '+')
        assertNotEquals(key, standard)
        for (other in listOf("$key=", standard, strayBits, key.dropLast(1), key + "A", "A".repeat(44), "", " $key")) {
            try {
                DeviceWords.of(sodium, other)
                fail("'$other' has words")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
