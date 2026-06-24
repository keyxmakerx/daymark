package com.daylie.app.security

import com.daylie.app.data.SettingsRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the app-lock PIN. The PIN is never stored in plaintext — only a salted
 * SHA-256 hash is kept in local preferences.
 */
@Singleton
class PinManager @Inject constructor(
    private val settings: SettingsRepository,
) {
    val isPinSet: Boolean get() = !settings.pinHash.isNullOrEmpty()

    fun setPin(pin: String) {
        settings.pinHash = hash(pin)
    }

    fun clearPin() {
        settings.pinHash = null
    }

    fun verify(pin: String): Boolean {
        val stored = settings.pinHash ?: return false
        return constantTimeEquals(stored, hash(pin))
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private companion object {
        // Static salt: protects against trivial rainbow-table lookup of short PINs
        // in the local prefs file. Not a secret on a single-user device.
        const val SALT = "daylie::pin::v1::"
    }
}
