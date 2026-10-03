package com.daymark.app.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.theme.moodColors
import com.daymark.app.ui.theme.moodLabels
import com.daymark.app.model.Mood
import com.daymark.app.ui.calendar.CalendarDays
import com.daymark.app.ui.calendar.CalendarViewModel
import com.daymark.app.ui.calendar.YearPixelsViewModel
import com.daymark.app.ui.components.MOOD_DOT_MARK_DP
import com.daymark.app.ui.components.MoodDot
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.StarsLegend
import com.daymark.app.ui.components.YearInPixelsGrid
import com.daymark.app.ui.components.YearInStarsGrid
import com.daymark.app.ui.stats.StatsViewModel
import com.daymark.app.util.DateUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

private enum class Scope { Week, Month, Year }

/**
 * Unified "Insights" tab — merges the former Stats, Calendar and Year-in-Pixels screens.
 * A time-scale toggle switches the period view (month grid vs. year pixels) while the
 * summary stats and mood charts stay below. Reuses the existing view-models unchanged.
 */
@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
    onSignalAction: (com.daymark.app.stats.Signals.Action) -> Unit = {},
    onReviewYear: (Int) -> Unit = {},
    statsViewModel: StatsViewModel = hiltViewModel(),
    calendarViewModel: CalendarViewModel = hiltViewModel(),
    yearViewModel: YearPixelsViewModel = hiltViewModel(),
    extrasViewModel: InsightsExtrasViewModel = hiltViewModel(),
    signalsViewModel: SignalsViewModel = hiltViewModel(),
) {
    val stats by statsViewModel.uiState.collectAsStateWithLifecycle()
    val calendar by calendarViewModel.uiState.collectAsStateWithLifecycle()
    val year by yearViewModel.uiState.collectAsStateWithLifecycle()
    val extras by extrasViewModel.uiState.collectAsStateWithLifecycle()
    val signals by signalsViewModel.signals.collectAsStateWithLifecycle()
    var insightsDismissed by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = SignalDismissalSaver,
    ) { mutableStateOf(emptySet<String>()) }
    var scope by remember { mutableStateOf(Scope.Month) }
    var yearStars by remember { mutableStateOf(true) }

    if (stats.totalEntries == 0) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No entries. Insights are drawn from entries.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // "For you" — the ranked Signals (rules-based, no AI), most relevant first. Skipped entirely
        // (no empty slot/spacing) once every card has been dismissed.
        if (visibleSignalCount(signals, com.daymark.app.stats.Signals.Surface.Insights, insightsDismissed) > 0) {
            SignalCards(
                signals = signals,
                onAction = onSignalAction,
                dismissed = insightsDismissed,
                onDismiss = { insightsDismissed = insightsDismissed + it },
                onControl = signalsViewModel::applyControl,
            )
        }

        // Time-scale toggle
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Scope.entries.forEachIndexed { index, s ->
                SegmentedButton(
                    selected = scope == s,
                    onClick = { scope = s },
                    shape = SegmentedButtonDefaults.itemShape(index, Scope.entries.size),
                ) { Text(s.name) }
            }
        }

        // Summary stats
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Entries", stats.totalEntries.toString(), Modifier.weight(1f))
            StatCard(
                "Avg mood",
                stats.averageMood?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–",
                Modifier.weight(1f),
            )
        }
        // One card where there were two streaks, and none at all at zero. The label says what is
        // being counted and the value carries its own denominator, so the number cannot be read as
        // a run that is currently alive. At zero there is nothing honest to draw: "0 of the last
        // 30" is a gap rendered as a nought, which is the one thing this product does not do to a
        // person who has been away.
        if (stats.daysWithEntryLast30 > 0) {
            StatCard(
                "Days with an entry",
                "${stats.daysWithEntryLast30} of the last ${com.daymark.app.stats.MoodStats.WINDOW_DAYS}",
                Modifier.fillMaxWidth(),
            )
        }

        // Period view
        when (scope) {
            Scope.Week -> PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("This week", style = MaterialTheme.typography.titleMedium)
                    WeekDays(stats.week, onDayClick, modifier = Modifier.padding(top = 12.dp))
                    MoodLegend(modifier = Modifier.padding(top = 14.dp))
                }
            }
            Scope.Month -> PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    PeriodHeader(
                        label = DateUtils.formatMonthYear(calendar.month.atDay(1)),
                        onPrev = calendarViewModel::previousMonth,
                        onNext = calendarViewModel::nextMonth,
                    )
                    MonthGrid(calendar.month, calendar.dayMoods, onDayClick)
                    MoodLegend(modifier = Modifier.padding(top = 14.dp))
                }
            }
            Scope.Year -> PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    PeriodHeader(
                        label = year.year.toString(),
                        onPrev = yearViewModel::previousYear,
                        onNext = yearViewModel::nextYear,
                    )
                    StarsGridToggle(
                        starsSelected = yearStars,
                        onSelect = { yearStars = it },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    if (yearStars) {
                        YearInStarsGrid(
                            year = year.year,
                            dayMoods = year.dayMoods,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        StarsLegend(modifier = Modifier.padding(top = 8.dp))
                    } else {
                        YearInPixelsGrid(
                            year = year.year,
                            dayMoods = year.dayMoods,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Button(
                        onClick = { onReviewYear(year.year) },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    ) { Text("Review my year") }
                }
            }
        }

        // Charts (always shown)
        SectionCard("Mood over last 30 days") { TrendChart(stats.trend) }
        SectionCard("Mood distribution") { MoodDistribution(stats.moodCounts) }
        if (stats.topActivities.isNotEmpty()) {
            SectionCard("Average mood by activity") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    stats.topActivities.forEach { stat ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${stat.name} (${stat.count})")
                            Text(String.format(Locale.getDefault(), "%.1f", stat.averageMood))
                        }
                    }
                }
            }
        }

        // --- Period in review + consistency ---
        if (extras.review.isNotBlank()) {
            SectionCard("In review") {
                Text(extras.review, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (extras.entriesByDay.isNotEmpty()) {
            SectionCard("Logging consistency") {
                com.daymark.app.ui.components.ConsistencyHeatmap(extras.entriesByDay)
            }
        }

        // --- Correlations & patterns (associations, not causes) ---
        val periodCompare = when (scope) {
            Scope.Week -> extras.weekCompare
            Scope.Month -> extras.monthCompare
            Scope.Year -> extras.yearCompare
        }
        periodCompare?.let { PeriodCompareCard(scope.name.lowercase(Locale.getDefault()), it) }

        if (extras.topUp.isNotEmpty() || extras.topDown.isNotEmpty()) {
            SectionCard("What goes with your mood") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Things logged alongside higher or lower moods. This shows association, not cause.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (extras.topUp.isNotEmpty()) {
                        FactorList("Lifts you up", extras.topUp, MaterialTheme.moodColors.forLevel(5))
                    }
                    if (extras.topDown.isNotEmpty()) {
                        FactorList("Weighs you down", extras.topDown, MaterialTheme.moodColors.forLevel(1))
                    }
                    extras.trackerCorrelations.takeIf { it.isNotEmpty() }?.let { corrs ->
                        Text("Trackers", style = MaterialTheme.typography.labelLarge)
                        corrs.forEach { c ->
                            val dir = if (c.r >= 0) "higher mood" else "lower mood"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${c.name} (${c.n}d)")
                                Text("$dir  r=${String.format(Locale.getDefault(), "%+.2f", c.r)}")
                            }
                        }
                    }
                }
            }
        }

        if (extras.dayOfWeek.isNotEmpty()) {
            SectionCard("By day of week") {
                LabeledMoodBars(
                    DayOfWeek.entries.mapNotNull { dow ->
                        extras.dayOfWeek[dow]?.let {
                            dow.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) to it
                        }
                    },
                )
            }
        }

        if (extras.timeOfDay.isNotEmpty()) {
            SectionCard("By time of day") {
                LabeledMoodBars(
                    com.daymark.app.stats.MoodPatterns.TimeBucket.entries.mapNotNull { b ->
                        extras.timeOfDay[b]?.let { b.label to it }
                    },
                )
            }
        }
    }
}

@Composable
private fun PeriodCompareCard(periodName: String, c: com.daymark.app.stats.MoodPatterns.PeriodComparison) {
    SectionCard("This $periodName vs last") {
        val cur = c.currentAvg
        if (cur == null) {
            Text("Not enough entries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Avg mood ${String.format(Locale.getDefault(), "%.1f", cur)} (${c.currentCount} entries)")
            c.deltaPct?.let { pct ->
                val up = pct >= 0
                Text(
                    "${if (up) "▲" else "▼"} ${String.format(Locale.getDefault(), "%.0f", kotlin.math.abs(pct))}%",
                    color = if (up) MaterialTheme.moodColors.forLevel(5) else MaterialTheme.moodColors.forLevel(2),
                    fontWeight = FontWeight.SemiBold,
                )
            } ?: Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FactorList(title: String, rows: List<FactorRow>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = accent)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${r.name} (${r.n})")
                Text(String.format(Locale.getDefault(), "%+.1f", r.delta), color = accent)
            }
        }
    }
}

/** Simple horizontal bars for mood values on the 1–5 scale. */
@Composable
private fun LabeledMoodBars(values: List<Pair<String, Double>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { (label, mood) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).height(14.dp)) {
                    val frac = ((mood - 1.0) / 4.0).toFloat().coerceIn(0.04f, 1f)
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.moodColors.forLevel(mood.toInt().coerceIn(1, 5))),
                    )
                }
                Text(String.format(Locale.getDefault(), "%.1f", mood), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Compact toggle between the night-sky "Stars" view and the dense "Grid" view of the year. */
@Composable
private fun StarsGridToggle(starsSelected: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        FilterChip(selected = starsSelected, onClick = { onSelect(true) }, label = { Text("Stars") })
        FilterChip(selected = !starsSelected, onClick = { onSelect(false) }, label = { Text("Grid") })
    }
}

@Composable
private fun PeriodHeader(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
        }
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
        }
    }
}

/** Non-lazy month grid (safe inside a scrolling Column, unlike LazyVerticalGrid). */
@Composable
private fun MonthGrid(month: java.time.YearMonth, dayMoods: Map<LocalDate, List<Int>>, onDayClick: (LocalDate) -> Unit) {
    Column {
        WeekdayHeader(DayOfWeek.entries)
        val leadingPad = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val days: List<LocalDate?> =
            List(leadingPad) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        val padded = days + List((7 - days.size % 7) % 7) { null }
        padded.chunked(7).forEach { week -> DayRow(week, dayMoods, onDayClick) }
    }
}

/**
 * Insights → Week (#411): the last seven days, oldest on the left and today on the right, drawn as
 * one row of the month. Every day is a [DayCell], so a day here keeps the month's rule: the same
 * paper square with its number in ink, one dot per entry in that entry's own mood colour, newest
 * first, at most [CalendarDays.MAX_DOTS], and a day with no entry is the same square with no dots.
 * Nothing averages a day, blends two moods, or sizes anything by what a day holds, so there are no
 * bars. Tapping a day opens its entries, as in the month, and a screen reader hears each day as the
 * month says it.
 */
@Composable
private fun WeekDays(week: CalendarDays.Week, onDayClick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        WeekdayHeader(week.days.map { it.dayOfWeek })
        DayRow(week.days, week.moods, onDayClick)
    }
}

/** The weekday names over a row of days, in the order the days run. */
@Composable
private fun WeekdayHeader(days: List<DayOfWeek>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        days.forEach { dow ->
            Text(
                dow.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).take(2),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One row of days, a [DayCell] each, in equal columns: a week of the month, or Insights → Week. A
 * null is an empty place before the first of a month or after its last.
 */
@Composable
private fun DayRow(dates: List<LocalDate?>, dayMoods: Map<LocalDate, List<Int>>, onDayClick: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        dates.forEach { date ->
            Box(Modifier.weight(1f)) {
                if (date != null) DayCell(date, dayMoods[date].orEmpty(), onClick = { onDayClick(date) })
            }
        }
    }
}

/**
 * One day of the month (#397), and of Insights → Week (#411): the same paper square for every day,
 * its number in the full ink, and one dot per entry beneath the number ([MoodDots]), newest first,
 * as the day's own list runs (#412). Nothing fills the square, so no day is ever a mood colour, a
 * blend of two, or dimmer than another; a day with no entry is this same square with no dots. The
 * number is onSurface on the sheet on every day: 14.26:1 light, 12.66:1 dark. Today keeps its ink
 * ring and bold number, which are structure and never a mood colour.
 *
 * A screen reader hears [CalendarDays.description] in place of the number: the date, then the mood
 * of each dot in the person's own words, or "nothing recorded".
 */
@Composable
private fun DayCell(date: LocalDate, moods: List<Int>, onClick: () -> Unit) {
    val isToday = date == LocalDate.now()
    val shape = RoundedCornerShape(11.dp)
    val labels = MaterialTheme.moodLabels
    val description = CalendarDays.description(date, moods, isToday, { labels.forLevel(it) }, Locale.getDefault())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onClick() }
            .semantics { contentDescription = description }
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The number and the dots say nothing of their own: the description above says both, and a
        // day is never read as a lone number, an average or a count.
        Box(
            modifier = Modifier
                .width(38.dp)
                .heightIn(min = 38.dp)
                .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MoodDots(CalendarDays.dots(moods), modifier = Modifier.padding(top = DOT_GAP_DP.dp))
            }
        }
    }
}

/**
 * A day's dots, three to a row, in the order given: one [MoodDot] per entry, in that entry's own mood
 * colour as the person has it, inside the soft-ink ring that keeps it visible on the sheet whatever
 * the colour (#412), beneath the number and never behind it. A day in the month or the week reads
 * a mood colour nowhere else, and only through [MoodDot], one entry's level at a time. The rows keep
 * the same height on every day, with dots or without, so every number sits at the same place and an
 * empty day is not a shorter one.
 */
@Composable
private fun MoodDots(levels: List<Int>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.height(DOT_AREA_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DOT_GAP_DP.dp),
    ) {
        levels.chunked(DOTS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(DOT_GAP_DP.dp)) {
                row.forEach { level -> MoodDot(level) }
            }
        }
    }
}

// Three ringed dots and their gaps are 28 dp, inside the 38 dp square of the narrowest cell.
private const val DOT_GAP_DP = 2
private const val DOTS_PER_ROW = 3
private const val DOT_ROWS = (CalendarDays.MAX_DOTS + DOTS_PER_ROW - 1) / DOTS_PER_ROW
private const val DOT_AREA_DP = DOT_ROWS * MOOD_DOT_MARK_DP + (DOT_ROWS - 1) * DOT_GAP_DP

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    PaperSurface(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.padding(top = 14.dp)) { content() }
        }
    }
}

@Composable
private fun TrendChart(trend: List<Double?>) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        val w = size.width
        val h = size.height
        fun y(level: Double) = h - ((level - 1.0) / 4.0).toFloat() * h
        for (level in 1..5) {
            val gy = y(level.toDouble())
            drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }
        val points = trend.mapIndexedNotNull { index, value -> value?.let { Offset(index / 29f * w, y(it)) } }
        if (points.size >= 2) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, primary, style = Stroke(width = 4f))
        }
        points.forEach { drawCircle(primary, radius = 5f, center = it) }
    }
}

@Composable
private fun MoodDistribution(counts: Map<Int, Int>) {
    val max = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Mood.ascending.reversed().forEach { mood ->
            val count = counts[mood.level] ?: 0
            val barColor = MaterialTheme.moodColors.forLevel(mood.level)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoodFaceIcon(level = mood.level, size = 22.dp)
                Box(modifier = Modifier.weight(1f).height(18.dp)) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
                        val barWidth = size.width * (count.toFloat() / max)
                        drawRoundRect(
                            color = barColor,
                            size = Size(barWidth.coerceAtLeast(2f), size.height),
                            cornerRadius = CornerRadius(8f, 8f),
                        )
                    }
                }
                Text(count.toString())
            }
        }
    }
}

@Composable
private fun MoodLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Mood.ascending.forEach { mood ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.moodColors.forLevel(mood.level)))
                Text(" ${MaterialTheme.moodLabels.forLevel(mood.level)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
