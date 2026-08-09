package com.daymark.companion.auth

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s step, ±1 step drift) built on the JDK's
 * `javax.crypto.Mac` — no third-party dependency. Verification is constant-time over the
 * candidate window and the digits are compared with [MessageDigest.isEqual].
 *
 * HONEST CUSTODY NOTE: a TOTP verifier fundamentally MUST hold the seed to recompute the
 * expected code, so the seed is stored as-is (base64url) in `AuthStore`, not Argon2id-hashed
 * (a hash cannot regenerate codes). This is the docs' honestly-weaker therapist-auth path
 * (COMPANION_SECURITY.md §5.2 / §11): TOTP is a phishable, server-stored authenticating
 * secret that NEVER unlocks any E2EE reading key — the server still holds nothing that
 * decrypts a record. Invite secrets, by contrast, only need verify (not regeneration) and so
 * ARE Argon2id-hashed at rest.
 */
object Totp {
    private const val DIGITS = 6
    private const val STEP_SECONDS = 30L
    private const val DRIFT_STEPS = 1

    /** Generate the 6-digit code for [secret] at [epochSeconds]. */
    fun code(secret: ByteArray, epochSeconds: Long): String = codeForCounter(secret, epochSeconds / STEP_SECONDS)

    /**
     * Constant-time verify: true if [presented] equals the code for any step within ±1 of
     * [epochSeconds]. The loop always runs the full window (no early-return timing leak).
     */
    fun verify(secret: ByteArray, presented: String, epochSeconds: Long): Boolean =
        verifyStep(secret, presented, epochSeconds) != null

    /**
     * Constant-time verify that returns **which step matched**, or null.
     *
     * The step is what makes a code single-use. RFC 6238 §5.2 requires that a code be accepted at
     * most once — otherwise anyone who observes one (shoulder-surfing, a screenshot, a phished
     * prompt) can replay it for the remainder of the ±90s window, and each acceptance here mints an
     * independent 8-hour session that outlives the victim logging out. Callers must pass the
     * returned step to `AuthStore.consumeTotpStep`, which rejects any step already spent.
     *
     * Same constant-time property as before: the loop always runs the full window rather than
     * returning early, so response timing does not reveal which step matched.
     */
    fun verifyStep(secret: ByteArray, presented: String, epochSeconds: Long): Long? {
        val counter = epochSeconds / STEP_SECONDS
        var matched = Long.MIN_VALUE
        for (d in -DRIFT_STEPS..DRIFT_STEPS) {
            val candidate = codeForCounter(secret, counter + d)
            if (MessageDigest.isEqual(candidate.toByteArray(Charsets.US_ASCII), presented.toByteArray(Charsets.US_ASCII))) {
                matched = counter + d
            }
        }
        return if (matched == Long.MIN_VALUE) null else matched
    }

    private fun codeForCounter(secret: ByteArray, counter: Long): String {
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = (hash[hash.size - 1].toInt() and 0x0f)
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000
        return otp.toString().padStart(DIGITS, '0')
    }
}
