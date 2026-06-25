package com.daymark.app.security

import android.content.SharedPreferences
import android.util.Base64
import com.daymark.app.data.SettingsRepository
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Handles the app-lock PIN. The PIN itself is never stored — only a PBKDF2 hash with a
 * random per-PIN salt, kept in an AES-256 [EncryptedSharedPreferences] store.
 *
 * PINs set by older versions (a static-salt SHA-256 hash in plain prefs) are still accepted
 * and transparently upgraded to the stronger scheme on the next successful unlock.
 */
@Singleton
class PinManager @Inject constructor(
    @Named("secure") private val securePrefs: SharedPreferences,
    private val settings: SettingsRepository,
) {
    val isPinSet: Boolean
        get() = !securePrefs.getString(KEY_HASH, null).isNullOrEmpty() ||
            !settings.pinHash.isNullOrEmpty()

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        securePrefs.edit()
            .putString(KEY_SALT, salt.toBase64())
            .putString(KEY_HASH, hash.toBase64())
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
        // Drop any legacy plaintext-prefs hash now that the secure one exists.
        settings.pinHash = null
    }

    fun clearPin() {
        securePrefs.edit()
            .remove(KEY_SALT).remove(KEY_HASH)
            .remove(KEY_FAILS).remove(KEY_LOCK_UNTIL)
            .apply()
        settings.pinHash = null
    }

    /** True while the user is in a cool-down after too many wrong PINs. */
    fun isLockedOut(): Boolean = lockRemainingMillis() > 0

    /** Milliseconds left in the current cool-down, or 0 if not locked out. */
    fun lockRemainingMillis(): Long =
        (securePrefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun verify(pin: String): Boolean {
        if (isLockedOut()) return false

        val storedHash = securePrefs.getString(KEY_HASH, null)
        val storedSalt = securePrefs.getString(KEY_SALT, null)
        val ok = when {
            storedHash != null && storedSalt != null ->
                constantTimeEquals(pbkdf2(pin, storedSalt.fromBase64()), storedHash.fromBase64())
            // Legacy fallback: verify against the old static-salt SHA-256, then migrate.
            !settings.pinHash.isNullOrEmpty() -> {
                val matched = constantTimeEquals(legacyHash(pin).toByteArray(), settings.pinHash!!.toByteArray())
                if (matched) setPin(pin)
                matched
            }
            else -> false
        }

        if (ok) resetAttempts() else recordFailure()
        return ok
    }

    private fun resetAttempts() {
        securePrefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
    }

    private fun recordFailure() {
        val fails = securePrefs.getInt(KEY_FAILS, 0) + 1
        val edit = securePrefs.edit().putInt(KEY_FAILS, fails)
        if (fails >= FREE_ATTEMPTS) {
            // Exponential backoff after the free attempts, capped.
            val steps = fails - FREE_ATTEMPTS
            val delay = (BASE_BACKOFF_MS * (1L shl steps.coerceAtMost(MAX_BACKOFF_SHIFT)))
                .coerceAtMost(MAX_BACKOFF_MS)
            edit.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + delay)
        }
        edit.apply()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun legacyHash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((LEGACY_SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    fun clearAttempts() = resetAttempts()

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILS = "pin_fail_count"
        const val KEY_LOCK_UNTIL = "pin_lock_until"
        const val SALT_BYTES = 16
        const val ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val LEGACY_SALT = "daylie::pin::v1::"

        // Lockout policy: 5 free tries, then exponential backoff capped at 5 minutes.
        const val FREE_ATTEMPTS = 5
        const val BASE_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_SHIFT = 5
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}
