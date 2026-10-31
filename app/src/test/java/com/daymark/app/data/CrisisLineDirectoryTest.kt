package com.daymark.app.data

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled list of national crisis lines (#521). Each entry is dialled exactly as written, so a
 * malformed number is a line that rings nowhere at the worst moment; and the list is picked from by
 * hand, so nothing may choose for the person. The no-detection check reads the list and the picker
 * as text and is first shown a planted read of the phone's region (CLAUDE.md §5). Runs through
 * `tools/jvm-source-tests.sh`.
 */
class CrisisLineDirectoryTest {

    private companion object {
        const val DIRECTORY = "app/src/main/java/com/daymark/app/data/CrisisLineDirectory.kt"
        const val PICKER = "app/src/main/java/com/daymark/app/ui/support/CrisisLine.kt"
        const val STORE = "app/src/main/java/com/daymark/app/data/CrisisStore.kt"

        /** What would let the phone, rather than the person, say which country they are in. */
        val DETECTS = Regex(
            """Locale\.getDefault|LocaleList|\.locales\b|TelephonyManager|networkCountryIso|simCountryIso|""" +
                """LocationManager|FusedLocation|ACCESS_(COARSE|FINE)_LOCATION""",
        )
        val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        /** The same reduction CrisisStore.dialable makes, which imports SharedPreferences and so cannot load here. */
        fun dialable(raw: String): String = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    @Test
    fun every_line_is_complete_and_dialable_as_written() {
        val lines = CrisisLineDirectory.lines
        assertTrue(lines.size >= 5)
        for (line in lines) {
            assertTrue(line.country, line.country.isNotBlank() && line.label.isNotBlank() && line.contact.isNotBlank())
            assertEquals("${line.country}'s number is not in dialable form", dialable(line.number), line.number)
            assertTrue("${line.country} has no digit to dial", line.number.any { it.isDigit() })
            assertTrue("${line.country} names no https source", line.source.startsWith("https://"))
            assertTrue("${line.country} has no checked-on date", ISO_DATE.matches(line.checkedOn))
            assertTrue("${line.country} was checked after the list was", line.checkedOn <= CrisisLineDirectory.CHECKED_ON)
        }
        // The control: a number written with spaces is caught.
        val spaced = lines.first().copy(number = "13 11 14")
        assertNotEquals(dialable(spaced.number), spaced.number)
    }

    /** The contact the person reads mentions the number the Call button dials. */
    @Test
    fun the_words_carry_the_number_they_dial() {
        for (line in CrisisLineDirectory.lines) {
            assertTrue("${line.country}: \"${line.contact}\" never says ${line.number}", dialable(line.contact).contains(line.number))
        }
        val wrong = CrisisLineDirectory.lines.first().let { it.copy(number = if (it.number == "999") "998" else "999") }
        assertFalse(dialable(wrong.contact).contains(wrong.number))
    }

    @Test
    fun the_list_is_in_country_order_with_each_country_once() {
        val countries = CrisisLineDirectory.lines.map { it.country }
        assertEquals(countries.sorted(), countries)
        assertEquals(countries.toSet().size, countries.size)
    }

    /** The default the crisis screen starts with is the list's own US entry, word for word. */
    @Test
    fun the_default_line_is_the_lists_us_line() {
        val us = CrisisLineDirectory.lines.single { it.country == "United States" }
        val store = repoFile(STORE).readText()
        fun constant(name: String) = Regex("""const val $name = "([^"]*)"""").find(store)!!.groupValues[1]
        assertEquals(constant("DEFAULT_LABEL"), us.label)
        assertEquals(constant("DEFAULT_CONTACT"), us.contact)
        assertEquals(constant("DEFAULT_NUMBER"), us.number)
        assertTrue(us.takesTexts)
    }

    @Test
    fun nothing_reads_the_phone_to_choose_a_line() {
        for (path in listOf(DIRECTORY, PICKER)) {
            val code = codeOnly(repoFile(path).readText())
            assertFalse("$path reads the phone's region or location", DETECTS.containsMatchIn(code))
        }
        // The control: the detector sees a planted read of the phone's region in a copy of the picker.
        val picker = repoFile(PICKER).readText()
        val planted = replaceOnce(picker, "for (line in CrisisLineDirectory.lines)", "for (line in CrisisLineDirectory.lines.sortedBy { it.country != Locale.getDefault().displayCountry })")
        assertTrue(DETECTS.containsMatchIn(codeOnly(planted)))
    }

    /** Picking fills the fields; only the person's Save writes the line. */
    @Test
    fun picking_a_line_never_saves_it() {
        val code = codeOnly(repoFile(PICKER).readText())
        val fill = code.substringAfter("fun CrisisLineDraft.fillFrom(").substringBefore("\n}")
        assertTrue(fill.contains("label = line.label"))
        fun saves(body: String) = Regex("""\.save\(|\.confirm\(|crisisStore""").containsMatchIn(body)
        assertFalse(saves(fill))
        assertTrue(saves("$fill\n    store.save(label, contact)"))
    }
}
