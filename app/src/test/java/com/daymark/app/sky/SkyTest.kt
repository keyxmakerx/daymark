package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout, and the four things it is not allowed to get wrong.
 *
 * 1. **It is the person's.** The same history draws the same sky, bit for bit, and a history that
 *    differs by one record draws a different one. [fingerprint] is the whole layout as a string, so
 *    "the same sky" means every coordinate, not a summary of them.
 * 2. **Nothing moves.** A star's position is fixed by its own identity, so inserting a thousand
 *    records elsewhere leaves it exactly where it was.
 * 3. **Absence has no glyph.** A stretch with nothing logged produces no marks at all — not faint
 *    ones, not placeholders, not an empty cell. The absence assertions here are paired with a
 *    demonstration that their detector can see a star when there is one to see, because an
 *    assertion that nothing was found is worthless from a detector that never finds anything.
 * 4. **It is bounded.** Ten years of daily use is a fixed, small amount of work and a fixed, small
 *    amount of memory, and no day can produce an unbounded pile of overlapping marks.
 */
class SkyTest {

    // 2020-01-01. Fixed, because a test that starts from "today" is a test that changes.
    private val start = SkyCalendar.epochDayOf(2020, 1, 1)

    // -------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------

    /**
     * The entire layout as one string, at full precision — `toRawBits` and not `toString`, so two
     * floats that print the same but differ in the last bit are not silently equal. This is what
     * "byte-identical" is checked against.
     */
    private fun fingerprint(layout: SkyLayout): String {
        val sb = StringBuilder()
        sb.append(layout.firstEpochMonth).append('|').append(layout.rowCount).append('|')
        sb.append(layout.rowStart.joinToString(",")).append('|')
        for (i in 0 until layout.starCount) {
            sb.append(layout.x[i].toRawBits()).append(':')
                .append(layout.y[i].toRawBits()).append(':')
                .append(layout.row[i]).append(':')
                .append(layout.kindOrdinal[i]).append(':')
                .append(layout.moodLevel[i]).append(':')
                .append(layout.epochDay[i]).append(':')
                .append(layout.recordIdsAt(i).joinToString("."))
                .append(';')
        }
        return sb.toString()
    }

    /** A history: one check-in a day for [days] days from [from], with a repeating mood pattern. */
    private fun dailyCheckIns(from: Long, days: Int, firstId: Long = 1L): List<SkyRecord> =
        (0 until days).map {
            SkyRecord(
                kind = SkyKind.CHECK_IN,
                id = firstId + it,
                epochDay = from + it,
                moodLevel = 1 + (it % 5),
            )
        }

    /** Stars whose day falls in `[from, to]`. The detector the absence assertions use. */
    private fun starsBetween(layout: SkyLayout, from: Long, to: Long): Int {
        var n = 0
        for (i in 0 until layout.starCount) if (layout.epochDay[i] in from..to) n++
        return n
    }

    private fun indexOfRecord(layout: SkyLayout, kind: SkyKind, id: Long): Int {
        for (i in 0 until layout.starCount) {
            if (layout.kindAt(i) == kind && layout.recordIdsAt(i).contains(id)) return i
        }
        return -1
    }

    // -------------------------------------------------------------------------------------------
    // 1. It is the person's.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the same history lays out identically`() {
        val history = dailyCheckIns(start, 400)
        assertEquals(fingerprint(Sky.layout(history)), fingerprint(Sky.layout(history)))
        // And across separately constructed input, so nothing is being carried by object identity.
        assertEquals(
            fingerprint(Sky.layout(dailyCheckIns(start, 400))),
            fingerprint(Sky.layout(dailyCheckIns(start, 400))),
        )
    }

    @Test
    fun `one more record is a different sky`() {
        // This is also what entitles the test above to mean anything: it shows the fingerprint is
        // capable of telling two layouts apart.
        val history = dailyCheckIns(start, 400)
        val plusOne = history + SkyRecord(SkyKind.JOURNAL, id = 9_001L, epochDay = start + 40)
        assertNotEquals(fingerprint(Sky.layout(history)), fingerprint(Sky.layout(plusOne)))
    }

    @Test
    fun `one different record id is a different sky`() {
        // The weaker difference: same count, same days, same kinds — only the identities differ.
        // If placement were driven by position-in-list rather than by identity, these would match.
        val a = dailyCheckIns(start, 60, firstId = 1L)
        val b = dailyCheckIns(start, 60, firstId = 5_000L)
        assertNotEquals(fingerprint(Sky.layout(a)), fingerprint(Sky.layout(b)))

        // "Visibly different", not different in the last bit: most stars must actually have moved.
        val la = Sky.layout(a)
        val lb = Sky.layout(b)
        assertEquals(la.starCount, lb.starCount)
        var moved = 0
        for (i in 0 until la.starCount) {
            if (Math.abs(la.x[i] - lb.x[i]) > 0.005f || Math.abs(la.y[i] - lb.y[i]) > 0.02f) moved++
        }
        assertTrue("only $moved of ${la.starCount} stars differ between two histories", moved > la.starCount * 3 / 4)
    }

    @Test
    fun `the order records arrive in does not matter`() {
        // A Room Flow does not promise an order, and neither do two DAOs merged together.
        val history = dailyCheckIns(start, 200) +
            (0 until 50).map { SkyRecord(SkyKind.JOURNAL, id = 700L + it, epochDay = start + it * 3) }
        val shuffled = ArrayList(history)
        val stream = SkyStream(99L)
        for (i in shuffled.indices.reversed()) {
            val j = (stream.nextUnit() * (i + 1)).toInt().coerceIn(0, i)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }
        assertNotEquals("the shuffle did nothing", history, shuffled.toList())
        assertEquals(fingerprint(Sky.layout(history)), fingerprint(Sky.layout(shuffled)))
    }

    @Test
    fun `nothing in the layout reads a clock`() {
        // A layout computed now and a layout computed after a measurable delay must agree. This
        // would catch a `System.currentTimeMillis()` used as a jitter source or a "days ago" term
        // in a coordinate — either of which would make the sky different every morning.
        val history = dailyCheckIns(start, 120)
        val first = fingerprint(Sky.layout(history))
        val until = System.currentTimeMillis() + 15
        var spin = 0L
        while (System.currentTimeMillis() < until) spin++
        assertTrue(spin >= 0)
        assertEquals(first, fingerprint(Sky.layout(history)))
    }

    // -------------------------------------------------------------------------------------------
    // 2. Nothing moves.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a star does not move when a thousand unrelated records are added`() {
        val original = dailyCheckIns(start, 30)
        val before = Sky.layout(original)
        val watched = indexOfRecord(before, SkyKind.CHECK_IN, 15L)
        assertTrue("the watched record is not in the layout", watched >= 0)

        // A thousand records: half of them *after* the watched star — "adding today's check-in must
        // not reflow 2019" — and half of them *before* it, which is the case a backup restore or a
        // late-arriving sync produces. Both directions matter, and only the second catches a layout
        // that draws its jitter from a stream: a stream is unmoved by anything appended after the
        // star it has already placed, so an insertion-only test would pass on a design where every
        // star's position depends on how many came before it.
        val later = (0 until 500).map {
            SkyRecord(SkyKind.CHECK_IN, id = 10_000L + it, epochDay = start + 60 + it, moodLevel = 3)
        }
        val earlier = (0 until 500).map {
            SkyRecord(SkyKind.JOURNAL, id = 20_000L + it, epochDay = start - 500 + it)
        }
        val after = Sky.layout(earlier + original + later)
        val moved = indexOfRecord(after, SkyKind.CHECK_IN, 15L)
        assertTrue(moved >= 0)

        assertEquals("x moved", before.x[watched], after.x[moved], 0f)
        assertEquals("y moved", before.y[watched], after.y[moved], 0f)
        // Compared as an absolute month, not as a row index: the earlier records legitimately move
        // where the sky *starts*, and the star has to stay in the month it happened in.
        assertEquals(
            "the star changed month",
            before.firstEpochMonth + before.row[watched],
            after.firstEpochMonth + after.row[moved],
        )
    }

    @Test
    fun `adding a record to an empty month does not shift the months after it`() {
        // Rows are calendar months, present whether or not anything is in them. If rows were
        // allocated only to months that had stars, filling a gap would push every later row down —
        // and the vertical axis would be a function of how much someone logged.
        val sparse = listOf(
            SkyRecord(SkyKind.CHECK_IN, id = 1L, epochDay = SkyCalendar.epochDayOf(2021, 1, 5), moodLevel = 2),
            SkyRecord(SkyKind.CHECK_IN, id = 2L, epochDay = SkyCalendar.epochDayOf(2021, 6, 20), moodLevel = 4),
        )
        val before = Sky.layout(sparse)
        assertEquals("January to June is six rows", 6, before.rowCount)

        val filled = sparse + SkyRecord(SkyKind.JOURNAL, id = 3L, epochDay = SkyCalendar.epochDayOf(2021, 3, 9))
        val after = Sky.layout(filled)
        assertEquals(6, after.rowCount)

        val june = indexOfRecord(after, SkyKind.CHECK_IN, 2L)
        assertEquals(before.row[indexOfRecord(before, SkyKind.CHECK_IN, 2L)], after.row[june])
        assertEquals(before.x[indexOfRecord(before, SkyKind.CHECK_IN, 2L)], after.x[june], 0f)
    }

    @Test
    fun `deleting a record leaves no trace of it`() {
        val history = dailyCheckIns(start, 90)
        val withoutOne = history.filterNot { it.id == 45L }
        val layout = Sky.layout(withoutOne)

        assertEquals("the record is still in the layout", -1, indexOfRecord(layout, SkyKind.CHECK_IN, 45L))
        assertTrue("the id survived in the packed array", !layout.recordIds.contains(45L))
        // No tombstone: the layout of the remaining records is what it would have been if the
        // deleted record had never existed. Not "the same minus a star" — identical.
        assertEquals(fingerprint(Sky.layout(withoutOne)), fingerprint(layout))
        assertEquals(history.size - 1, layout.starCount)
    }

    // -------------------------------------------------------------------------------------------
    // 3. Absence has no glyph.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the absence detector can see a star`() {
        // Run first, so the assertions below are entitled to their silence.
        val present = Sky.layout(
            listOf(SkyRecord(SkyKind.CHECK_IN, id = 1L, epochDay = start + 100, moodLevel = 3)),
        )
        assertEquals(1, starsBetween(present, start + 90, start + 110))
        assertEquals(0, starsBetween(present, start + 111, start + 200))
    }

    @Test
    fun `a stretch with nothing logged draws nothing`() {
        // Three weeks off in the middle — someone who stopped and came back. This is the case the
        // whole surface is designed around, and what it must produce is not a faint mark, not a
        // dimmed cell and not a placeholder, but no instruction at all.
        val gapFrom = start + 40
        val gapTo = start + 61
        val history = dailyCheckIns(start, 40) +
            (0 until 40).map {
                SkyRecord(SkyKind.CHECK_IN, id = 500L + it, epochDay = gapTo + 1 + it, moodLevel = 3)
            }
        val layout = Sky.layout(history)

        assertEquals("something was drawn in the gap", 0, starsBetween(layout, gapFrom, gapTo))
        assertEquals("the history around the gap was not drawn", 80, layout.starCount)
        // And the months themselves still exist as rows, so the sky above and below is continuous.
        assertTrue(layout.rowCount >= 3)
    }

    @Test
    fun `a month with no records has a row and no stars`() {
        val layout = Sky.layout(
            listOf(
                SkyRecord(SkyKind.JOURNAL, id = 1L, epochDay = SkyCalendar.epochDayOf(2022, 2, 3)),
                SkyRecord(SkyKind.JOURNAL, id = 2L, epochDay = SkyCalendar.epochDayOf(2022, 5, 3)),
            ),
        )
        assertEquals(4, layout.rowCount)
        for (row in 1..2) {
            assertEquals("row $row emitted a mark", layout.rowStart[row], layout.rowStart[row + 1])
            assertTrue(Sky.rowRange(layout, row, row).isEmpty())
        }
        // The detector again: the rows that do have stars report them.
        assertEquals(1, Sky.rowRange(layout, 0, 0).count())
        assertEquals(1, Sky.rowRange(layout, 3, 3).count())
    }

    @Test
    fun `mood never decides whether a star exists`() {
        // A history of nothing but the worst mood produces exactly as many stars as a history of
        // nothing but the best. There is no threshold anywhere that a hard day falls below.
        val worst = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it, moodLevel = 1) }
        val best = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it, moodLevel = 5) }
        val none = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) }
        assertEquals(60, Sky.layout(worst).starCount)
        assertEquals(60, Sky.layout(best).starCount)
        assertEquals(60, Sky.layout(none).starCount)
        // And they land in the same places, because position never consults the mood.
        val w = Sky.layout(worst)
        val b = Sky.layout(best)
        for (i in 0 until w.starCount) {
            assertEquals(w.x[i], b.x[i], 0f)
            assertEquals(w.y[i], b.y[i], 0f)
        }
    }

    // -------------------------------------------------------------------------------------------
    // The map from date to position.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every star sits inside its own row`() {
        val history = dailyCheckIns(start, 800) +
            (0 until 300).map { SkyRecord(SkyKind.PROJECT_STEP, 4_000L + it, start + it * 2) }
        val layout = Sky.layout(history)
        for (i in 0 until layout.starCount) {
            assertTrue("x = ${layout.x[i]}", layout.x[i] >= 0f && layout.x[i] < 1f)
            assertTrue("y = ${layout.y[i]}", layout.y[i] >= 0f && layout.y[i] < 1f)
            assertTrue("row = ${layout.row[i]}", layout.row[i] in 0 until layout.rowCount)
            val month = layout.firstEpochMonth + layout.row[i]
            assertEquals(
                "star drawn in the wrong month row",
                month,
                SkyCalendar.epochMonth(layout.epochDay[i]),
            )
        }
    }

    @Test
    fun `x is monotonic in the date, so time still runs left to right`() {
        val layout = Sky.layout(dailyCheckIns(start, 800))
        for (i in 1 until layout.starCount) {
            if (layout.row[i] != layout.row[i - 1]) continue
            if (layout.epochDay[i] == layout.epochDay[i - 1]) continue
            assertTrue(
                "day ${layout.epochDay[i]} drew to the left of day ${layout.epochDay[i - 1]}",
                layout.x[i] > layout.x[i - 1],
            )
        }
    }

    @Test
    fun `there is no per-day cell for anything to snap to`() {
        // Several stars on one day must land at distinct positions across the day's width, not
        // stacked on a column. A layout with per-day cells would give every star on the 5th the
        // same x, and the gaps between columns would be countable.
        val day = start + 10
        val sameDay = (0 until 8).map { SkyRecord(SkyKind.JOURNAL, 60L + it, day) }
        val layout = Sky.layout(sameDay)
        val xs = (0 until layout.starCount).map { layout.x[it] }.sorted()
        assertEquals(8, xs.size)
        for (i in 1 until xs.size) {
            assertNotEquals("two stars share a column", xs[i], xs[i - 1])
        }
        val monthLength = SkyCalendar.lengthOfMonth(SkyCalendar.epochMonth(day))
        val spreadInDays = (xs.last() - xs.first()) * monthLength
        assertTrue("the day's stars are stacked, spread was $spreadInDays days", spreadInDays > 0.4f)
    }

    // -------------------------------------------------------------------------------------------
    // 4. Bounded.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a day cannot draw more than the cap, and loses nothing doing it`() {
        val day = start + 5
        // 120 records on one date, across three kinds. Bulk import, or a long day in the app.
        val flood = (0 until 40).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, day, moodLevel = 1 + it % 5) } +
            (0 until 40).map { SkyRecord(SkyKind.JOURNAL, 200L + it, day) } +
            (0 until 40).map { SkyRecord(SkyKind.PROJECT_STEP, 400L + it, day) }
        val layout = Sky.layout(flood)

        assertTrue("drew ${layout.starCount} stars on one day", layout.starCount <= Sky.MAX_STARS_PER_DAY)
        assertTrue("folded the day away entirely", layout.starCount >= 3)

        // Nothing is dropped and nothing is duplicated: every record id appears exactly once.
        val seen = ArrayList<Long>()
        for (i in 0 until layout.starCount) for (id in layout.recordIdsAt(i)) seen.add(id)
        assertEquals("records lost or doubled in the fold", flood.size, seen.size)
        assertEquals(flood.map { it.id }.toSet(), seen.toSet())

        // Every kind that happened still has at least one star. A fold that silenced a kind would
        // erase an act the person performed.
        val kinds = (0 until layout.starCount).map { layout.kindAt(it) }.toSet()
        assertEquals(setOf(SkyKind.CHECK_IN, SkyKind.JOURNAL, SkyKind.PROJECT_STEP), kinds)
    }

    @Test
    fun `an ordinary day is never folded`() {
        // The cap must not touch real use. Six acts in a day across four kinds is a heavy day, and
        // it must still be six separate stars.
        val day = start + 3
        val ordinary = listOf(
            SkyRecord(SkyKind.CHECK_IN, 1L, day, moodLevel = 2),
            SkyRecord(SkyKind.CHECK_IN, 2L, day, moodLevel = 4),
            SkyRecord(SkyKind.JOURNAL, 3L, day),
            SkyRecord(SkyKind.EXERCISE, 4L, day),
            SkyRecord(SkyKind.PROJECT_STEP, 5L, day),
            SkyRecord(SkyKind.GOAL_REACHED, 6L, day),
        )
        val layout = Sky.layout(ordinary)
        assertEquals(6, layout.starCount)
        for (i in 0 until layout.starCount) assertEquals(1, layout.recordCountAt(i))
    }

    @Test
    fun `ten years of daily use is a small, quick layout`() {
        val days = 3653
        val history = dailyCheckIns(start, days) +
            (0 until days step 3).map { SkyRecord(SkyKind.JOURNAL, 100_000L + it, start + it) } +
            (0 until days step 7).map { SkyRecord(SkyKind.PROJECT_STEP, 200_000L + it, start + it) }

        val began = System.nanoTime()
        val layout = Sky.layout(history)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000.0
        println("  laid out ${history.size} records into ${layout.starCount} stars " +
            "across ${layout.rowCount} rows in ${"%.1f".format(elapsedMs)} ms")

        assertEquals("nothing was dropped", history.size, layout.starCount)
        // 2020-01-01 through 2029-12-31 inclusive: ten calendar years, 120 month rows.
        assertEquals("ten years is 120 month rows", 120, layout.rowCount)
        assertTrue("layout took ${elapsedMs}ms", elapsedMs < 2000.0)

        // The structural bound: however much history there is, one row holds at most this many.
        for (r in 0 until layout.rowCount) {
            val inRow = layout.rowStart[r + 1] - layout.rowStart[r]
            assertTrue("row $r holds $inRow stars", inRow <= Sky.MAX_STARS_PER_ROW)
        }
    }

    @Test
    fun `culling a range is a contiguous slice`() {
        val layout = Sky.layout(dailyCheckIns(start, 900))
        for (r in 0 until layout.rowCount) {
            val range = Sky.rowRange(layout, r, r)
            for (i in range) assertEquals("row $r slice contained a star from row ${layout.row[i]}", r, layout.row[i])
        }
        // Every star belongs to exactly one row's slice, so nothing is culled away or drawn twice.
        assertEquals(layout.starCount, Sky.rowRange(layout, 0, layout.rowCount - 1).count())
        // Out-of-range arguments clamp rather than throw: the viewport regularly runs past the ends.
        assertEquals(layout.starCount, Sky.rowRange(layout, -50, layout.rowCount + 50).count())
        assertTrue(Sky.rowRange(SkyLayout.EMPTY, 0, 10).isEmpty())
    }

    // -------------------------------------------------------------------------------------------
    // Degrading to nothing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a brand-new install gets a sky with nothing of theirs in it`() {
        val layout = Sky.layout(emptyList())
        assertEquals(SkyLayout.Emptiness.NO_RECORDS, layout.emptiness)
        assertEquals(0, layout.starCount)
        assertEquals(0, layout.rowCount)
        // No throw, no special case: every accessor the renderer uses works on it unchanged.
        assertTrue(Sky.rowRange(layout, 0, 0).isEmpty())
        assertTrue(Sky.list(layout).isEmpty())
        assertEquals(fingerprint(SkyLayout.EMPTY), fingerprint(layout))
        // The one line under it asks for nothing and promises nothing.
        assertTrue(SkyLayout.EMPTY_LINE.isNotEmpty())
        assertTrue("the empty state congratulates or nags", !SkyLayout.EMPTY_LINE.contains('!'))
    }

    @Test
    fun `a first check-in is a sky with one star in it`() {
        val layout = Sky.layout(listOf(SkyRecord(SkyKind.CHECK_IN, 1L, start, moodLevel = 3)))
        assertEquals(SkyLayout.Emptiness.FIRST_LIGHT, layout.emptiness)
        assertEquals(1, layout.starCount)
        assertEquals(1, layout.rowCount)
        assertEquals(SkyKind.CHECK_IN.introduction, layout.kindAt(0).introduction)
    }

    @Test
    fun `two stars is an ordinary sky`() {
        val layout = Sky.layout(
            listOf(
                SkyRecord(SkyKind.CHECK_IN, 1L, start, moodLevel = 3),
                SkyRecord(SkyKind.CHECK_IN, 2L, start + 1, moodLevel = 3),
            ),
        )
        assertEquals(SkyLayout.Emptiness.POPULATED, layout.emptiness)
    }

    // -------------------------------------------------------------------------------------------
    // The text equivalent.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the list skips empty months without comment`() {
        val layout = Sky.layout(
            listOf(
                SkyRecord(SkyKind.JOURNAL, 1L, SkyCalendar.epochDayOf(2022, 3, 4)),
                SkyRecord(SkyKind.JOURNAL, 2L, SkyCalendar.epochDayOf(2022, 3, 19)),
                SkyRecord(SkyKind.CHECK_IN, 3L, SkyCalendar.epochDayOf(2022, 7, 1), moodLevel = 2),
            ),
        )
        assertEquals("March to July is five rows", 5, layout.rowCount)

        val list = Sky.list(layout)
        val headings = list.filterIsInstance<SkyListItem.MonthHeading>()
        assertEquals("an empty month was announced", 2, headings.size)
        assertEquals(listOf(3, 7), headings.map { it.month })
        assertEquals(listOf(2022, 2022), headings.map { it.year })
        assertEquals("the count is not the number of stars under the heading", listOf(2, 1), headings.map { it.itemCount })

        // The detector: the same function does produce a heading for a month that has stars.
        assertTrue(headings.isNotEmpty())
        assertEquals(layout.starCount, list.filterIsInstance<SkyListItem.Star>().size)
    }

    @Test
    fun `the list is in time order and addresses the same stars as the sky`() {
        val layout = Sky.layout(dailyCheckIns(start, 200))
        val list = Sky.list(layout)
        var previousDay = Long.MIN_VALUE
        var expectedIndex = 0
        for (item in list) {
            if (item is SkyListItem.Star) {
                assertEquals("the list renumbered the stars", expectedIndex, item.index)
                expectedIndex++
                val day = layout.epochDay[item.index]
                assertTrue("the list is out of order", day >= previousDay)
                previousDay = day
            }
        }
        assertEquals(layout.starCount, expectedIndex)
    }

    // -------------------------------------------------------------------------------------------
    // Kinds.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `an unknown kind key does not resolve to a real kind`() {
        // A backup from a later version, or a hand-edited file. It must read back as "nothing this
        // version draws", never as a wrong kind — a life event silently rendered as a check-in
        // would put the app's word on something the person authored.
        assertEquals(null, SkyKind.fromKey("vanished_in_a_later_version"))
        assertEquals(null, SkyKind.fromKey(null))
        for (kind in SkyKind.entries) assertEquals(kind, SkyKind.fromKey(kind.key))
        assertEquals("kind keys are not unique", SkyKind.entries.size, SkyKind.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `no introduction congratulates anyone`() {
        // "Naming, not praise." Congratulation is evaluation, and evaluation is the thing this
        // surface does not do.
        val praise = listOf("!", "great", "nice", "well done", "keep", "amazing", "proud", "streak")
        for (kind in SkyKind.entries) {
            val line = kind.introduction.lowercase()
            for (word in praise) {
                assertTrue("${kind.key} says \"${kind.introduction}\"", !line.contains(word))
            }
            assertTrue("${kind.key} has no introduction", kind.introduction.isNotEmpty())
        }
        // The detector: the check does fire on a line that praises.
        assertTrue(praise.any { "Nice work!".lowercase().contains(it) })
    }

    @Test
    fun `different kinds on the same day do not land on each other`() {
        val day = start + 17
        val layout = Sky.layout(SkyKind.entries.mapIndexed { i, kind -> SkyRecord(kind, 1L + i, day) })
        assertEquals(SkyKind.entries.size, layout.starCount)
        for (i in 0 until layout.starCount) {
            for (j in (i + 1) until layout.starCount) {
                assertTrue(
                    "${layout.kindAt(i)} and ${layout.kindAt(j)} coincide",
                    Math.abs(layout.x[i] - layout.x[j]) > 1e-6f || Math.abs(layout.y[i] - layout.y[j]) > 1e-6f,
                )
            }
        }
    }
}
