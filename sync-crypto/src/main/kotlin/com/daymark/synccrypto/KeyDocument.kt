package com.daymark.synccrypto

/**
 * The owner's key document, as `GET /v1/keydoc` serves it (docs/SYNC_PROTOCOL.md §1.2 and §2): the
 * key parameters, or the wrapped key once one exists. [SyncCrypto.openWithPassphrase] turns either
 * into the owner's keys, and [SyncCrypto.openWithRecoveryCode] the wrapped key (#403).
 *
 * Both are read before any key is derived, and refused whole ([KeyDocumentException]) for anything
 * the web refuses before it derives: a version other than 1, a wrapped key with no slots, and KDF
 * parameters that are not Argon2id at or above the floor on ANY slot, including slots the secret at
 * hand will not open and slots of a kind this reader does not know. The parameters travel inside the
 * document, so they are the server's to lower; a weak sibling slot must not survive a round trip
 * next to a strong one (`companion/web/src/lib/recovery/dataKey.ts`, `validateBlob`).
 *
 * A slot of a kind other than `passphrase` or `recovery` is then skipped, as the web skips it, so a
 * kind added later (a passkey's PRF output, a Shamir share) does not lock this reader out of a
 * document it can otherwise open. Nothing but its KDF parameters is read from it. Every salt, nonce
 * and wrapped key of a known slot is base64 in the protocol's one form, URL-safe without padding
 * ([SyncCrypto.fromBase64]), at its exact length.
 *
 * The phone is stricter than the web in these places, each of which refuses only what no writer
 * produces:
 *  - Every slot of a known kind is decoded and length-checked, where the web checks only the slot it
 *    opens.
 *  - `v`, `memMiB` and `ops` must be written as JSON integers: `1.0`, `256.0` and `3e0` are refused,
 *    and so is `"256"`, a string the web reads as the number. The key parameters must name
 *    `xchacha20poly1305`, a field the web does not read.
 *  - A document is at most [MAX_CHARS] characters and [StrictJson.MAX_DEPTH] levels deep, which
 *    the server enforces on what it stores.
 *
 * Fields a reader does not use are ignored on both sides. A name given twice keeps its last value,
 * as in `JSON.parse`.
 *
 * Nothing here holds a secret: salts, nonces and wrapped keys are public by design (§1.2). Even so,
 * no refusal carries any part of the document.
 */
sealed class KeyDocument {

    /**
     * §1.2's key parameters: Argon2id over the passphrase and this salt, at these parameters, is the
     * master. Nothing in them can tell a wrong passphrase from a right one; the snapshot that then
     * fails to open is what refuses it.
     */
    class KeyParams internal constructor(
        val kdf: SyncCrypto.KdfParams,
        internal val salt: ByteArray,
    ) : KeyDocument()

    /**
     * §1.2's wrapped key: the master, locked once per secret, in slots. [slots] holds the slots of
     * the kinds this reader opens, in document order. A slot of any other kind was held to the floor
     * and skipped, and is not kept here, so this is never a document to write back in place of the
     * one that was read.
     */
    class WrappedKey internal constructor(val slots: List<Slot>) : KeyDocument() {

        /** One locked copy of the master. [kind] and [kdf] are public; the bytes are not needed outside. */
        class Slot internal constructor(
            val kind: SlotKind,
            val kdf: SyncCrypto.KdfParams,
            internal val salt: ByteArray,
            internal val nonce: ByteArray,
            internal val ciphertext: ByteArray,
        )

        /**
         * The document as the web's writer serialises the same object: `JSON.stringify` of
         * `{ v: 1, slots: [{ kind, kdf: { alg, memMiB, ops }, saltB64, nonceB64, ctB64 }, …] }`, no
         * spaces. Every string in it is a fixed word or base64url, so none needs escaping.
         */
        internal fun toJson(): String = slots.joinToString(",", prefix = "{\"v\":1,\"slots\":[", postfix = "]}") {
            "{\"kind\":\"${it.kind.wire}\"," +
                "\"kdf\":{\"alg\":\"argon2id\",\"memMiB\":${it.kdf.memMiB},\"ops\":${it.kdf.ops}}," +
                "\"saltB64\":\"${SyncCrypto.toBase64(it.salt)}\"," +
                "\"nonceB64\":\"${SyncCrypto.toBase64(it.nonce)}\"," +
                "\"ctB64\":\"${SyncCrypto.toBase64(it.ciphertext)}\"}"
        }
    }

    /**
     * Which secret a slot is locked under. The kind is bound into the slot's associated data
     * (`"daymark.datakey.v1|" + kind`), so a slot relabelled by whoever stores it does not open.
     */
    enum class SlotKind(internal val wire: String) {
        PASSPHRASE("passphrase"),
        RECOVERY("recovery"),
    }

    companion object {
        /**
         * The longest document read, in UTF-16 units: the server stores at most 16 KiB of wrapped key
         * and 4 KiB of key parameters (§2), and a text never has more UTF-16 units than UTF-8 bytes,
         * so nothing the server keeps is longer.
         */
        const val MAX_CHARS = 16 * 1024

        internal const val SALT_BYTES = 16
        internal const val NONCE_BYTES = 24
        internal const val MASTER_BYTES = 32
        internal const val TAG_BYTES = 16

        /**
         * A key document of either kind, told apart by its shape: a `slots` member makes it the
         * wrapped key, a `saltB64` member the key parameters, and both or neither is refused. The
         * text is the response body, decoded as UTF-8.
         */
        fun parse(json: String): KeyDocument {
            val root = root(json)
            val wrapped = "slots" in root
            val params = "saltB64" in root
            return when {
                wrapped && !params -> wrappedKey(root)
                params && !wrapped -> keyParams(root)
                else -> refuse(KeyDocumentException.Reason.NOT_A_KEY_DOCUMENT)
            }
        }

        /** The key parameters, and nothing else, for a caller that knows which kind it asked for. */
        fun parseKeyParams(json: String): KeyParams = keyParams(root(json))

        /** The wrapped key, and nothing else. */
        fun parseWrappedKey(json: String): WrappedKey = wrappedKey(root(json))

        private fun root(json: String): Map<*, *> {
            if (json.length > MAX_CHARS) refuse(KeyDocumentException.Reason.NOT_A_KEY_DOCUMENT)
            val value = try {
                StrictJson.parse(json)
            } catch (_: StrictJson.JsonException) {
                refuse(KeyDocumentException.Reason.NOT_A_KEY_DOCUMENT)
            }
            return value as? Map<*, *> ?: refuse(KeyDocumentException.Reason.NOT_A_KEY_DOCUMENT)
        }

        private fun keyParams(root: Map<*, *>): KeyParams {
            requireVersion1(root)
            if (root["alg"] != "xchacha20poly1305") refuse(KeyDocumentException.Reason.UNSUPPORTED_FORMAT)
            val kdf = kdf(root["kdf"])
            return KeyParams(kdf, bytes(root["saltB64"], SALT_BYTES))
        }

        private fun wrappedKey(root: Map<*, *>): WrappedKey {
            requireVersion1(root)
            val slots = root["slots"] as? List<*> ?: refuse(KeyDocumentException.Reason.NO_SLOTS)
            if (slots.isEmpty()) refuse(KeyDocumentException.Reason.NO_SLOTS)
            // Every slot's KDF first, whatever its kind, before any field of any slot is decoded, as
            // the web's validateBlob holds every slot to the floor before it opens one.
            val checked = slots.map { slot ->
                val fields = slot as? Map<*, *> ?: refuse(KeyDocumentException.Reason.NOT_A_KEY_DOCUMENT)
                fields to kdf(fields["kdf"])
            }
            // Then the slots of the kinds this reader opens. A slot of any other kind is skipped, as
            // the web skips it, and nothing else in it is read.
            return WrappedKey(
                checked.mapNotNull { (fields, kdf) ->
                    val kind = SlotKind.entries.firstOrNull { it.wire == fields["kind"] } ?: return@mapNotNull null
                    WrappedKey.Slot(
                        kind = kind,
                        kdf = kdf,
                        salt = bytes(fields["saltB64"], SALT_BYTES),
                        nonce = bytes(fields["nonceB64"], NONCE_BYTES),
                        ciphertext = bytes(fields["ctB64"], MASTER_BYTES + TAG_BYTES),
                    )
                },
            )
        }

        private fun requireVersion1(root: Map<*, *>) {
            val v = root["v"]
            if (v !is StrictJson.Number || v.lexeme != "1") refuse(KeyDocumentException.Reason.UNSUPPORTED_FORMAT)
        }

        /** Argon2id, and at or above the floor, or the document is refused before anything is derived. */
        private fun kdf(value: Any?): SyncCrypto.KdfParams {
            val fields = value as? Map<*, *> ?: refuse(KeyDocumentException.Reason.KDF_BELOW_FLOOR)
            if (fields["alg"] != "argon2id") refuse(KeyDocumentException.Reason.KDF_BELOW_FLOOR)
            val memMiB = jsonInt(fields["memMiB"]) ?: refuse(KeyDocumentException.Reason.KDF_BELOW_FLOOR)
            val ops = jsonInt(fields["ops"]) ?: refuse(KeyDocumentException.Reason.KDF_BELOW_FLOOR)
            val params = SyncCrypto.KdfParams(memMiB = memMiB, ops = ops)
            if (!params.meetsFloor) refuse(KeyDocumentException.Reason.KDF_BELOW_FLOOR)
            return params
        }

        private val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")

        /** A JSON integer that fits an Int, or null for anything else (a string, a fraction, `1e3`). */
        private fun jsonInt(value: Any?): Int? {
            val number = value as? StrictJson.Number ?: return null
            if (!JSON_INTEGER.matches(number.lexeme)) return null
            return number.lexeme.toIntOrNull()
        }

        private fun bytes(value: Any?, length: Int): ByteArray {
            val text = value as? String ?: refuse(KeyDocumentException.Reason.MALFORMED_BASE64)
            val decoded = try {
                SyncCrypto.fromBase64(text)
            } catch (_: IllegalArgumentException) {
                refuse(KeyDocumentException.Reason.MALFORMED_BASE64)
            }
            if (decoded.size != length) refuse(KeyDocumentException.Reason.WRONG_LENGTH)
            return decoded
        }

        private fun refuse(reason: KeyDocumentException.Reason): Nothing = throw KeyDocumentException(reason)
    }
}

/**
 * A key document refused, or a secret that did not open it. The message is fixed per [reason] and
 * carries no secret, no key material and no part of the document.
 */
class KeyDocumentException internal constructor(val reason: Reason) : Exception("key document: ${reason.description}") {

    enum class Reason(internal val description: String) {
        /** Not one JSON object of either kind, or a member of the wrong type. */
        NOT_A_KEY_DOCUMENT("not a key document"),

        /** `v` is not 1, or the key parameters name a cipher other than XChaCha20-Poly1305. */
        UNSUPPORTED_FORMAT("a version or cipher this reader does not read"),

        /** A wrapped key whose `slots` is missing, not a list, or empty. */
        NO_SLOTS("a wrapped key with no slots"),

        /** KDF parameters that are not Argon2id at 256 MiB and 3 passes or more, on any slot of any kind. */
        KDF_BELOW_FLOOR("KDF parameters below the security floor; nothing was derived"),

        /** A salt, nonce or wrapped key that is not URL-safe base64 without padding. */
        MALFORMED_BASE64("a field that is not URL-safe base64 without padding"),

        /** A salt, nonce or wrapped key of the wrong length. */
        WRONG_LENGTH("a field of the wrong length"),

        /**
         * No slot opens with the kind of secret offered: the key parameters have no recovery slot, and
         * a wrapped key may hold only slots of other kinds.
         */
        NO_SLOT_OF_THAT_KIND("no slot opens with that kind of secret"),

        /**
         * The secret was derived and the slot did not authenticate. That is all that is known: a wrong
         * secret and a slot someone edited are one outcome here, not two.
         */
        DID_NOT_OPEN("that secret does not open this key document"),
    }
}
