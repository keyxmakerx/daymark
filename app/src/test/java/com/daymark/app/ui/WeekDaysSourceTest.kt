package com.daymark.app.ui

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Every week view draws a day as the month does, never as an average or a blend (#411); every mood
 * dot is drawn inside a ring that measures at least 3:1 on the sheet and the paper in both themes;
 * and a day's dots run in the order the day's own list runs (#412).
 *
 * Two week views. Insights → Week is `WeekDays` in InsightsScreen.kt, one row of the month's own
 * `DayCell`, fed by `StatsViewModel`; Home's strip is `WeekGlance` in HomeScreen.kt, fed by
 * `HomeViewModel`. Both view models build their week with `CalendarDays.week`, whose behaviour
 * `CalendarDaysTest` runs, from entries turned into days by `DayEntries.kt`. Every dot is `MoodDot`
 * (`ui/components/MoodDot.kt`). The Compose code cannot run here, so it is read as text through
 * [codeOnly], where neither a comment nor a string can hide a call or fake one:
 *
 *  - nothing a week's day passes through averages it, blends two moods, reads the 30-day trend of
 *    daily averages, or sizes anything by what a day holds;
 *  - `MoodDot` draws a disc of a colour-scheme role and the mood colour inside it, a ring's width
 *    in, and that role measures at least 3:1 against the sheet and the paper of both schemes,
 *    worked out here from Theme.kt and Color.kt;
 *  - no composable that draws a day in the month or a week reads a mood colour except through
 *    `MoodDot`;
 *  - `CalendarDays.moodsByDay` sorts in the direction the day's own list is queried in
 *    (`EntryDao.observeBetween`), and nothing between that query and a drawn dot re-orders a day.
 *
 * Every check is shown a planted example in a copy of the real files first (CLAUDE.md §5), measured
 * against what the real files already hold, so a control never turns red for a rule it does not
 * name, and every plant must change the text it is planted in. `MonthGridSourceTest` holds the month
 * the same way. Reading source is a weaker claim than what a person sees; both views are checked by
 * hand in both themes, with default and recoloured moods.
 */
class WeekDaysSourceTest {

    private companion object {
        const val INSIGHTS = "app/src/main/java/com/daymark/app/ui/insights/InsightsScreen.kt"
        const val HOME = "app/src/main/java/com/daymark/app/ui/home/HomeScreen.kt"
        const val HOME_VM = "app/src/main/java/com/daymark/app/ui/home/HomeViewModel.kt"
        const val STATS_VM = "app/src/main/java/com/daymark/app/ui/stats/StatsViewModel.kt"
        const val CALENDAR_VM = "app/src/main/java/com/daymark/app/ui/calendar/CalendarViewModel.kt"
        const val DAY_MODEL = "app/src/main/java/com/daymark/app/ui/calendar/CalendarDays.kt"
        const val DAY_ENTRIES = "app/src/main/java/com/daymark/app/ui/calendar/DayEntries.kt"
        const val MOOD_DOT = "app/src/main/java/com/daymark/app/ui/components/MoodDot.kt"
        const val ENTRY_DAO = "app/src/main/java/com/daymark/app/data/dao/EntryDao.kt"
        const val ENTRY_REPO = "app/src/main/java/com/daymark/app/data/EntryRepository.kt"
        const val DAY_VM = "app/src/main/java/com/daymark/app/ui/calendar/DayDetailViewModel.kt"
        const val DAY_SCREEN = "app/src/main/java/com/daymark/app/ui/calendar/DayDetailScreen.kt"
        const val THEME = "app/src/main/java/com/daymark/app/ui/theme/Theme.kt"
        const val COLORS = "app/src/main/java/com/daymark/app/ui/theme/Color.kt"

        /** The Insights composables a day of the week passes through, from the row to its dots. */
        val INSIGHTS_WEEK = listOf("WeekDays", "WeekdayHeader", "DayRow", "DayCell", "MoodDots")

        /** The Home composables the strip passes through. */
        val HOME_WEEK = listOf("GlanceRow", "WeekGlance")

        /**
         * A day reduced to one number or one mood, two colours mixed into one, or the 30-day trend of
         * daily averages the old Week bars were cut from.
         */
        val REDUCTION = Regex(
            """\baverage|\bavg|\bmean\b|\bmedian|\blerp\s*\(|\bmoodColor\s*\(|\bblend|\bDouble\b|""" +
                """\bsum(?:Of|By)?\s*\(|\broundToInt\b|\b(?:max|min)(?:Of|By|OrNull)?\s*\(|\btrend\b""",
            RegexOption.IGNORE_CASE,
        )

        /** A height taken from what a day holds: a fraction of the room, or a size scaled by something. */
        val BAR = Regex("""\bfillMaxHeight\s*\(\s*[^)\s][^)]*\)|\bheight\s*\((?:[^()]|\([^()]*\))*[*/](?:[^()]|\([^()]*\))*\)""")

        /** A mood colour by any name the theme or the model gives one, or the blend, as in MonthGridSourceTest. */
        val MOOD_COLOUR = Regex(
            """\b(?:moodColors|LocalMoodColors|MoodColors|moodColor|Mood(?:Awful|Bad|Meh|Good|Rad)\w*)\b|\.\s*color\b""",
        )

        /** Anything that puts a list in another order. */
        val REORDER = Regex("""\b(?:sorted\w*|sort\w*|reversed|asReversed|reverse|shuffled|shuffle)\s*[({]""")

        /** The whole ringed mark, whitespace removed: a disc of the ring's colour, then the mood colour a ring's width in. */
        val RING = Regex(
            """\.size\(MOOD_DOT_MARK_DP\.dp\)\.clip\(CircleShape\)\.background\((.+?)\)""" +
                """\.padding\(MOOD_DOT_RING_DP\.dp\)\.clip\(CircleShape\)\.background\(MaterialTheme\.moodColors\.forLevel\(level\)\)""",
        )

        /** The least a mark needs against what it sits on (WCAG 2.1, 1.4.11). */
        const val MARK_CONTRAST = 3.0

        /** The two schemes in Theme.kt, and the grounds a dot sits on in each: the sheet, and the paper. */
        val SCHEMES = listOf("LightPaperColors", "DarkPaperColors")
        val GROUNDS = listOf("surface", "background")
    }

    /** The files these checks read, as text. A control swaps one for a planted copy. */
    private data class Tree(
        val insights: String,
        val home: String,
        val homeVm: String,
        val statsVm: String,
        val calendarVm: String,
        val dayModel: String,
        val dayEntries: String,
        val moodDot: String,
        val dao: String,
        val repo: String,
        val dayVm: String,
        val dayScreen: String,
        val theme: String,
        val colors: String,
    )

    private val real = Tree(
        insights = repoFile(INSIGHTS).readText(),
        home = repoFile(HOME).readText(),
        homeVm = repoFile(HOME_VM).readText(),
        statsVm = repoFile(STATS_VM).readText(),
        calendarVm = repoFile(CALENDAR_VM).readText(),
        dayModel = repoFile(DAY_MODEL).readText(),
        dayEntries = repoFile(DAY_ENTRIES).readText(),
        moodDot = repoFile(MOOD_DOT).readText(),
        dao = repoFile(ENTRY_DAO).readText(),
        repo = repoFile(ENTRY_REPO).readText(),
        dayVm = repoFile(DAY_VM).readText(),
        dayScreen = repoFile(DAY_SCREEN).readText(),
        theme = repoFile(THEME).readText(),
        colors = repoFile(COLORS).readText(),
    )

    // ---- Reading the code ----

    /** The body of `fun [name]` in [source], as code only. */
    private fun body(source: String, name: String): String {
        val code = codeOnly(source)
        val range = functionBodyRange(code, name)
        assertNotNull("fun $name is gone, or is no longer a block body", range)
        return code.substring(range!!)
    }

    /** The whole of `fun [name]`, its parameters included, as code only: from `fun` to its closing brace. */
    private fun function(source: String, name: String): String {
        val code = codeOnly(source)
        val start = Regex("""\bfun\s+$name\s*\(""").find(code)
        val range = functionBodyRange(code, name)
        assertNotNull("fun $name is gone, or is no longer a block body", range)
        return code.substring(start!!.range.first, range!!.last + 2)
    }

    /** The Week branch of Insights' period switch: from `Scope.Week ->` to the next branch. */
    private fun weekBranch(insights: String): String {
        val code = codeOnly(insights)
        val from = code.indexOf("Scope.Week ->")
        val until = code.indexOf("Scope.Month ->", from)
        assertTrue("the Week branch of the period switch has moved", from >= 0 && until > from)
        return code.substring(from, until)
    }

    /** What Home hands its glance: the arguments of the call to `GlanceRow`. */
    private fun glanceCall(home: String): String {
        val code = codeOnly(home)
        val at = Regex("""\bGlanceRow\(\s*totalEntries\s*=""").find(code)
        assertNotNull("Home no longer calls GlanceRow with its entries and week", at)
        return argumentsOfCall(code.substring(at!!.range.first), "GlanceRow")!!
    }

    /** The arguments a view model builds its week from: the call to `CalendarDays.week`. */
    private fun weekArguments(viewModel: String): String {
        val args = argumentsOfCall(codeOnly(viewModel), "CalendarDays.week")
        assertNotNull("a view model no longer builds its week with CalendarDays.week", args)
        return args!!
    }

    private fun squeezed(text: String) = text.replace(Regex("\\s+"), "")

    /** Every place a day of either week passes through, by name, as code. */
    private fun weekPlaces(t: Tree): List<Pair<String, String>> =
        listOf("the Week branch" to weekBranch(t.insights)) +
            INSIGHTS_WEEK.map { it to function(t.insights, it) } +
            HOME_WEEK.map { it to function(t.home, it) } +
            listOf(
                "Home's glance" to glanceCall(t.home),
                "HomeViewModel.kt" to codeOnly(t.homeVm),
                "StatsViewModel's week" to weekArguments(t.statsVm),
                "CalendarDays.kt" to codeOnly(t.dayModel),
                "DayEntries.kt" to codeOnly(t.dayEntries),
                "MoodDot.kt" to codeOnly(t.moodDot),
            )

    /** Where a day of either week is laid out, so where a height could be taken from it. */
    private fun layoutPlaces(t: Tree): List<Pair<String, String>> =
        listOf("the Week branch" to weekBranch(t.insights)) +
            INSIGHTS_WEEK.map { it to function(t.insights, it) } +
            HOME_WEEK.map { it to function(t.home, it) }

    /** Every average, blend, trend and data-driven height in either week, one line each. */
    private fun reductions(t: Tree): List<String> =
        weekPlaces(t).flatMap { (where, code) -> REDUCTION.findAll(code).map { "$where: ${it.value}" }.toList() } +
            layoutPlaces(t).flatMap { (where, code) ->
                BAR.findAll(code).map { "$where sizes a day by what it holds: ${it.value}" }.toList()
            }

    /** Every mood colour read by a composable that draws a day, anywhere but in `MoodDot`, one line each. */
    private fun moodColoursOutsideTheDot(t: Tree): List<String> =
        (listOf("the Week branch" to weekBranch(t.insights), "MonthGrid" to function(t.insights, "MonthGrid")) +
            INSIGHTS_WEEK.map { it to function(t.insights, it) } +
            HOME_WEEK.map { it to function(t.home, it) })
            .flatMap { (where, code) -> MOOD_COLOUR.findAll(code).map { "$where reads a mood colour: ${it.value}" }.toList() }

    // ---- Measuring the ring ----

    /** Color.kt's `val Name = Color(0xAARRGGBB)` tokens, as RRGGBB. */
    private fun tokens(colors: String): Map<String, String> =
        Regex("""\bval\s+(\w+)\s*=\s*Color\(\s*0[xX][0-9a-fA-F]{2}([0-9a-fA-F]{6})\s*\)""").findAll(codeOnly(colors))
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }

    /** The token each role of [scheme] is given in Theme.kt. */
    private fun roles(theme: String, scheme: String): Map<String, String> {
        val code = codeOnly(theme)
        val at = Regex("""\bval\s+$scheme\s*=\s*(lightColorScheme|darkColorScheme)\(""").find(code) ?: return emptyMap()
        val args = argumentsOfCall(code.substring(at.range.first), at.groupValues[1]) ?: return emptyMap()
        return Regex("""\b(\w+)\s*=\s*(\w+)""").findAll(args).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** WCAG 2 relative luminance of an RRGGBB colour. */
    private fun luminance(rgb: String): Double {
        fun channel(i: Int): Double {
            val c = rgb.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }

    private fun contrast(a: String, b: String): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun ratio(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    /** The ring's colour-scheme role, measured against every ground in every scheme: one line per shortfall. */
    private fun ringContrast(role: String, t: Tree): List<String> {
        val tokens = tokens(t.colors)
        return SCHEMES.flatMap { scheme ->
            val roles = roles(t.theme, scheme)
            val ring = roles[role]?.let { tokens[it] }
                ?: return@flatMap listOf("$scheme gives the ring's role, $role, no colour this test can read")
            GROUNDS.mapNotNull { ground ->
                val under = roles[ground]?.let { tokens[it] }
                    ?: return@mapNotNull "$scheme's $ground has no colour this test can read"
                val measured = contrast(ring, under)
                if (measured >= MARK_CONTRAST) {
                    null
                } else {
                    "the ring, $role, measures ${ratio(measured)}:1 on the $scheme $ground, under $MARK_CONTRAST:1"
                }
            }
        }
    }

    /** What is wrong with the ring as `MoodDot` draws it: missing, not a role, or too faint. */
    private fun ringDrawingFindings(t: Tree): List<String> {
        val chain = RING.find(squeezed(body(t.moodDot, "MoodDot"))) ?: return listOf("MoodDot does not draw its colour inside a ring")
        val ring = chain.groupValues[1]
        val role = Regex("""^MaterialTheme\.colorScheme\.(\w+)$""").find(ring)?.groupValues?.get(1)
            ?: return listOf("the ring is not a colour-scheme role: $ring")
        return ringContrast(role, t)
    }

    /** What is wrong with the ring's size: under 1 dp, or inside the colour instead of around it. */
    private fun ringSizeFindings(t: Tree): List<String> {
        val found = mutableListOf<String>()
        val code = codeOnly(t.moodDot)
        val width = Regex("""\bconst\s+val\s+MOOD_DOT_RING_DP\s*=\s*(\d+)""").find(code)?.groupValues?.get(1)?.toInt()
        if (width == null || width < 1) found += "the ring is less than 1 dp wide: $width"
        if (!squeezed(code).contains("constvalMOOD_DOT_MARK_DP=MOOD_DOT_DP+2*MOOD_DOT_RING_DP")) {
            found += "the mark is not the whole dot with a ring on each side, so the ring eats the colour"
        }
        return found
    }

    /** Everything wrong with the ring, one line each. */
    private fun ringFindings(t: Tree): List<String> = ringDrawingFindings(t) + ringSizeFindings(t)

    /**
     * [t] with `MoodDot`'s whole modifier chain, from its size to its mood colour, replaced by
     * [chain]: a control that decides the drawing entirely, whatever the real file holds today.
     */
    private fun withDotChain(t: Tree, chain: String): Tree {
        val whole = Regex("""\.size\(MOOD_DOT_\w+\.dp\)[\s\S]*?\.background\(MaterialTheme\.moodColors\.forLevel\(level\)\)""")
        val found = whole.findAll(t.moodDot).toList()
        check(found.size == 1) { "MoodDot's chain is not there exactly once" }
        val planted = t.moodDot.replaceRange(found.single().range, chain)
        check(planted != t.moodDot) { "the chain planted is the chain already there" }
        return t.copy(moodDot = planted)
    }

    // ---- The order ----

    /** The direction the day's own list is queried in, ASC or DESC: the ORDER BY of `observeBetween`. */
    private fun dayListDirection(dao: String): String? {
        val code = withoutComments(dao)
        val fn = code.indexOf("fun observeBetween(")
        val query = if (fn < 0) -1 else code.lastIndexOf("@Query(", fn)
        if (query < 0) return null
        val order = Regex("""ORDER\s+BY\s+dateTime\b(\s+(ASC|DESC)\b)?""", RegexOption.IGNORE_CASE)
            .find(code.substring(query, fn)) ?: return null
        return order.groups[2]?.value?.uppercase() ?: "ASC"
    }

    /** The direction `CalendarDays.moodsByDay` sorts a day's entries in by time, ASC or DESC. */
    private fun modelDirection(dayModel: String): String? {
        val code = codeOnly(dayModel)
        val fn = code.indexOf("fun moodsByDay(")
        if (fn < 0) return null
        val next = Regex("""\bfun\s""").find(code, fn + 3)?.range?.first ?: code.length
        val sort = code.substring(fn, next)
        return when {
            Regex("""compareByDescending<DayEntry>\s*\{\s*it\.epochMillis\s*\}""").containsMatchIn(sort) -> "DESC"
            Regex("""compareBy<DayEntry>\s*\(?\s*\{\s*it\.epochMillis\s*\}""").containsMatchIn(sort) -> "ASC"
            else -> null
        }
    }

    /** Every place between the day's list or the day model and a drawn dot, by name, as code. */
    private fun orderPlaces(t: Tree): List<Pair<String, String>> =
        listOf(
            "DayDetailViewModel.kt" to codeOnly(t.dayVm),
            "DayDetailScreen.kt" to codeOnly(t.dayScreen),
            "DayEntries.kt" to codeOnly(t.dayEntries),
            "CalendarViewModel.kt" to codeOnly(t.calendarVm),
            "HomeViewModel's week" to weekArguments(t.homeVm),
            "StatsViewModel's week" to weekArguments(t.statsVm),
            "the Week branch" to weekBranch(t.insights),
            "MonthGrid" to function(t.insights, "MonthGrid"),
        ) +
            listOf("week", "dots", "description").map { "CalendarDays.$it" to function(t.dayModel, it) } +
            INSIGHTS_WEEK.map { it to function(t.insights, it) } +
            HOME_WEEK.map { it to function(t.home, it) }

    /** Whether the day model sorts a day the way the day's own list is queried, one line per mismatch. */
    private fun directionFindings(t: Tree): List<String> {
        val found = mutableListOf<String>()
        val list = dayListDirection(t.dao)
        val model = modelDirection(t.dayModel)
        if (list == null) found += "EntryDao.observeBetween no longer says what order the day's own list runs in"
        if (model == null) found += "CalendarDays.moodsByDay no longer sorts by time in a direction this test can read"
        if (list != null && model != null && list != model) {
            found += "the day's own list runs $list by time, and its dots run $model"
        }
        return found
    }

    /** Everything that would make a day's dots run in another order than its own list, one line each. */
    private fun orderFindings(t: Tree): List<String> {
        val found = directionFindings(t).toMutableList()
        if (!squeezed(codeOnly(t.repo)).contains("funobserveBetween(from:Long,to:Long):Flow<List<EntryWithActivities>>=entryDao.observeBetween(from,to)")) {
            found += "EntryRepository.observeBetween no longer hands the query's order through untouched"
        }
        if (!codeOnly(t.dayVm).contains(".observeBetween(")) found += "the day's own list no longer comes from observeBetween"
        orderPlaces(t).forEach { (where, code) -> REORDER.findAll(code).forEach { found += "$where re-orders a day: ${it.value}" } }
        return found
    }

    /** [dao] with the day's own list queried in [direction], whatever it says now. */
    private fun daoRunning(dao: String, direction: String): String {
        val fn = dao.indexOf("fun observeBetween(")
        val query = dao.lastIndexOf("@Query(", fn)
        check(fn >= 0 && query >= 0) { "EntryDao.observeBetween and its query have moved" }
        val set = Regex("""ORDER\s+BY\s+dateTime\b(\s+(ASC|DESC)\b)?""").replace(dao.substring(query, fn), "ORDER BY dateTime $direction")
        return dao.substring(0, query) + set + dao.substring(fn)
    }

    /** [dayModel] with `moodsByDay` sorting by time in [direction], whatever it does now. */
    private fun modelRunning(dayModel: String, direction: String): String {
        val comparator = Regex("""compareBy(?:Descending)?<DayEntry>\s*\{\s*it\.epochMillis\s*\}""")
        check(comparator.findAll(dayModel).count() == 1) { "moodsByDay's comparator is not there exactly once" }
        val by = if (direction == "DESC") "compareByDescending" else "compareBy"
        return comparator.replace(dayModel, "$by<DayEntry> { it.epochMillis }")
    }

    // ---- The rules ----

    @Test
    fun `the week views' code was found, and the scanner reads code and only code`() {
        assertTrue("the Week branch no longer draws WeekDays from the week", weekBranch(real.insights).contains("WeekDays(stats.week,"))
        val week = body(real.insights, "WeekDays")
        assertTrue("WeekDays is no longer one row of the month", week.contains("DayRow(week.days,"))
        assertTrue("WeekDays no longer names its days", week.contains("WeekdayHeader(week.days.map { it.dayOfWeek })"))
        assertTrue("DayRow no longer draws DayCell", body(real.insights, "DayRow").contains("DayCell("))
        assertTrue("DayCell no longer draws the dots CalendarDays chose", body(real.insights, "DayCell").contains("MoodDots(CalendarDays.dots(moods)"))
        assertTrue("MoodDots no longer draws MoodDot", body(real.insights, "MoodDots").contains("MoodDot(level)"))

        val strip = body(real.home, "WeekGlance")
        assertTrue("Home's strip no longer draws MoodDot", strip.contains("MoodDot(level)"))
        assertTrue(
            "Home's strip no longer draws the dots CalendarDays chose at the strip's cap",
            argumentsOfCall(strip, "CalendarDays.dots")?.contains("CalendarDays.STRIP_MAX_DOTS") == true,
        )
        assertTrue(
            "Home's strip no longer says exactly what it draws: its words must use the strip's cap too",
            argumentsOfCall(strip, "CalendarDays.description")?.contains("CalendarDays.STRIP_MAX_DOTS") == true,
        )
        assertTrue("Home's strip no longer gives each day its words", strip.contains("semantics { contentDescription = description }"))
        assertTrue("GlanceRow no longer draws the strip from the week", body(real.home, "GlanceRow").contains("WeekGlance(week = week,"))
        assertTrue("Home no longer hands the glance its week", glanceCall(real.home).contains("week = state.week"))

        for ((file, source, entries) in listOf(Triple("HomeViewModel", real.homeVm, "all"), Triple("StatsViewModel", real.statsVm, "entries"))) {
            assertTrue("$file's week is not what CalendarDays.week builds", Regex("""\bweek\s*=\s*CalendarDays\.week\(""").containsMatchIn(codeOnly(source)))
            val args = squeezed(weekArguments(source))
            assertTrue(
                "$file builds its week from something other than its entries, as days, and today: $args",
                args.startsWith("$entries.map") && args.contains("toDayEntry()") && args.endsWith(",today"),
            )
        }
        assertTrue("MoodDot.kt lost MoodDot", functionBodyRange(codeOnly(real.moodDot), "MoodDot") != null)

        // A phrase only WeekGlance's comment holds: the raw text has it and the code does not, so a
        // comment can neither hide a call from these checks nor trip them.
        val code = codeOnly(real.home)
        assertEquals("the scanner changed the length", real.home.length, code.length)
        val phrase = "does not read as bars"
        assertTrue("WeekGlance's comment no longer says \"$phrase\"; choose another", real.home.contains(phrase))
        assertFalse("the scanner left a comment", code.contains(phrase))
    }

    @Test
    fun `no week view averages a day, blends two moods or sizes a day by what it holds`() {
        val found = reductions(real)
        assertTrue(
            "A day in a week is its entries, each in its own mood, never their average, a blend of their " +
                "colours, the 30-day trend or a bar (#411). These reduce or measure a day:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `every mood dot is drawn inside a ring that measures at least 3 to 1 in both themes`() {
        val found = ringFindings(real)
        assertTrue(
            "A mood dot is its colour inside a ring of a colour-scheme role that measures at least 3:1 on the " +
                "sheet and the paper, light and dark, whatever colour the mood is (#412):\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `no day in the month or a week reads a mood colour except through MoodDot`() {
        val found = moodColoursOutsideTheDot(real)
        assertTrue(
            "Every dot is a MoodDot, so none is drawn without its ring (#412). These draw a mood colour " +
                "some other way:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the dots run in the order the day's own list runs, and nothing re-orders a day`() {
        val found = orderFindings(real)
        assertTrue(
            "A day's dots, and the words a screen reader hears for them, run in the order the day's own " +
                "list shows its entries (#412):\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
        assertEquals("the day's own list runs newest first", "DESC", dayListDirection(real.dao))
    }


    // ---- The controls ----
    //
    // Each plant goes into a copy of the real files at a line no rule is about, and each is measured
    // against what the real files already hold, so a control keeps proving its check can see while
    // the rule it serves is broken in the real code.

    /** Where a line can be planted inside `WeekGlance`: its first statement. */
    private val inStrip = "val labels = MaterialTheme.moodLabels\n"

    /** Where a line can be planted inside `WeekDays`: its first statement. */
    private val inWeek = "WeekdayHeader(week.days.map { it.dayOfWeek })\n"

    /** Where a line can be planted inside `MoodDots`. */
    private val inDots = "modifier = modifier.height(DOT_AREA_DP.dp),\n"

    /**
     * That a plant added exactly [expected] to what the real files hold, in any order. A finding the
     * real files already hold is struck from the planted copy's list where it first appears, which can
     * move the rest, so the two sides are compared sorted: which lines were added, not where they fall.
     */
    private fun assertAdded(expected: List<String>, planted: List<String>, before: List<String>) =
        assertEquals(expected.sorted(), findingsAdded(planted, before).sorted())

    /** `MoodDot`'s whole chain with a ring of [colour], as the real one is written, on one line. */
    private fun ringed(colour: String) =
        ".size(MOOD_DOT_MARK_DP.dp).clip(CircleShape).background($colour)" +
            ".padding(MOOD_DOT_RING_DP.dp).clip(CircleShape).background(MaterialTheme.moodColors.forLevel(level))"

    @Test
    fun `the reduction check sees an average, a blend, the trend and a bar, planted back`() {
        val before = reductions(real)

        // The old Week bars, put back into the Week branch.
        val bars = real.copy(
            insights = plantAfter(
                real.insights,
                "WeekDays(stats.week, onDayClick, modifier = Modifier.padding(top = 12.dp))\n",
                "                    WeekBars(stats.trend, modifier = Modifier.padding(top = 12.dp))\n",
            ),
        )
        assertAdded(listOf("the Week branch: trend"), reductions(bars), before)

        // A bar in the average's blended colour, inside the week's own row.
        val fill = real.copy(
            insights = plantAfter(real.insights, inWeek, "        Box(Modifier.fillMaxHeight(frac).background(moodColor(v, MaterialTheme.moodColors)))\n"),
        )
        assertAdded(
            listOf("WeekDays: moodColor(", "WeekDays sizes a day by what it holds: fillMaxHeight(frac)"),
            reductions(fill),
            before,
        )

        // Home's old bar: its height from the mean, its colour the mean rounded.
        val strip = real.copy(
            home = plantAfter(real.home, inStrip, "    Box(Modifier.height(WeekGlanceHeight * fraction).background(moodColors.forLevel(mean.roundToInt())))\n"),
        )
        assertAdded(
            listOf(
                "WeekGlance: mean",
                "WeekGlance: roundToInt",
                "WeekGlance sizes a day by what it holds: height(WeekGlanceHeight * fraction)",
            ),
            reductions(strip),
            before,
        )

        // The old stub's height, which a nested condition must not hide.
        val stub = real.copy(
            home = plantAfter(real.home, inStrip, "    Box(Modifier.height(if (fraction == null) 3.dp else WeekGlanceHeight * fraction))\n"),
        )
        assertAdded(
            listOf("WeekGlance sizes a day by what it holds: height(if (fraction == null) 3.dp else WeekGlanceHeight * fraction)"),
            reductions(stub),
            before,
        )

        // The view models' plumbing, and the day model's.
        val homeVm = real.copy(
            homeVm = plantAfter(real.homeVm, "val today = LocalDate.now()\n", "            val old = byDay[today]?.map { it.entry.moodLevel }?.let { MoodStats.averageMood(it) }\n"),
        )
        assertAdded(listOf("HomeViewModel.kt: average"), reductions(homeVm), before)
        val statsVm = real.copy(
            statsVm = replaceOnce(
                real.statsVm,
                "CalendarDays.week(entries.map { it.toDayEntry() }, today)",
                "CalendarDays.week(entries.map { it.toDayEntry().copy(moodLevel = trend.last()!!.roundToInt()) }, today)",
            ),
        )
        assertAdded(listOf("StatsViewModel's week: trend", "StatsViewModel's week: roundToInt"), reductions(statsVm), before)
        val dayModel = real.copy(
            dayModel = plantAfter(real.dayModel, "    const val WEEK_DAYS = 7\n", "    private val old: Double = listOf(2, 4).average()\n"),
        )
        assertAdded(listOf("CalendarDays.kt: Double", "CalendarDays.kt: average"), reductions(dayModel), before)
        val dayEntries = real.copy(
            dayEntries = replaceOnce(real.dayEntries, "moodLevel = entry.moodLevel,", "moodLevel = listOf(entry.moodLevel).average().toInt(),"),
        )
        assertAdded(listOf("DayEntries.kt: average"), reductions(dayEntries), before)
        val blend = real.copy(
            moodDot = replaceOnce(
                real.moodDot,
                ".background(MaterialTheme.moodColors.forLevel(level))",
                ".background(lerp(MaterialTheme.moodColors.forLevel(level), MaterialTheme.moodColors.forLevel(level + 1), 0.5f))",
            ),
        )
        assertAdded(listOf("MoodDot.kt: lerp("), reductions(blend), before)

        // Control: StatsViewModel does hold averages, for the 30-day chart; the check reads only its
        // week on purpose, and would see the rest if it read the file.
        assertTrue(REDUCTION.containsMatchIn(codeOnly(real.statsVm)))
        // Control: the old strip's signature, a list of means, is caught by its type alone.
        assertTrue(REDUCTION.containsMatchIn("private fun WeekGlance(week: List<Double?>, daysLogged: Int)"))
    }

    @Test
    fun `the ring check sees a dot with no ring, a raw ring, a faint ring and a ring inside the dot`() {
        // Each of these sets MoodDot's whole chain, so each holds whatever MoodDot says today.
        val bare = withDotChain(real, ".size(MOOD_DOT_DP.dp).clip(CircleShape).background(MaterialTheme.moodColors.forLevel(level))")
        assertEquals(listOf("MoodDot does not draw its colour inside a ring"), ringDrawingFindings(bare))

        val raw = withDotChain(real, ringed("Color(0xFF6B655B)"))
        assertEquals(listOf("the ring is not a colour-scheme role: Color(0xFF6B655B)"), ringDrawingFindings(raw))

        // The faint ink: 2.61:1 on the light sheet and 2.37:1 on the light paper, and above 3:1 on
        // both dark grounds.
        val faint = withDotChain(real, ringed("MaterialTheme.colorScheme.tertiary"))
        assertEquals(
            listOf(
                "the ring, tertiary, measures 2.61:1 on the LightPaperColors surface, under 3.0:1",
                "the ring, tertiary, measures 2.37:1 on the LightPaperColors background, under 3.0:1",
            ),
            ringDrawingFindings(faint),
        )
        // Control: the soft ink planted the same way passes, so the planting is not what is refused.
        assertEquals(emptyList<String>(), ringDrawingFindings(withDotChain(real, ringed("MaterialTheme.colorScheme.onSurfaceVariant"))))

        // The ring's size: none at all, derived from the width the real file gives, and a ring laid
        // over the colour instead of around it.
        val before = ringSizeFindings(real)
        val width = Regex("""const val MOOD_DOT_RING_DP = (\d+)""").find(real.moodDot)!!.groupValues[1]
        val thin = real.copy(moodDot = replaceOnce(real.moodDot, "const val MOOD_DOT_RING_DP = $width", "const val MOOD_DOT_RING_DP = 0"))
        assertAdded(listOf("the ring is less than 1 dp wide: 0"), ringSizeFindings(thin), before)
        val inside = real.copy(
            moodDot = replaceOnce(
                real.moodDot,
                "const val MOOD_DOT_MARK_DP = MOOD_DOT_DP + 2 * MOOD_DOT_RING_DP",
                "const val MOOD_DOT_MARK_DP = MOOD_DOT_DP",
            ),
        )
        assertAdded(
            listOf("the mark is not the whole dot with a ring on each side, so the ring eats the colour"),
            ringSizeFindings(inside),
            before,
        )
    }

    @Test
    fun `the ring's measure reproduces the faint dots the issue found, and the ring's own figures`() {
        val tokens = tokens(real.colors)
        val light = roles(real.theme, "LightPaperColors")
        val dark = roles(real.theme, "DarkPaperColors")
        val sheet = tokens.getValue(light.getValue("surface"))
        assertEquals("FCFAF5", sheet)
        // #412's own figures: Meh and Good on the light sheet, under 3:1. The ring is for them.
        assertEquals("2.32", ratio(contrast(tokens.getValue("MoodMeh"), sheet)))
        assertEquals("2.67", ratio(contrast(tokens.getValue("MoodGood"), sheet)))
        // The soft ink the ring is drawn in: on the sheet and the paper, light then dark.
        assertEquals(
            listOf("5.53", "5.04", "7.29", "7.99"),
            listOf(light, dark).flatMap { roles ->
                GROUNDS.map { ratio(contrast(tokens.getValue(roles.getValue("onSurfaceVariant")), tokens.getValue(roles.getValue(it)))) }
            },
        )
    }

    @Test
    fun `the mood colour check sees an unringed dot in each view, planted back`() {
        val before = moodColoursOutsideTheDot(real)
        val bare = "Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.moodColors.forLevel(3)))"

        val strip = real.copy(home = plantAfter(real.home, inStrip, "    $bare\n"))
        assertAdded(listOf("WeekGlance reads a mood colour: moodColors"), moodColoursOutsideTheDot(strip), before)

        val dots = real.copy(insights = plantAfter(real.insights, inDots, "        $bare\n"))
        assertAdded(listOf("MoodDots reads a mood colour: moodColors"), moodColoursOutsideTheDot(dots), before)

        // The old strip's way in, through the composition local, and the same in the week's row.
        val local = real.copy(home = plantAfter(real.home, inStrip, "    val moodColors = LocalMoodColors.current\n"))
        assertAdded(
            listOf("WeekGlance reads a mood colour: moodColors", "WeekGlance reads a mood colour: LocalMoodColors"),
            moodColoursOutsideTheDot(local),
            before,
        )
        val row = real.copy(insights = plantAfter(real.insights, inWeek, "        val c = LocalMoodColors.current\n"))
        assertAdded(listOf("WeekDays reads a mood colour: LocalMoodColors"), moodColoursOutsideTheDot(row), before)

        // Control: the legend under the week reads mood colours, and is not a day.
        assertTrue(weekBranch(real.insights).contains("MoodLegend("))
    }

    @Test
    fun `the order check sees the list and the dots run opposite ways, and a day re-ordered, planted back`() {
        // Every pairing of the list's direction and the dots', set outright, so this holds whatever
        // the real files say today: a mismatch is found exactly when the two differ.
        var mismatches = 0
        for (list in listOf("ASC", "DESC")) {
            for (model in listOf("ASC", "DESC")) {
                val t = real.copy(dao = daoRunning(real.dao, list), dayModel = modelRunning(real.dayModel, model))
                assertEquals("the list was not set to $list", list, dayListDirection(t.dao))
                assertEquals("the dots were not set to $model", model, modelDirection(t.dayModel))
                val expected = if (list == model) emptyList() else listOf("the day's own list runs $list by time, and its dots run $model")
                assertEquals("list $list, dots $model", expected, directionFindings(t))
                if (list != model) mismatches++
            }
        }
        assertEquals(2, mismatches)

        // A day re-ordered on its way to the screen, in each view, in a view model and in the day's list.
        val before = orderFindings(real)
        val dots = real.copy(insights = plantAfter(real.insights, inDots, "        val x = levels.asReversed()\n"))
        assertAdded(listOf("MoodDots re-orders a day: asReversed("), orderFindings(dots), before)
        val strip = real.copy(home = plantAfter(real.home, inStrip, "    val x = week.days.sortedDescending()\n"))
        assertAdded(listOf("WeekGlance re-orders a day: sortedDescending("), orderFindings(strip), before)
        val homeVm = real.copy(homeVm = replaceOnce(real.homeVm, "all.map { it.toDayEntry() }", "all.map { it.toDayEntry() }.asReversed()"))
        assertAdded(listOf("HomeViewModel's week re-orders a day: asReversed("), orderFindings(homeVm), before)
        val dayList = real.copy(dayScreen = replaceOnce(real.dayScreen, "items(entries, key", "items(entries.sortedBy { it.entry.dateTime }, key"))
        assertAdded(listOf("DayDetailScreen.kt re-orders a day: sortedBy {"), orderFindings(dayList), before)

        // Control: the check can see a sort, because the day model's own is one; the rule reads that
        // sort only to learn its direction.
        assertTrue(REORDER.containsMatchIn(codeOnly(real.dayModel)))
    }
}
