package com.daylie.app.security

import android.content.SharedPreferences
import android.util.Base64
import com.daylie.app.data.SettingsRepository
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
            .apply()
        // Drop any legacy plaintext-prefs hash now that the secure one exists.
        settings.pinHash = null
    }

    fun clearPin() {
        securePrefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
        settings.pinHash = null
    }

    fun verify(pin: String): Boolean {
        val storedHash = securePrefs.getString(KEY_HASH, null)
        val storedSalt = securePrefs.getString(KEY_SALT, null)
        if (storedHash != null && storedSalt != null) {
            val computed = pbkdf2(pin, storedSalt.fromBase64())
            return constantTimeEquals(computed, storedHash.fromBase64())
        }
        // Legacy fallback: verify against the old static-salt SHA-256, then migrate.
        val legacy = settings.pinHash
        if (!legacy.isNullOrEmpty()) {
            val ok = constantTimeEquals(legacyHash(pin).toByteArray(), legacy.toByteArray())
            if (ok) setPin(pin)
            return ok
        }
        return false
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

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val KEY_BITS = 256
        const val LEGACY_SALT = "daylie::pin::v1::"
    }
}
