package com.daymark.app.sync

import android.content.SharedPreferences
import com.daymark.app.security.KeystoreAead
import com.daymark.synccrypto.KeptClinicians
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The clinicians this phone has added (#174), as [KeptClinicians] bytes sealed under a keystore key of
 * their own, in the encrypted preferences the server link is kept in ([ServerLinkStore]).
 *
 * The bytes hold each relationship's inbox token, which the server keeps only a fingerprint of, so this
 * is the one copy there is. That is why a wrap the keystore can no longer open reads as [Reading.Unreadable]
 * and never as an empty list: an empty list would let the next clinician added write over the tokens.
 * Every call runs the keystore, so none belongs on the main thread. Nothing is logged.
 */
@Singleton
class ClinicianStore @Inject constructor(
    @Named("secure") private val securePrefs: SharedPreferences,
) {
    private val keystore = KeystoreAead(ALIAS)

    sealed interface Reading {
        class Kept(val clinicians: KeptClinicians) : Reading

        /** Something is kept and cannot be read here. Nothing may be written over it. */
        object Unreadable : Reading
    }

    @Synchronized
    fun read(): Reading {
        val encoded = securePrefs.getString(KEY, null) ?: return Reading.Kept(KeptClinicians.EMPTY)
        val sealed = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return Reading.Unreadable
        }
        val bytes = keystore.open(sealed, AAD) ?: return Reading.Unreadable
        try {
            return KeptClinicians.decode(bytes)?.let { Reading.Kept(it) } ?: Reading.Unreadable
        } finally {
            bytes.fill(0)
        }
    }

    /** Keeps [clinicians], replacing what was kept, before it returns. */
    @Synchronized
    fun keep(clinicians: KeptClinicians) {
        val bytes = clinicians.encode()
        try {
            val sealed = keystore.seal(bytes, AAD)
            // commit(), not apply(): a run's scalar must be on disk before its code is on screen.
            check(securePrefs.edit().putString(KEY, Base64.getEncoder().encodeToString(sealed)).commit()) { "not kept" }
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        /** The keystore alias the clinicians are sealed under, and nothing else. */
        const val ALIAS = "daymark_clinicians_v1"
        const val KEY = "clinicians_v1"
        val AAD = "daymark.clinicians.v1".toByteArray(Charsets.US_ASCII)
    }
}
