package com.daylie.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvFieldTest {

    @Test
    fun plainValueIsUnquoted() {
        assertEquals("Good", csvField("Good"))
        assertEquals("Work; Friends", csvField("Work; Friends"))
    }

    @Test
    fun commaForcesQuoting() {
        assertEquals("\"a, b\"", csvField("a, b"))
    }

    @Test
    fun quotesAreDoubledAndWrapped() {
        assertEquals("\"she said \"\"hi\"\"\"", csvField("she said \"hi\""))
    }

    @Test
    fun newlineForcesQuoting() {
        assertEquals("\"line1\nline2\"", csvField("line1\nline2"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", csvField(""))
    }
}
