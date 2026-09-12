package com.daymark.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The [Aead] whose key lives in the phone's hardware-backed keystore and never comes out.
 *
 * ## What this buys
 *
 * The key material behind [alias] is generated inside the TEE and is not readable by this process,
 * by root, or by anything that copies the phone's storage — only *usable*, and only on this phone.
 * So a database wrapped with it is device-bound: an image of `/data/data/com.daymark.app/` taken
 * off the phone contains the encrypted journal and a wrap that nothing in the image can open.
 *
 * The issue's own words for why that costs nothing: with `android:allowBackup="false"` and no sync
 * in the `foss` flavour, losing the device is already losing the data. Binding the file to the
 * device takes nothing further away from anybody.
 *
 * ## What it does not buy, and why the PIN wrap replaces it
 *
 * A key with no authentication requirement is usable by this app whenever this app runs. So while
 * this wrap is the only one, the journal opens without anybody being asked for anything — which is
 * correct for someone who has set no PIN, and would make a liar of the settings copy for someone who
 * has. `DataKeyStore` therefore deletes this wrap, and this key, when a PIN of six or more digits is
 * set. See the note at the top of `DataKeyWraps.kt`.
 *
 * ## The parameters, and the two that are deliberately absent
 *
 * AES-256-GCM, no padding, randomized IV required. No `setUserAuthenticationRequired` and no
 * `setIsStrongBoxBacked`, both stated here because their absence is a decision rather than an
 * oversight:
 *
 *  - **No user-authentication requirement.** It sounds like free security and is not. It ties the
 *    key to the device's lock screen, so a person who changes or removes their phone's screen lock
 *    finds the key permanently invalidated on most devices, and their journal unopenable — a
 *    catastrophic loss caused by an unrelated settings change they had no reason to connect to this
 *    app. The PIN wrap is how a person asks for their journal to require something; it is not
 *    imposed sideways through the OS lock.
 *  - **No StrongBox requirement.** StrongBox exists only on some devices, and requiring it makes key
 *    generation throw `StrongBoxUnavailableException` on the rest. The TEE-backed key this gets
 *    instead is not weaker against the threat that matters here (an image of the storage), and an
 *    app that refuses to start on a mid-range phone is not a security win.
 *
 * ## Nothing here has ever run
 *
 * This class needs the Android Keystore, which exists on a phone and nowhere else. It is not
 * exercised by any test in this repository — CI compiles it and never executes it — and the
 * structural test beside it can only read the source. Treat every claim above as unverified until
 * it has been tried on a real device with a backup taken first.
 */
class KeystoreAead(private val alias: String) : Aead {

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No GCMParameterSpec on the way in, deliberately: the keystore requires that IT generate
        // the IV (setRandomizedEncryptionRequired above), and passing one is an InvalidAlgorithm-
        // ParameterException rather than an override. The IV it chose is read back off the cipher.
        cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate())
        cipher.updateAAD(associatedData)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray? {
        if (sealed.size < IV_BYTES + TAG_BITS / 8) return null
        val key = existingKey() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(associatedData)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /**
     * Whether this device still holds the key.
     *
     * False after [deleteKey], and also false if the keystore lost it — which happens, rarely, on a
     * factory-reset-protection event or an OEM firmware update that clears the TEE. Callers treat
     * both the same way, because from here they are the same thing.
     */
    fun hasKey(): Boolean = existingKey() != null

    /** Remove the key, so that what it wrapped can never be opened by this device again. */
    fun deleteKey() {
        try {
            keystore()?.deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            // Nothing useful to do, and nothing to say: the caller is deleting this because it is
            // replacing it, and a key that will not delete is handled by the wrap being overwritten.
        }
    }

    private fun keystore(): KeyStore? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun existingKey(): SecretKey? = try {
        keystore()?.getKey(alias, null) as? SecretKey
    } catch (_: GeneralSecurityException) {
        // UnrecoverableKeyException lands here: the entry exists but the key behind it does not.
        null
    }

    private fun keyOrCreate(): SecretKey = existingKey() ?: createKey()

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** The alias the journal's device wrap is kept under. */
        const val DATA_KEY_ALIAS = "daymark_data_key_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}
