package com.daymark.synccrypto

import com.daymark.synccrypto.KeyDocumentException.Reason
import com.daymark.synccrypto.KeyDocumentVector.CODE
import com.daymark.synccrypto.KeyDocumentVector.KEY_PARAMS
import com.daymark.synccrypto.KeyDocumentVector.KEY_PARAMS_SALT
import com.daymark.synccrypto.KeyDocumentVector.MASTER
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE_NONCE
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE_SALT
import com.daymark.synccrypto.KeyDocumentVector.PASSPHRASE_UTF8
import com.daymark.synccrypto.KeyDocumentVector.PAYLOAD
import com.daymark.synccrypto.KeyDocumentVector.RECOVERY_NONCE
import com.daymark.synccrypto.KeyDocumentVector.RECOVERY_SALT
import com.daymark.synccrypto.KeyDocumentVector.Raw
import com.daymark.synccrypto.KeyDocumentVector.SUBKEY_1
import com.daymark.synccrypto.KeyDocumentVector.SUBKEY_2
import com.daymark.synccrypto.KeyDocumentVector.SUBKEY_3
import com.daymark.synccrypto.KeyDocumentVector.SUBKEY_4
import com.daymark.synccrypto.KeyDocumentVector.TYPED_CODE
import com.daymark.synccrypto.KeyDocumentVector.WRAPPED
import com.daymark.synccrypto.KeyDocumentVector.WRAPPED_SHA256
import com.daymark.synccrypto.KeyDocumentVector.hex
import com.daymark.synccrypto.KeyDocumentVector.json
import com.daymark.synccrypto.KeyDocumentVector.kdf
import com.daymark.synccrypto.KeyDocumentVector.kdfTree
import com.daymark.synccrypto.KeyDocumentVector.keyParamsTree
import com.daymark.synccrypto.KeyDocumentVector.slot
import com.daymark.synccrypto.KeyDocumentVector.slots
import com.daymark.synccrypto.KeyDocumentVector.strayBits
import com.daymark.synccrypto.KeyDocumentVector.wrappedTree
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * The key documents the phone is held to (#403): the constants in [KeyDocumentVector], which
 * `companion/web/src/lib/recovery/dataKeyVector.test.ts` made with the web's own writer and asserts
 * in the same terms. The phone opens both documents with the same secrets to the same master and the
 * same four subkeys; its writer makes the wrapped key's exact bytes from the same inputs, so a
 * document the phone made opens on the web; and it refuses, before deriving anything, every mutation
 * the web test refuses, under the same names.
 *
 * Every open here runs Argon2id at the floor, 256 MiB and 3 passes, because the floor is checked on
 * every slot and a vector below it would be refused on both sides. [CountingSodium] counts the runs,
 * so a refusal is shown to come before any. The ceiling, 512 MiB and 8 passes, is held the same way
 * on every slot of every kind and on the key parameters; its edge is shown to reach Argon2id by a
 * [CountingSodium] that derives nothing, so no test here spends 512 MiB.
 */
class KeyDocumentVectorTest {

    private val sodium = CountingSodium()
    private val crypto = SyncCrypto(sodium)

    @Test
    fun theTestsOwnBuilderWritesTheVector_soEachMutationBelowStartsFromIt() {
        assertEquals(WRAPPED, json(wrappedTree()))
        assertEquals(KEY_PARAMS, json(keyParamsTree()))
        assertEquals(WRAPPED_SHA256, sha256(WRAPPED))
    }

    @Test
    fun readsTheRecoveryCodeAsTheWebDoes_thisCheckSymbolAndThisTypingOfIt() {
        assertEquals(CODE.last(), RecoveryCode.checkSymbol(PAYLOAD))
        assertEquals(CODE, RecoveryCode.normalize(TYPED_CODE))
        assertEquals(CODE, RecoveryCode.parse(TYPED_CODE).canonical)
    }

    @Test
    fun thePassphraseReachesArgon2idAsTheseBytes() {
        assertEquals(PASSPHRASE_UTF8, hex(SyncCrypto.secretBytes(PASSPHRASE)))
    }

    @Test
    fun theKeyParametersGiveThisMaster_andDeriveKeysTheSameFirstTwoSubkeys() {
        val document = KeyDocument.parse(KEY_PARAMS)
        assertTrue(document is KeyDocument.KeyParams)
        val params = document as KeyDocument.KeyParams
        assertArrayEquals(KEY_PARAMS_SALT, params.salt)
        assertEquals(256, params.kdf.memMiB)
        assertEquals(3, params.kdf.ops)

        assertEquals(MASTER, hex(crypto.masterWithPassphrase(document, PASSPHRASE)))
        val opened = crypto.openWithPassphrase(document, PASSPHRASE)
        assertEquals(SUBKEY_1, hex(opened.syncKey))
        assertEquals(SUBKEY_2, hex(opened.manifestSeed))
        // SyncCrypto.deriveKeys at its default, the floor, is the same derivation.
        val derived = crypto.deriveKeys(PASSPHRASE, KEY_PARAMS_SALT)
        assertEquals(SUBKEY_1, hex(derived.syncKey))
        assertEquals(SUBKEY_2, hex(derived.manifestSeed))
        assertEquals(3, sodium.argon2idRuns)
    }

    @Test
    fun thePassphraseOpensTheWrappedKeyToThatMaster_whoseFourSubkeysAreThese() {
        val master = crypto.masterWithPassphrase(KeyDocument.parse(WRAPPED), PASSPHRASE)
        assertEquals(MASTER, hex(master))
        assertEquals(SUBKEY_1, hex(crypto.deriveSubkey(master, 1, 32)))
        assertEquals(SUBKEY_2, hex(crypto.deriveSubkey(master, 2, 32)))
        assertEquals(SUBKEY_3, hex(crypto.deriveSubkey(master, 3, 32)))
        assertEquals(SUBKEY_4, hex(crypto.deriveSubkey(master, 4, 32)))
        assertEquals("one derivation, for the one passphrase slot", 1, sodium.argon2idRuns)
    }

    @Test
    fun theRecoveryCodeAsTypedOpensItToTheSameKeys() {
        val keys = crypto.openWithRecoveryCode(KeyDocument.parse(WRAPPED), TYPED_CODE)
        assertEquals(SUBKEY_1, hex(keys.syncKey))
        assertEquals(SUBKEY_2, hex(keys.manifestSeed))
        assertEquals(1, sodium.argon2idRuns)
    }

    @Test
    fun thePhonesWriterMakesTheWebsBytesExactly_soADocumentThePhoneMadeOpensOnTheWeb() {
        val master = unhex(MASTER)
        val document = KeyDocument.WrappedKey(
            listOf(
                crypto.wrapSlot(master, PASSPHRASE, KeyDocument.SlotKind.PASSPHRASE, SyncCrypto.KdfParams.DEFAULT, PASSPHRASE_SALT, PASSPHRASE_NONCE),
                crypto.wrapSlot(master, CODE, KeyDocument.SlotKind.RECOVERY, SyncCrypto.KdfParams.DEFAULT, RECOVERY_SALT, RECOVERY_NONCE),
            ),
        )
        val text = document.toJson()
        assertEquals(WRAPPED, text)
        assertEquals(WRAPPED_SHA256, sha256(text))
    }

    @Test
    fun aWrongPassphraseOrAWrongCodeFailsInTheAead_andSaysNothingMore() {
        val document = KeyDocument.parse(WRAPPED)
        val right = crypto.openWithPassphrase(document, PASSPHRASE)
        assertEquals(SUBKEY_1, hex(right.syncKey))

        val wrongPassphrase = "$PASSPHRASE!"
        val byPassphrase = assertRefused(Reason.DID_NOT_OPEN) { crypto.openWithPassphrase(document, wrongPassphrase) }

        // Another code in its own right: one payload symbol changed, the check symbol recomputed.
        val otherPayload = (if (PAYLOAD[0] == 'A') "B" else "A") + PAYLOAD.substring(1)
        val otherCode = otherPayload + RecoveryCode.checkSymbol(otherPayload)
        assertNotEquals(CODE, otherCode)
        assertEquals(otherCode, RecoveryCode.parse(otherCode).canonical)
        val byCode = assertRefused(Reason.DID_NOT_OPEN) { crypto.openWithRecoveryCode(document, otherCode) }

        assertEquals("one outcome, in one sentence", byPassphrase.message, byCode.message)
        assertEquals(3, sodium.argon2idRuns)
    }

    @Test
    fun theKindIsBoundIntoTheSlot_soARelabelledSlotDoesNotOpenUnderTheSameSecret() {
        // The recovery slot with its own label opens with the code's 30 symbols...
        val keys = crypto.openWithRecoveryCode(KeyDocument.parse(WRAPPED), CODE)
        assertEquals(SUBKEY_1, hex(keys.syncKey))

        // ...and the same slot relabelled "passphrase", offered the same 30 symbols as a passphrase,
        // does not. The secret, salt, nonce and parameters are unchanged: only the associated data
        // differs, because it names the kind.
        val relabelled = wrappedTree()
        relabelled.slot(1)["kind"] = "passphrase"
        relabelled.slots().removeAt(0)
        val text = json(relabelled)
        assertNotEquals(WRAPPED, text)
        assertRefused(Reason.DID_NOT_OPEN) { crypto.openWithPassphrase(KeyDocument.parse(text), CODE) }
        assertEquals(2, sodium.argon2idRuns)
    }

    @Test
    fun onlyTheFirstPassphraseSlotIsTried_andEachRecoverySlotInTurn_asTheWebDoes() {
        val vector = KeyDocument.parseWrappedKey(WRAPPED)
        val master = unhex(MASTER)
        val salt = ByteArray(16) { (0x80 + it).toByte() }
        val nonce = ByteArray(24) { (0x90 + it).toByte() }
        val otherPassphrase = "$PASSPHRASE, and another"
        val otherPayload = PAYLOAD.substring(0, 1) + (if (PAYLOAD[1] == 'A') 'B' else 'A') + PAYLOAD.substring(2)
        val otherCode = otherPayload + RecoveryCode.checkSymbol(otherPayload)
        assertNotEquals(CODE, otherCode)
        // Each locks the same master under a secret that is not the vector's.
        val otherPassphraseSlot = crypto.wrapSlot(master, otherPassphrase, KeyDocument.SlotKind.PASSPHRASE, SyncCrypto.KdfParams.DEFAULT, salt, nonce)
        val otherRecoverySlot = crypto.wrapSlot(master, otherCode, KeyDocument.SlotKind.RECOVERY, SyncCrypto.KdfParams.DEFAULT, salt, nonce)

        // The vector's passphrase slot, second in line, is never tried: the first passphrase slot is
        // the one a passphrase opens (dataKey.ts, unwrapWithPassphrase), so this is the web's answer too.
        var runs = sodium.argon2idRuns
        assertRefused(Reason.DID_NOT_OPEN) {
            crypto.openWithPassphrase(KeyDocument.WrappedKey(listOf(otherPassphraseSlot) + vector.slots), PASSPHRASE)
        }
        assertEquals(1, sodium.argon2idRuns - runs)

        // Recovery slots are tried in order, and the first that opens gives the master.
        runs = sodium.argon2idRuns
        val keys = crypto.openWithRecoveryCode(
            KeyDocument.WrappedKey(listOf(vector.slots[0], otherRecoverySlot, vector.slots[1])),
            TYPED_CODE,
        )
        assertEquals(SUBKEY_1, hex(keys.syncKey))
        assertEquals(2, sodium.argon2idRuns - runs)
    }

    @Test
    fun refusesWhatTheWebRefuses_underTheSameNames_beforeAnyKeyIsDerived() {
        // The rows of dataKeyVector.test.ts, "what both sides refuse before deriving anything". Each
        // mutation is derived from the vector's own value and checked to change the document.
        val rows: List<Triple<String, (MutableMap<String, Any?>) -> Unit, Reason>> = listOf(
            Triple("version 2", { it["v"] = Raw("2") }, Reason.UNSUPPORTED_FORMAT),
            Triple("no slots", { it["slots"] = mutableListOf<Any?>() }, Reason.NO_SLOTS),
            Triple("the recovery slot at 255 MiB", { it.slot(1).kdf()["memMiB"] = Raw("255") }, Reason.KDF_BELOW_FLOOR),
            Triple("the recovery slot at 2 passes", { it.slot(1).kdf()["ops"] = Raw("2") }, Reason.KDF_BELOW_FLOOR),
            Triple("the passphrase slot on argon2i", { it.slot(0).kdf()["alg"] = "argon2i" }, Reason.KDF_BELOW_FLOOR),
            Triple("the passphrase slot with no kdf", { it.slot(0).remove("kdf") }, Reason.KDF_BELOW_FLOOR),
            Triple("the passphrase slot at 513 MiB", { it.slot(0).kdf()["memMiB"] = Raw("513") }, Reason.KDF_ABOVE_CEILING),
            Triple("the passphrase slot at 9 passes", { it.slot(0).kdf()["ops"] = Raw("9") }, Reason.KDF_ABOVE_CEILING),
            Triple("the recovery slot at 513 MiB", { it.slot(1).kdf()["memMiB"] = Raw("513") }, Reason.KDF_ABOVE_CEILING),
            Triple("the recovery slot at 9 passes", { it.slot(1).kdf()["ops"] = Raw("9") }, Reason.KDF_ABOVE_CEILING),
            Triple(
                "the passphrase slot at 1024 MiB and 2 passes",
                { it.slot(0)["kdf"] = kdfTree().apply { this["memMiB"] = Raw("1024"); this["ops"] = Raw("2") } },
                Reason.KDF_BELOW_FLOOR,
            ),
            Triple("a padded salt", { it.slot(0)["saltB64"] = "${it.slot(0)["saltB64"]}==" }, Reason.MALFORMED_BASE64),
            Triple(
                "a nonce in the standard alphabet",
                { it.slot(0)["nonceB64"] = (it.slot(0)["nonceB64"] as String).replace('-', '+') },
                Reason.MALFORMED_BASE64,
            ),
            Triple(
                "a ciphertext in the standard alphabet",
                { it.slot(0)["ctB64"] = (it.slot(0)["ctB64"] as String).replace('_', '/') },
                Reason.MALFORMED_BASE64,
            ),
            Triple(
                "a salt with bits after its last byte",
                { it.slot(0)["saltB64"] = strayBits(it.slot(0)["saltB64"] as String) },
                Reason.MALFORMED_BASE64,
            ),
            Triple("a salt of 12 bytes", { it.slot(0)["saltB64"] = (it.slot(0)["saltB64"] as String).take(16) }, Reason.WRONG_LENGTH),
            Triple("a ciphertext one byte longer", { it.slot(0)["ctB64"] = "${it.slot(0)["ctB64"]}AA" }, Reason.WRONG_LENGTH),
        )
        // The unmutated vector clears every check a row trips.
        KeyDocument.parse(json(wrappedTree()))
        for ((name, mutate, reason) in rows) {
            val tree = wrappedTree()
            mutate(tree)
            val text = json(tree)
            assertNotEquals("$name must change the document", WRAPPED, text)
            assertRefused(reason, name) { crypto.openWithPassphrase(KeyDocument.parse(text), PASSPHRASE) }
        }
        assertEquals(0, sodium.argon2idRuns)
    }

    @Test
    fun aSlotOfAnUnknownKindIsSkipped_andStillHeldToTheRange() {
        // Kinds this reader does not know, as a later writer may add them (a passkey's PRF output, a
        // Shamir share), before, between and after the vector's two slots. Each carries KDF
        // parameters at the floor and nothing a known slot needs: no salt, nonce or ciphertext.
        fun unknownSlots(): MutableList<Any?> = mutableListOf(
            linkedMapOf<String, Any?>("kind" to "webauthn-prf", "kdf" to kdfTree(), "credentialId" to "not base64 !!"),
            linkedMapOf<String, Any?>("kdf" to kdfTree()), // no kind at all
            linkedMapOf<String, Any?>("kind" to Raw("1"), "kdf" to kdfTree()), // a kind that is not a string
        )
        fun document(): MutableMap<String, Any?> = wrappedTree().apply {
            val (prf, kindless, numbered) = unknownSlots()
            slots().add(0, prf)
            slots().add(2, kindless)
            slots().add(numbered)
        }
        val text = json(document())
        assertNotEquals(WRAPPED, text)

        // Skipped: what is read is exactly the vector's two slots, in order.
        val read = KeyDocument.parseWrappedKey(text)
        assertEquals(WRAPPED, read.toJson())
        // The positive control: the known slots beside them still open, with either secret.
        assertEquals(SUBKEY_1, hex(crypto.openWithPassphrase(read, PASSPHRASE).syncKey))
        assertEquals(SUBKEY_1, hex(crypto.openWithRecoveryCode(read, TYPED_CODE).syncKey))
        assertEquals(2, sodium.argon2idRuns)

        // A wrapped key of unknown kinds only is read, and has no slot for either secret: the same
        // refusal as any document without that kind.
        val unknownOnly = KeyDocument.parse(json(linkedMapOf<String, Any?>("v" to Raw("1"), "slots" to unknownSlots())))
        assertRefused(Reason.NO_SLOT_OF_THAT_KIND) { crypto.openWithPassphrase(unknownOnly, PASSPHRASE) }
        assertRefused(Reason.NO_SLOT_OF_THAT_KIND) { crypto.openWithRecoveryCode(unknownOnly, CODE) }

        // Still held to the floor and the ceiling, as the web holds every slot before it picks one: a
        // weak slot of a kind nobody reads must not survive a round trip next to strong ones, and a
        // costly one must not set what a reader spends.
        val rangeRows: List<Triple<String, (MutableMap<String, Any?>) -> Unit, Reason>> = listOf(
            Triple("the unknown slot at 255 MiB", { it.slot(0).kdf()["memMiB"] = Raw("255") }, Reason.KDF_BELOW_FLOOR),
            Triple("the unknown slot at 2 passes", { it.slot(0).kdf()["ops"] = Raw("2") }, Reason.KDF_BELOW_FLOOR),
            Triple("the unknown slot on argon2i", { it.slot(0).kdf()["alg"] = "argon2i" }, Reason.KDF_BELOW_FLOOR),
            Triple("the unknown slot with no kdf", { it.slot(0).remove("kdf") }, Reason.KDF_BELOW_FLOOR),
            Triple("the kindless slot at 255 MiB", { it.slot(2).kdf()["memMiB"] = Raw("255") }, Reason.KDF_BELOW_FLOOR),
            Triple("the unknown slot at 513 MiB", { it.slot(0).kdf()["memMiB"] = Raw("513") }, Reason.KDF_ABOVE_CEILING),
            Triple("the unknown slot at 9 passes", { it.slot(0).kdf()["ops"] = Raw("9") }, Reason.KDF_ABOVE_CEILING),
        )
        for ((name, mutate, reason) in rangeRows) {
            val tree = document()
            mutate(tree)
            val mutated = json(tree)
            assertNotEquals("$name must change the document", text, mutated)
            assertRefused(reason, name) { crypto.openWithPassphrase(KeyDocument.parse(mutated), PASSPHRASE) }
        }
        assertEquals("nothing after the positive control ran Argon2id", 2, sodium.argon2idRuns)

        // And the edge is in the range: the unknown slot at 512 MiB and 8 passes is skipped, and the
        // passphrase slot beside it goes to Argon2id at its own parameters, as it always does.
        val edge = document().apply { slot(0)["kdf"] = kdfTree().apply { this["memMiB"] = Raw("512"); this["ops"] = Raw("8") } }
        val edgeText = json(edge)
        assertNotEquals(text, edgeText)
        assertEquals(WRAPPED, KeyDocument.parseWrappedKey(edgeText).toJson())
        assertEdgeReachesArgon2id(listOf(3L to 256L * MIB)) { it.openWithPassphrase(KeyDocument.parse(edgeText), PASSPHRASE) }
    }

    @Test
    fun bothEndsOfTheRangeAreInIt_andOnePastEitherIsNot() {
        // The validator itself, at the edges the rows sit just outside of, with the web's numbers.
        for ((memMiB, ops) in listOf(256 to 3, 512 to 8, 256 to 8, 512 to 3)) {
            val params = SyncCrypto.KdfParams(memMiB = memMiB, ops = ops)
            assertTrue("$memMiB MiB, $ops passes", params.meetsFloor && params.withinCeiling)
        }
        assertFalse(SyncCrypto.KdfParams(memMiB = 513, ops = 8).withinCeiling)
        assertFalse(SyncCrypto.KdfParams(memMiB = 512, ops = 9).withinCeiling)
        assertEquals(512, SyncCrypto.KdfParams.CEILING_MEM_MIB)
        assertEquals(8, SyncCrypto.KdfParams.CEILING_OPS)
    }

    @Test
    fun aSlotOfEitherKindAt512MiBAnd8PassesIsNotRefused_Argon2idIsAskedForExactlyThat() {
        fun atTheEdge(index: Int): String = json(
            wrappedTree().apply { slot(index)["kdf"] = kdfTree().apply { this["memMiB"] = Raw("512"); this["ops"] = Raw("8") } },
        )
        val passphraseEdge = atTheEdge(0)
        val recoveryEdge = atTheEdge(1)
        assertNotEquals(WRAPPED, passphraseEdge)
        assertNotEquals(WRAPPED, recoveryEdge)
        assertEdgeReachesArgon2id(listOf(8L to 512L * MIB)) { it.openWithPassphrase(KeyDocument.parse(passphraseEdge), PASSPHRASE) }
        assertEdgeReachesArgon2id(listOf(8L to 512L * MIB)) { it.openWithRecoveryCode(KeyDocument.parse(recoveryEdge), TYPED_CODE) }
    }

    @Test
    fun theKeyParametersAreHeldToTheSameRange() {
        // The rows of dataKeyVector.test.ts, "the key parameters are held to the same range".
        val rows: List<Pair<String, (MutableMap<String, Any?>) -> Unit>> = listOf(
            "the key parameters at 513 MiB" to { it.kdf()["memMiB"] = Raw("513") },
            "the key parameters at 9 passes" to { it.kdf()["ops"] = Raw("9") },
        )
        for ((name, mutate) in rows) {
            val tree = keyParamsTree()
            mutate(tree)
            val text = json(tree)
            assertNotEquals("$name must change the document", KEY_PARAMS, text)
            assertRefused(Reason.KDF_ABOVE_CEILING, name) { crypto.openWithPassphrase(KeyDocument.parse(text), PASSPHRASE) }
        }
        assertEquals(0, sodium.argon2idRuns)

        val edge = json(keyParamsTree().apply { kdf()["memMiB"] = Raw("512"); kdf()["ops"] = Raw("8") })
        assertNotEquals(KEY_PARAMS, edge)
        assertEdgeReachesArgon2id(listOf(8L to 512L * MIB)) { it.openWithPassphrase(KeyDocument.parse(edge), PASSPHRASE) }
    }

    @Test
    fun theRangeIsCheckedAgainWhereAKeyIsDerived_notOnlyWhereADocumentIsRead() {
        // Documents made in this module without the reader, as a later writer or a test may make them:
        // the key parameters and the slot in use are held to the range again before Argon2id.
        val (p, r) = KeyDocument.parseWrappedKey(WRAPPED).slots
        fun at(slot: KeyDocument.WrappedKey.Slot, kdf: SyncCrypto.KdfParams) =
            KeyDocument.WrappedKey.Slot(slot.kind, kdf, slot.salt, slot.nonce, slot.ciphertext)
        val costly = SyncCrypto.KdfParams(memMiB = 513, ops = 3)
        val weak = SyncCrypto.KdfParams(memMiB = 255, ops = 3)
        assertRefused(Reason.KDF_ABOVE_CEILING) { crypto.openWithPassphrase(KeyDocument.WrappedKey(listOf(at(p, costly), r)), PASSPHRASE) }
        assertRefused(Reason.KDF_ABOVE_CEILING) { crypto.openWithRecoveryCode(KeyDocument.WrappedKey(listOf(p, at(r, costly))), TYPED_CODE) }
        assertRefused(Reason.KDF_ABOVE_CEILING) { crypto.openWithPassphrase(KeyDocument.KeyParams(costly, KEY_PARAMS_SALT), PASSPHRASE) }
        assertRefused(Reason.KDF_BELOW_FLOOR) { crypto.openWithPassphrase(KeyDocument.KeyParams(weak, KEY_PARAMS_SALT), PASSPHRASE) }
        assertEquals(0, sodium.argon2idRuns)
    }

    /**
     * [open] is not refused by any check before Argon2id: run against libsodium that derives nothing,
     * it fails in Argon2id itself, having asked for exactly [asked] (passes, bytes).
     */
    private fun assertEdgeReachesArgon2id(asked: List<Pair<Long, Long>>, open: (SyncCrypto) -> Unit) {
        val stub = CountingSodium(deriveNothing = true)
        try {
            open(SyncCrypto(stub))
            fail("expected Argon2id to be reached, and to fail")
        } catch (e: SyncCrypto.SyncCryptoException) {
            assertEquals("Argon2id key derivation failed", e.message)
        }
        assertEquals(asked, stub.askedFor)
    }

    @Test
    fun aMistypedCodeIsRefusedByItsCheckSymbol_beforeTheDocumentIsOpened() {
        val at = 5
        val typo = CODE.substring(0, at) + (if (CODE[at] == 'X') 'Y' else 'X') + CODE.substring(at + 1)
        assertNotEquals(CODE, typo)
        for (document in listOf(KeyDocument.parse(WRAPPED), KeyDocument.parse(KEY_PARAMS))) {
            try {
                crypto.openWithRecoveryCode(document, typo)
                fail("expected the check symbol to refuse the code")
            } catch (e: RecoveryCodeException) {
                assertEquals(RecoveryCode.Fault.CHECKSUM, e.fault)
            }
        }
        assertEquals(0, sodium.argon2idRuns)
    }

    @Test
    fun aDocumentWithNoSlotForTheSecretOfferedSaysSo_withNothingDerived() {
        // The right code, and the key parameters, which have no recovery slot.
        assertRefused(Reason.NO_SLOT_OF_THAT_KIND) { crypto.openWithRecoveryCode(KeyDocument.parse(KEY_PARAMS), CODE) }
        // The right passphrase, and a wrapped key with only its recovery slot left.
        val recoveryOnly = wrappedTree()
        recoveryOnly.slots().removeAt(0)
        assertRefused(Reason.NO_SLOT_OF_THAT_KIND) { crypto.openWithPassphrase(KeyDocument.parse(json(recoveryOnly)), PASSPHRASE) }
        assertEquals(0, sodium.argon2idRuns)
    }

    private fun assertRefused(reason: Reason, label: String = reason.name, block: () -> Unit): KeyDocumentException {
        try {
            block()
        } catch (e: KeyDocumentException) {
            assertEquals(label, reason, e.reason)
            // Fixed per reason: nothing of the document or the secret can be in it.
            assertEquals(label, "key document: ${reason.description}", e.message)
            assertNull(label, e.cause)
            return e
        }
        fail("$label: expected KeyDocumentException($reason)")
        throw AssertionError("unreachable")
    }

    private fun sha256(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private companion object {
        const val MIB = 1024L * 1024
    }

    private fun unhex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
