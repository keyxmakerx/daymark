package com.daymark.app.security

import android.content.SharedPreferences
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** What asking this phone for the journal's key produced. */
sealed interface DataKeyResult {

    class Available(val key: ByteArray) : DataKeyResult

    /**
     * There is a wrapped key on this phone and the hardware keystore can no longer open it.
     *
     * Rare, and real: a firmware update that clears the TEE, a factory-reset-protection event, some
     * OEM behaviour nobody wrote down. The journal that key opens cannot be read by anything, ever.
     * The one thing that must NOT happen here is a fresh key being made — it does not open the old
     * file, and handing it to SQLCipher surfaces later as "file is not a database" with nothing
     * pointing at the cause.
     */
    data object Lost : DataKeyResult

    /**
     * This device cannot hold a key at all — its keystore will not generate one. Nothing was ever
     * encrypted, so nothing is lost; the app carries on exactly as it did before any of this.
     */
    data object Unsupported : DataKeyResult
}

/**
 * The one data key the journal is encrypted with, and the only place one is ever made.
 *
 * ## What this does today
 *
 * Makes a random 32-byte key on first run — for everybody, whether or not they ever set a PIN — and
 * keeps a copy of it wrapped under a key held in the phone's hardware keystore. That wrap is what
 * makes the database file device-bound: an image of app-private storage taken off the phone holds
 * the encrypted journal and a wrap that nothing in the image can open.
 *
 * Nothing is asked of the person. There is no PIN in this, no code to write down, nothing that can
 * be forgotten — which is the point: the improvement arrives for everybody, rather than for whoever
 * happens to turn a setting on.
 *
 * ## What this deliberately does NOT do yet
 *
 * `DataKeyWraps` can also wrap this key under a key derived from a PIN and under one derived from a
 * written-down recovery code. Both are built and tested and nothing calls them, because arming them
 * means the keystore wrap has to go away when a PIN is set — otherwise the app opens the journal
 * without ever asking, and "if you forget the PIN, only your recovery code opens them" is a false
 * sentence over a true switch.
 *
 * And a journal that will not open without a PIN is one the reminder alarms cannot read either:
 * `BootReceiver` re-arms every reminder from the database after a restart and `ReminderReceiver`
 * reads its row when the alarm fires, both from a cold process with nobody having typed anything.
 * Daily reminders quietly stopping for everybody who sets a PIN is a decision about somebody's
 * product and somebody's users, and it is not made here. `docs/DECISIONS.md` has it.
 */
@Singleton
class DataKeyStore @Inject constructor(
    @Named("secure") private val securePrefs: SharedPreferences,
) {

    private val keystore = KeystoreAead(KeystoreAead.DATA_KEY_ALIAS)

    @Volatile
    private var cached: ByteArray? = null

    /**
     * The key the database is encrypted with, making one on first use.
     *
     * Synchronized because Hilt calls this from whichever thread first asks for a DAO, and two
     * threads racing here would make two keys, of which only one could ever open the file.
     */
    @Synchronized
    fun dataKey(): DataKeyResult {
        cached?.let { return DataKeyResult.Available(it) }

        val stored = readWrap()
        if (stored != null) {
            val opened = DataKeyWraps.openWithDeviceKey(stored, keystore)
                ?: return DataKeyResult.Lost
            cached = opened
            return DataKeyResult.Available(opened)
        }

        return mintAndStore()
    }

    /**
     * Throw away this journal's key and begin an empty one.
     *
     * The only operation here that destroys anything, and nothing calls it except a person choosing
     * it on a screen that says what it removes. The database FILE is deleted by the caller, which
     * owns the paths; this end of it drops the wrap and the keystore key and makes a new one.
     *
     * Photos are not touched, here or by the caller. They were never encrypted and are not part of
     * what could not be opened, so removing them would be destroying something for no reason — and
     * the screen says so, rather than leaving somebody to find out.
     */
    @Synchronized
    fun startNewJournal(): DataKeyResult {
        cached = null
        // The key goes as well as the wrap. A keystore alias nothing can account for is how a
        // keystore fills up with keys nobody dares delete.
        keystore.deleteKey()
        securePrefs.edit().remove(KEY_KEYSTORE_WRAP).commit()
        return mintAndStore()
    }

    private fun mintAndStore(): DataKeyResult {
        val fresh = DataKeyWraps.newDataKey()
        val wrap = try {
            DataKeyWraps.sealWithDeviceKey(fresh, keystore)
        } catch (_: Exception) {
            // A device whose keystore will not generate an AES key at all. Not something this class
            // can work around: with nowhere to keep the key between launches, encrypting the journal
            // with it would lose the journal the moment the process ends.
            fresh.fill(0)
            return DataKeyResult.Unsupported
        }
        writeWrap(wrap)
        cached = fresh
        return DataKeyResult.Available(fresh)
    }

    private fun readWrap(): ByteArray? {
        val encoded = securePrefs.getString(KEY_KEYSTORE_WRAP, null) ?: return null
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeWrap(wrap: ByteArray) {
        // commit(), not apply(). This is written immediately before the key is used to encrypt a
        // database, and a process killed between the two would leave a file nothing can open.
        // apply() is exactly that window.
        securePrefs.edit()
            .putString(KEY_KEYSTORE_WRAP, Base64.getEncoder().encodeToString(wrap))
            .commit()
    }

    private companion object {
        // java.util.Base64 rather than android.util.Base64: it exists from API 26, this app's
        // minSdk, and unlike the Android one it also runs in a JVM unit test.
        const val KEY_KEYSTORE_WRAP = "data_key_wrap_keystore"
    }
}
