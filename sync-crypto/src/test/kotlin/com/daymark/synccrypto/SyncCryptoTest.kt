package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

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
        // to pass before any networking lands (see docs/COMPANION_PHONE.md §6).
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
        // so the ciphertext itself is directly comparable across languages. This is the format-1
        // AEAD output, unpadded; the whole format-1 envelope around it is opened below.
        val nonce = ByteArray(24) { (it + 1).toByte() }
        val aad = "daymark.snapshot.v1|devA|7".toByteArray(Charsets.UTF_8)
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

    // ---- snapshots are padded before they are encrypted (#315) ------------------------------
    // The same checks as crypto.test.ts's section of that name.

    private val paddingKey = sodium.randomBytesBuf(32)
    private val paddingNonce = ByteArray(24) { (0xa0 + it).toByte() }

    @Test
    fun everySnapshotIsWrittenInFormat2WithABodyThatIsABucketSize() {
        for (n in intArrayOf(0, 1, 19, 4091, 4092, 4093, 8187, 8188, 8189, 70_000, MIB - 5, MIB - 4, MIB - 3)) {
            val blob = crypto.encryptSnapshot(bytes(n), paddingKey, "devA", 3)
            assertEquals("n=$n", 0x02.toByte(), blob[4])
            assertTrue("n=$n", isBucket(blob.size - OVERHEAD))
            assertEquals("n=$n", Padding.paddedLength(4L + n), (blob.size - OVERHEAD).toLong())
            assertEquals("n=$n", SyncCrypto.snapshotBlobLength(n.toLong()), blob.size.toLong())
        }
    }

    @Test
    fun theSameCheckFailsOnAnUnpaddedSnapshot_positiveControl() {
        val n = 5000
        val legacy = envelopeFromParts(0x01, bytes(n), "daymark.snapshot.v1|devA|3", paddingNonce, paddingKey)
        assertEquals(n, legacy.size - OVERHEAD)
        assertNotEquals(0, n and (n - 1)) // not a power of two, so its length is not already a bucket size
        assertFalse(isBucket(legacy.size - OVERHEAD))
    }

    @Test
    fun roundTripsOneByteUnderExactlyOnAndOneByteOverEachBoundaryIncluding1MiB() {
        // The 4-byte length prefix counts toward the bucket, so each boundary sits 4 bytes below it.
        var boundary = Padding.MIN_PADDED
        while (boundary <= MIB) {
            val over = if (boundary == MIB) 1_081_344 else 2 * boundary // above 1 MiB, the first Padmé size
            for ((n, body) in listOf(boundary - 5 to boundary, boundary - 4 to boundary, boundary - 3 to over)) {
                val plain = bytes(n, n)
                val blob = crypto.encryptSnapshot(plain, paddingKey, "devA", n.toLong())
                assertEquals("n=$n", body, blob.size - OVERHEAD)
                assertArrayEquals("n=$n", plain, crypto.decryptSnapshot(blob, paddingKey, "devA", n.toLong()))
            }
            boundary *= 2
        }
    }

    @Test
    fun aSnapshotStoredUnpaddedInFormat1StillOpens() {
        val plain = bytes(5000)
        val legacy = envelopeFromParts(0x01, plain, "daymark.snapshot.v1|devA|3", paddingNonce, paddingKey)
        assertEquals(0x01.toByte(), legacy[4])
        assertArrayEquals(plain, crypto.decryptSnapshot(legacy, paddingKey, "devA", 3))
    }

    @Test
    fun aChangedFormatByteFailsToOpen_soItCannotMakeAReaderSkipUnpadding() {
        val plain = bytes(100)
        val blob = crypto.encryptSnapshot(plain, paddingKey, "devA", 3)
        assertArrayEquals(plain, crypto.decryptSnapshot(blob, paddingKey, "devA", 3)) // the unchanged blob opens
        val relabelled = blob.copyOf()
        relabelled[4] = if (blob[4] == SyncCrypto.FMT_PADDED) SyncCrypto.FMT_UNPADDED else SyncCrypto.FMT_PADDED
        assertNotEquals(blob[4], relabelled[4])
        assertAeadRefused { crypto.decryptSnapshot(relabelled, paddingKey, "devA", 3) }

        // And the other way: a format-1 envelope relabelled as format 2.
        val legacy = envelopeFromParts(0x01, plain, "daymark.snapshot.v1|devA|3", paddingNonce, paddingKey)
        assertArrayEquals(plain, crypto.decryptSnapshot(legacy, paddingKey, "devA", 3))
        val promoted = legacy.copyOf()
        promoted[4] = if (legacy[4] == SyncCrypto.FMT_UNPADDED) SyncCrypto.FMT_PADDED else SyncCrypto.FMT_UNPADDED
        assertNotEquals(legacy[4], promoted[4])
        assertAeadRefused { crypto.decryptSnapshot(promoted, paddingKey, "devA", 3) }
    }

    @Test
    fun refusesAFormatByteItDoesNotKnow() {
        val blob = crypto.encryptSnapshot(bytes(10), paddingKey, "devA", 3)
        assertArrayEquals(bytes(10), crypto.decryptSnapshot(blob, paddingKey, "devA", 3))
        val unknown = blob.copyOf()
        unknown[4] = (maxOf(SyncCrypto.FMT_PADDED, SyncCrypto.FMT_UNPADDED) + 1).toByte()
        assertFalse(unknown[4] == SyncCrypto.FMT_PADDED || unknown[4] == SyncCrypto.FMT_UNPADDED)
        val e = assertThrowsSyncCrypto { crypto.decryptSnapshot(unknown, paddingKey, "devA", 3) }
        assertTrue(e.message, e.message!!.contains("unsupported envelope format"))
    }

    @Test
    fun refusesAFormat2BodyWhosePaddingPadCouldNotHaveProduced() {
        // Only a holder of the sync key can make one. Strictness makes a writer that pads wrongly
        // fail loudly instead of writing snapshots only it can read.
        val good = Padding.pad(bytes(10))
        val opened = crypto.decryptSnapshot(
            envelopeFromParts(0x02, good, "daymark.snapshot.v2|devA|3", paddingNonce, paddingKey), paddingKey, "devA", 3,
        )
        assertArrayEquals(bytes(10), opened) // the same construction, well padded, opens
        val bad = good.copyOf()
        val last = bad.size - 1
        bad[last] = (if (good[last] == 0.toByte()) 1 else 0).toByte()
        assertNotEquals(good[last], bad[last])
        val blob = envelopeFromParts(0x02, bad, "daymark.snapshot.v2|devA|3", paddingNonce, paddingKey)
        val e = assertThrowsSyncCrypto { crypto.decryptSnapshot(blob, paddingKey, "devA", 3) }
        assertTrue(e.message, e.message!!.contains("padding"))
    }

    @Test
    fun unpaddingWaitsForTheAead_aTamperedPaddingByteIsRefusedAsTampering() {
        // The flipped ciphertext bit sits over the last padding byte. Were the body unpadded before
        // it was authenticated, this would be refused as bad padding; it is refused as tampering.
        val plain = bytes(10)
        val blob = crypto.encryptSnapshot(plain, paddingKey, "devA", 3)
        assertArrayEquals(plain, crypto.decryptSnapshot(blob, paddingKey, "devA", 3))
        val tampered = blob.copyOf()
        val lastPaddingByte = blob.size - 16 - 1
        tampered[lastPaddingByte] = (tampered[lastPaddingByte].toInt() xor 0x01).toByte()
        assertAeadRefused { crypto.decryptSnapshot(tampered, paddingKey, "devA", 3) }
    }

    // ---- the snapshot vectors the phone is held to (#316) -----------------------------------
    // The constants of crypto.test.ts's section of that name, generated by the web reference.
    // A change to any of them is a change to the wire format, never a refactor.

    @Test
    fun format1_theEnvelopeAroundThePinnedCiphertextOpensToThePlaintext() {
        val key = vectorKey()
        val envelope = byteArrayOf(0x44, 0x4d, 0x53, 0x31, 0x01) + VECTOR_NONCE + unhex(V1_CIPHERTEXT)
        assertEquals(64, envelope.size)
        assertEquals(VECTOR_PLAINTEXT, String(crypto.decryptSnapshot(envelope, key, "devA", 7), Charsets.UTF_8))
    }

    @Test
    fun format2_theEnvelopeBuiltFromItsDocumentedPartsIsExactlyTheWebsBytes_andItOpens() {
        // Stated without Padding or SyncCrypto: the u32 prefix 19, the text, zeros to 4096, then the
        // AEAD under the literal format-2 associated data.
        val key = vectorKey()
        val plaintext = VECTOR_PLAINTEXT.toByteArray(Charsets.UTF_8)
        assertEquals(19, plaintext.size)
        val body = ByteArray(4096).also { it[3] = 19; plaintext.copyInto(it, 4) }
        val envelope = envelopeFromParts(0x02, body, "daymark.snapshot.v2|devA|7", VECTOR_NONCE, key)
        assertV2Vector(envelope)
        assertEquals(VECTOR_PLAINTEXT, String(crypto.decryptSnapshot(envelope, key, "devA", 7), Charsets.UTF_8))
    }

    @Test
    fun format2_theWriterUnderTheVectorNonceMakesExactlyTheWebsBytes() {
        val key = vectorKey()
        val envelope = crypto.sealSnapshot(VECTOR_PLAINTEXT.toByteArray(Charsets.UTF_8), key, "devA", 7, VECTOR_NONCE)
        assertV2Vector(envelope)
    }

    @Test
    fun theVectorPinsTheAssociatedDataToo_theSameBodyUnderTheFormat1NameHasAnotherTag() {
        val key = vectorKey()
        val body = Padding.pad(VECTOR_PLAINTEXT.toByteArray(Charsets.UTF_8))
        val underV1Name = envelopeFromParts(0x02, body, "daymark.snapshot.v1|devA|7", VECTOR_NONCE, key)
        assertEquals(V2_CIPHERTEXT_HEAD, hex(underV1Name.copyOfRange(29, 45))) // same key, nonce and body
        assertNotEquals(V2_TAG, hex(underV1Name.copyOfRange(V2_LENGTH - 16, V2_LENGTH)))
    }

    @Test
    fun theWriterMakesExactlyThatConstructionForWhateverNonceItDraws() {
        val key = vectorKey()
        val plaintext = VECTOR_PLAINTEXT.toByteArray(Charsets.UTF_8)
        val blob = crypto.encryptSnapshot(plaintext, key, "devA", 7)
        val drawn = blob.copyOfRange(5, 29)
        val rebuilt = envelopeFromParts(0x02, Padding.pad(plaintext), "daymark.snapshot.v2|devA|7", drawn, key)
        assertArrayEquals(rebuilt, blob)
    }

    @Test
    fun theVectorCheckCanFail_positiveControl() {
        // One bit anywhere in the envelope and assertV2Vector refuses it.
        val key = vectorKey()
        val envelope = crypto.sealSnapshot(VECTOR_PLAINTEXT.toByteArray(Charsets.UTF_8), key, "devA", 7, VECTOR_NONCE)
        assertV2Vector(envelope)
        val changed = envelope.copyOf()
        changed[2000] = (changed[2000].toInt() xor 0x01).toByte()
        try {
            assertV2Vector(changed)
        } catch (_: AssertionError) {
            return
        }
        fail("assertV2Vector accepted an envelope with a changed byte")
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun vectorKey(): ByteArray {
        val key = crypto.deriveKeys("conformance-vector", ByteArray(16) { it.toByte() }, fast).syncKey
        assertEquals(VECTOR_SYNC_KEY, hex(key))
        return key
    }

    /** The format-2 vector: its length, header, first ciphertext block, tag and SHA-256. */
    private fun assertV2Vector(envelope: ByteArray) {
        assertEquals(V2_LENGTH, envelope.size)
        assertEquals(V2_HEADER, hex(envelope.copyOfRange(0, 29)))
        assertEquals(V2_CIPHERTEXT_HEAD, hex(envelope.copyOfRange(29, 45)))
        assertEquals(V2_TAG, hex(envelope.copyOfRange(V2_LENGTH - 16, V2_LENGTH)))
        assertEquals(V2_SHA256, hex(MessageDigest.getInstance("SHA-256").digest(envelope)))
    }

    /**
     * A snapshot envelope built from its documented parts (docs/SYNC_PROTOCOL.md §1.1), not by
     * SyncCrypto: magic, format byte, nonce, then the AEAD of [body] under [aadText]. Tests use it
     * to make the format-1 envelopes nothing writes any more, and to state format 2 independently
     * of the writer.
     */
    private fun envelopeFromParts(format: Byte, body: ByteArray, aadText: String, nonce: ByteArray, key: ByteArray): ByteArray {
        val aad = aadText.toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(body.size + 16)
        val cipherLen = LongArray(1)
        assertTrue(
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                cipher, cipherLen, body, body.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
            ),
        )
        return byteArrayOf(0x44, 0x4d, 0x53, 0x31, format) + nonce + cipher.copyOf(cipherLen[0].toInt())
    }

    /** A length the padding could have produced for some input: the buckets, and nothing else. */
    private fun isBucket(len: Int): Boolean =
        len >= Padding.MIN_PADDED && Padding.paddedLength(len.toLong()) == len.toLong()

    private fun bytes(n: Int, seed: Int = 7): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    private fun assertThrowsSyncCrypto(block: () -> Unit): SyncCrypto.SyncCryptoException {
        try {
            block()
        } catch (e: SyncCrypto.SyncCryptoException) {
            return e
        }
        fail("expected SyncCryptoException")
        throw AssertionError("unreachable")
    }

    /** Refused by the AEAD itself, before anything reads the body. */
    private fun assertAeadRefused(block: () -> Unit) {
        val e = assertThrowsSyncCrypto(block)
        assertTrue(e.message, e.message!!.startsWith("decryption failed"))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private companion object {
        const val MIB = 1 shl 20

        /** What a snapshot blob adds to its body: magic (4), format byte (1), nonce (24) and tag (16). */
        const val OVERHEAD = 4 + 1 + 24 + 16

        val VECTOR_NONCE = ByteArray(24) { (it + 1).toByte() }
        const val VECTOR_PLAINTEXT = """{"hello":"daymark"}"""
        const val VECTOR_SYNC_KEY = "3d0a8d769e09df76fcb676a3e0eb792f3a5f2106c9d81759622eadd73a51fd65"

        // As the format-1 test above pins it: the AEAD output alone, ciphertext then tag.
        const val V1_CIPHERTEXT = "02658812b667c934edd8a3ff9a50b3882b1a33621fa8f0d2f9a3fb272e12f29c387699"
        const val V2_HEADER = "444d5331" + "02" + "0102030405060708090a0b0c0d0e0f101112131415161718"
        const val V2_LENGTH = 4141 // the 29-byte header, the 4096-byte padded body, the 16-byte tag
        const val V2_CIPHERTEXT_HEAD = "7947e064a129ce73bb96a8bcd91fb69b" // the first 16 bytes after the header
        const val V2_TAG = "d55196e0a95b086092d65200787c1a49"
        const val V2_SHA256 = "ab71fc3e71bb1fff1a1e1ad1ca04c2a59e9192c686d7b6b6d0356561b40841c3"
    }
}
