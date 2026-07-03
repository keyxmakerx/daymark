package com.daymark.companion.auth

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Server-side secret handling. IMPORTANT SCOPE NOTE: none of this touches the E2EE key
 * hierarchy (that is client-side only; the server holds nothing that decrypts a record). It
 * is used ONLY to:
 *
 *  - hash the low-entropy *authenticating* secrets we must store at rest (invite code, TOTP
 *    secret) with Argon2id, so a DB leak does not hand over the plaintext secret;
 *  - derive an opaque, non-reversible routing id (`relRef`) from an inbox token with BLAKE2b,
 *    exactly like the sync bearer token is only ever compared, never printed;
 *  - hash session / rel tokens for lookup (BLAKE2b) so the DB never stores a live bearer.
 *
 * Constant-time compares (via MessageDigest.isEqual) are used everywhere a caller-supplied
 * secret is checked. See docs/COMPANION_SECURITY.md §4/§5.
 */
object Secrets {
    private val rng = SecureRandom()
    private val B64URL = Base64.getUrlEncoder().withoutPadding()
    private val B64URL_DEC = Base64.getUrlDecoder()

    // Argon2id parameters for at-rest hashing of authenticating secrets. These are modest by
    // design: the secrets we hash (invite codes, TOTP secrets) are high-entropy tokens, so the
    // KDF cost is defense-in-depth against a DB leak, not the primary barrier. Tunable but
    // fixed here so a stored hash is self-describing by its embedded salt.
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_MEM_KIB = 65_536 // 64 MiB
    private const val ARGON_PARALLELISM = 1
    private const val ARGON_HASH_LEN = 32
    private const val SALT_LEN = 16

    /** 256-bit CSPRNG token, base64url no-padding. Used for invite secrets and session ids. */
    fun newToken(bytes: Int = 32): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return B64URL.encodeToString(b)
    }

    fun b64url(bytes: ByteArray): String = B64URL.encodeToString(bytes)

    /**
     * Opaque, non-reversible routing id from an inbox token: base64url(BLAKE2b-256(token)).
     * The raw token is presented per-request and never stored; only this digest is persisted,
     * and it fits BlobStore's strict charset. (We take the first 32 chars to stay well under
     * the 64-char limit while keeping 192 bits of the digest.)
     */
    fun relRefOf(inboxToken: String): String {
        val d = blake2b(inboxToken.toByteArray(Charsets.UTF_8), 32)
        // base64url of 32 bytes = 43 chars; keep all of it (<= 64, strict charset ok).
        return B64URL.encodeToString(d)
    }

    /** BLAKE2b digest of [data] to [lenBytes] bytes. */
    fun blake2b(data: ByteArray, lenBytes: Int): ByteArray {
        val dig = Blake2bDigest(lenBytes * 8)
        dig.update(data, 0, data.size)
        val out = ByteArray(lenBytes)
        dig.doFinal(out, 0)
        return out
    }

    /** BLAKE2b-256 of a token, base64url — used to store a session/token lookup key at rest. */
    fun tokenHash(token: String): String = B64URL.encodeToString(blake2b(token.toByteArray(Charsets.UTF_8), 32))

    /**
     * Argon2id hash of [secret], returned as a self-describing string
     * `argon2id$<b64url salt>$<b64url hash>` so verification needs no separate salt column.
     */
    fun hashSecret(secret: String): String {
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val hash = argon2(secret.toByteArray(Charsets.UTF_8), salt)
        return "argon2id\$${B64URL.encodeToString(salt)}\$${B64URL.encodeToString(hash)}"
    }

    /** Constant-time verify of [secret] against a stored [encoded] hash from [hashSecret]. */
    fun verifySecret(secret: String, encoded: String?): Boolean {
        if (encoded == null) return false
        val parts = encoded.split('$')
        if (parts.size != 3 || parts[0] != "argon2id") return false
        val salt = try { B64URL_DEC.decode(parts[1]) } catch (_: Exception) { return false }
        val expected = try { B64URL_DEC.decode(parts[2]) } catch (_: Exception) { return false }
        val actual = argon2(secret.toByteArray(Charsets.UTF_8), salt)
        return MessageDigest.isEqual(actual, expected)
    }

    /** Length-independent constant-time compare. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun argon2(password: ByteArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON_ITERATIONS)
            .withMemoryAsKB(ARGON_MEM_KIB)
            .withParallelism(ARGON_PARALLELISM)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator().also { it.init(params) }
        val out = ByteArray(ARGON_HASH_LEN)
        gen.generateBytes(password, out)
        return out
    }
}
