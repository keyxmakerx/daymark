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
 * 2. **Nothing moves.** A star's position is fixed by its own identity — its kind and its anchor
 *    record id — so inserting a thousand records elsewhere leaves it exactly where it was. The date
 *    is not an input either (`docs/SKY.md` §3.1), so a star does not even know when it is from.
 * 3. **The field has no regions.** There are no month rows and no axis. Nothing about the sky maps
 *    a stretch of time onto a patch of screen, so there is no patch that a hard month could empty.
 *    The absence assertions here are paired with a demonstration that their detector can see a
 *    mark when there is one to see, because an assertion that nothing was found is worthless from
 *    a detector that never finds anything.
 * 4. **It looks like a sky.** The scatter is warped into clumps by [SkyWarp], and the two axes are
 *    independent draws — the version of this file before 2026-09-16 salted the hash *after* the
 *    mixer, and every star in the app sat on the line `y = 1 - x` at a correlation of -1.0.
 * 5. **It is bounded.** Ten years of daily use is a fixed, small amount of work and a fixed, small
 *    amount of memory, and no day can produce an unbounded pile of overlapping marks.
 */
class SkyTest {

    // 2020-01-01. Fixed, because a test that starts from "today" is a test that changes.
    private val start = SkyCalendar.epochDayOf(2020, 1, 1)

    /** One sky's seed. Fixed, for the same reason the start date is. */
    private val seed = 0x5B1E5EEDL

    private fun layout(records: List<SkyRecord>): SkyLayout = Sky.layout(records, seed)

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

    /**
     * How many of [bands] horizontal strips of the field hold no star at all.
     *
     * This is the shape the month rows produced and the shape the scatter exists to make
     * impossible: a band right across the surface with nothing in it, which reads as a stretch of
     * someone's life that is missing. Strips and not cells, because a cell that happens to be empty
     * is sky and a band that is empty is a hole.
     */
    private fun emptyBands(values: FloatArray, bands: Int = 12): Int {
        val counts = IntArray(bands)
        for (v in values) counts[(v * bands).toInt().coerceIn(0, bands - 1)]++
        return counts.count { it == 0 }
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

        // "Visibly different", not different in the last bit: most stars must actually have moved.
        val la = layout(a)
        val lb = layout(b)
        assertEquals(la.starCount, lb.starCount)
        var moved = 0
        for (i in 0 until la.starCount) {
            if (Math.abs(la.x[i] - lb.x[i]) > 0.02f || Math.abs(la.y[i] - lb.y[i]) > 0.02f) moved++
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
    fun `a star does not move when a thousand unrelated records are added`() {
        val original = dailyCheckIns(start, 30)
        val before = layout(original)
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
        val after = layout(earlier + original + later)
        val moved = indexOfRecord(after, SkyKind.CHECK_IN, 15L)
        assertTrue(moved >= 0)

        assertEquals("x moved", before.x[watched], after.x[moved], 0f)
        assertEquals("y moved", before.y[watched], after.y[moved], 0f)
        // Not just the watched one: every star of the original history is exactly where it was.
        for (record in original) {
            val was = indexOfRecord(before, record.kind, record.id)
            val now = indexOfRecord(after, record.kind, record.id)
            assertEquals("record ${record.id} x", before.x[was], after.x[now], 0f)
            assertEquals("record ${record.id} y", before.y[was], after.y[now], 0f)
        }
    }

    @Test
    fun `a star is in the same place alone as it is in a crowd`() {
        // The index and the count are not inputs. A star laid out by itself and the same star laid
        // out among five thousand others is the same star in the same place — which is what makes
        // "adding a record reflows nothing" true at the root rather than in the common case.
        val lonely = SkyRecord(SkyKind.PRACTICE, id = 77L, epochDay = start + 900, moodLevel = 4)
        val alone = layout(listOf(lonely))

        val crowd = dailyCheckIns(start, 2_000) +
            (0 until 3_000).map { SkyRecord(SkyKind.JOURNAL, 50_000L + it, start + it / 2) } +
            lonely
        val among = layout(crowd)

        val here = indexOfRecord(among, SkyKind.PRACTICE, 77L)
        assertTrue(here >= 0)
        assertEquals(5_001, among.starCount)
        assertEquals("x", alone.x[0], among.x[here], 0f)
        assertEquals("y", alone.y[0], among.y[here], 0f)
        // The detector: the crowd really does contain other stars in other places, so the equality
        // above is not two readings of an array with one entry in it.
        assertNotEquals(alone.x[0], among.x[0])
    }

    @Test
    fun `the date decides nothing about where a star is`() {
        // Position is the record's kind and id, and the date is not in it (`docs/SKY.md` §3.1) —
        // which is the whole reason a hard month cannot be drawn as an empty band. The same six
        // records, moved eleven years and three days, land on exactly the same coordinates.
        val records = SkyKind.entries.mapIndexed { i, kind ->
            SkyRecord(kind, id = 31L + i, epochDay = start + i, moodLevel = SkyGlyph.MOOD_NONE)
        }
        val shifted = records.map { it.copy(epochDay = it.epochDay + 4_018) }

        val a = layout(records)
        val b = layout(shifted)
        assertEquals(records.size, a.starCount)
        assertEquals(records.size, b.starCount)
        for (i in 0 until a.starCount) {
            assertEquals("star $i x", a.x[i], b.x[i], 0f)
            assertEquals("star $i y", a.y[i], b.y[i], 0f)
        }
        // The detector: the dates really did change, so the equality above is a fact about the
        // placement and not about two identical inputs.
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
     * The property the month rows were deleted to buy, measured.
     *
     * A year of daily use with one whole month missing must leave no hole. Under the old layout the
     * missing month was a row, and a row with nothing in it was a visibly empty band across the
     * screen; under scatter there is no band to be empty, because the month never occupied a region
     * in the first place.
     */
    @Test
    fun `a whole month missing leaves no hole in the field`() {
        val year = (0 until 365).map {
            SkyRecord(SkyKind.CHECK_IN, id = 1L + it, epochDay = start + it, moodLevel = 1 + it % 5)
        }
        val june = SkyCalendar.epochMonth(SkyCalendar.epochDayOf(2020, 6, 1))
        val withoutJune = year.filterNot { SkyCalendar.epochMonth(it.epochDay) == june }
        assertEquals("June was not actually removed", 30, year.size - withoutJune.size)

        val full = layout(year)
        val holed = layout(withoutJune)

        // Every surviving star is exactly where it was, so nothing closed over the gap either.
        for (record in withoutJune) {
            val was = indexOfRecord(full, record.kind, record.id)
            val now = indexOfRecord(holed, record.kind, record.id)
            assertEquals(full.x[was], holed.x[now], 0f)
            assertEquals(full.y[was], holed.y[now], 0f)
        }

        // And no band of the field emptied, in either direction. A year with a month cut out of it
        // still covers the whole sky, because the month was never a place.
        assertEquals("a horizontal band of the field emptied", 0, emptyBands(holed.y))
        assertEquals("a vertical band of the field emptied", 0, emptyBands(holed.x))
        assertEquals(0, emptyBands(full.y))
        assertEquals(0, emptyBands(full.x))

        // The detector, and the thing this replaced. A sky with a row per month — y decided by the
        // date — loses a whole band the moment a month goes, and the same measurement says so.
        fun bandedY(records: List<SkyRecord>): FloatArray {
            val months = records.map { SkyCalendar.epochMonth(it.epochDay) }
            val firstMonth = months.min()
            val rows = months.max() - firstMonth + 1
            return FloatArray(records.size) { i -> (months[i] - firstMonth + 0.5f) / rows }
        }
        assertEquals("the detector fires on a full year of rows", 0, emptyBands(bandedY(year)))
        assertEquals(
            "the detector cannot see an empty month row",
            1,
            emptyBands(bandedY(withoutJune)),
        )
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
    fun `every star is inside the field`() {
        val history = dailyCheckIns(start, 800) +
            (0 until 300).map { SkyRecord(SkyKind.PROJECT_STEP, 4_000L + it, start + it * 2) }
        val laid = layout(history)
        for (i in 0 until laid.starCount) {
            assertTrue("x = ${laid.x[i]}", laid.x[i] >= 0f && laid.x[i] < 1f)
            assertTrue("y = ${laid.y[i]}", laid.y[i] >= 0f && laid.y[i] < 1f)
        }
    }

    @Test
    fun `the correlation detector can see a correlation`() {
        // Run first, so the decorrelation assertion below is entitled to its silence. This is the
        // exact shape the old layout had — `between(hash xor Y_SALT, ...)` with a salt whose top 24
        // bits are all ones gave `y = 1 - x`, measured at -1.0 and drawn as a diagonal line of
        // stars for as long as it existed.
        val xs = FloatArray(500) { SkyRandom.unit(SkyRandom.mix(1L, it.toLong())) }
        val mirrored = FloatArray(500) { 1f - xs[it] }
        assertEquals(-1.0, pearson(xs, mirrored), 1e-6)
        assertEquals(1.0, pearson(xs, xs), 1e-6)
    }

    @Test
    fun `the two axes are drawn independently`() {
        // Every kind, so a salt collision between an axis and a kind would show up here too.
        val records = ArrayList<SkyRecord>()
        var id = 1L
        for (kind in SkyKind.entries) {
            for (n in 0 until 1_000) {
                records.add(SkyRecord(kind, id++, start + n, moodLevel = 1 + n % 5))
            }
        }

        // Measured on the identity hash, which is where the rule lives and where it was broken.
        val hx = FloatArray(records.size) { Sky.unwarpedX(records[it].kind, records[it].id) }
        val hy = FloatArray(records.size) { Sky.unwarpedY(records[it].kind, records[it].id) }
        val hashR = pearson(hx, hy)
        println("  x/y correlation of the placement hash over ${records.size} stars: r = ${"%.4f".format(hashR)}")
        // 6,000 samples put the standard error near 0.013, so 0.05 is roughly four of them: wide
        // enough that this is not a coin toss, narrow enough that it catches anything structural.
        assertTrue("the axes are correlated at r = $hashR", Math.abs(hashR) < 0.05)

        // And on what is actually drawn. The bound is wider on purpose: SkyWarp deliberately puts
        // structure into the field, and with only LUMPS² lattice values that structure carries a
        // small correlation of its own, which varies with the seed and measures under 0.09 across
        // the seeds sampled. It is still an order of magnitude away from the -1.0 the pre-2026-09-16
        // placement produced, which is the failure this guards.
        val laid = layout(records)
        assertEquals(6_000, laid.starCount)
        val drawnR = pearson(laid.x, laid.y)
        println("  x/y correlation as drawn: r = ${"%.4f".format(drawnR)}")
        assertTrue("the drawn axes are correlated at r = $drawnR", Math.abs(drawnR) < 0.15)

        // Both axes have to use their whole range, or a correlation near zero could still be two
        // stars' worth of variation around a single point.
        assertTrue(laid.x.min() < 0.05f && laid.x.max() > 0.95f)
        assertTrue(laid.y.min() < 0.05f && laid.y.max() > 0.95f)
    }

    @Test
    fun `the sky clumps rather than spreading evenly`() {
        val records = (0 until 6_000).map {
            SkyRecord(SkyKind.CHECK_IN, id = 1L + it, epochDay = start + it / 3, moodLevel = 3)
        }
        val laid = layout(records)
        val clumped = clumpIndex(laid.x, laid.y)

        // The detector: the same measurement on an unwarped scatter of the same size, which is what
        // the placement hash produces before SkyWarp touches it.
        val flatX = FloatArray(6_000) { SkyRandom.unit(SkyRandom.mix(0x11L, it.toLong())) }
        val flatY = FloatArray(6_000) { SkyRandom.unit(SkyRandom.mix(0x22L, it.toLong())) }
        val flat = clumpIndex(flatX, flatY)

        println("  clump index: warped ${"%.2f".format(clumped)}, unwarped ${"%.2f".format(flat)}")
        assertTrue("an even scatter measured as clumped at $flat", flat < 1.6)
        assertTrue("the sky did not clump: $clumped", clumped > flat * 1.5)
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
