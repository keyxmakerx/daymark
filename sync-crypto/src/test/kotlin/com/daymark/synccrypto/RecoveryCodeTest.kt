package com.daymark.synccrypto

import com.daymark.synccrypto.KeyDocumentVector.CODE
import com.daymark.synccrypto.RecoveryCode.Fault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

/**
 * [RecoveryCode] against the web's reader, `companion/web/src/lib/recovery/recoveryCode.ts`: the same
 * normalisation, the same faults in the same order at the same positions, and the same check symbol
 * (#403). KeyDocumentVectorTest holds the shared vector: one code, and one way of typing it back.
 */
class RecoveryCodeTest {

    private val display = CODE.chunked(5).joinToString("-")

    @Test
    fun readsThePrintedFormAndTheWaysPeopleTypeItBack() {
        assertEquals("K7M2Q-XR9CT-4HWAZ-P3NE8-GUV6D-YJF59", display)
        val typings = listOf(CODE, display, display.lowercase(), " $display\n", display.replace("-", " - ")) +
            ('\u2010'..'\u2015').map { display.replace('-', it) } +
            JS_WHITESPACE.map { display.replace('-', it) }
        for (typed in typings) assertEquals(typed, CODE, RecoveryCode.parse(typed).canonical)
    }

    @Test
    fun dropsExactlyWhatTheWebDrops_andFoldsExactlyWhatItFolds_overEveryCodePoint() {
        // The web's normalisation, measured with Node 22 over every code point when this was written:
        // `\s`, '-' and U+2010..U+2015 are dropped, and full Unicode upper-casing folds these, and
        // only these, characters beyond ASCII into symbols of a code (or into 0, O, 1, I, L).
        val expectedFolds = mapOf(
            0xDF to "SS", 0x131 to "I", 0x17F to "S",
            0xFB00 to "FF", 0xFB01 to "FI", 0xFB02 to "FL", 0xFB03 to "FFI", 0xFB04 to "FFL", 0xFB05 to "ST", 0xFB06 to "ST",
        )
        val symbols = RecoveryCode.ALPHABET + "0O1IL"
        val dropped = sortedSetOf<Int>()
        val folds = sortedMapOf<Int, String>()
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            val normal = RecoveryCode.normalize(String(Character.toChars(cp)))
            if (normal.isEmpty()) dropped += cp
            val asciiAlnum = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code
            if (!asciiAlnum && normal.isNotEmpty() && normal.all { it in symbols }) folds[cp] = normal
        }
        assertEquals((JS_WHITESPACE.map { it.code } + '-'.code + (0x2010..0x2015)).toSortedSet(), dropped)
        assertEquals(expectedFolds.toSortedMap(), folds)
        for (c in 'a'..'z') assertEquals(c.uppercase(), RecoveryCode.normalize(c.toString()))
    }

    @Test
    fun upperCasingDoesNotDependOnTheDevicesLanguage() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Turkish upper-cases i to İ (U+0130); the web's toUpperCase, and this, never do.
            assertEquals("I", RecoveryCode.normalize("i"))
            assertEquals(CODE, RecoveryCode.parse(display.lowercase()).canonical)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun namesEachFault_inTheWebsOrder_atThePositionAfterNormalising() {
        assertFault(Fault.EMPTY, null, "")
        assertFault(Fault.EMPTY, null, " -\u2013\u00A0\n\t ")
        for (c in "0O1ILoil") assertFault(Fault.CONFUSABLE, 3, CODE.replaceAt(2, c))
        for (c in "#\u00E9\u212A\uFF21") assertFault(Fault.NOT_IN_ALPHABET, 3, CODE.replaceAt(2, c))
        // The hyphen goes before positions are counted: the 0 is the sixth symbol.
        assertFault(Fault.CONFUSABLE, 6, "K7M2Q-0XR9CT")
        // Characters before length: a long paste is told what it contains, not how long it is.
        val longPaste = CODE + "23456#"
        assertFault(Fault.NOT_IN_ALPHABET, longPaste.length, longPaste)
        assertFault(Fault.TOO_SHORT, null, CODE.dropLast(1))
        assertFault(Fault.TOO_LONG, null, CODE + CODE.last())
        // Length before the check symbol: 31 symbols whose check is also wrong are too long.
        val wrongCheck = CODE.replaceAt(29, otherSymbol(CODE[29]))
        assertNotEquals(CODE, wrongCheck)
        assertFault(Fault.CHECKSUM, null, wrongCheck)
        assertFault(Fault.TOO_LONG, null, wrongCheck + "2")
    }

    @Test
    fun theCheckSymbolCatchesEverySingleSymbolChange_atEveryPosition() {
        var checked = 0
        for (i in CODE.indices) {
            for (s in RecoveryCode.ALPHABET) {
                if (s == CODE[i]) continue
                val changed = CODE.replaceAt(i, s)
                assertNotEquals(CODE, changed)
                assertFault(Fault.CHECKSUM, null, changed)
                checked++
            }
        }
        assertEquals(30 * 30, checked)
    }

    @Test
    fun andEverySwapOfTwoDifferentPayloadSymbols() {
        var checked = 0
        for (i in 0 until RecoveryCode.PAYLOAD_SYMBOLS) {
            for (j in i + 1 until RecoveryCode.PAYLOAD_SYMBOLS) {
                if (CODE[i] == CODE[j]) continue // swapping equal symbols changes nothing
                val swapped = CODE.replaceAt(i, CODE[j]).replaceAt(j, CODE[i])
                assertNotEquals(CODE, swapped)
                assertFault(Fault.CHECKSUM, null, swapped)
                checked++
            }
        }
        assertEquals("every pair was checked, not a sample", pairsOfDifferentPayloadSymbols(), checked)
    }

    @Test
    fun neitherTheCodeNorARefusalShowsWhatWasTyped() {
        val code = RecoveryCode.parse(display)
        assertFalse(code.toString().contains(CODE))
        for (group in display.split('-')) assertFalse(code.toString().contains(group))
        val typo = CODE.replaceAt(5, otherSymbol(CODE[5]))
        try {
            RecoveryCode.parse(typo)
            fail("expected a refusal")
        } catch (e: RecoveryCodeException) {
            assertEquals("recovery code refused: CHECKSUM", e.message)
            assertNull(e.cause)
        }
    }

    private fun assertFault(fault: Fault, at: Int?, typed: String) {
        try {
            RecoveryCode.parse(typed)
            fail("expected $fault")
        } catch (e: RecoveryCodeException) {
            assertEquals(fault, e.fault)
            assertEquals(at, e.at)
        }
    }

    private fun String.replaceAt(i: Int, c: Char): String = substring(0, i) + c + substring(i + 1)

    private fun otherSymbol(c: Char): Char = if (c == 'X') 'Y' else 'X'

    private fun pairsOfDifferentPayloadSymbols(): Int {
        val payload = CODE.take(RecoveryCode.PAYLOAD_SYMBOLS)
        return (0 until payload.length).sumOf { i -> (i + 1 until payload.length).count { j -> payload[i] != payload[j] } }
    }

    private companion object {
        /** JavaScript's `\s`, as Node 22 matches it over every code point. */
        val JS_WHITESPACE: List<Char> = listOf(
            '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', '\u00A0', '\u1680',
            '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
            '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
        )
    }
}
