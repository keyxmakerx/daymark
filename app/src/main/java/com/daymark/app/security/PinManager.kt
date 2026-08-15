package com.daymark.app.security

import android.content.SharedPreferences
import android.os.SystemClock
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
            .putLong(KEY_LOCK_ELAPSED_START, 0L)
            .putLong(KEY_LOCK_DURATION, 0L)
            .apply()
        // Drop any legacy plaintext-prefs hash now that the secure one exists.
        settings.pinHash = null
    }

    fun clearPin() {
        securePrefs.edit()
            .remove(KEY_SALT).remove(KEY_HASH)
            .remove(KEY_FAILS).remove(KEY_LOCK_UNTIL)
            .remove(KEY_LOCK_ELAPSED_START).remove(KEY_LOCK_DURATION)
            .apply()
        settings.pinHash = null
    }

    /** True while the user is in a cool-down after too many wrong PINs. */
    fun isLockedOut(): Boolean = lockRemainingMillis() > 0

    /** Milliseconds left in the current cool-down, or 0 if not locked out. */
    fun lockRemainingMillis(): Long =
        LockoutDeadline(
            wallUntilMs = securePrefs.getLong(KEY_LOCK_UNTIL, 0L),
            elapsedStartMs = securePrefs.getLong(KEY_LOCK_ELAPSED_START, 0L),
            durationMs = securePrefs.getLong(KEY_LOCK_DURATION, 0L),
            capMs = MAX_BACKOFF_MS,
        ).remainingMs(System.currentTimeMillis(), SystemClock.elapsedRealtime())

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
        securePrefs.edit()
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .putLong(KEY_LOCK_ELAPSED_START, 0L)
            .putLong(KEY_LOCK_DURATION, 0L)
            .apply()
    }

    private fun recordFailure() {
        val fails = securePrefs.getInt(KEY_FAILS, 0) + 1
        val edit = securePrefs.edit().putInt(KEY_FAILS, fails)
        if (fails >= FREE_ATTEMPTS) {
            // Exponential backoff after the free attempts, capped.
            val steps = fails - FREE_ATTEMPTS
            val delay = (BASE_BACKOFF_MS * (1L shl steps.coerceAtMost(MAX_BACKOFF_SHIFT)))
                .coerceAtMost(MAX_BACKOFF_MS)
            // Both clocks, because neither alone survives the threat here: the wall clock is
            // attacker-settable from the lock screen, and elapsedRealtime() is wiped by a reboot.
            // See [LockoutDeadline] for how the two are combined.
            edit.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + delay)
                .putLong(KEY_LOCK_ELAPSED_START, SystemClock.elapsedRealtime())
                .putLong(KEY_LOCK_DURATION, delay)
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
        const val KEY_LOCK_ELAPSED_START = "pin_lock_elapsed_start"
        const val KEY_LOCK_DURATION = "pin_lock_duration"
        const val SALT_BYTES = 16
        const val ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val LEGACY_SALT = "daymark::pin::v1::"

        // Lockout policy: 5 free tries, then exponential backoff capped at 5 minutes.
        const val FREE_ATTEMPTS = 5
        const val BASE_BACKOFF_MS = 15_000L
        const val MAX_BACKOFF_SHIFT = 5
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}

/**
 * How much of a wrong-PIN cool-down is left, given the two clocks Android offers.
 *
 * The cool-down used to be a single `System.currentTimeMillis()` deadline. Anyone holding a locked
 * phone could open the system clock settings from the notification shade, step the date forward,
 * and the cool-down was gone — so the exponential backoff never actually cost an attacker anything
 * and the 5-minute cap was decoration. Walking all 10,000 four-digit PINs went from roughly a
 * month of enforced waiting to however fast someone can type.
 *
 * `SystemClock.elapsedRealtime()` can't be set, but it restarts at zero on reboot, so it can't be
 * the only record either — otherwise "hold power, reboot, guess again" replaces "change the clock,
 * guess again". So both are stored and the STRICTER (longer) remainder wins:
 *
 *  - Wall clock moved forward: the monotonic remainder is untouched and keeps the lockout.
 *  - Reboot ([nowElapsedMs] has gone backwards past [elapsedStartMs]): the monotonic evidence is
 *    gone, so the cool-down is treated as freshly started rather than as served. A reboot can only
 *    ever cost an attacker time.
 *  - Wall clock moved backward (deliberately, or by an NTP correction after a bad RTC): the result
 *    is clamped to [durationMs], so the worst a wrong wall clock can do to an honest person is make
 *    them serve one full cool-down — at most 5 minutes — instead of the seconds they had left.
 *    That asymmetry is the tradeoff taken here: locking a distressed person out of their own
 *    journal for up to 5 extra minutes is a smaller harm than handing an attacker unlimited guesses.
 */
internal class LockoutDeadline(
    private val wallUntilMs: Long,
    private val elapsedStartMs: Long,
    private val durationMs: Long,
    private val capMs: Long,
) {
    fun remainingMs(nowWallMs: Long, nowElapsedMs: Long): Long {
        val wallRemaining = wallUntilMs - nowWallMs
        if (durationMs <= 0L) {
            // Either there is no cool-down, or it was written by a build that stored the wall
            // deadline alone. Nothing to cross-check against, so honour the wall clock for this
            // one carried-over lockout — bounded by the cap so a stale record can't outlive it.
            return wallRemaining.coerceIn(0L, capMs)
        }
        val elapsedRemaining = if (nowElapsedMs < elapsedStartMs) {
            durationMs
        } else {
            elapsedStartMs + durationMs - nowElapsedMs
        }
        return maxOf(wallRemaining, elapsedRemaining).coerceIn(0L, durationMs)
    }
}
