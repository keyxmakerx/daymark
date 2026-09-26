package com.daymark.synccrypto

import com.daymark.synccrypto.PairingCode.Fault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * [PairingCode] against the server's `PairingCode` (#189, #432): the same alphabet and check symbol, read
 * with the recovery code's normalisation, and refused before anything is sent. The vector is the
 * server's code `K7M4RD96QA`; its id and proof are in DeviceSignatureVectorTest.
 *
 * Each mutation below is derived from the code it changes, and asserted to differ from it before it is
 * asserted refused, so no sweep can pass or fail on a change that changed nothing (`CLAUDE.md` §5).
 */
class PairingCodeTest {

    private val code = "K7M4RD96QA"

    @Test
    fun theAlphabetIsTheServersAndTheRecoveryCodes() {
        assertEquals("23456789ABCDEFGHJKMNPQRSTUVWXYZ", PairingCode.ALPHABET)
        assertEquals(RecoveryCode.ALPHABET, PairingCode.ALPHABET)
        assertEquals(31, PairingCode.ALPHABET.toSet().size)
        assertEquals(10, PairingCode.SYMBOLS)
        assertEquals(code, PairingCode.parse(code).canonical)
    }

    @Test
    fun readsTheCodeAsTheConsoleShowsItAndAsPeopleTypeItBack() {
        val display = code.chunked(5).joinToString("-")
        assertEquals("K7M4R-D96QA", display)
        val typings = listOf(code, display, display.lowercase(), " k7m4r d96qa\n", display.replace("-", " - ")) +
            ('‐'..'―').map { display.replace('-', it) } +
            listOf(' ', ' ', '　', '﻿', '\t').map { display.replace('-', it) }
        for (typed in typings) assertEquals(typed, code, PairingCode.parse(typed).canonical)
        assertEquals(PairingCode.parse(code), PairingCode.parse(display.lowercase()))
        assertEquals(PairingCode.parse(code).hashCode(), PairingCode.parse(display.lowercase()).hashCode())
    }

    @Test
    fun namesEachFault_inOrder_atThePositionAfterNormalising() {
        assertFault(Fault.EMPTY, null, "")
        assertFault(Fault.EMPTY, null, " -– \n\t ")
        for (c in "0O1ILoil") assertFault(Fault.CONFUSABLE, 3, code.replaceAt(2, c))
        for (c in "#éＡ") assertFault(Fault.NOT_IN_ALPHABET, 3, code.replaceAt(2, c))
        // The hyphen goes before positions are counted: the 0 is the sixth symbol.
        assertFault(Fault.CONFUSABLE, 6, "K7M4R-0D96QA")
        // Characters before length: a long paste is told what it holds, not how long it is.
        val longPaste = code + "23456#"
        assertFault(Fault.NOT_IN_ALPHABET, longPaste.length, longPaste)
        assertFault(Fault.TOO_SHORT, null, code.dropLast(1))
        assertFault(Fault.TOO_LONG, null, code + code.last())
        // Length before the check symbol: eleven symbols whose check is also wrong are too long.
        val wrongCheck = code.replaceAt(9, nextSymbol(code[9]))
        assertNotEquals(code, wrongCheck)
        assertFault(Fault.CHECK_SYMBOL, null, wrongCheck)
        assertFault(Fault.TOO_LONG, null, wrongCheck + "2")
    }

    @Test
    fun theCheckSymbolCatchesEverySingleSymbolSubstitution() {
        assertEquals(10 * 30, substitutionsRefused(code))
    }

    @Test
    fun andEveryAdjacentSwap_theCheckSymbolsOwnIncluded() {
        assertEquals("K7M4RD96QA has no two equal neighbours, so every one of its nine swaps is checked", 9, adjacentSwapsRefused(code))
    }

    /**
     * The same two sweeps over codes made from random payloads: repeated symbols happen here, and a swap
     * of two equal neighbours changes nothing, so it is skipped rather than asserted refused.
     */
    @Test
    fun andOnRandomCodes_eachMutationDerivedFromTheCodeItChanges() {
        val random = Random(432)
        var swapsSkipped = 0
        repeat(300) {
            val payload = String(CharArray(PairingCode.PAYLOAD_SYMBOLS) { PairingCode.ALPHABET[random.nextInt(31)] })
            val drawn = payload + PairingCode.checkSymbol(payload)
            assertEquals("control: a code made with the check symbol is a code", drawn, PairingCode.parse(drawn).canonical)
            assertEquals(drawn, 10 * 30, substitutionsRefused(drawn))
            val swaps = adjacentSwapsRefused(drawn)
            swapsSkipped += 9 - swaps
            assertEquals(drawn, (0 until 9).count { drawn[it] != drawn[it + 1] }, swaps)
        }
        assertTrue("the draw never repeated a neighbour, so the skip was never exercised", swapsSkipped > 0)
    }

    @Test
    fun theCheckSymbolIsTheServersSum() {
        // Positions 1 to 9, each times its symbol's value, mod 31: computed here without the code under test.
        val values = code.take(9).map { PairingCode.ALPHABET.indexOf(it) }
        val sum = values.withIndex().sumOf { (i, v) -> (i + 1) * v } % 31
        assertEquals(code[9], PairingCode.ALPHABET[sum])
        assertEquals(code[9], PairingCode.checkSymbol(code.take(9)))
    }

    @Test
    fun neitherTheCodeNorARefusalShowsWhatWasTyped() {
        val parsed = PairingCode.parse(code)
        assertFalse(parsed.toString().contains(code))
        for (group in code.chunked(5)) assertFalse(parsed.toString().contains(group))
        val typo = code.replaceAt(4, nextSymbol(code[4]))
        assertNotEquals(code, typo)
        try {
            PairingCode.parse(typo)
            fail("expected a refusal")
        } catch (e: PairingCodeException) {
            assertEquals("pairing code refused: CHECK_SYMBOL", e.message)
            assertNull(e.cause)
        }
    }

    /** Every code one symbol away from [valid], each refused by its check symbol; how many there were. */
    private fun substitutionsRefused(valid: String): Int {
        var refused = 0
        for (i in valid.indices) {
            for (s in PairingCode.ALPHABET) {
                if (s == valid[i]) continue
                val changed = valid.replaceAt(i, s)
                assertNotEquals(valid, changed)
                assertFault(Fault.CHECK_SYMBOL, null, changed)
                refused++
            }
        }
        return refused
    }

    /** Every swap of two different neighbours in [valid], the check symbol included, each refused; how many there were. */
    private fun adjacentSwapsRefused(valid: String): Int {
        var refused = 0
        for (i in 0 until valid.length - 1) {
            if (valid[i] == valid[i + 1]) continue
            val swapped = valid.replaceAt(i, valid[i + 1]).replaceAt(i + 1, valid[i])
            assertNotEquals(valid, swapped)
            assertFault(Fault.CHECK_SYMBOL, null, swapped)
            refused++
        }
        return refused
    }

    private fun assertFault(fault: Fault, at: Int?, typed: String) {
        try {
            PairingCode.parse(typed)
            fail("expected $fault")
        } catch (e: PairingCodeException) {
            assertEquals(fault, e.fault)
            assertEquals(at, e.at)
        }
    }

    private fun String.replaceAt(i: Int, c: Char): String = substring(0, i) + c + substring(i + 1)

    /** The symbol after [c] in the alphabet, wrapping: never [c] itself. */
    private fun nextSymbol(c: Char): Char = PairingCode.ALPHABET[(PairingCode.ALPHABET.indexOf(c) + 1) % PairingCode.ALPHABET.length]
}
