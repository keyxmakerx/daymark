package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import java.io.IOException

/**
 * The paired phone's errands with its server: opening the owner's sync key with the passphrase,
 * sending an encrypted copy of the journal (#432), and fetching the newest copy back (#168). Every request is signed
 * ([SignedRequests]); none carries the owner's token.
 *
 * WHAT A STOP ASKS OF THE PHONE ([Then]). A refused signature (401) means the server no longer takes
 * this phone's key: revoked on the web, or every phone disconnected by a re-issued token. The phone
 * forgets the pairing, so it sends no further request the server would refuse, and says so once
 * ([PhoneWords.DISCONNECTED]). A key document other than the one the sync key came from means the
 * passphrase is asked for again. Anything else leaves everything as it was.
 *
 * NO REFUSED REQUEST IS SENT AGAIN, and nothing loops. The one request sent a second time is the write
 * of a copy that met another copy at the same version (409): the versions are read again and the copy
 * is sealed again at the next one, once. Every refusal of a signature counts toward the lockout of the
 * phone's whole network address.
 */
class PhoneSync(
    private val sodium: LazySodium,
    private val transport: Transport,
    private val clock: PhoneClock = PhoneClock.SYSTEM,
) {
    private val crypto = SyncCrypto(sodium)

    /** What the phone must do once an errand stops. */
    enum class Then {
        /** Nothing: everything the phone holds is as it was. */
        TRY_AGAIN,

        /** Forget the sync key and ask for the passphrase again. */
        UNLOCK_AGAIN,

        /** Forget the pairing: the server refuses this phone's key. */
        PAIR_AGAIN,
    }

    /** What opening the key came to. */
    sealed interface Unlock {
        /**
         * The passphrase opened the wrapped key. Keep [syncKey] (subkey 1 of the owner's master) and
         * [keyDocumentTag], the ETag of the document it came from, which every send checks first.
         * [ownerPublic] is the owner's public pairing keys, what approving a clinician seals back to
         * them (#174); public, and the same keys the owner console derives. [ownerBoxSecret] is the
         * private half of the box key, which opens what clinicians seal to the owner (#177): secret,
         * kept as the sync key is kept, and wiped by the caller.
         */
        class Opened internal constructor(
            val syncKey: ByteArray,
            val keyDocumentTag: String,
            val ownerPublic: PairingPayloads.OwnerKeys,
            val ownerBoxSecret: ByteArray,
        ) : Unlock

        class Stopped internal constructor(val words: String, val then: Then) : Unlock
    }

    /** What sending a copy came to. */
    sealed interface Sending {
        /** The server took the copy as [version] of the phone's lineage, at [atMillis] on the phone's clock. */
        class Sent internal constructor(val version: Long, val atMillis: Long) : Sending

        class Stopped internal constructor(val words: String, val then: Then) : Sending
    }

    /** What fetching the newest copy came to. */
    sealed interface Fetching {
        /**
         * [plaintext] is the journal's backup, opened from [version] of [lineage]. Nothing on the phone
         * has changed: the caller shows what the copy holds and asks before it replaces or adds anything.
         */
        class Fetched internal constructor(val lineage: String, val version: Long, val plaintext: ByteArray) : Fetching

        class Stopped internal constructor(val words: String, val then: Then) : Fetching
    }

    /**
     * Reads the owner's key document (`GET /v1/keydoc`, signed) and opens it with [passphrase].
     *
     * Only the wrapped key is opened. Over the key parameters any passphrase derives some key, and a
     * mistyped one would seal every copy under a key nothing else opens, with nothing to tell; the
     * owner console turns the key parameters into the wrapped key when it is next unlocked there
     * (docs/SYNC_PROTOCOL.md §3), so the phone asks for that ([PhoneWords.KEY_NOT_READY]). Argon2id runs
     * here, at the document's parameters, 256 MiB at least: seconds on a phone. Blocks the caller.
     */
    fun unlock(server: PairedServer, passphrase: String): Unlock {
        val answer = try {
            SignedRequests(server, transport, clock).get(KEY_DOCUMENT)
        } catch (_: IOException) {
            return Unlock.Stopped(PhoneWords.UNREACHABLE, Then.TRY_AGAIN)
        }
        when (answer.status) {
            200 -> {}
            401 -> return Unlock.Stopped(PhoneWords.DISCONNECTED, Then.PAIR_AGAIN)
            404 -> return Unlock.Stopped(PhoneWords.NO_KEY_YET, Then.TRY_AGAIN)
            429 -> return Unlock.Stopped(PhoneWords.PAUSED, Then.TRY_AGAIN)
            in 500..599 -> return Unlock.Stopped(PhoneWords.UNREACHABLE, Then.TRY_AGAIN)
            else -> return Unlock.Stopped(PhoneWords.KEY_UNREADABLE, Then.TRY_AGAIN)
        }
        val tag = answer.header(ETAG) ?: return Unlock.Stopped(PhoneWords.KEY_UNREADABLE, Then.TRY_AGAIN)
        val text = Answers.utf8(answer.body) ?: return Unlock.Stopped(PhoneWords.KEY_UNREADABLE, Then.TRY_AGAIN)
        val document = try {
            KeyDocument.parse(text)
        } catch (_: KeyDocumentException) {
            return Unlock.Stopped(PhoneWords.KEY_UNREADABLE, Then.TRY_AGAIN)
        }
        if (document !is KeyDocument.WrappedKey) return Unlock.Stopped(PhoneWords.KEY_NOT_READY, Then.TRY_AGAIN)
        val keys = try {
            crypto.openWithPassphrase(document, passphrase)
        } catch (e: KeyDocumentException) {
            val words = when (e.reason) {
                KeyDocumentException.Reason.DID_NOT_OPEN,
                KeyDocumentException.Reason.NO_SLOT_OF_THAT_KIND,
                -> PhoneWords.WRONG_PASSPHRASE
                else -> PhoneWords.KEY_UNREADABLE
            }
            return Unlock.Stopped(words, Then.TRY_AGAIN)
        } catch (_: SyncCrypto.SyncCryptoException) {
            return Unlock.Stopped(PhoneWords.COULD_NOT_OPEN, Then.TRY_AGAIN)
        }
        keys.manifestSeed.fill(0)
        return Unlock.Opened(keys.syncKey, tag, keys.ownerPublic, keys.ownerBoxSecret)
    }

    /**
     * Sends [plaintext], the journal's backup, as the next version of [lineage], sealed under
     * [syncKey] (docs/SYNC_PROTOCOL.md §1.1): padded, format 2, the lineage and the version in its
     * associated data. In order:
     *
     * 1. The key document is read again, and nothing is sent unless its ETag is still [keyDocumentTag]:
     *    a copy is only ever sealed under the key the server's document opens to.
     * 2. The lineage's versions are read (`GET /v1/snapshots/{lineage}`); none, or a 404, is version 0,
     *    and otherwise the next is the newest plus one.
     * 3. The copy is sealed at that version and written (`PUT`), its length stated. A 409 means another
     *    write took that version, or one past it: steps 2 and 3 run once more, and a second 409 stops.
     *
     * [lineage] must be this phone's own ([PhoneLineage]), never a web console's lane. A copy whose
     * sealed size is over the server's default limit is not sent at all. Blocks the caller.
     */
    fun send(server: PairedServer, syncKey: ByteArray, keyDocumentTag: String, lineage: String, plaintext: ByteArray): Sending {
        if (!PhoneLineage.isPhoneLineage(lineage)) return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
        if (SyncCrypto.snapshotBlobLength(plaintext.size.toLong()) > MAX_BLOB_BYTES) {
            return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
        }
        val requests = SignedRequests(server, transport, clock)

        val document = try {
            requests.get(KEY_DOCUMENT)
        } catch (_: IOException) {
            return Sending.Stopped(PhoneWords.UNREACHABLE, Then.TRY_AGAIN)
        }
        when (document.status) {
            200 -> if (document.header(ETAG) != keyDocumentTag) return Sending.Stopped(PhoneWords.KEY_CHANGED, Then.UNLOCK_AGAIN)
            404 -> return Sending.Stopped(PhoneWords.KEY_CHANGED, Then.UNLOCK_AGAIN)
            else -> return refusal(document.status) ?: Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
        }

        for (attempt in 1..WRITES) {
            val newest = try {
                requests.get(VERSIONS + lineage)
            } catch (_: IOException) {
                return Sending.Stopped(PhoneWords.UNREACHABLE, Then.TRY_AGAIN)
            }
            val version = when (newest.status) {
                200 -> (newestVersion(newest.body, lineage) ?: return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)) + 1
                404 -> 0L
                else -> return refusal(newest.status) ?: Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
            }
            val envelope = try {
                crypto.encryptSnapshot(plaintext, syncKey, lineage, version)
            } catch (_: SyncCrypto.SyncCryptoException) {
                return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
            }
            val written = try {
                requests.put("$VERSIONS$lineage/$version", envelope)
            } catch (_: IOException) {
                // The copy may or may not have arrived. The next send reads the versions again, so a
                // copy that did arrive is simply followed by the next one.
                return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
            }
            when (written.status) {
                201 -> return Sending.Sent(version, clock.nowMillis())
                409 -> if (attempt < WRITES) continue else return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
                else -> return refusal(written.status) ?: Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
            }
        }
        return Sending.Stopped(PhoneWords.NOT_SENT, Then.TRY_AGAIN)
    }

    /**
     * Fetches the newest copy any phone sent and opens it under [syncKey] (#168). In order:
     *
     * 1. The key document is read again, exactly as [send] reads it: a copy is opened only under the key
     *    the server's document still opens to.
     * 2. The lineages are listed (`GET /v1/snapshots`), and only phones' are kept ([PhoneLineage]), never
     *    a web console's lane. For each, at most [MAX_LINEAGES], its versions are read.
     * 3. The version the server lists as most recently stored, across them all, is fetched and opened
     *    with that lineage and version as its associated data. One that does not open is refused.
     *
     * WHAT THIS DOES NOT ESTABLISH. The choice of copy rests on the server's own listing. A server can
     * offer no copy it was not given under this key, since nothing else opens, but it can offer an older
     * one as the newest; refusing that needs a signed manifest and a watermark kept on the device (#179).
     * So the caller shows the date written inside the copy, which the server cannot change, and the
     * person decides. Nothing is fetched twice and nothing loops. Blocks the caller.
     */
    fun fetch(server: PairedServer, syncKey: ByteArray, keyDocumentTag: String): Fetching {
        val requests = SignedRequests(server, transport, clock)
        try {
            val document = requests.get(KEY_DOCUMENT)
            when (document.status) {
                200 -> if (document.header(ETAG) != keyDocumentTag) return Fetching.Stopped(PhoneWords.KEY_CHANGED_FETCH, Then.UNLOCK_AGAIN)
                404 -> return Fetching.Stopped(PhoneWords.KEY_CHANGED_FETCH, Then.UNLOCK_AGAIN)
                else -> return fetchRefusal(document.status) ?: Fetching.Stopped(PhoneWords.NOT_FETCHED, Then.TRY_AGAIN)
            }

            val listing = requests.get(LINEAGES)
            if (listing.status != 200) return fetchRefusal(listing.status) ?: Fetching.Stopped(PhoneWords.NOT_FETCHED, Then.TRY_AGAIN)
            val names = (Answers.jsonObject(listing.body)?.get("lineages") as? List<*>)
                ?: return Fetching.Stopped(PhoneWords.NOT_FETCHED, Then.TRY_AGAIN)
            val phones = names.filterIsInstance<String>().filter(PhoneLineage::isPhoneLineage).distinct().sorted().take(MAX_LINEAGES)

            var best: Stored? = null
            for (lineage in phones) {
                val versions = requests.get(VERSIONS + lineage)
                val listed = when (versions.status) {
                    200 -> newestStored(versions.body, lineage) ?: return Fetching.Stopped(PhoneWords.NOT_FETCHED, Then.TRY_AGAIN)
                    404 -> continue
                    else -> return fetchRefusal(versions.status) ?: Fetching.Stopped(PhoneWords.NOT_FETCHED, Then.TRY_AGAIN)
                }
                val newest = listed.stored ?: continue
                if (best == null || newest.createdAt > best.createdAt) best = newest
            }
            val chosen = best ?: return Fetching.Stopped(PhoneWords.NO_COPY, Then.TRY_AGAIN)

            val blob = requests.get("$VERSIONS${chosen.lineage}/${chosen.version}")
            if (blob.status != 200) return fetchRefusal(blob.status) ?: Fetching.Stopped(PhoneWords.NOT_FETCHED, Then.TRY_AGAIN)
            val plaintext = try {
                crypto.decryptSnapshot(blob.body, syncKey, chosen.lineage, chosen.version)
            } catch (_: SyncCrypto.SyncCryptoException) {
                return Fetching.Stopped(PhoneWords.COPY_DID_NOT_OPEN, Then.TRY_AGAIN)
            }
            return Fetching.Fetched(chosen.lineage, chosen.version, plaintext)
        } catch (_: IOException) {
            return Fetching.Stopped(PhoneWords.UNREACHABLE_FETCH, Then.TRY_AGAIN)
        }
    }

    /** One stored version, as the server lists it. */
    private class Stored(val lineage: String, val version: Long, val createdAt: Long)

    /** A lineage's listing, read: its newest version, or null for a lineage with none. */
    private class Listed(val stored: Stored?)

    /**
     * The newest version in a lineage's listing (the highest version number, with the time the server
     * says it stored it), or null for an answer that is not that list for [lineage].
     */
    private fun newestStored(body: ByteArray, lineage: String): Listed? {
        val fields = Answers.jsonObject(body) ?: return null
        if (fields["lineage"] != lineage) return null
        val versions = fields["versions"] as? List<*> ?: return null
        var newest: Stored? = null
        for (entry in versions) {
            val map = entry as? Map<*, *> ?: return null
            val version = Answers.wholeNumber(map["version"]) ?: return null
            val createdAt = Answers.wholeNumber(map["createdAt"]) ?: return null
            if (newest == null || version > newest.version) newest = Stored(lineage, version, createdAt)
        }
        return Listed(newest)
    }

    private fun fetchRefusal(status: Int): Fetching.Stopped? = when (status) {
        401 -> Fetching.Stopped(PhoneWords.DISCONNECTED, Then.PAIR_AGAIN)
        429 -> Fetching.Stopped(PhoneWords.PAUSED, Then.TRY_AGAIN)
        in 500..599 -> Fetching.Stopped(PhoneWords.UNREACHABLE_FETCH, Then.TRY_AGAIN)
        else -> null
    }

    /** A 401, a 429 or a 5xx as a stop; null for any other status, which the caller reads itself. */
    private fun refusal(status: Int): Sending.Stopped? = when (status) {
        401 -> Sending.Stopped(PhoneWords.DISCONNECTED, Then.PAIR_AGAIN)
        429 -> Sending.Stopped(PhoneWords.PAUSED, Then.TRY_AGAIN)
        in 500..599 -> Sending.Stopped(PhoneWords.UNREACHABLE, Then.TRY_AGAIN)
        else -> null
    }

    /**
     * The newest version in `{"lineage":…,"versions":[{"version":n,…},…]}`, -1 for none, or null for an
     * answer that is not that list for [lineage].
     */
    private fun newestVersion(body: ByteArray, lineage: String): Long? {
        val fields = Answers.jsonObject(body) ?: return null
        if (fields["lineage"] != lineage) return null
        val versions = fields["versions"] as? List<*> ?: return null
        var newest = -1L
        for (entry in versions) {
            val version = Answers.wholeNumber((entry as? Map<*, *>)?.get("version")) ?: return null
            if (version > newest) newest = version
        }
        return newest
    }

    companion object {
        const val KEY_DOCUMENT = "/v1/keydoc"
        const val VERSIONS = "/v1/snapshots/"
        const val LINEAGES = "/v1/snapshots"

        /** The most phone lineages a fetch reads the versions of: one per phone the owner has paired, and then some. */
        const val MAX_LINEAGES = 16

        /** The server's default `DAYMARK_MAX_BLOB_BYTES`, which a sealed copy must not pass. */
        const val MAX_BLOB_BYTES = 26_214_400L

        /** A copy is written at most twice: once, and once more after a 409. */
        const val WRITES = 2

        private const val ETAG = "ETag"
    }
}

/**
 * The name of this phone's copies on the server (#432): `phone_` and the unpadded base64url of 16
 * random bytes, 28 characters, inside the server's `[A-Za-z0-9_-]{1,64}`. Made once per phone and kept,
 * so every copy the phone sends, before and after pairing again, is a version of the one lineage. It
 * never begins `lane_`, which names a web console's lane (docs/SYNC_PROTOCOL.md §1.4), and the phone
 * writes under no name that is not one of these.
 */
object PhoneLineage {
    const val PREFIX = "phone_"
    private const val RANDOM_BYTES = 16

    /** A new lineage name, for a phone that has none. */
    fun create(sodium: LazySodium): String = PREFIX + SyncCrypto.toBase64(sodium.randomBytesBuf(RANDOM_BYTES))

    /** Whether [name] is a phone's lineage as [create] makes one: the prefix and 16 bytes, canonically spelled. */
    fun isPhoneLineage(name: String): Boolean {
        if (!name.startsWith(PREFIX)) return false
        return try {
            SyncCrypto.fromBase64(name.substring(PREFIX.length)).size == RANDOM_BYTES
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
