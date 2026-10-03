package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CPace pinned to the CFRG working group's own test vectors, byte for byte — the same bytes
 * `companion/web/src/lib/pairing/cpace.test.ts` pins the browser side to (its header explains
 * the transitive-agreement mechanism). Copied verbatim from the draft-irtf-cfrg-cpace
 * repository's testvectors.md, "Test vector for CPace using group ristretto255 and hash
 * SHA-512". Runs real native libsodium on the host JVM, exactly like SyncCryptoTest.
 */
class CpaceCryptoTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val cpace = CpaceCrypto(sodium)

    private fun hex(h: String): ByteArray {
        val clean = h.replace(Regex("\\s"), "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // --- the draft's CPACE-RISTRETTO255-SHA512 vector, verbatim ---------------------------

    private val prs = "Password".toByteArray(Charsets.US_ASCII)
    private val ci = hex("6f630b425f726573706f6e6465720b415f696e69746961746f72")
    private val sid = hex("7e4b4791d6a8ef019b936c79fb7f2c57")
    private val generatorString = hex(
        "11435061636552697374726574746f3235350850617373776f726464" +
            "00000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000001a6f630b425f726573706f6e" +
            "6465720b415f696e69746961746f72107e4b4791d6a8ef019b936c79" +
            "fb7f2c57",
    )
    private val generator = hex("a6fc82c3b8968fbb2e06fee81ca858586dea50d248f0c7ca6a18b0902a30b36b")
    private val ada = "ADa".toByteArray(Charsets.US_ASCII)
    private val yaScalar = hex("da3d23700a9e5699258aef94dc060dfda5ebb61f02a5ea77fad53f4ff0976d08")
    private val yaPoint = hex("d40fb265a7abeaee7939d91a585fe59f7053f982c296ec413c624c669308f87a")
    private val adb = "ADb".toByteArray(Charsets.US_ASCII)
    private val ybScalar = hex("d2316b454718c35362d83d69df6320f38578ed5984651435e2949762d900b80d")
    private val ybPoint = hex("08bcf6e9777a9c313a3db6daa510f2d398403319c2341bd506a92e672eb7e307")
    private val iskIr = hex(
        "4c5469a16b2364c4b944ebc1a79e51d1674ad47db26e8718154f59fa" +
            "ebfaa52d8346f30aa58377117eb20d527f2cbc5c76381f7fd372e89d" +
            "f8239f87f2e02ed1",
    )

    // --- lv encoding (draft appendix pins) ------------------------------------------------

    @Test
    fun lvEncodingMatchesTheDraftPinsIncludingTheLeb128BoundaryAt128() {
        assertEquals("00", toHex(CpaceCrypto.prependLen(ByteArray(0))))
        assertEquals(5, CpaceCrypto.prependLen("1234".toByteArray()).size)
        val b128 = ByteArray(128) { it.toByte() }
        val enc = CpaceCrypto.prependLen(b128)
        // 128 needs two LEB128 bytes: 0x80 (low 7 bits + continue), 0x01.
        assertEquals(130, enc.size)
        assertEquals("8001", toHex(enc.copyOfRange(0, 2)))
        assertArrayEquals(b128, CpaceCrypto.parseLv(enc, 1)[0])
    }

    @Test
    fun parseIsTheExactInverseAndRefusesTrailingOrTruncatedBytes() {
        val cat = CpaceCrypto.lvCat("ab".toByteArray(), ByteArray(0), "c".toByteArray())
        val items = CpaceCrypto.parseLv(cat, 3)
        assertEquals(listOf("6162", "", "63"), items.map(::toHex))
        try {
            CpaceCrypto.parseLv(cat.copyOfRange(0, cat.size - 1), 3)
            fail("truncated input must be refused")
        } catch (e: CpaceCrypto.CpaceException) {
            assertTrue(e.message!!.contains("truncated"))
        }
        try {
            CpaceCrypto.parseLv(cat + byteArrayOf(0), 3)
            fail("trailing bytes must be refused")
        } catch (e: CpaceCrypto.CpaceException) {
            assertTrue(e.message!!.contains("trailing"))
        }
    }

    // --- generator derivation -------------------------------------------------------------

    @Test
    fun buildsTheExact172ByteGeneratorString() {
        assertArrayEquals(generatorString, cpace.generatorString(prs, ci, sid))
    }

    @Test
    fun hashesToThePublishedGeneratorPoint() {
        assertArrayEquals(generator, cpace.calculateGenerator(prs, ci, sid))
    }

    // --- the full exchange against the published vector -----------------------------------

    @Test
    fun reproducesMsgAMsgBAndTheInitiatorResponderIskByteForByte() {
        val a = cpace.start(prs, ci, sid, ada, yaScalar)
        assertArrayEquals(CpaceCrypto.lvCat(yaPoint, ada), a.msgA)

        val b = cpace.respond(prs, ci, sid, a.msgA, adb, ybScalar)
        assertArrayEquals(CpaceCrypto.lvCat(ybPoint, adb), b.msgB)
        assertArrayEquals(iskIr, b.isk)

        assertArrayEquals(iskIr, cpace.finish(sid, a, b.msgB))
    }

    // --- the properties the pairing design leans on ---------------------------------------

    @Test
    fun aFreshRandomExchangeAgreesOnBothSidesAndNeverRepeats() {
        fun run(): String {
            val a = cpace.start(prs, ci, sid, ada)
            val b = cpace.respond(prs, ci, sid, a.msgA, adb)
            val iskA = cpace.finish(sid, a, b.msgB)
            assertArrayEquals(b.isk, iskA)
            assertEquals(CpaceCrypto.ISK_BYTES, iskA.size)
            return toHex(iskA)
        }
        assertFalse(run() == run())
    }

    @Test
    fun aWrongCodeYieldsADifferentKeyAndNoError() {
        // The mismatch surfaces as an AEAD failure at the next layer, where a human decides
        // what it means — this layer cannot tell a typo from an attacker, by construction,
        // which is why a wrong code must never burn an invite (docs/COMPANION_PAIRING.md §6, §7).
        val a = cpace.start(prs, ci, sid, ada)
        val b = cpace.respond("Passw0rd".toByteArray(), ci, sid, a.msgA, adb)
        assertFalse(cpace.finish(sid, a, b.msgB).contentEquals(b.isk))
    }

    @Test
    fun aMismatchedSidOrCiAlsoDiverges() {
        val a = cpace.start(prs, ci, sid, ada)
        val otherSid = sid.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val b = cpace.respond(prs, ci, otherSid, a.msgA, adb)
        assertFalse(cpace.finish(sid, a, b.msgB).contentEquals(b.isk))

        val c = cpace.respond(prs, "someone-else".toByteArray(), sid, a.msgA, adb)
        assertFalse(cpace.finish(sid, a, c.msgB).contentEquals(c.isk))
    }

    @Test
    fun aTamperedMessageEitherAbortsOrDiverges() {
        val a = cpace.start(prs, ci, sid, ada)
        val honest = cpace.respond(prs, ci, sid, a.msgA, adb, ybScalar)
        val tampered = a.msgA.copyOf().also { it[5] = (it[5].toInt() xor 0x40).toByte() }
        val outcome = try {
            toHex(cpace.respond(prs, ci, sid, tampered, adb, ybScalar).isk)
        } catch (_: CpaceCrypto.CpaceException) {
            "aborted"
        }
        assertFalse(outcome == toHex(honest.isk))
    }

    @Test
    fun theIdentityPointIsRefusedOutright() {
        // The draft's mandatory scalar_mult_vfy abort: an identity K would make the key
        // independent of both scalars, so the point is refused before any key exists.
        val forged = CpaceCrypto.lvCat(ByteArray(32), ada)
        try {
            cpace.respond(prs, ci, sid, forged, adb)
            fail("identity point must be refused")
        } catch (_: CpaceCrypto.CpaceException) {
            // expected
        }
    }

    @Test
    fun aSidOfTheWrongSizeIsRefusedBeforeAnyCryptoRuns() {
        assertEquals(16, CpaceCrypto.SID_BYTES)
        try {
            cpace.start(prs, ci, sid.copyOfRange(0, 8), ada)
            fail("short sid must be refused")
        } catch (e: CpaceCrypto.CpaceException) {
            assertTrue(e.message!!.contains("16 bytes"))
        }
    }
}
