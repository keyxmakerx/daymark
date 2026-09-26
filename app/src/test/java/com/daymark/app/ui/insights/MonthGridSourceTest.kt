package com.daymark.app.ui.insights

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.argumentsOfCall
import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Insights → Month never fills a day with a mood colour, never blends two moods, and never reduces a
 * day to an average (#397).
 *
 * Three composables in `InsightsScreen.kt` draw the month: `MonthGrid` lays out the weeks, `DayCell`
 * draws one day, and `MoodDots` draws that day's dots. The days come from `CalendarViewModel`
 * through `CalendarDays`, whose behaviour `CalendarDaysTest` runs. What is left is the shape of the
 * Compose code, which this module cannot run, so it is read as text through [codeOnly], where
 * neither a comment nor a string can hide a call or fake one:
 *
 *  - `MonthGrid` and `DayCell` name no mood colour, and paint no fill at all, so every day is the
 *    same paper;
 *  - the day number takes `onSurface` and nothing else, so a day with no entry is not dimmer;
 *  - `MoodDots` is the one place the month reads a mood colour: a fixed-size dot per entry's level;
 *  - nothing in the month, its view model or its day model averages, blends or holds a `Double`.
 *
 * Every check is shown a planted example in the real file first (CLAUDE.md §5), and each plant is
 * measured against what the real file already holds, so a control never turns red for a rule it
 * does not name. Reading source is a weaker claim than what a person sees; the screen is checked by
 * hand in both themes. These run in seconds through `tools/jvm-source-tests.sh`, and in CI.
 */
class MonthGridSourceTest {

    private companion object {
        const val INSIGHTS = "app/src/main/java/com/daymark/app/ui/insights/InsightsScreen.kt"
        const val VIEW_MODEL = "app/src/main/java/com/daymark/app/ui/calendar/CalendarViewModel.kt"
        const val DAY_MODEL = "app/src/main/java/com/daymark/app/ui/calendar/CalendarDays.kt"

        /** The number's colour as it must be written. */
        const val INK = "MaterialTheme.colorScheme.onSurface"

        /**
         * A mood colour, by any name the theme or the model gives one, or the blend of two: the holder
         * (`MaterialTheme.moodColors`, `LocalMoodColors`, a `MoodColors` passed in), the raw tokens,
         * an enum's `.color`, or `moodColor(`. Not `forLevel` on its own, which the labels share.
         */
        val MOOD_COLOUR = Regex(
            """\b(?:moodColors|LocalMoodColors|MoodColors|moodColor|Mood(?:Awful|Bad|Meh|Good|Rad)\w*)\b|\.\s*color\b""",
        )

        /** Anything that paints an area, rather than a word or a ring. */
        val FILL = Regex("""\bbackground\s*\(|\bdraw(?:Rect|RoundRect|Circle|Oval|Path)\s*\(|\b(?:Surface|Card|Canvas)\s*\(""")

        /** A day reduced to one number, or two colours mixed into one. */
        val AVERAGE_OR_BLEND = Regex(
            """\baverage|\bmean\b|\bmedian|\blerp\s*\(|\bmoodColor\s*\(|\bblend|\bDouble\b|\bsum(?:Of)?\s*\(""",
            RegexOption.IGNORE_CASE,
        )

        /** What dims, recolours or strikes a number without changing its colour argument. */
        val DIMMERS = listOf("alpha", "Color.", "TextDecoration", "LineThrough")
    }

    private val insights: String = repoFile(INSIGHTS).readText()
    private val viewModel: String = repoFile(VIEW_MODEL).readText()
    private val dayModel: String = repoFile(DAY_MODEL).readText()

    /**
     * The body of `fun [name](...) { ... }` in [code], braces balanced, or null when there is none.
     * [code] is code only, so a brace in a string or a comment cannot unbalance it.
     */
    private fun bodyRange(code: String, name: String): IntRange? {
        val start = Regex("""\bfun\s+$name\s*\(""").find(code) ?: return null
        var i = start.range.last
        var depth = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        val open = code.indexOf('{', i)
        // An expression body is not how these are written; say so rather than read the next function.
        if (open < 0 || code.substring(i + 1, open).contains('=')) return null
        depth = 0
        for (j in open until code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return (open + 1) until j
                }
            }
        }
        return null
    }

    private fun body(source: String, name: String): String {
        val code = codeOnly(source)
        val range = bodyRange(code, name)
        assertNotNull("fun $name is gone from the month's code, or is no longer a block body", range)
        return code.substring(range!!)
    }

    /** The Month branch of the period switch: from `Scope.Month ->` to the next branch. */
    private fun monthBranch(source: String): String {
        val code = codeOnly(source)
        val from = code.indexOf("Scope.Month ->")
        val until = code.indexOf("Scope.Year ->", from)
        assertTrue("the Month branch of the period switch has moved", from >= 0 && until > from)
        return code.substring(from, until)
    }

    /** Every fill and every mood colour in `MonthGrid` and `DayCell`, one line each. */
    private fun fillsAndMoodColours(source: String): List<String> =
        listOf("MonthGrid", "DayCell").flatMap { name ->
            val code = body(source, name)
            MOOD_COLOUR.findAll(code).map { "$name names a mood colour: ${it.value}" }.toList() +
                FILL.findAll(code).map { "$name paints a fill: ${it.value}" }.toList()
        }

    /** Every average or blend in the month's code, its view model and its day model, one line each. */
    private fun averagesAndBlends(insightsSource: String, viewModelSource: String, dayModelSource: String): List<String> {
        val places = listOf(
            "MonthGrid" to body(insightsSource, "MonthGrid"),
            "DayCell" to body(insightsSource, "DayCell"),
            "MoodDots" to body(insightsSource, "MoodDots"),
            "the Month branch" to monthBranch(insightsSource),
            "CalendarViewModel.kt" to codeOnly(viewModelSource),
            "CalendarDays.kt" to codeOnly(dayModelSource),
        )
        return places.flatMap { (where, code) -> AVERAGE_OR_BLEND.findAll(code).map { "$where: ${it.value}" }.toList() }
    }

    /** Where in [source] the day number's colour is written: the `color =` argument of DayCell's `Text`. */
    private fun numberColourRange(source: String): IntRange {
        val code = codeOnly(source)
        val cell = bodyRange(code, "DayCell")
        assertNotNull("fun DayCell is gone from the month's code", cell)
        val text = code.indexOf("Text(", cell!!.first)
        assertTrue("DayCell no longer draws its number with Text", text in cell)
        val args = argumentsOfCall(code.substring(text), "Text")
        assertNotNull("DayCell's Text call does not close", args)
        val colour = Regex("""\bcolor\s*=\s*([^,]+)""").find(args!!)?.groups?.get(1)
        assertNotNull("DayCell's number no longer names its colour", colour)
        val argsStart = text + "Text(".length
        return (argsStart + colour!!.range.first)..(argsStart + colour.range.last)
    }

    private fun numberColour(source: String): String =
        source.substring(numberColourRange(source)).replace(Regex("\\s+"), " ").trim()

    /** [source] with [addition] written straight after [anchor], which must be there exactly once. */
    private fun plantAfter(source: String, anchor: String, addition: String): String {
        assertEquals("the anchor \"$anchor\" is not there exactly once", 1, source.split(anchor).size - 1)
        val planted = source.replace(anchor, anchor + addition)
        assertNotEquals("nothing was planted", source, planted)
        return planted
    }

    /** The findings a plant added: those in [planted], less one of each already in [real]. */
    private fun added(planted: List<String>, real: List<String>): List<String> =
        planted.toMutableList().apply { real.forEach { remove(it) } }

    @Test
    fun `the month's code was found, and the scanner reads code and only code`() {
        val cell = body(insights, "DayCell")
        assertTrue("MonthGrid no longer draws DayCell", body(insights, "MonthGrid").contains("DayCell("))
        assertTrue("the scanner ate DayCell's code", cell.contains("Text(") && cell.contains("clickable"))
        assertTrue("the scanner ate MoodDots' code", body(insights, "MoodDots").let { it.contains("Box(") && it.contains("chunked(") })
        assertTrue("the Month branch no longer draws MonthGrid", monthBranch(insights).contains("MonthGrid("))
        assertTrue("the view model no longer builds its days through CalendarDays", codeOnly(viewModel).contains("CalendarDays.moodsByDay("))
        assertTrue("CalendarDays.kt lost its dots", codeOnly(dayModel).contains("fun dots("))

        // A phrase only DayCell's comment holds: the raw text has it and the code does not, so a
        // comment can neither hide a call from these checks nor trip them.
        val code = codeOnly(insights)
        assertEquals("the scanner changed the length", insights.length, code.length)
        val phrase = "never read as a lone number"
        assertTrue("DayCell's comment no longer says \"$phrase\"; choose another", insights.substring(bodyRange(code, "DayCell")!!).contains(phrase))
        assertFalse("the scanner left a comment", cell.contains(phrase))
    }

    @Test
    fun `no day in the month is filled, and none with a mood colour`() {
        val found = fillsAndMoodColours(insights)
        assertTrue(
            "Every day is the same paper, and a mood colour is drawn only as an entry's own dot, in " +
                "MoodDots (#397). These fill a day or colour it by mood:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `every day's number is the full ink, whatever the day holds`() {
        assertEquals(
            "The day number is onSurface on every day, 14.26:1 light and 12.66:1 dark; a day with no " +
                "entry is never dimmer (#397, #408)",
            INK,
            numberColour(insights),
        )
        val cell = body(insights, "DayCell")
        for (dimmer in DIMMERS) assertFalse("DayCell dims, recolours or strikes a day with $dimmer", cell.contains(dimmer))
    }

    @Test
    fun `a day's only colour is one fixed-size dot per entry, in that entry's own mood`() {
        assertTrue(
            "MoodDots takes each entry's level, not a day's summary",
            Regex("""\bfun\s+MoodDots\s*\(\s*levels\s*:\s*List<Int>""").containsMatchIn(codeOnly(insights)),
        )
        val dots = body(insights, "MoodDots")
        assertTrue(
            "a dot is a fixed-size circle filled with one entry's own mood colour",
            dots.contains(".size(DOT_DP.dp).clip(CircleShape).background(moods.forLevel(level))"),
        )
        assertTrue("MoodDots reads the person's own mood colours", dots.contains("val moods = MaterialTheme.moodColors"))
        assertTrue(
            "DayCell draws the dots CalendarDays chose, which CalendarDaysTest holds",
            body(insights, "DayCell").contains("MoodDots(CalendarDays.dots(moods)"),
        )
    }

    @Test
    fun `a screen reader hears the day's description, never a lone number`() {
        val cell = body(insights, "DayCell")
        assertTrue("DayCell no longer builds its words from CalendarDays", cell.contains("CalendarDays.description("))
        assertTrue("DayCell no longer gives its words to the screen reader", cell.contains("contentDescription = description"))
        assertTrue("the number and the dots are read as well as the description", cell.contains("clearAndSetSemantics"))
    }

    @Test
    fun `nothing in the month averages or blends`() {
        val found = averagesAndBlends(insights, viewModel, dayModel)
        assertTrue(
            "A day is its entries, each in its own mood, never their average or a blend of their " +
                "colours (#397). These reduce or mix a day:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the fill check sees a day filled, planted back into the real file`() {
        val real = fillsAndMoodColours(insights)
        val box = ".heightIn(min = 38.dp)\n"

        // The fill #397 took out, as it was: the day's average blended between two mood colours, and
        // the hairline under a day with nothing.
        val oldFill = plantAfter(
            insights,
            box,
            "                .background(if (moods.isNotEmpty()) moodColor(moods.average(), MaterialTheme.moodColors) " +
                "else MaterialTheme.colorScheme.surfaceVariant)\n",
        )
        assertEquals(
            listOf("DayCell names a mood colour: moodColor", "DayCell names a mood colour: moodColors", "DayCell paints a fill: background("),
            added(fillsAndMoodColours(oldFill), real),
        )
        // One entry's own colour behind the whole day is still a fill, with nothing averaged...
        val oneMood = plantAfter(insights, box, "                .background(MaterialTheme.moodColors.forLevel(moods.first()))\n")
        assertEquals(
            listOf("DayCell names a mood colour: moodColors", "DayCell paints a fill: background("),
            added(fillsAndMoodColours(oneMood), real),
        )
        // ...a mood colour reached through the enum is a mood colour...
        val enumColour = plantAfter(insights, box, "                .border(1.dp, Mood.fromLevel(moods.first()).color, shape)\n")
        assertEquals(listOf("DayCell names a mood colour: .color"), added(fillsAndMoodColours(enumColour), real))
        // ...and the grid is read as well as the day.
        val grid = plantAfter(
            insights,
            "Box(Modifier.weight(1f)) {\n",
            "                        Box(Modifier.background(MaterialTheme.moodColors.forLevel(3)))\n",
        )
        assertEquals(
            listOf("MonthGrid names a mood colour: moodColors", "MonthGrid paints a fill: background("),
            added(fillsAndMoodColours(grid), real),
        )
        // Control: the labels' forLevel, which DayCell does call, is not a colour.
        assertTrue("DayCell no longer reads the labels, so the regex's exception is untested", body(insights, "DayCell").contains("labels.forLevel("))
    }

    @Test
    fun `the number check sees a dimmer number, planted back into the real file`() {
        // The number as it was: white on the fill, and the soft ink on the hairline of an empty day.
        val old = "if (moods.isNotEmpty()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant"
        val planted = insights.replaceRange(numberColourRange(insights), old)
        assertNotEquals("nothing was planted", insights, planted)
        assertEquals(old, numberColour(planted))
        assertTrue("the dimmer check cannot see a raw colour", body(planted, "DayCell").contains("Color."))
    }

    @Test
    fun `the average check sees an average in every place it reads, planted back`() {
        val real = averagesAndBlends(insights, viewModel, dayModel)
        val average = "listOf(1.0).average()\n"

        val cell = plantAfter(insights, ".heightIn(min = 38.dp)\n", "                .background(moodColor(moods.average(), MaterialTheme.moodColors))\n")
        assertEquals(listOf("DayCell: moodColor(", "DayCell: average"), added(averagesAndBlends(cell, viewModel, dayModel), real))

        val grid = plantAfter(insights, "val leadingPad = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value\n", "        $average")
        assertEquals(listOf("MonthGrid: average"), added(averagesAndBlends(grid, viewModel, dayModel), real))

        val dots = plantAfter(insights, "    val moods = MaterialTheme.moodColors\n", "    $average")
        assertEquals(listOf("MoodDots: average"), added(averagesAndBlends(dots, viewModel, dayModel), real))

        val branch = plantAfter(insights, "MonthGrid(calendar.month, calendar.dayMoods, onDayClick)\n", "                    $average")
        assertEquals(listOf("the Month branch: average"), added(averagesAndBlends(branch, viewModel, dayModel), real))

        // The view model's old line, put back, and the same in the day model.
        val oldViewModel = viewModel + "\nprivate val old = listOf(2, 4).map { it }.average()\n"
        assertEquals(listOf("CalendarViewModel.kt: average"), added(averagesAndBlends(insights, oldViewModel, dayModel), real))
        val oldDayModel = dayModel + "\nprivate val old: Double = listOf(2, 4).average()\n"
        assertEquals(
            listOf("CalendarDays.kt: Double", "CalendarDays.kt: average"),
            added(averagesAndBlends(insights, viewModel, oldDayModel), real),
        )
    }
}
