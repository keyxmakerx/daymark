package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout, and the five things it is not allowed to get wrong.
 *
 * 1. **It is the person's.** The same history draws the same sky, bit for bit, and a history that
 *    differs by one record draws a different one. [fingerprint] is the whole layout as a string, so
 *    "the same sky" means every coordinate, not a summary of them.
 * 2. **Appending moves nothing.** Every star lies along one guide, counted in memories
 *    ([SkyForm]), so a newer record lands further along and nothing already drawn moves. A record
 *    with an older date shifts the later stars one place along, and nothing before it. Each of
 *    these is checked in a sky of every form: river, galaxies and open sky.
 * 3. **A quiet stretch takes no room.** The guide is measured in memories, never days, so a long
 *    gap takes exactly the room a short one does: none. The absence assertions here are paired with
 *    a demonstration that their detector can see a mark when there is one to see.
 * 4. **It looks like a sky.** No form is every sky's, every form is far from an even scatter, and a
 *    star's own scatter is
 *    drawn from independent hashes of its identity, never a hash salted after the mixer — the
 *    version of this placement before 2026-09-16 did that and put every star on `y = 1 - x`.
 * 5. **It is bounded.** Ten years of daily use is a fixed, small amount of work and a fixed, small
 *    amount of memory, and no day can produce an unbounded pile of overlapping marks.
 */
class SkyTest {

    // 2020-01-01. Fixed, because a test that starts from "today" is a test that changes.
    private val start = SkyCalendar.epochDayOf(2020, 1, 1)

    /** One sky's seed. Fixed, for the same reason the start date is. */
    private val seed = 0x5B1E5EEDL

    private fun layout(records: List<SkyRecord>): SkyLayout = Sky.layout(records, seed)

    /** The first seed of each form, so a property is checked in a river, galaxies and an open sky. */
    private val everyForm: List<Long> by lazy {
        val found = LinkedHashMap<SkyForm.Form, Long>()
        var s = 1L
        while (found.size < SkyForm.Form.entries.size && s < 1_000L) {
            found.getOrPut(SkyForm.formOf(s)) { s }
            s++
        }
        assertEquals("a form no seed reaches", SkyForm.Form.entries.size, found.size)
        found.values.toList()
    }

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
        for (i in 0 until layout.starCount) {
            sb.append(layout.x[i].toRawBits()).append(':')
                .append(layout.y[i].toRawBits()).append(':')
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

    /**
     * Pearson's r between two equal-length series.
     *
     * Written out rather than approximated by eye, because "the two axes look independent" is
     * exactly the judgement that missed a correlation of -1.0 for as long as the old placement
     * existed. Every use of it below is paired with [`the correlation detector can see a
     * correlation`], which shows it returns -1 for a series that really is the mirror of another.
     */
    private fun pearson(xs: FloatArray, ys: FloatArray): Double {
        require(xs.size == ys.size && xs.isNotEmpty())
        var sumX = 0.0
        var sumY = 0.0
        for (i in xs.indices) {
            sumX += xs[i]
            sumY += ys[i]
        }
        val meanX = sumX / xs.size
        val meanY = sumY / ys.size
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX == 0.0 || varianceY == 0.0) return 0.0
        return covariance / Math.sqrt(varianceX * varianceY)
    }

    /**
     * How clumped a set of points is: the index of dispersion of the counts in an `n × n` grid.
     *
     * Uniform scatter gives about 1 — the counts are Poisson, whose variance equals its mean.
     * Clustering pushes it above 1, because the same points arrive in fewer, fuller cells.
     */
    private fun clumpIndex(xs: FloatArray, ys: FloatArray, cells: Int = 10): Double {
        val counts = IntArray(cells * cells)
        for (i in xs.indices) {
            val cx = (xs[i] * cells).toInt().coerceIn(0, cells - 1)
            val cy = (ys[i] * cells).toInt().coerceIn(0, cells - 1)
            counts[cy * cells + cx]++
        }
        val mean = xs.size.toDouble() / counts.size
        var variance = 0.0
        for (c in counts) variance += (c - mean) * (c - mean)
        variance /= counts.size
        return variance / mean
    }

    // -------------------------------------------------------------------------------------------
    // 1. It is the person's.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the same history lays out identically`() {
        val history = dailyCheckIns(start, 400)
        assertEquals(fingerprint(layout(history)), fingerprint(layout(history)))
        // And across separately constructed input, so nothing is being carried by object identity.
        assertEquals(
            fingerprint(layout(dailyCheckIns(start, 400))),
            fingerprint(layout(dailyCheckIns(start, 400))),
        )
    }

    @Test
    fun `one more record is a different sky`() {
        // This is also what entitles the test above to mean anything: it shows the fingerprint is
        // capable of telling two layouts apart.
        val history = dailyCheckIns(start, 400)
        val plusOne = history + SkyRecord(SkyKind.JOURNAL, id = 9_001L, epochDay = start + 40)
        assertNotEquals(fingerprint(layout(history)), fingerprint(layout(plusOne)))
    }

    @Test
    fun `one different record id is a different sky`() {
        // The weaker difference: same count, same days, same kinds — only the identities differ.
        // If placement were driven by position-in-list rather than by identity, these would match.
        val a = dailyCheckIns(start, 60, firstId = 1L)
        val b = dailyCheckIns(start, 60, firstId = 5_000L)
        assertNotEquals(fingerprint(layout(a)), fingerprint(layout(b)))

        // Different within the stretch, not in the last bit: the river is the seed's, so the same
        // seed keeps the course, and each star's own identity moves it within its stretch.
        val la = layout(a)
        val lb = layout(b)
        assertEquals(la.starCount, lb.starCount)
        var moved = 0
        for (i in 0 until la.starCount) {
            if (Math.abs(la.x[i] - lb.x[i]) > 0.005f || Math.abs(la.y[i] - lb.y[i]) > 0.005f) moved++
        }
        assertTrue(
            "only $moved of ${la.starCount} stars differ between two histories",
            moved > la.starCount * 3 / 4,
        )
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
        assertEquals(fingerprint(layout(history)), fingerprint(layout(shuffled)))
    }

    @Test
    fun `nothing in the layout reads a clock`() {
        // A layout computed now and a layout computed after a measurable delay must agree. This
        // would catch a `System.currentTimeMillis()` used as a jitter source or a "days ago" term
        // in a coordinate — either of which would make the sky different every morning.
        val history = dailyCheckIns(start, 120)
        val first = fingerprint(layout(history))
        val until = System.currentTimeMillis() + 15
        var spin = 0L
        while (System.currentTimeMillis() < until) spin++
        assertTrue(spin >= 0)
        assertEquals(first, fingerprint(layout(history)))
    }

    @Test
    fun `the seed decides how the sky clumps and nothing else`() {
        val history = dailyCheckIns(start, 300)
        val here = Sky.layout(history, seed)
        val elsewhere = Sky.layout(history, seed + 1)

        // Same stars, same records, same dates, same moods — a different sky.
        assertEquals(here.starCount, elsewhere.starCount)
        assertTrue(here.epochDay.contentEquals(elsewhere.epochDay))
        assertTrue(here.recordIds.contentEquals(elsewhere.recordIds))
        assertNotEquals(fingerprint(here), fingerprint(elsewhere))

        // And the same seed is the same sky, or the line above is measuring noise.
        assertEquals(fingerprint(here), fingerprint(Sky.layout(history, seed)))
    }

    // -------------------------------------------------------------------------------------------
    // 2. Nothing moves.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `appending a thousand newer records moves nothing already drawn`() {
        val original = dailyCheckIns(start, 30)
        val later = (0 until 1_000).map {
            SkyRecord(SkyKind.CHECK_IN, id = 10_000L + it, epochDay = start + 60 + it / 2, moodLevel = 3)
        }
        for (seed in everyForm) {
            val before = Sky.layout(original, seed)
            val after = Sky.layout(original + later, seed)
            assertEquals(original.size + later.size, after.starCount)
            for (record in original) {
                val was = indexOfRecord(before, record.kind, record.id)
                val now = indexOfRecord(after, record.kind, record.id)
                assertEquals("${after.form} record ${record.id} x", before.x[was], after.x[now], 0f)
                assertEquals("${after.form} record ${record.id} y", before.y[was], after.y[now], 0f)
            }
        }
    }

    @Test
    fun `an older record shifts later stars along and moves nothing before it`() {
        // Two stretches with a gap between them, and a late arrival dated inside the second: a
        // restore, a sync, or a back-dated entry. It joins that stretch, so that stretch and every
        // star after it may move; the first stretch must not.
        val original = dailyCheckIns(start, 20) + dailyCheckIns(start + 30, 20, firstId = 100L)
        val late = SkyRecord(SkyKind.JOURNAL, id = 9_000L, epochDay = start + 35)
        for (seed in everyForm) {
            val before = Sky.layout(original, seed)
            val after = Sky.layout(original + late, seed)
            var moved = 0
            for (record in original) {
                val was = indexOfRecord(before, record.kind, record.id)
                val now = indexOfRecord(after, record.kind, record.id)
                if (record.epochDay < start + 30) {
                    assertEquals("${after.form} record ${record.id} x", before.x[was], after.x[now], 0f)
                    assertEquals("${after.form} record ${record.id} y", before.y[was], after.y[now], 0f)
                } else if (before.x[was] != after.x[now] || before.y[was] != after.y[now]) {
                    moved++
                }
            }
            // The detector: the later stars really did shift, so the equalities above are not a
            // layout that ignores the late record altogether.
            assertTrue("${after.form}: nothing after the late record moved", moved > 0)
        }
    }

    @Test
    fun `moving every date by the same amount moves nothing`() {
        // Placement reads dates only through which days have something on them, relative to each
        // other. The same history eleven years and three days later is the same sky.
        val records = dailyCheckIns(start, 20) +
            SkyKind.entries.mapIndexed { i, kind -> SkyRecord(kind, id = 300L + i, epochDay = start + 40 + i * 3) }
        val shifted = records.map { it.copy(epochDay = it.epochDay + 4_018) }

        val a = layout(records)
        val b = layout(shifted)
        assertEquals(records.size, a.starCount)
        for (i in 0 until a.starCount) {
            assertEquals("star $i x", a.x[i], b.x[i], 0f)
            assertEquals("star $i y", a.y[i], b.y[i], 0f)
        }
        // The detector: the dates really did change.
        for (i in 0 until a.starCount) assertNotEquals(a.epochDay[i], b.epochDay[i])
    }

    @Test
    fun `deleting a record leaves no trace of it`() {
        val history = dailyCheckIns(start, 90)
        val withoutOne = history.filterNot { it.id == 45L }
        val laid = layout(withoutOne)

        assertEquals("the record is still in the layout", -1, indexOfRecord(laid, SkyKind.CHECK_IN, 45L))
        assertTrue("the id survived in the packed array", !laid.recordIds.contains(45L))
        // No tombstone: the layout of the remaining records is what it would have been if the
        // deleted record had never existed. Not "the same minus a star" — identical.
        assertEquals(fingerprint(layout(withoutOne)), fingerprint(laid))
        assertEquals(history.size - 1, laid.starCount)
    }

    // -------------------------------------------------------------------------------------------
    // 3. The field has no regions.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the absence detector can see a star`() {
        // Run first, so the assertions below are entitled to their silence.
        val present = layout(
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
        val laid = layout(history)

        assertEquals("something was drawn in the gap", 0, starsBetween(laid, gapFrom, gapTo))
        assertEquals("the history around the gap was not drawn", 80, laid.starCount)
    }

    /**
     * The property the sky is measured in memories to buy: a long silence takes no more room than a
     * short one. Two histories, identical but for how long the gap in the middle is, lay out
     * identically, because the gap is never a reach of anything.
     */
    @Test
    fun `a long gap takes no more room than a short one`() {
        fun history(gapDays: Int): List<SkyRecord> =
            dailyCheckIns(start, 30) +
                (0 until 30).map {
                    SkyRecord(SkyKind.CHECK_IN, id = 500L + it, epochDay = start + 30 + gapDays + it, moodLevel = 3)
                }
        for (seed in everyForm) {
            val weeks = Sky.layout(history(21), seed)
            val months = Sky.layout(history(120), seed)
            assertEquals(60, weeks.starCount)
            assertEquals(60, months.starCount)
            for (i in 0 until weeks.starCount) {
                assertEquals("${weeks.form} star $i x", weeks.x[i], months.x[i], 0f)
                assertEquals("${weeks.form} star $i y", weeks.y[i], months.y[i], 0f)
            }
            // The detector: one more memory before the second stretch DOES take room, and the stars
            // after it move. A layout that ignored the guide would not notice either difference.
            val oneMore = Sky.layout(history(21) + SkyRecord(SkyKind.JOURNAL, 9_999L, start + 40), seed)
            val firstAfter = indexOfRecord(oneMore, SkyKind.CHECK_IN, 529L)
            val wasAt = indexOfRecord(weeks, SkyKind.CHECK_IN, 529L)
            assertTrue(oneMore.x[firstAfter] != weeks.x[wasAt] || oneMore.y[firstAfter] != weeks.y[wasAt])
        }
    }

    @Test
    fun `mood never decides whether a star exists, or where it is`() {
        // A history of nothing but the worst mood produces exactly as many stars as a history of
        // nothing but the best. There is no threshold anywhere that a hard day falls below.
        val worst = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it, moodLevel = 1) }
        val best = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it, moodLevel = 5) }
        val none = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) }
        assertEquals(60, layout(worst).starCount)
        assertEquals(60, layout(best).starCount)
        assertEquals(60, layout(none).starCount)
        // And they land in the same places, because position never consults the mood.
        val w = layout(worst)
        val b = layout(best)
        for (i in 0 until w.starCount) {
            assertEquals(w.x[i], b.x[i], 0f)
            assertEquals(w.y[i], b.y[i], 0f)
        }
    }

    // -------------------------------------------------------------------------------------------
    // 4. It looks like a sky.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every star is inside the sky`() {
        val history = dailyCheckIns(start, 800) +
            (0 until 300).map { SkyRecord(SkyKind.PROJECT_STEP, 4_000L + it, start + it * 2) }
        for (seed in listOf(seed, 1L, 2L, 3L, 4L, 5L, 6L, 7L) + everyForm) {
            val laid = Sky.layout(history, seed)
            for (i in 0 until laid.starCount) {
                assertTrue("x = ${laid.x[i]}", laid.x[i] >= 0f && laid.x[i] <= 1f)
                assertTrue("y = ${laid.y[i]}", laid.y[i] >= 0f && laid.y[i] <= laid.height)
            }
        }
    }

    @Test
    fun `the correlation detector can see a correlation`() {
        // Run first, so the decorrelation assertion below is entitled to its silence. This is the
        // exact shape of the pre-2026-09-16 bug: a salt applied after the mixer gave `y = 1 - x`.
        val xs = FloatArray(500) { SkyRandom.unit(SkyRandom.mix(1L, it.toLong())) }
        val mirrored = FloatArray(500) { 1f - xs[it] }
        assertEquals(-1.0, pearson(xs, mirrored), 1e-6)
        assertEquals(1.0, pearson(xs, xs), 1e-6)
    }

    @Test
    fun `a star's own scatter is drawn from independent hashes`() {
        val ids = LongArray(6_000) { Sky.identityOf(SkyKind.entries[it % SkyKind.entries.size], it.toLong()) }
        val a = FloatArray(ids.size) { SkyForm.unit(ids[it], 6) }
        val b = FloatArray(ids.size) { SkyForm.unit(ids[it], 7) }
        val r = pearson(a, b)
        // 6,000 samples put the standard error near 0.013, so 0.05 is roughly four of them.
        assertTrue("two scatter draws are correlated at r = $r", Math.abs(r) < 0.05)
    }

    @Test
    fun `the sky clumps rather than spreading evenly`() {
        // On-and-off days, so every shape is in it: runs, streams, singles and a band.
        val records = (0 until 6_000).filter { (it / 7) % 3 != 2 || it % 4 == 0 }.map {
            SkyRecord(SkyKind.CHECK_IN, id = 1L + it, epochDay = start + it / 3, moodLevel = 3)
        }

        // The detector: the same measurement on an even scatter of the same size.
        val flatX = FloatArray(records.size) { SkyRandom.unit(SkyRandom.mix(0x11L, it.toLong())) }
        val flatY = FloatArray(records.size) { SkyRandom.unit(SkyRandom.mix(0x22L, it.toLong())) }
        val flat = clumpIndex(flatX, flatY)
        assertTrue("an even scatter measured as clumped at $flat", flat < 1.6)

        for (seed in everyForm) {
            val laid = Sky.layout(records, seed)
            val clumped = clumpIndex(laid.x, FloatArray(laid.starCount) { laid.y[it] / laid.height })
            println("  clump index: ${laid.form} ${"%.2f".format(clumped)}, even ${"%.2f".format(flat)}")
            assertTrue("${laid.form} did not clump: $clumped", clumped > flat * 1.5)
        }
    }

    @Test
    fun `a river is one form among three, and skies come in different sizes`() {
        val counts = IntArray(SkyForm.Form.entries.size)
        var smallest = Float.MAX_VALUE
        var largest = 0f
        for (s in 0L until 3_000L) {
            counts[SkyForm.formOf(s).ordinal]++
            val size = SkyForm.sizeOf(s)
            smallest = minOf(smallest, size)
            largest = maxOf(largest, size)
        }
        for (form in SkyForm.Form.entries) {
            val share = counts[form.ordinal] / 3_000.0
            println("  ${form.name}: ${"%.1f".format(share * 100)}% of skies")
            assertTrue("$form is in ${"%.3f".format(share)} of skies", share in 0.2..0.5)
        }
        assertTrue("the smallest sky is $smallest", smallest < 0.75f)
        assertTrue("the largest sky is $largest", largest > 1.3f)
    }

    // -------------------------------------------------------------------------------------------
    // 5. Bounded.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a day cannot draw more than the cap, and loses nothing doing it`() {
        val day = start + 5
        // 120 records on one date, across three kinds. Bulk import, or a long day in the app.
        val flood = (0 until 40).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, day, moodLevel = 1 + it % 5) } +
            (0 until 40).map { SkyRecord(SkyKind.JOURNAL, 200L + it, day) } +
            (0 until 40).map { SkyRecord(SkyKind.PROJECT_STEP, 400L + it, day) }
        val laid = layout(flood)

        assertTrue("drew ${laid.starCount} stars on one day", laid.starCount <= Sky.MAX_STARS_PER_DAY)
        assertTrue("folded the day away entirely", laid.starCount >= 3)

        // Nothing is dropped and nothing is duplicated: every record id appears exactly once.
        val seen = ArrayList<Long>()
        for (i in 0 until laid.starCount) for (recordId in laid.recordIdsAt(i)) seen.add(recordId)
        assertEquals("records lost or doubled in the fold", flood.size, seen.size)
        assertEquals(flood.map { it.id }.toSet(), seen.toSet())

        // Every kind that happened still has at least one star. A fold that silenced a kind would
        // erase an act the person performed.
        val kinds = (0 until laid.starCount).map { laid.kindAt(it) }.toSet()
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
            SkyRecord(SkyKind.PRACTICE, 4L, day),
            SkyRecord(SkyKind.PROJECT_STEP, 5L, day),
            SkyRecord(SkyKind.GOAL_REACHED, 6L, day),
        )
        val laid = layout(ordinary)
        assertEquals(6, laid.starCount)
        for (i in 0 until laid.starCount) assertEquals(1, laid.recordCountAt(i))
    }

    @Test
    fun `ten years of daily use is a small, quick layout`() {
        val days = 3653
        val history = dailyCheckIns(start, days) +
            (0 until days step 3).map { SkyRecord(SkyKind.JOURNAL, 100_000L + it, start + it) } +
            (0 until days step 7).map { SkyRecord(SkyKind.PROJECT_STEP, 200_000L + it, start + it) }

        val began = System.nanoTime()
        val laid = layout(history)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000.0
        println(
            "  laid out ${history.size} records into ${laid.starCount} stars " +
                "in ${"%.1f".format(elapsedMs)} ms",
        )

        assertEquals("nothing was dropped", history.size, laid.starCount)
        assertTrue("layout took ${elapsedMs}ms", elapsedMs < 2000.0)
        // Four packed arrays and two index arrays, one entry per star: memory is a function of how
        // many stars there are and of nothing else, with no per-month or per-day structure left to
        // grow with the span. Ten years of daily use is one such array of about 5,000.
        assertEquals(laid.starCount + 1, laid.idStart.size)
        assertEquals(history.size, laid.recordIds.size)
    }

    // -------------------------------------------------------------------------------------------
    // Degrading to nothing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a brand-new install gets a sky with nothing of theirs in it`() {
        val laid = layout(emptyList())
        assertEquals(SkyLayout.Emptiness.NO_RECORDS, laid.emptiness)
        assertEquals(0, laid.starCount)
        // No throw, no special case: every accessor the renderer uses works on it unchanged.
        assertTrue(Sky.list(laid).isEmpty())
        assertEquals(fingerprint(SkyLayout.EMPTY), fingerprint(laid))
        // The one line under it asks for nothing and promises nothing.
        assertTrue(SkyLayout.EMPTY_LINE.isNotEmpty())
        assertTrue("the empty state congratulates or nags", !SkyLayout.EMPTY_LINE.contains('!'))
    }

    @Test
    fun `a first check-in is a sky with one star in it`() {
        val laid = layout(listOf(SkyRecord(SkyKind.CHECK_IN, 1L, start, moodLevel = 3)))
        assertEquals(SkyLayout.Emptiness.FIRST_LIGHT, laid.emptiness)
        assertEquals(1, laid.starCount)
        assertEquals(SkyKind.CHECK_IN.introduction, laid.kindAt(0).introduction)
    }

    @Test
    fun `two stars is an ordinary sky`() {
        val laid = layout(
            listOf(
                SkyRecord(SkyKind.CHECK_IN, 1L, start, moodLevel = 3),
                SkyRecord(SkyKind.CHECK_IN, 2L, start + 1, moodLevel = 3),
            ),
        )
        assertEquals(SkyLayout.Emptiness.POPULATED, laid.emptiness)
    }

    // -------------------------------------------------------------------------------------------
    // The text equivalent, which is now the only way to reach a particular date.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the list skips empty months without comment`() {
        val laid = layout(
            listOf(
                SkyRecord(SkyKind.JOURNAL, 1L, SkyCalendar.epochDayOf(2022, 3, 4)),
                SkyRecord(SkyKind.JOURNAL, 2L, SkyCalendar.epochDayOf(2022, 3, 19)),
                SkyRecord(SkyKind.CHECK_IN, 3L, SkyCalendar.epochDayOf(2022, 7, 1), moodLevel = 2),
            ),
        )

        val list = Sky.list(laid)
        val headings = list.filterIsInstance<SkyListItem.MonthHeading>()
        assertEquals("an empty month was announced", 2, headings.size)
        assertEquals(listOf(3, 7), headings.map { it.month })
        assertEquals(listOf(2022, 2022), headings.map { it.year })
        assertEquals(
            "the count is not the number of stars under the heading",
            listOf(2, 1),
            headings.map { it.itemCount },
        )

        // The detector: the same function does produce a heading for a month that has stars.
        assertTrue(headings.isNotEmpty())
        assertEquals(laid.starCount, list.filterIsInstance<SkyListItem.Star>().size)
    }

    @Test
    fun `the list groups by month across a year boundary`() {
        // December and January are adjacent months and different years, which is the one place a
        // grouping that compared only the month number would silently merge two headings into one.
        val laid = layout(
            listOf(
                SkyRecord(SkyKind.JOURNAL, 1L, SkyCalendar.epochDayOf(2021, 12, 30)),
                SkyRecord(SkyKind.JOURNAL, 2L, SkyCalendar.epochDayOf(2022, 1, 2)),
                SkyRecord(SkyKind.JOURNAL, 3L, SkyCalendar.epochDayOf(2022, 12, 2)),
            ),
        )
        val headings = Sky.list(laid).filterIsInstance<SkyListItem.MonthHeading>()
        assertEquals(listOf(2021 to 12, 2022 to 1, 2022 to 12), headings.map { it.year to it.month })
        assertEquals(listOf(1, 1, 1), headings.map { it.itemCount })
    }

    @Test
    fun `the list is in time order and addresses the same stars as the sky`() {
        val laid = layout(dailyCheckIns(start, 200))
        val list = Sky.list(laid)
        var previousDay = Long.MIN_VALUE
        var expectedIndex = 0
        for (item in list) {
            if (item is SkyListItem.Star) {
                assertEquals("the list renumbered the stars", expectedIndex, item.index)
                expectedIndex++
                val day = laid.epochDay[item.index]
                assertTrue("the list is out of order", day >= previousDay)
                previousDay = day
            }
        }
        assertEquals(laid.starCount, expectedIndex)
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

    /**
     * The kind that means a thought record must not read as a jog.
     *
     * It was `EXERCISE("exercise", "An exercise you finished.")`, and the maintainer read his own
     * design as physical exercise — which is the strongest evidence there is that a user would.
     * Physical exercise is a goal and has stars already; nothing is lost by taking the word back.
     */
    @Test
    fun `the practice kind cannot be read as physical exercise`() {
        // The detector fires on the copy this replaced, in both the key and the line. Without this
        // the sweep below would pass against a vocabulary that never changed.
        assertTrue("detector is broken", readsAsWorkout("exercise"))
        assertTrue("detector is broken", readsAsWorkout("An exercise you finished."))
        assertTrue("detector is broken", readsAsWorkout("A workout you did."))
        assertFalse("detector is too greedy", readsAsWorkout("A practice you used."))

        assertEquals("practice", SkyKind.PRACTICE.key)
        for (kind in SkyKind.entries) {
            assertFalse("${kind.name}'s key reads as a workout: ${kind.key}", readsAsWorkout(kind.key))
            assertFalse(
                "${kind.name} says \"${kind.introduction}\", which reads as physical exercise",
                readsAsWorkout(kind.introduction),
            )
        }
        // And the line has to say what the kind is, not merely avoid saying what it is not.
        assertTrue(SkyKind.PRACTICE.introduction.lowercase().contains("practice"))
    }

    private fun readsAsWorkout(text: String): Boolean =
        listOf("exercise", "workout", "gym", "cardio", "training").any { text.contains(it, ignoreCase = true) }

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
        val laid = layout(SkyKind.entries.mapIndexed { i, kind -> SkyRecord(kind, 1L + i, day) })
        assertEquals(SkyKind.entries.size, laid.starCount)
        for (i in 0 until laid.starCount) {
            for (j in (i + 1) until laid.starCount) {
                assertTrue(
                    "${laid.kindAt(i)} and ${laid.kindAt(j)} coincide",
                    Math.abs(laid.x[i] - laid.x[j]) > 1e-6f || Math.abs(laid.y[i] - laid.y[j]) > 1e-6f,
                )
            }
        }
    }
}
