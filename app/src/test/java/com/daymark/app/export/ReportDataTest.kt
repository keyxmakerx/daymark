package com.daymark.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReportDataTest {

    private val entries = listOf(
        ReportEntry(2_000L, 4, "good day", listOf("Work", "Gym")),
        ReportEntry(1_000L, 2, "rough", listOf("Sleep")),
    )
    private val journal = listOf(ReportJournalEntry(1_500L, "Title", "Body text"))

    @Test
    fun canonicalPayloadIsOrderStable() {
        val a = ReportDataBuilder.canonicalPayload(0, 5_000, entries, journal)
        val b = ReportDataBuilder.canonicalPayload(0, 5_000, entries.reversed(), journal)
        assertEquals("entry order must not affect the canonical payload", a, b)
    }

    @Test
    fun hashIsDeterministic() {
        val h1 = ReportDataBuilder.sha256Hex(ReportDataBuilder.canonicalPayload(0, 5_000, entries, journal))
        val h2 = ReportDataBuilder.sha256Hex(ReportDataBuilder.canonicalPayload(0, 5_000, entries, journal))
        assertEquals(h1, h2)
        assertEquals("SHA-256 hex is 64 chars", 64, h1.length)
    }

    @Test
    fun differentDataDiffersInHash() {
        val base = ReportDataBuilder.sha256Hex(ReportDataBuilder.canonicalPayload(0, 5_000, entries, journal))
        val changed = entries.toMutableList().also { it[0] = it[0].copy(moodLevel = 5) }
        val other = ReportDataBuilder.sha256Hex(ReportDataBuilder.canonicalPayload(0, 5_000, changed, journal))
        assertNotEquals(base, other)
    }

    @Test
    fun rangeIsPartOfHash() {
        val a = ReportDataBuilder.sha256Hex(ReportDataBuilder.canonicalPayload(0, 5_000, entries, journal))
        val b = ReportDataBuilder.sha256Hex(ReportDataBuilder.canonicalPayload(0, 9_999, entries, journal))
        assertNotEquals(a, b)
    }
}
