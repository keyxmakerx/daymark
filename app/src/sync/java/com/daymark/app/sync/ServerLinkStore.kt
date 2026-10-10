package com.daymark.app.sync

import android.content.SharedPreferences
import com.daymark.app.security.KeystoreAead
import com.daymark.synccrypto.KeptLink
import com.daymark.synccrypto.PairedServer
import com.daymark.synccrypto.PairingPayloads
import com.daymark.synccrypto.PhoneLineage
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * What this phone keeps about its Companion server (#432), sealed at rest under a key of the phone's
 * own hardware keystore that never leaves it ([KeystoreAead], the helper the journal's own key is kept
 * with), then written to the encrypted preferences the PIN's hash is kept in.
 *
 * - THE LINK ([KeptLink]): the server's address, the key's id and seed, and once the passphrase has
 *   opened it the sync key and the ETag of the key document it came from, and the last copy sent. It is
 *   written only once the registration poll answers `registered`: a pending key is held in memory and
 *   nowhere else. Forgetting the pairing removes it.
 * - THE OWNER'S PUBLIC PAIRING KEYS, which opening the sync key also yields: what approving a
 *   clinician seals back to them (#174). Public, but kept sealed with the rest, and forgotten with the
 *   link, since only the passphrase that opened them can show they are the owner's.
 * - THE OWNER'S PRIVATE BOX KEY, which opens the game plans and assignments clinicians seal to the
 *   owner (#177). Secret, so kept exactly as the sync key is, and forgotten with the link.
 * - THE LINEAGE: the name of this phone's copies on the server, made once for this phone and kept on
 *   its own, so pairing again sends to the same one.
 *
 * Each is sealed with its own associated data, so neither can be read back as the other. A wrap the
 * keystore can no longer open reads as nothing kept: the phone pairs again. Every call here runs the
 * keystore, so none belongs on the main thread. Nothing is logged.
 */
@Singleton
class ServerLinkStore @Inject constructor(
    @Named("secure") private val securePrefs: SharedPreferences,
    private val parts: ServerSyncParts,
) {
    private val keystore = KeystoreAead(ALIAS)

    /** The link this phone keeps, or null: never paired, forgotten, or no longer readable here. */
    @Synchronized
    fun read(): KeptLink? {
        val bytes = openFromPrefs(LINK, LINK_AAD) ?: return null
        try {
            return KeptLink.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** Keeps [link], replacing what was kept. The caller still owns [link], and wipes it. */
    @Synchronized
    fun keep(link: KeptLink) {
        val bytes = link.encode()
        try {
            sealToPrefs(LINK, LINK_AAD, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** Keeps the server the poll has just said registered this phone. */
    fun keepPaired(server: PairedServer) {
        val link = KeptLink.of(server)
        try {
            keep(link)
        } finally {
            link.wipe()
        }
    }

    /** Forgets the pairing and any sync key with it. The lineage stays: it is this phone's, not the pairing's. */
    @Synchronized
    fun forgetLink() {
        // commit(), not apply(): a phone told it was disconnected must not find the pairing again.
        securePrefs.edit().remove(LINK).remove(OWNER_KEYS).remove(OWNER_BOX_SECRET).commit()
    }

    /**
     * Keeps the private half of the owner's box key, which opens what clinicians seal to them (#177).
     * Kept as the sync key is kept, and forgotten with the link. The caller still owns [secret], and wipes it.
     */
    @Synchronized
    fun keepOwnerBoxSecret(secret: ByteArray) {
        sealToPrefs(OWNER_BOX_SECRET, OWNER_BOX_SECRET_AAD, secret)
    }

    /** A copy of the owner's private box key, which the caller wipes; null until the passphrase has opened it here. */
    @Synchronized
    fun ownerBoxSecret(): ByteArray? = openFromPrefs(OWNER_BOX_SECRET, OWNER_BOX_SECRET_AAD)?.takeIf { it.size == BOX_SECRET_BYTES }

    /** Keeps the owner's public pairing keys, which the passphrase opened with the sync key. */
    @Synchronized
    fun keepOwnerKeys(keys: PairingPayloads.OwnerKeys) {
        sealToPrefs(OWNER_KEYS, OWNER_KEYS_AAD, "${keys.boxPubB64}\n${keys.signPubB64}".toByteArray(Charsets.US_ASCII))
    }

    /** The owner's public pairing keys, or null until the passphrase has opened the key on this phone. */
    @Synchronized
    fun ownerKeys(): PairingPayloads.OwnerKeys? {
        val bytes = openFromPrefs(OWNER_KEYS, OWNER_KEYS_AAD) ?: return null
        val parts = String(bytes, Charsets.US_ASCII).split('\n')
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) PairingPayloads.OwnerKeys(parts[0], parts[1]) else null
    }

    /** This phone's lineage, made the first time it is asked for and the same from then on. */
    @Synchronized
    fun lineage(): String {
        openFromPrefs(LINEAGE, LINEAGE_AAD)?.let { bytes ->
            val kept = String(bytes, Charsets.US_ASCII)
            if (PhoneLineage.isPhoneLineage(kept)) return kept
        }
        val made = PhoneLineage.create(parts.sodium)
        sealToPrefs(LINEAGE, LINEAGE_AAD, made.toByteArray(Charsets.US_ASCII))
        return made
    }

    private fun openFromPrefs(name: String, aad: ByteArray): ByteArray? {
        val encoded = securePrefs.getString(name, null) ?: return null
        val sealed = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return keystore.open(sealed, aad)
    }

    private fun sealToPrefs(name: String, aad: ByteArray, bytes: ByteArray) {
        val sealed = keystore.seal(bytes, aad)
        securePrefs.edit().putString(name, Base64.getEncoder().encodeToString(sealed)).commit()
    }

    private companion object {
        /** The keystore alias the server link is sealed under, and nothing else. */
        const val ALIAS = "daymark_server_link_v1"
        const val LINK = "server_link_v1"
        const val LINEAGE = "server_lineage_v1"
        const val OWNER_KEYS = "server_owner_keys_v1"
        const val OWNER_BOX_SECRET = "server_owner_box_secret_v1"
        const val BOX_SECRET_BYTES = 32
        val LINK_AAD = "daymark.server-link.v1".toByteArray(Charsets.US_ASCII)
        val LINEAGE_AAD = "daymark.server-lineage.v1".toByteArray(Charsets.US_ASCII)
        val OWNER_KEYS_AAD = "daymark.server-owner-keys.v1".toByteArray(Charsets.US_ASCII)
        val OWNER_BOX_SECRET_AAD = "daymark.server-owner-box-secret.v1".toByteArray(Charsets.US_ASCII)
    }
}
