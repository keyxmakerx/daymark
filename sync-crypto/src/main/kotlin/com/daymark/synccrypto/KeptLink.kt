package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * What the phone keeps about its server once the registration poll answers `registered` (#432), and
 * the bytes it is kept as, which the app seals under a key of the phone's own keystore before they
 * touch storage: the server's address, the key's id and seed; once the passphrase has opened it, the
 * sync key and the ETag of the key document it came from; and the last copy the server took.
 *
 * The bytes, big-endian:
 *
 * ```
 * "DMPL" 0x01
 * u16 length, then the address in UTF-8          https:// and a host, as the pairing verdict read it
 * u8 length, then the key id in ASCII            22 characters of base64url
 * the 32-byte seed of the key                    DeviceKey.seedToKeep
 * u8 flags                                       0x01: the sync key follows; 0x02: the last copy follows
 * [0x01] the 32-byte sync key, u8 length, the key document's ETag in printable ASCII
 * [0x02] i64 when the last copy was taken, in ms on the phone's clock; i64 its version
 * ```
 *
 * Read strictly ([decode]): a byte out of place is no link at all, and the phone pairs again rather
 * than guess. A link holds its own copies of the seed and the sync key; [wipe] clears them, and the
 * caller wipes [encode]'s bytes once they are sealed.
 */
class KeptLink private constructor(
    val address: String,
    val keyId: String,
    private val seed: ByteArray,
    private val syncKey: ByteArray?,
    /** The ETag of the key document the sync key came from; null while there is no sync key. */
    val keyDocumentTag: String?,
    /** When the server took the last copy, on the phone's clock, in milliseconds; null before the first. */
    val lastSentAt: Long?,
    /** The version the last copy was taken as; null before the first. */
    val lastSentVersion: Long?,
) {
    init {
        require(address.startsWith(HTTPS)) { "only an https address is kept" }
        require(address.toByteArray(Charsets.UTF_8).size <= 0xFFFF) { "an address too long to keep" }
        require(isKeyId(keyId)) { "not a key id" }
        require(seed.size == SEED_BYTES) { "a seed is $SEED_BYTES bytes" }
        require((syncKey == null) == (keyDocumentTag == null)) { "a sync key is kept with its document's tag" }
        require(syncKey == null || syncKey.size == SYNC_KEY_BYTES) { "a sync key is $SYNC_KEY_BYTES bytes" }
        require(keyDocumentTag == null || isTag(keyDocumentTag)) { "not a tag" }
        require((lastSentAt == null) == (lastSentVersion == null)) { "the last copy is its time and its version" }
        require(lastSentVersion == null || lastSentVersion >= 0) { "a version is 0 or more" }
    }

    /** Whether the passphrase has opened the sync key and it is kept. */
    val hasSyncKey: Boolean get() = syncKey != null

    /** The sync key, as a copy the caller wipes; null before the passphrase has opened it. */
    fun syncKey(): ByteArray? = syncKey?.copyOf()

    /** The server, with the key made again from the seed; null if that key is not the one this link names. */
    fun server(sodium: LazySodium): PairedServer? {
        val key = DeviceKey.restore(sodium, seed)
        return if (key.keyId == keyId) PairedServer(address, key) else null
    }

    /** This link with the sync key the passphrase opened, and the tag of the document it came from. */
    fun withSyncKey(syncKey: ByteArray, keyDocumentTag: String): KeptLink =
        KeptLink(address, keyId, seed.copyOf(), syncKey.copyOf(), keyDocumentTag, lastSentAt, lastSentVersion)

    /** This link without its sync key, for the passphrase to be asked for again. */
    fun withoutSyncKey(): KeptLink = KeptLink(address, keyId, seed.copyOf(), null, null, lastSentAt, lastSentVersion)

    /** This link with the copy the server just took. */
    fun withLastSent(atMillis: Long, version: Long): KeptLink =
        KeptLink(address, keyId, seed.copyOf(), syncKey?.copyOf(), keyDocumentTag, atMillis, version)

    /** The bytes to seal, which hold the seed and any sync key: the caller wipes them once sealed. */
    fun encode(): ByteArray {
        val addressBytes = address.toByteArray(Charsets.UTF_8)
        val keyIdBytes = keyId.toByteArray(Charsets.US_ASCII)
        val tagBytes = keyDocumentTag?.toByteArray(Charsets.US_ASCII)
        var size = MAGIC.size + 1 + 2 + addressBytes.size + 1 + keyIdBytes.size + SEED_BYTES + 1
        if (syncKey != null) size += SYNC_KEY_BYTES + 1 + tagBytes!!.size
        if (lastSentAt != null) size += 16
        val out = ByteArray(size)
        val buffer = ByteBuffer.wrap(out)
        buffer.put(MAGIC).put(VERSION)
        buffer.putShort(addressBytes.size.toShort()).put(addressBytes)
        buffer.put(keyIdBytes.size.toByte()).put(keyIdBytes)
        buffer.put(seed)
        buffer.put(((if (syncKey != null) HAS_SYNC_KEY else 0) or (if (lastSentAt != null) HAS_LAST_SENT else 0)).toByte())
        if (syncKey != null) buffer.put(syncKey).put(tagBytes!!.size.toByte()).put(tagBytes)
        if (lastSentAt != null) buffer.putLong(lastSentAt).putLong(lastSentVersion!!)
        check(!buffer.hasRemaining())
        return out
    }

    /** Clears this link's own copies of the seed and the sync key. */
    fun wipe() {
        seed.fill(0)
        syncKey?.fill(0)
    }

    override fun toString(): String = "KeptLink($address, $keyId)"

    companion object {
        private val MAGIC = "DMPL".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 0x01
        private const val HAS_SYNC_KEY = 0x01
        private const val HAS_LAST_SENT = 0x02
        private const val SEED_BYTES = 32
        private const val SYNC_KEY_BYTES = 32
        private const val HTTPS = "https://"

        /** What the phone keeps the moment the poll answers `registered`: the address and the key. */
        fun of(server: PairedServer): KeptLink {
            val seed = server.key.seedToKeep()
            return KeptLink(server.address, server.key.keyId, seed, null, null, null, null)
        }

        /** The link [bytes] hold, or null for anything that is not one exactly. */
        fun decode(bytes: ByteArray): KeptLink? {
            val buffer = ByteBuffer.wrap(bytes)
            var seed: ByteArray? = null
            var syncKey: ByteArray? = null
            try {
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC) || buffer.get() != VERSION) return null
                val address = Answers.utf8(ByteArray(buffer.short.toInt() and 0xFFFF).also { buffer.get(it) }) ?: return null
                val keyId = ascii(ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }) ?: return null
                seed = ByteArray(SEED_BYTES).also { buffer.get(it) }
                val flags = buffer.get().toInt() and 0xFF
                if (flags and (HAS_SYNC_KEY or HAS_LAST_SENT).inv() != 0) return null
                var tag: String? = null
                if (flags and HAS_SYNC_KEY != 0) {
                    syncKey = ByteArray(SYNC_KEY_BYTES).also { buffer.get(it) }
                    tag = ascii(ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }) ?: return null
                }
                var lastSentAt: Long? = null
                var lastSentVersion: Long? = null
                if (flags and HAS_LAST_SENT != 0) {
                    lastSentAt = buffer.long
                    lastSentVersion = buffer.long
                }
                if (buffer.hasRemaining()) return null
                if (!address.startsWith(HTTPS) || !isKeyId(keyId) || (tag != null && !isTag(tag))) return null
                if (lastSentVersion != null && lastSentVersion < 0) return null
                val link = KeptLink(address, keyId, seed, syncKey, tag, lastSentAt, lastSentVersion)
                seed = null
                syncKey = null
                return link
            } catch (_: BufferUnderflowException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            } finally {
                // Only what did not become a link is wiped here: a link owns the arrays it was given.
                seed?.fill(0)
                syncKey?.fill(0)
            }
        }

        private fun ascii(bytes: ByteArray): String? =
            if (bytes.all { it in 0x20..0x7E }) String(bytes, Charsets.US_ASCII) else null

        private fun isKeyId(value: String): Boolean = try {
            SyncCrypto.fromBase64(value).size == DeviceSignature.KEY_ID_BYTES
        } catch (_: IllegalArgumentException) {
            false
        }

        /** An ETag as the server writes one, or any other short printable ASCII: it is compared, never read. */
        private fun isTag(value: String): Boolean = value.length in 1..255 && value.all { it in ' '..'~' }
    }
}
