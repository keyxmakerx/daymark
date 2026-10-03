package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * The clinicians this phone has added (#174), and the bytes they are kept as, which the app seals under
 * a key of the phone's own keystore before they touch storage, as it seals [KeptLink].
 *
 * Each relationship holds the owner's own name for the clinician, its inbox token (the secret the
 * server holds only a digest of, so this is the one copy the phone has), every key pair recorded for it
 * in the order recorded, and at most one open pairing run. Key records are insert-only: a replacement is
 * a new record after the old, never a write over it, and the newest is the one in use.
 *
 * The bytes, big-endian:
 *
 * ```
 * "DMCL" 0x01, u8 how many relationships, then each:
 * u8 length, the id in ASCII                     22 characters of base64url, made on this phone
 * u16 length, the owner's name for them in UTF-8
 * u8 length, the inbox token in ASCII            43 characters of base64url
 * u8 how many key records, then each: the 32-byte box key, the 32-byte signing key, i64 when, in ms
 * u8 0 or 1: an open run follows
 * [1] u8+relationship reference, u8+invite id, u8+exchange id, u8+sid, the 32-byte scalar, u16+msgA, u8 0 or 1 and then u16+the pinned reply
 * ```
 *
 * Read strictly ([decode]): a byte out of place is nothing kept. The bytes hold inbox tokens and run
 * scalars, so the caller wipes them once sealed; nothing here is logged, and [toString] names no secret.
 */
class KeptClinicians private constructor(val clinicians: List<Clinician>) {

    /** One key pair recorded for a relationship, and when. */
    class KeyRecord(val boxPub: ByteArray, val signPub: ByteArray, val pinnedAt: Long) {
        init {
            require(boxPub.size == KEY_BYTES && signPub.size == KEY_BYTES) { "a key is $KEY_BYTES bytes" }
        }
    }

    class Clinician internal constructor(
        val id: String,
        /** The owner's own name for them. Never the name the clinician typed. */
        val displayName: String,
        val inboxToken: String,
        val keys: List<KeyRecord>,
        val run: ClinicianPairing.OwnerRun?,
    ) {
        init {
            require(ID.matches(id)) { "not an id" }
            require(validName(displayName)) { "not a name" }
            require(TOKEN.matches(inboxToken)) { "not an inbox token" }
            require(keys.size <= MAX_KEY_RECORDS) { "too many key records" }
        }

        /** The keys in use: the newest record, or null while none is held. */
        val current: KeyRecord? get() = keys.lastOrNull()

        override fun toString(): String = "Clinician($id)"
    }

    /** Adds a clinician under the owner's [name], with a fresh id and [inboxToken]. */
    fun adding(sodium: LazySodium, name: String, inboxToken: String): Pair<KeptClinicians, Clinician> {
        require(clinicians.size < MAX_CLINICIANS) { "too many clinicians" }
        val added = Clinician(SyncCrypto.toBase64(sodium.randomBytesBuf(ID_BYTES)), name.trim(), inboxToken, emptyList(), null)
        return KeptClinicians(clinicians + added) to added
    }

    fun find(id: String): Clinician? = clinicians.firstOrNull { it.id == id }

    /** The relationship [id] with [change] applied; the others as they were. */
    fun changing(id: String, change: (Clinician) -> Clinician): KeptClinicians =
        KeptClinicians(clinicians.map { if (it.id == id) change(it) else it })

    /** Removes the relationship [id] from this phone. Nothing on the server changes by it. */
    fun removing(id: String): KeptClinicians = KeptClinicians(clinicians.filter { it.id != id })

    /**
     * The [ClinicianCeremony.Keep] for relationship [id]: every change is applied here and handed to
     * [persist] before the call returns, so a run is kept before its code is shown.
     */
    class Book(private var kept: KeptClinicians, private val id: String, private val clock: PhoneClock, private val persist: (KeptClinicians) -> Unit) :
        ClinicianCeremony.Keep {
        val now: KeptClinicians get() = kept

        private fun change(f: (Clinician) -> Clinician) {
            val next = kept.changing(id, f)
            persist(next)
            kept = next
        }

        override fun saveRun(run: ClinicianPairing.OwnerRun) =
            change { Clinician(it.id, it.displayName, it.inboxToken, it.keys, run) }

        override fun loadRun(): ClinicianPairing.OwnerRun? = kept.find(id)?.run

        override fun forgetRun() {
            if (kept.find(id)?.run != null) change { Clinician(it.id, it.displayName, it.inboxToken, it.keys, null) }
        }

        override fun held(): ClinicianKeys.Held? = kept.find(id)?.current?.let { ClinicianKeys.Held(it.boxPub, it.signPub) }

        override fun others(): List<ClinicianKeys.Named> = kept.clinicians.filter { it.id != id }.mapNotNull { c ->
            c.current?.let { ClinicianKeys.Named(c.displayName, it.boxPub, it.signPub) }
        }

        override fun pin(offer: PairingPayloads.TherapistOffer): Boolean {
            val box = decodeKey(offer.boxPubB64) ?: return false
            val sign = decodeKey(offer.signPubB64) ?: return false
            val held = kept.find(id) ?: return false
            val current = held.current
            if (current != null && current.boxPub.contentEquals(box) && current.signPub.contentEquals(sign)) return true
            if (held.keys.size >= MAX_KEY_RECORDS) return false
            change { Clinician(it.id, it.displayName, it.inboxToken, it.keys + KeyRecord(box, sign, clock.nowMillis()), it.run) }
            return true
        }
    }

    /** The bytes to seal. They hold inbox tokens and run scalars: the caller wipes them once sealed. */
    fun encode(): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += MAGIC
        parts += byteArrayOf(VERSION, clinicians.size.toByte())
        for (c in clinicians) {
            parts += u8(c.id.toByteArray(Charsets.US_ASCII))
            parts += u16(c.displayName.toByteArray(Charsets.UTF_8))
            parts += u8(c.inboxToken.toByteArray(Charsets.US_ASCII))
            parts += byteArrayOf(c.keys.size.toByte())
            for (k in c.keys) parts += k.boxPub + k.signPub + ByteBuffer.allocate(8).putLong(k.pinnedAt).array()
            val run = c.run
            if (run == null) {
                parts += byteArrayOf(0)
            } else {
                parts += byteArrayOf(1)
                parts += u8(run.relRef.toByteArray(Charsets.US_ASCII))
                parts += u8(run.inviteId.toByteArray(Charsets.US_ASCII))
                parts += u8(run.exchangeId.toByteArray(Charsets.US_ASCII))
                parts += u8(run.sidB64.toByteArray(Charsets.US_ASCII))
                parts += run.start.ya
                parts += u16(run.start.msgA)
                val pinned = run.pinnedMsgBB64
                parts += if (pinned == null) byteArrayOf(0) else byteArrayOf(1) + u16(pinned.toByteArray(Charsets.US_ASCII))
            }
        }
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    override fun toString(): String = "KeptClinicians(${clinicians.size})"

    companion object {
        val EMPTY = KeptClinicians(emptyList())

        private val MAGIC = "DMCL".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 0x01
        private const val KEY_BYTES = 32
        private const val ID_BYTES = 16
        private const val SCALAR_BYTES = 32
        private const val MAX_CLINICIANS = 64
        private const val MAX_KEY_RECORDS = 64
        private val ID = Regex("[A-Za-z0-9_-]{22}")
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val SERVER_ID = Regex("[A-Za-z0-9_-]{1,128}")

        /** The owner's name for a clinician: not blank, and kept to the rule the clinician's own is. */
        fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() && PairingPayloads.validDisplayName(name)

        /** The relationships [bytes] hold, or null for anything that is not that exactly. */
        fun decode(bytes: ByteArray): KeptClinicians? {
            val buffer = ByteBuffer.wrap(bytes)
            return try {
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC) || buffer.get() != VERSION) return null
                val count = buffer.get().toInt() and 0xFF
                if (count > MAX_CLINICIANS) return null
                val list = ArrayList<Clinician>(count)
                repeat(count) {
                    val id = ascii(readU8(buffer)) ?: return null
                    val name = Answers.utf8(readU16(buffer)) ?: return null
                    val token = ascii(readU8(buffer)) ?: return null
                    val keyCount = buffer.get().toInt() and 0xFF
                    val keys = ArrayList<KeyRecord>(keyCount)
                    repeat(keyCount) {
                        val box = ByteArray(KEY_BYTES).also { buffer.get(it) }
                        val sign = ByteArray(KEY_BYTES).also { buffer.get(it) }
                        keys += KeyRecord(box, sign, buffer.long)
                    }
                    val run = when (buffer.get().toInt()) {
                        0 -> null
                        1 -> {
                            val relRef = ascii(readU8(buffer)) ?: return null
                            val inviteId = ascii(readU8(buffer)) ?: return null
                            val exchangeId = ascii(readU8(buffer)) ?: return null
                            val sid = ascii(readU8(buffer)) ?: return null
                            val ya = ByteArray(SCALAR_BYTES).also { buffer.get(it) }
                            val msgA = readU16(buffer)
                            val pinned = when (buffer.get().toInt()) {
                                0 -> null
                                1 -> ascii(readU16(buffer)) ?: return null
                                else -> return null
                            }
                            if (!SERVER_ID.matches(relRef) || !SERVER_ID.matches(inviteId) || !SERVER_ID.matches(exchangeId)) return null
                            if (!isBase64(sid) || (pinned != null && !isBase64(pinned))) return null
                            ClinicianPairing.OwnerRun(relRef, inviteId, exchangeId, sid, CpaceCrypto.StartResult(ya, msgA), pinned)
                        }
                        else -> return null
                    }
                    list += Clinician(id, name, token, keys, run)
                }
                if (buffer.hasRemaining()) return null
                if (list.map { it.id }.toSet().size != list.size) return null
                KeptClinicians(list)
            } catch (_: BufferUnderflowException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun readU8(buffer: ByteBuffer) = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
        private fun readU16(buffer: ByteBuffer) = ByteArray(buffer.short.toInt() and 0xFFFF).also { buffer.get(it) }

        private fun u8(bytes: ByteArray): ByteArray {
            require(bytes.size <= 0xFF) { "too long to keep" }
            return byteArrayOf(bytes.size.toByte()) + bytes
        }

        private fun u16(bytes: ByteArray): ByteArray {
            require(bytes.size <= 0xFFFF) { "too long to keep" }
            return ByteBuffer.allocate(2).putShort(bytes.size.toShort()).array() + bytes
        }

        private fun ascii(bytes: ByteArray): String? =
            if (bytes.all { it in 0x20..0x7E }) String(bytes, Charsets.US_ASCII) else null

        private fun isBase64(value: String): Boolean = try {
            SyncCrypto.fromBase64(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun decodeKey(b64: String): ByteArray? = try {
            SyncCrypto.fromBase64(b64).takeIf { it.size == KEY_BYTES }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
