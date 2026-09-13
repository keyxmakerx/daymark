package com.daymark.app.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * WRAPPING THE DATA KEY — the one 32-byte secret that opens the journal, and the two or three
 * different things that can be asked for in order to get at it.
 *
 * ─── THE SHAPE, AND WHY IT IS NOT "KEY THE DATABASE FROM THE PIN" ──────────────────────────────
 *
 * The obvious design derives the database key from the PIN directly. It is rejected here for one
 * reason that has nothing to do with cryptography: changing the PIN would then mean re-encrypting
 * every row of somebody's journal, at the moment they are standing in a settings screen, with a
 * half-converted database on disk if the phone runs out of battery. And a forgotten PIN would mean
 * the data is gone, permanently, with nothing anybody could do — which for a person in distress who
 * has forgotten six digits is a harm this product would be creating.
 *
 * So there is one random data key (DEK), made once, never changed, never derived from anything. The
 * database is encrypted with it and only with it. What changes is the set of WRAPS — small sealed
 * blobs, each of which contains that same DEK encrypted under a different key:
 *
 *   KEYSTORE   the DEK under an AES key held in the phone's hardware-backed keystore. The key
 *              material never leaves the TEE and cannot be copied off the device, so this wrap
 *              makes the database file device-bound at no cost to the person: nothing to remember,
 *              nothing to type, nothing to lose.
 *   PIN        the DEK under a key derived from the PIN. Exists only while a PIN of six or more
 *              digits is set.
 *   RECOVERY   the DEK under a key derived from the recovery code, which is shown once and written
 *              down on paper. Exists only alongside a PIN wrap.
 *
 * Changing the PIN rewrites ONE blob of a hundred-odd bytes. The database is never touched.
 *
 * ─── WHY THE KEYSTORE WRAP IS REMOVED WHEN A PIN IS SET ────────────────────────────────────────
 *
 * This file does not decide that — `DataKeyStore` does — but it is the question the design turns on
 * and it belongs written down next to the wraps themselves. If the Keystore wrap stayed while a PIN
 * wrap existed, the app could open the journal on this phone without ever asking for the PIN, and
 * then "if you forget the PIN, only your recovery code opens them" would be false. Copy that says
 * a thing is locked, over a thing that opens itself, is the exact failure this whole issue exists
 * to correct. So the wraps are a SET, and which wraps exist is the security state:
 *
 *   no PIN, or a PIN shorter than six digits  ->  { KEYSTORE }          opens without asking
 *   a PIN of six digits or more               ->  { PIN, RECOVERY }     asks, and means it
 *
 * ─── THE BLOB FORMAT, AND WHY THE HEADER IS AUTHENTICATED ──────────────────────────────────────
 *
 *   byte  0        format version
 *   byte  1        which wrap this is
 *   byte  2        which key-derivation function produced the key
 *   bytes 3..6     iteration count, big-endian
 *   byte  7        salt length
 *   bytes 8..      the salt
 *   remainder      whatever the AEAD produced
 *
 * Every byte of that header is fed to the AEAD as associated data. That is not decoration. Without
 * it, somebody holding the file could rewrite the iteration count from four hundred thousand to one
 * and the app would obligingly derive the key that way — turning a PIN search that costs real time
 * into one that costs none, with no fault reported anywhere, because the ciphertext itself would
 * still be untouched. Authenticating the header means any edit to it makes the blob refuse to open.
 *
 * The version byte is checked and an unknown one is REFUSED rather than guessed at. A future format
 * that adds a field would be read by this code as the old format with the fields in the wrong
 * places, and the most likely outcome of that is a derived key that is wrong in a way nothing can
 * diagnose.
 *
 * ─── FAIL CLOSED, AND SAY NOTHING ──────────────────────────────────────────────────────────────
 *
 * Every open returns null on failure and null is the only failure. A wrong PIN, a truncated blob, a
 * tampered header, an unknown version and a wrap of the wrong kind are indistinguishable from the
 * outside, deliberately: the distinction is of no use to the person and of considerable use to
 * somebody probing the file. Nothing here throws a message, logs anything, or includes any part of
 * a secret in any value it returns.
 */

/** Which of the three things is holding this copy of the data key. */
enum class WrapKind(val id: Byte) {
    KEYSTORE(1),
    PIN(2),
    RECOVERY(3),
    ;

    companion object {
        fun ofId(id: Byte): WrapKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Seal and open bytes under some key.
 *
 * An interface rather than a concrete class because one of the three implementations lives in the
 * Android Keystore and cannot be constructed, exercised or tested anywhere but on a phone. Behind
 * this interface, everything else in this file is ordinary Kotlin that runs in a JVM unit test.
 */
interface Aead {
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** The plaintext, or null if it did not open. Null is the only failure; see the header note. */
    fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray?
}

/**
 * AES-256-GCM over a key the caller holds as bytes — the PIN and recovery wraps.
 *
 * A fresh 12-byte IV per seal, prefixed to the ciphertext. Twelve bytes because that is the length
 * GCM is specified for and the only one that skips an internal re-derivation of the counter block;
 * random rather than a counter because these blobs are rewritten rarely and independently and there
 * is no shared place to keep a counter honest. A 128-bit tag, which is GCM's full tag.
 */
class AesGcmAead(keyBytes: ByteArray, private val random: SecureRandom = SecureRandom()) : Aead {

    // SecretKeySpec clones the array, so the caller is free to zero theirs the moment this returns.
    private val key: SecretKeySpec = SecretKeySpec(keyBytes, "AES")

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray? {
        if (sealed.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            // The wrong key, a tampered header and a corrupt blob all land here and all look the
            // same from outside. That is the point; see the header note.
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

object DataKeyWraps {

    /** The format the header below describes. An unknown version is refused, never guessed at. */
    const val FORMAT_VERSION: Byte = 1

    /** 256 bits, which is what SQLCipher takes as a raw key. */
    const val DATA_KEY_BYTES = 32

    const val SALT_BYTES = 16

    const val KDF_NONE: Byte = 0
    const val KDF_PBKDF2_HMAC_SHA256: Byte = 1

    /**
     * The PIN wrap's work factor: twice what `PinManager` spends verifying a PIN today.
     *
     * WHAT THIS BUYS AND WHAT IT CANNOT. A six-digit PIN is a million possibilities. At this work
     * factor a search costs roughly 4 x 10^11 HMACs, which is hours on hardware somebody can rent
     * and not a number anybody should feel safe behind. Raising it further does not change that:
     * iterations buy a constant, and a constant is not a defence against a search space of a
     * million. What actually defends a PIN wrap is the PIN being longer, which is why the settings
     * copy says "a longer one takes longer to guess" rather than claiming the file is safe.
     *
     * The number is therefore chosen as "as much as can be spent without the unlock feeling broken"
     * rather than as a security threshold. HOW MUCH THAT IS HAS NEVER BEEN MEASURED ON A PHONE —
     * nothing in this project's CI runs on a device — so this constant is a starting point to be
     * timed on real hardware and tuned, not a settled value. It is low-risk to raise later: raising
     * it changes only what new wraps are written with, because every blob carries the count it was
     * made with.
     */
    const val PIN_ITERATIONS = 420_000

    /**
     * The recovery code's work factor, the same number for the same reason of not having two.
     *
     * Here it is defence in depth and nothing more. The code carries 99 bits of entropy, so no
     * iteration count is what stands between an attacker and this wrap — the code itself is, by a
     * margin no hardware closes.
     */
    const val RECOVERY_ITERATIONS = 420_000

    private const val HEADER_FIXED_BYTES = 8

    /** A fresh data key. The only place one is ever made. */
    fun newDataKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(DATA_KEY_BYTES).also { random.nextBytes(it) }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /** What a blob says about itself, once it has been checked over. */
    data class Header(
        val kind: WrapKind,
        val kdf: Byte,
        val iterations: Int,
        val salt: ByteArray,
        /** Where the sealed bytes start. */
        val bodyAt: Int,
    ) {
        /** The exact bytes the AEAD authenticated. */
        fun associatedData(blob: ByteArray): ByteArray = blob.copyOfRange(0, bodyAt)

        // Generated equals/hashCode would compare the salt by identity. Nothing depends on that
        // here, but a data class holding a ByteArray is a trap worth closing where it is written.
        override fun equals(other: Any?): Boolean =
            other is Header && kind == other.kind && kdf == other.kdf &&
                iterations == other.iterations && salt.contentEquals(other.salt) &&
                bodyAt == other.bodyAt

        override fun hashCode(): Int =
            (((kind.hashCode() * 31 + kdf) * 31 + iterations) * 31 + salt.contentHashCode()) * 31 + bodyAt
    }

    /** The header of a blob, or null if it is not one this version can read. */
    fun headerOf(blob: ByteArray): Header? {
        if (blob.size < HEADER_FIXED_BYTES) return null
        if (blob[0] != FORMAT_VERSION) return null
        val kind = WrapKind.ofId(blob[1]) ?: return null
        val kdf = blob[2]
        if (kdf != KDF_NONE && kdf != KDF_PBKDF2_HMAC_SHA256) return null
        val iterations = ((blob[3].toInt() and 0xFF) shl 24) or
            ((blob[4].toInt() and 0xFF) shl 16) or
            ((blob[5].toInt() and 0xFF) shl 8) or
            (blob[6].toInt() and 0xFF)
        if (iterations < 0) return null
        val saltLength = blob[7].toInt() and 0xFF
        val bodyAt = HEADER_FIXED_BYTES + saltLength
        if (blob.size <= bodyAt) return null
        // A KDF-less wrap carries no salt and a KDF-ed one must carry a real salt. Both halves are
        // checked, because a blob claiming PBKDF2 with a zero-length salt would derive the same key
        // for everybody on earth.
        if (kdf == KDF_NONE && saltLength != 0) return null
        if (kdf == KDF_PBKDF2_HMAC_SHA256 && saltLength < SALT_BYTES) return null
        return Header(kind, kdf, iterations, blob.copyOfRange(HEADER_FIXED_BYTES, bodyAt), bodyAt)
    }

    private fun header(kind: WrapKind, kdf: Byte, iterations: Int, salt: ByteArray): ByteArray {
        require(salt.size <= 255) { "salt too long for the one-byte length field" }
        return byteArrayOf(
            FORMAT_VERSION,
            kind.id,
            kdf,
            (iterations ushr 24).toByte(),
            (iterations ushr 16).toByte(),
            (iterations ushr 8).toByte(),
            iterations.toByte(),
            salt.size.toByte(),
        ) + salt
    }

    /**
     * PBKDF2-HMAC-SHA256 to 256 bits.
     *
     * `CharArray` rather than `String` so the caller can zero it; a String would sit in the heap
     * until a garbage collection that may never come while the app is alive.
     */
    fun deriveKey(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "a key derivation with no iterations is not one" }
        require(salt.size >= SALT_BYTES) { "salt is shorter than $SALT_BYTES bytes" }
        val spec = PBEKeySpec(secret, salt, iterations, DATA_KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The data key under a key derived from something a person knows (the PIN) or holds (the code).
     *
     * [aeadFor] takes the derived key and returns something that can seal with it. In production it
     * is `::AesGcmAead`; in a test it can be anything, which is what lets the blob format be
     * exercised without a real cipher in the way.
     */
    fun sealWithSecret(
        kind: WrapKind,
        secret: CharArray,
        dataKey: ByteArray,
        salt: ByteArray,
        iterations: Int,
        aeadFor: (ByteArray) -> Aead,
    ): ByteArray {
        require(kind != WrapKind.KEYSTORE) { "the keystore wrap derives nothing from a secret" }
        require(dataKey.size == DATA_KEY_BYTES) { "a data key is $DATA_KEY_BYTES bytes" }
        val head = header(kind, KDF_PBKDF2_HMAC_SHA256, iterations, salt)
        val derived = deriveKey(secret, salt, iterations)
        try {
            return head + aeadFor(derived).seal(dataKey, head)
        } finally {
            derived.fill(0)
        }
    }

    /** The data key under a key the device holds and this code never sees. */
    fun sealWithDeviceKey(dataKey: ByteArray, aead: Aead): ByteArray {
        require(dataKey.size == DATA_KEY_BYTES) { "a data key is $DATA_KEY_BYTES bytes" }
        val head = header(WrapKind.KEYSTORE, KDF_NONE, 0, ByteArray(0))
        return head + aead.seal(dataKey, head)
    }

    /**
     * The data key back out of a secret-derived wrap, or null.
     *
     * [expected] is required rather than read off the blob, so that a caller holding a PIN can never
     * be handed the recovery wrap to open, or the other way round. The kind is inside the
     * authenticated header, so this is a real check and not a courtesy.
     */
    fun openWithSecret(
        blob: ByteArray,
        expected: WrapKind,
        secret: CharArray,
        aeadFor: (ByteArray) -> Aead,
    ): ByteArray? {
        val head = headerOf(blob) ?: return null
        if (head.kind != expected) return null
        if (head.kdf != KDF_PBKDF2_HMAC_SHA256) return null
        if (head.iterations <= 0) return null
        val derived = try {
            deriveKey(secret, head.salt, head.iterations)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: GeneralSecurityException) {
            // An empty secret, or a provider that will not take these parameters. Same answer as
            // every other failure: null, and nothing said about which one it was.
            return null
        }
        try {
            val opened = aeadFor(derived).open(
                blob.copyOfRange(head.bodyAt, blob.size),
                head.associatedData(blob),
            ) ?: return null
            // A blob that opens but does not contain a data key is not a data key. Anything that
            // got this far is authenticated, so this is a format check rather than a defence, and
            // it is here so a length bug cannot be handed to SQLCipher as a key.
            return if (opened.size == DATA_KEY_BYTES) opened else null
        } finally {
            derived.fill(0)
        }
    }

    /** The data key back out of the device-held wrap, or null. */
    fun openWithDeviceKey(blob: ByteArray, aead: Aead): ByteArray? {
        val head = headerOf(blob) ?: return null
        if (head.kind != WrapKind.KEYSTORE) return null
        if (head.kdf != KDF_NONE) return null
        val opened = aead.open(
            blob.copyOfRange(head.bodyAt, blob.size),
            head.associatedData(blob),
        ) ?: return null
        return if (opened.size == DATA_KEY_BYTES) opened else null
    }

    /**
     * SQLCipher's raw-key form: `x'<64 hex characters>'`, as ASCII bytes.
     *
     * WHY THIS SPELLING AND NOT JUST THE 32 BYTES. Handed an arbitrary byte string, SQLCipher treats
     * it as a PASSPHRASE and runs its own PBKDF2 over it on every open — a quarter of a second of
     * work to turn a key that is already 256 uniform bits into a different 256 bits, every single
     * time the database opens. Handed exactly this spelling, it recognises a raw key and uses it as
     * given. The `x'...'` wrapper and lower-case hex are not cosmetic: they are the thing SQLCipher
     * pattern-matches on.
     */
    fun rawKeySpelling(dataKey: ByteArray): ByteArray {
        require(dataKey.size == DATA_KEY_BYTES) { "a data key is $DATA_KEY_BYTES bytes" }
        val hex = StringBuilder(DATA_KEY_BYTES * 2 + 3)
        hex.append("x'")
        for (b in dataKey) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4])
            hex.append(HEX[v and 0x0F])
        }
        hex.append('\'')
        return ByteArray(hex.length) { hex[it].code.toByte() }
    }

    private const val HEX = "0123456789abcdef"
}
