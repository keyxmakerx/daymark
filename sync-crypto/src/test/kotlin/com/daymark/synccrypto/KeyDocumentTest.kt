package com.daymark.synccrypto

import com.daymark.synccrypto.KeyDocumentException.Reason
import com.daymark.synccrypto.KeyDocumentVector.KEY_PARAMS
import com.daymark.synccrypto.KeyDocumentVector.KEY_PARAMS_SALT
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE_CT_B64
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE_NONCE
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE_SALT
import com.daymark.synccrypto.KeyDocumentVector.RECOVERY_CT_B64
import com.daymark.synccrypto.KeyDocumentVector.RECOVERY_NONCE
import com.daymark.synccrypto.KeyDocumentVector.RECOVERY_SALT
import com.daymark.synccrypto.KeyDocumentVector.Raw
import com.daymark.synccrypto.KeyDocumentVector.WRAPPED
import com.daymark.synccrypto.KeyDocumentVector.json
import com.daymark.synccrypto.KeyDocumentVector.kdf
import com.daymark.synccrypto.KeyDocumentVector.kdfTree
import com.daymark.synccrypto.KeyDocumentVector.keyParamsTree
import com.daymark.synccrypto.KeyDocumentVector.slot
import com.daymark.synccrypto.KeyDocumentVector.slots
import com.daymark.synccrypto.KeyDocumentVector.wrappedTree
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [KeyDocument] read on its own, with no key derived: which shape is which, which JSON forms of one
 * document read as it, what is refused, and that no refusal carries the document (#403). The vector
 * and the refusals it shares with the web are in KeyDocumentVectorTest; the places the phone is
 * stricter than the web are here, each against a document that is otherwise the vector.
 */
class KeyDocumentTest {

    @Test
    fun tellsTheTwoKindsApartByShape_andReadsEveryField() {
        val params = KeyDocument.parse(KEY_PARAMS) as KeyDocument.KeyParams
        assertArrayEquals(KEY_PARAMS_SALT, params.salt)
        assertEquals(256, params.kdf.memMiB)
        assertEquals(3, params.kdf.ops)

        val wrapped = KeyDocument.parse(WRAPPED) as KeyDocument.WrappedKey
        assertEquals(listOf(KeyDocument.SlotKind.PASSPHRASE, KeyDocument.SlotKind.RECOVERY), wrapped.slots.map { it.kind })
        val (p, r) = wrapped.slots
        assertArrayEquals(PASSPHRASE_SALT, p.salt)
        assertArrayEquals(PASSPHRASE_NONCE, p.nonce)
        assertArrayEquals(SyncCrypto.fromBase64(PASSPHRASE_CT_B64), p.ciphertext)
        assertArrayEquals(RECOVERY_SALT, r.salt)
        assertArrayEquals(RECOVERY_NONCE, r.nonce)
        assertArrayEquals(SyncCrypto.fromBase64(RECOVERY_CT_B64), r.ciphertext)
        for (slot in wrapped.slots) {
            assertEquals(256, slot.kdf.memMiB)
            assertEquals(3, slot.kdf.ops)
            assertEquals(48, slot.ciphertext.size)
        }
    }

    @Test
    fun aDocumentOfBothShapesOrNeitherIsRefused_andEachReaderTakesOnlyItsOwnKind() {
        val both = wrappedTree().apply { this["saltB64"] = keyParamsTree()["saltB64"] }
        assertRefused(Reason.NOT_A_KEY_DOCUMENT) { KeyDocument.parse(json(both)) }
        assertRefused(Reason.NOT_A_KEY_DOCUMENT) { KeyDocument.parse("""{"v":1}""") }

        assertTrue(KeyDocument.parseKeyParams(KEY_PARAMS).kdf.meetsFloor)
        assertEquals(2, KeyDocument.parseWrappedKey(WRAPPED).slots.size)
        assertRefused(Reason.UNSUPPORTED_FORMAT) { KeyDocument.parseKeyParams(WRAPPED) } // it names no cipher
        assertRefused(Reason.NO_SLOTS) { KeyDocument.parseWrappedKey(KEY_PARAMS) }
    }

    @Test
    fun anyJsonFormOfTheSameDocumentReadsTheSame() {
        // Whitespace, members in another order, members no reader uses, and escapes: JSON.parse reads
        // all of these as the vector, and so does the phone.
        val salt = (wrappedTree().slot(0)["saltB64"] as String)
        val spelled = """
            {
              "slots" : [
                { "ctB64": "$PASSPHRASE_CT_B64", "note": {"nested": [1, 2.5e3, null, true]},
                  "nonceB64": "${SyncCrypto.toBase64(PASSPHRASE_NONCE)}",
                  "saltB64": "\u${"%04x".format(salt[0].code)}${salt.substring(1)}",
                  "kdf": { "ops": 3, "memMiB": 256, "alg": "argon2id", "extra": "ignored" },
                  "kind": "\u0070assphrase" },
            ${"\t"}${json(wrappedTree().slot(1))}
              ],
              "v"
                : 1,
              "comment": "fields no reader uses are ignored on both sides"
            }
        """.trimIndent()
        assertNotEquals(WRAPPED, spelled)
        val read = KeyDocument.parseWrappedKey(spelled)
        val vector = KeyDocument.parseWrappedKey(WRAPPED)
        assertEquals(vector.toJson(), read.toJson())
    }

    @Test
    fun aNameGivenTwiceKeepsItsLastValue_asInJsonParse() {
        val tail = WRAPPED.removePrefix("{\"v\":1,")
        assertEquals("{\"v\":1,$tail", WRAPPED)
        KeyDocument.parse("{\"v\":2,\"v\":1,$tail") // the last v is 1
        assertRefused(Reason.UNSUPPORTED_FORMAT) { KeyDocument.parse("{\"v\":1,\"v\":2,$tail") }
    }

    @Test
    fun theWrappedKeyIsReadMoreStrictlyThanTheWebReadsIt_whereNoWriterDiffers() {
        // Each of these opens on the web with the vector's passphrase (checked against dataKey.ts when
        // this was written): it decodes only the slot it opens, and compares numbers loosely. No
        // writer on either side produces any of them.
        assertAllRefused(
            listOf(
                Triple(
                    "a malformed nonce in the slot a passphrase does not open",
                    { it.slot(1)["nonceB64"] = "${it.slot(1)["nonceB64"]}=" },
                    Reason.MALFORMED_BASE64,
                ),
                Triple(
                    "a longer ciphertext in the slot a passphrase does not open",
                    { it.slot(1)["ctB64"] = "${it.slot(1)["ctB64"]}AA" },
                    Reason.WRONG_LENGTH,
                ),
                Triple("v as 1.0", { it["v"] = Raw("1.0") }, Reason.UNSUPPORTED_FORMAT),
                Triple("memMiB as a string", { it.slot(0).kdf()["memMiB"] = "256" }, Reason.KDF_BELOW_FLOOR),
                Triple("memMiB as 256.0", { it.slot(0).kdf()["memMiB"] = Raw("256.0") }, Reason.KDF_BELOW_FLOOR),
                Triple("ops as 3e0", { it.slot(0).kdf()["ops"] = Raw("3e0") }, Reason.KDF_BELOW_FLOOR),
            ),
        )
    }

    @Test
    fun membersOfTheWrongTypeAreRefused_asTheWebRefusesThem() {
        // The web refuses these too: the first three in dataKey.ts, the last in libsodium's own check
        // that the memory limit is a 32-bit integer.
        assertAllRefused(
            listOf(
                Triple("v as a string", { it["v"] = "1" }, Reason.UNSUPPORTED_FORMAT),
                Triple("a slot that is not an object", { it.slots()[0] = Raw("7") }, Reason.NOT_A_KEY_DOCUMENT),
                Triple("slots that are not a list", { it["slots"] = linkedMapOf<String, Any?>("0" to it.slot(0)) }, Reason.NO_SLOTS),
                Triple("memMiB past any Int", { it.slot(0).kdf()["memMiB"] = Raw("4294967552") }, Reason.KDF_BELOW_FLOOR),
            ),
        )
    }

    private fun assertAllRefused(rows: List<Triple<String, (MutableMap<String, Any?>) -> Unit, Reason>>) {
        KeyDocument.parse(json(wrappedTree()))
        for ((name, mutate, reason) in rows) {
            val tree = wrappedTree()
            mutate(tree)
            val text = json(tree)
            assertNotEquals("$name must change the document", WRAPPED, text)
            assertRefused(reason, name) { KeyDocument.parse(text) }
        }
    }

    @Test
    fun theKeyParametersAreRefusedForWhatTheWebRefuses_andForAVersionOrCipherItDoesNotRead() {
        // The web's reader (sync/client.ts) refuses the first four; it does not read v or alg.
        val rows: List<Triple<String, (MutableMap<String, Any?>) -> Unit, Reason>> = listOf(
            Triple("255 MiB", { it.kdf()["memMiB"] = Raw("255") }, Reason.KDF_BELOW_FLOOR),
            Triple("argon2i", { it.kdf()["alg"] = "argon2i" }, Reason.KDF_BELOW_FLOOR),
            Triple("a padded salt", { it["saltB64"] = "${it["saltB64"]}==" }, Reason.MALFORMED_BASE64),
            Triple("a salt of 12 bytes", { it["saltB64"] = (it["saltB64"] as String).take(16) }, Reason.WRONG_LENGTH),
            Triple("version 2", { it["v"] = Raw("2") }, Reason.UNSUPPORTED_FORMAT),
            Triple("another cipher", { it["alg"] = "aes256gcm" }, Reason.UNSUPPORTED_FORMAT),
        )
        KeyDocument.parse(json(keyParamsTree()))
        assertEquals(KEY_PARAMS, json(keyParamsTree()))
        for ((name, mutate, reason) in rows) {
            val tree = keyParamsTree()
            mutate(tree)
            val text = json(tree)
            assertNotEquals("$name must change the document", KEY_PARAMS, text)
            assertRefused(reason, name) { KeyDocument.parse(text) }
        }
    }

    @Test
    fun aKdfAboveTheFloorIsRead_theFloorIsAFloor() {
        val tree = wrappedTree()
        tree.slot(0)["kdf"] = kdfTree().apply { this["memMiB"] = Raw("512"); this["ops"] = Raw("4") }
        val slot = KeyDocument.parseWrappedKey(json(tree)).slots[0]
        assertEquals(512, slot.kdf.memMiB)
        assertEquals(4, slot.kdf.ops)
    }

    @Test
    fun whatIsNotOneJsonObjectIsRefused() {
        for (text in listOf("", "   ", "not json", "[]", "1", "null", "\"{}\"", "$WRAPPED x", "$WRAPPED}", WRAPPED.dropLast(1), "\uFEFF$WRAPPED")) {
            assertRefused(Reason.NOT_A_KEY_DOCUMENT, text.take(20)) { KeyDocument.parse(text) }
        }
        // The positive control for the whitespace rows: trailing JSON whitespace is fine.
        KeyDocument.parse("$WRAPPED \n\t\r")
    }

    @Test
    fun aDocumentLongerThanTheServerKeepsIsRefused_andOneAtTheLimitIsRead() {
        val atLimit = WRAPPED + " ".repeat(KeyDocument.MAX_CHARS - WRAPPED.length)
        assertEquals(KeyDocument.MAX_CHARS, atLimit.length)
        KeyDocument.parse(atLimit)
        assertRefused(Reason.NOT_A_KEY_DOCUMENT) { KeyDocument.parse("$atLimit ") }
    }

    @Test
    fun aDocumentNestedDeeperThanTheServerKeepsIsRefused() {
        // The vector is four levels deep; a member no reader uses takes it to exactly the limit, and
        // one level more is refused.
        fun nested(levels: Int) = "[".repeat(levels) + "]".repeat(levels)
        val tail = WRAPPED.removePrefix("{")
        val atLimit = "{\"deep\":${nested(StrictJson.MAX_DEPTH - 1)},$tail"
        KeyDocument.parse(atLimit)
        assertRefused(Reason.NOT_A_KEY_DOCUMENT) { KeyDocument.parse("{\"deep\":${nested(StrictJson.MAX_DEPTH)},$tail") }
    }

    private fun assertRefused(reason: Reason, label: String = reason.name, block: () -> Unit) {
        try {
            block()
        } catch (e: KeyDocumentException) {
            assertEquals(label, reason, e.reason)
            // Fixed per reason: nothing of the document can be in it.
            assertEquals(label, "key document: ${reason.description}", e.message)
            assertNull(label, e.cause)
            return
        }
        fail("$label: expected KeyDocumentException($reason)")
    }
}
