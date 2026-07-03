package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs on the plain host JVM via lazysodium-java's JNA-loaded native libsodium — no Android
 * SDK or emulator needed (see sync-crypto/build.gradle.kts and SyncCrypto's class KDoc). The
 * real Android `sync` flavor swaps in lazysodium-android at runtime; only that swap is
 * untested here, everything about the algorithm itself is exercised for real.
 */
class SyncCryptoTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val crypto = SyncCrypto(sodium)

    // Small KDF params keep tests fast; production defaults to >=256 MiB / 3 ops
    // (SyncCrypto.KdfParams.DEFAULT), same trade-off crypto.test.ts makes with its `FAST` const.
    private val fast = SyncCrypto.KdfParams(memMiB = 8, ops = 2)

    @Test
    fun roundTripsASnapshotUnderTheDerivedKey() {
        val salt = crypto.newSalt()
        val keys = crypto.deriveKeys("correct horse battery staple", salt, fast)
        val plaintext = """{"version":12,"entries":[{"id":1,"moodLevel":4}]}""".toByteArray(Charsets.UTF_8)
        val blob = crypto.encryptSnapshot(plaintext, keys.syncKey, "devA", 7)
        val back = crypto.decryptSnapshot(blob, keys.syncKey, "devA", 7)
        assertArrayEquals(plaintext, back)
    }

    @Test
    fun isDeterministicSamePassphraseSaltParamsDeriveTheSameKey() {
        val salt = crypto.newSalt()
        val a = crypto.deriveKeys("pass", salt, fast)
        val b = crypto.deriveKeys("pass", salt, fast)
        assertArrayEquals(a.syncKey, b.syncKey)
        assertArrayEquals(a.manifestSeed, b.manifestSeed)
    }

    @Test
    fun failsToDecryptWithTheWrongPassphrase() {
        val salt = crypto.newSalt()
        val good = crypto.deriveKeys("right", salt, fast)
        val bad = crypto.deriveKeys("wrong", salt, fast)
        val blob = crypto.encryptSnapshot("secret".toByteArray(), good.syncKey, "devA", 0)
        try {
            crypto.decryptSnapshot(blob, bad.syncKey, "devA", 0)
            fail("expected decryption to fail")
        } catch (_: SyncCrypto.SyncCryptoException) {
            // expected
        }
    }

    @Test
    fun detectsTamperingViaAead() {
        val keys = crypto.deriveKeys("p", crypto.newSalt(), fast)
        val blob = crypto.encryptSnapshot("hello".toByteArray(), keys.syncKey, "devA", 0)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte() // flip a ciphertext bit
        try {
            crypto.decryptSnapshot(blob, keys.syncKey, "devA", 0)
            fail("expected tamper detection to reject the blob")
        } catch (_: SyncCrypto.SyncCryptoException) {
            // expected
        }
    }

    @Test
    fun bindsLineageAndVersionViaAad_wrongVersionOrLineageFails() {
        val keys = crypto.deriveKeys("p", crypto.newSalt(), fast)
        val blob = crypto.encryptSnapshot("hi".toByteArray(), keys.syncKey, "devA", 3)
        assertThrowsSyncCrypto { crypto.decryptSnapshot(blob, keys.syncKey, "devA", 4) }
        assertThrowsSyncCrypto { crypto.decryptSnapshot(blob, keys.syncKey, "devB", 3) }
    }

    @Test
    fun rejectsANonDaymarkEnvelope() {
        val keys = crypto.deriveKeys("p", crypto.newSalt(), fast)
        assertThrowsSyncCrypto {
            crypto.decryptSnapshot(byteArrayOf(9, 9, 9, 9, 1, 2, 3), keys.syncKey, "devA", 0)
        }
    }

    @Test
    fun base64IsUrlSafeNoPadding_theSyncProtocolConformanceVector() {
        // bytes 0x00..0x0F -> RFC 4648 section 5 URL-safe, no padding.
        // This is THE conformance vector docs/SYNC_PROTOCOL.md §1.2 requires the phone client
        // to pass before any networking lands (see docs/COMPANION_PHONE_2B.md build order).
        val bytes = ByteArray(16) { it.toByte() }
        val b64 = SyncCrypto.toBase64(bytes)
        assertEquals("AAECAwQFBgcICQoLDA0ODw", b64)
        assertFalse("must not contain standard-base64 chars or padding", b64.any { it == '+' || it == '/' || it == '=' })
        assertArrayEquals(bytes, SyncCrypto.fromBase64(b64))
    }

    @Test
    fun signsAndVerifiesAManifestRejectsTampering() {
        val keys = crypto.deriveKeys("p", crypto.newSalt(), fast)
        val manifest = SyncCrypto.Manifest(lineage = "devA", head = 2, entries = listOf(SyncCrypto.ManifestEntry(2, "abc")))
        val signed = crypto.signManifest(manifest, keys.manifestSeed)
        assertEquals(signed.publicKeyB64, crypto.manifestPublicKeyB64(keys.manifestSeed))
        assertTrue(crypto.verifyManifest(manifest, signed.signatureB64, signed.publicKeyB64))

        val tampered = manifest.copy(entries = listOf(SyncCrypto.ManifestEntry(2, "EVIL")))
        assertFalse(crypto.verifyManifest(tampered, signed.signatureB64, signed.publicKeyB64))
    }

    /**
     * Cross-language conformance vectors: fixed inputs run through BOTH this Kotlin port (via
     * lazysodium-java, exercised here) AND the actual reference `crypto.ts` (via
     * libsodium-wrappers-sumo on Node) during development of this class, independently of each
     * other. The two implementations produced byte-identical output for every value below —
     * this test pins those outputs so a future change that breaks interop fails loudly. Nothing
     * under companion/ was modified to produce these; they're derived purely from public,
     * already-committed reference code run against fixed inputs.
     */
    @Test
    fun crossLanguageConformanceVectorsMatchTheTsReference() {
        val salt = ByteArray(16) { it.toByte() } // 0x00..0x0f
        val keys = crypto.deriveKeys("conformance-vector", salt, SyncCrypto.KdfParams(memMiB = 8, ops = 2))
        assertEquals("3d0a8d769e09df76fcb676a3e0eb792f3a5f2106c9d81759622eadd73a51fd65", hex(keys.syncKey))
        assertEquals("ae7248a30ab84f021da9eda1a3fc791d5670c51952300ee0a39fdf657b9f6b3e", hex(keys.manifestSeed))
        assertEquals("PQqNdp4J33b8tnaj4Ot5LzpfIQbJ2BdZYi6t1zpR_WU", SyncCrypto.toBase64(keys.syncKey))

        // Encrypt with a manually fixed nonce (bypassing the random one SyncCrypto generates)
        // so the ciphertext itself is directly comparable across languages.
        val nonce = ByteArray(24) { (it + 1).toByte() }
        val aad = SyncCrypto.aad("devA", 7)
        val plaintext = """{"hello":"daymark"}""".toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(plaintext.size + 16)
        val cipherLen = LongArray(1)
        val encOk = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            cipher, cipherLen, plaintext, plaintext.size.toLong(), aad, aad.size.toLong(), null, nonce, keys.syncKey,
        )
        assertTrue(encOk)
        assertEquals("02658812b667c934edd8a3ff9a50b3882b1a33621fa8f0d2f9a3fb272e12f29c387699", hex(cipher))

        // Ed25519: deterministic per RFC 8032, so signatures are directly comparable too.
        val signSeed = ByteArray(32) { ((it * 3 + 1) and 0xff).toByte() }
        val pub = ByteArray(32)
        val sec = ByteArray(64)
        assertTrue(sodium.cryptoSignSeedKeypair(pub, sec, signSeed))
        val msg = "hello-manifest".toByteArray(Charsets.UTF_8)
        val sig = ByteArray(64)
        assertTrue(sodium.cryptoSignDetached(sig, msg, msg.size.toLong(), sec))
        assertEquals("5c8ee580f7c1d08f801e472165889625f16d98f237b3abdfa858c50893148e1e", hex(pub))
        assertEquals(
            "ca847433b21c77a8c14027590c50af5822a4dbc495a678938d273e7d2a9007046e01f151252c1d71262ab718d96e10beba7588a32fb6581957dbbf3f040db20b",
            hex(sig),
        )

        assertEquals("d49a37c336a19f31718b0eef7c2b36e15d6b6bcd8f5673a42b7bb2df42bb0d90", crypto.sha256Hex("sha-input".toByteArray()))
    }

    private fun assertThrowsSyncCrypto(block: () -> Unit) {
        try {
            block()
            fail("expected SyncCryptoException")
        } catch (_: SyncCrypto.SyncCryptoException) {
            // expected
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
