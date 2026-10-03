package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone keeps about its server (#432), as the bytes it seals: every state round-trips to the
 * same link and the same key, and anything that is not exactly such bytes is no link at all.
 */
class KeptLinkTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val key = DeviceKey.generate(sodium)
    private val server = PairedServer("https://daymark.test:8443/companion", key)
    private val syncKey = ByteArray(32) { (0xA0 + it).toByte() }
    private val tag = "\"" + "0f".repeat(32) + "\""

    private fun same(a: KeptLink, b: KeptLink) {
        assertEquals(a.address, b.address)
        assertEquals(a.keyId, b.keyId)
        assertEquals(a.hasSyncKey, b.hasSyncKey)
        assertArrayEquals(a.syncKey(), b.syncKey())
        assertEquals(a.keyDocumentTag, b.keyDocumentTag)
        assertEquals(a.lastSentAt, b.lastSentAt)
        assertEquals(a.lastSentVersion, b.lastSentVersion)
        assertArrayEquals(a.encode(), b.encode())
    }

    @Test
    fun everyStateRoundTrips_toTheSameKey() {
        val paired = KeptLink.of(server)
        val unlocked = paired.withSyncKey(syncKey, tag)
        val sent = unlocked.withLastSent(1_790_000_000_123L, 7)
        val relocked = sent.withoutSyncKey()
        for (link in listOf(paired, unlocked, sent, relocked)) {
            val read = KeptLink.decode(link.encode()) ?: throw AssertionError("$link did not read back")
            same(link, read)
            val restored = read.server(sodium) ?: throw AssertionError("the key did not come back")
            assertEquals(key.keyId, restored.key.keyId)
            assertEquals(key.publicKeyB64, restored.key.publicKeyB64)
            assertEquals(server.address, restored.address)
        }
        assertFalse(paired.hasSyncKey)
        assertArrayEquals(syncKey, unlocked.syncKey())
        assertEquals(7L, sent.lastSentVersion)
        assertEquals("the last copy outlives the passphrase being asked again", 7L, relocked.lastSentVersion)
        assertNull(relocked.keyDocumentTag)
    }

    @Test
    fun theBytesAreTheLayoutTheHeaderStates() {
        val bytes = KeptLink.of(server).withSyncKey(syncKey, tag).withLastSent(5, 2).encode()
        val address = server.address.toByteArray(Charsets.UTF_8)
        assertArrayEquals("DMPL".toByteArray(Charsets.US_ASCII) + byteArrayOf(1), bytes.copyOfRange(0, 5))
        assertEquals(address.size, ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF))
        val seedAt = 7 + address.size + 1 + 22
        assertArrayEquals(key.seedToKeep(), bytes.copyOfRange(seedAt, seedAt + 32))
        assertEquals(0x03, bytes[seedAt + 32].toInt())
        assertArrayEquals(syncKey, bytes.copyOfRange(seedAt + 33, seedAt + 65))
        assertEquals(seedAt + 65 + 1 + tag.length + 16, bytes.size)
    }

    @Test
    fun anythingButTheBytesExactlyIsNoLink() {
        val good = KeptLink.of(server).withSyncKey(syncKey, tag).withLastSent(5, 2).encode()
        assertNotNull(KeptLink.decode(good))
        for (length in good.indices) assertNull("cut at $length", KeptLink.decode(good.copyOf(length)))
        assertNull("a byte past the end", KeptLink.decode(good + byteArrayOf(0)))
        // Each change derived from the byte it changes, so it is never a no-op.
        fun flipped(at: Int, mask: Int) = good.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }
        assertNull("the magic", KeptLink.decode(flipped(0, 0x01)))
        assertNull("the version", KeptLink.decode(flipped(4, 0x02)))
        val flagsAt = 7 + server.address.length + 1 + 22 + 32
        assertNull("a flag nobody writes", KeptLink.decode(flipped(flagsAt, 0x04)))
        // Another address that is not https is refused, whoever wrote it.
        val http = KeptLink.of(server).encode().let { bytes ->
            val text = String(bytes, Charsets.ISO_8859_1).replace("https://daymark.test:8443", "http://daymark.test:8443/")
            text.toByteArray(Charsets.ISO_8859_1)
        }
        assertNull(KeptLink.decode(http))
    }

    @Test
    fun aSeedThatIsNotTheNamedKeysGivesNoServer() {
        val bytes = KeptLink.of(server).encode()
        val seedAt = 7 + server.address.length + 1 + 22
        val changed = bytes.copyOf().also { it[seedAt] = (it[seedAt].toInt() xor 0x01).toByte() }
        val link = KeptLink.decode(changed) ?: throw AssertionError("the bytes are well formed")
        assertNull("a key other than the one named was made", link.server(sodium))
        assertNotNull("the control", KeptLink.decode(bytes)!!.server(sodium))
    }

    @Test
    fun wipingClearsTheLinksOwnCopies_andOnlyThem() {
        val link = KeptLink.of(server).withSyncKey(syncKey, tag)
        val before = link.encode()
        link.wipe()
        assertArrayEquals(ByteArray(32), link.syncKey())
        assertTrue("the caller's sync key was wiped with the link's", syncKey.any { it != 0.toByte() })
        assertFalse(before.contentEquals(link.encode()))
        assertEquals("a wiped link names a key it cannot make", null, link.server(sodium))
    }
}
