package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The phone's half of pairing must be the same owner as the browser's (docs/COMPANION_PAIRING.md
 * §14). Both vectors here are the ones the web pins: the identity in `owner/identity.test.ts`, the
 * channel identifier in `pairing/relay.test.ts`. A value that differs here means a clinician who
 * paired in the browser would see a different owner on the phone.
 */
class OwnerPairingVectorTest {

    private val crypto = SyncCrypto(LazySodiumJava(SodiumJava()))

    private val master0to31 = ByteArray(32) { it.toByte() }

    @Test
    fun `master bytes 0 to 31 give the owner keys the web pins`() {
        val id = crypto.ownerIdentityFromMaster(master0to31)
        assertEquals("Zd9mRefJZVYSFKZIzfgyItz7yvoRPvxVy2AiO7ObPjg", SyncCrypto.toBase64(id.boxPublicKey))
        assertEquals("Ps0OIIdt4nHg8dUlJTef98V9EJ_s6jsPMUI4LHoYOTQ", SyncCrypto.toBase64(id.signPublicKey))
    }

    @Test
    fun `the identity is a function of the master and of nothing else`() {
        val a = crypto.ownerIdentityFromMaster(master0to31)
        val b = crypto.ownerIdentityFromMaster(master0to31.copyOf())
        assertArrayEquals(a.boxSecretKey, b.boxSecretKey)
        assertArrayEquals(a.signSecretKey, b.signSecretKey)
        // Control: a master that differs in one byte is a different owner.
        val other = master0to31.copyOf().also { it[31] = (it[31] + 1).toByte() }
        assertNotEquals(
            SyncCrypto.toBase64(a.signPublicKey),
            SyncCrypto.toBase64(crypto.ownerIdentityFromMaster(other).signPublicKey),
        )
    }

    @Test
    fun `the two halves come from different subkeys, and neither is the sync layer's`() {
        val id = crypto.ownerIdentityFromMaster(master0to31)
        val manifestPub = crypto.manifestPublicKeyB64(crypto.deriveSubkey(master0to31, 2L, 32))
        assertNotEquals(manifestPub, SyncCrypto.toBase64(id.signPublicKey))
        assertNotEquals(SyncCrypto.toBase64(id.boxPublicKey), SyncCrypto.toBase64(id.signPublicKey))
    }

    @Test
    fun `a master of the wrong length is refused, never fitted`() {
        for (size in listOf(0, 31, 33, 64)) {
            try {
                crypto.ownerIdentityFromMaster(ByteArray(size))
                fail("a $size-byte master was accepted")
            } catch (expected: SyncCrypto.SyncCryptoException) {
            }
        }
    }

    @Test
    fun `deriving leaves the master as it was, and wipe zeroes only the private halves`() {
        val master = master0to31.copyOf()
        val id = crypto.ownerIdentityFromMaster(master)
        assertArrayEquals(master0to31, master)
        val pub = id.signPublicKey.copyOf()
        // Control: the private halves hold something before they are wiped.
        assertTrue(id.boxSecretKey.any { it != 0.toByte() } && id.signSecretKey.any { it != 0.toByte() })
        id.wipe()
        assertTrue(id.boxSecretKey.all { it == 0.toByte() } && id.signSecretKey.all { it == 0.toByte() })
        assertArrayEquals(pub, id.signPublicKey)
    }

    @Test
    fun `the channel identifier is the bytes the web pins`() {
        val ci = CpaceCrypto.channelIdentifier("rel-a", "inv-1")
        assertEquals(
            "106461796d61726b2f63706163652f76320572656c2d6105696e762d31",
            ci.joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            listOf("daymark/cpace/v2", "rel-a", "inv-1"),
            CpaceCrypto.parseLv(ci, 3).map { it.toString(Charsets.UTF_8) },
        )
    }

    @Test
    fun `a shifted boundary between the two ids is a different channel`() {
        val ci = CpaceCrypto.channelIdentifier("rel-a", "inv-1")
        assertNotEquals(ci.toList(), CpaceCrypto.channelIdentifier("rel-", "ainv-1").toList())
        assertNotEquals(ci.toList(), CpaceCrypto.channelIdentifier("rel-a", "inv-2").toList())
        assertNotEquals(ci.toList(), CpaceCrypto.channelIdentifier("rel-b", "inv-1").toList())
    }
}
