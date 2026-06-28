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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.theme.moodColors
import com.daymark.app.ui.theme.moodLabels
import com.daymark.app.model.Mood
import com.daymark.app.ui.calendar.CalendarViewModel
import com.daymark.app.ui.calendar.YearPixelsViewModel
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.YearInPixelsGrid
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
    statsViewModel: StatsViewModel = hiltViewModel(),
    calendarViewModel: CalendarViewModel = hiltViewModel(),
    yearViewModel: YearPixelsViewModel = hiltViewModel(),
    extrasViewModel: InsightsExtrasViewModel = hiltViewModel(),
) {
    val stats by statsViewModel.uiState.collectAsStateWithLifecycle()
    val calendar by calendarViewModel.uiState.collectAsStateWithLifecycle()
    val year by yearViewModel.uiState.collectAsStateWithLifecycle()
    val extras by extrasViewModel.uiState.collectAsStateWithLifecycle()
    var scope by remember { mutableStateOf(Scope.Month) }

    if (stats.totalEntries == 0) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Log a few entries to see your insights.", style = MaterialTheme.typography.bodyLarge)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Current streak", "${stats.currentStreak}d", Modifier.weight(1f))
            StatCard("Longest streak", "${stats.longestStreak}d", Modifier.weight(1f))
        }

        // Period view
        when (scope) {
            Scope.Week -> PaperSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("This week", style = MaterialTheme.typography.titleMedium)
                    WeekBars(stats.trend, modifier = Modifier.padding(top = 12.dp))
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
                    YearInPixelsGrid(
                        year = year.year,
                        dayMoods = year.dayMoods,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
            Text("Not enough entries yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun MonthGrid(month: java.time.YearMonth, dayMoods: Map<LocalDate, Double>, onDayClick: (LocalDate) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            DayOfWeek.entries.forEach { dow ->
                Text(
                    dow.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).take(2),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val leadingPad = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val days: List<LocalDate?> =
            List(leadingPad) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        val padded = days + List((7 - days.size % 7) % 7) { null }
        padded.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f)) {
                        if (date != null) DayCell(date, dayMoods[date], onClick = { onDayClick(date) })
                    }
                }
            }
        }
    }
}

/** Last 7 days of average mood as simple bars. */
@Composable
private fun WeekBars(trend: List<Double?>, modifier: Modifier = Modifier) {
    val week = trend.takeLast(7)
    val today = LocalDate.now()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        week.forEachIndexed { i, v ->
            val date = today.minusDays((week.size - 1 - i).toLong())
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.height(110.dp).width(26.dp), contentAlignment = Alignment.BottomCenter) {
                    val frac = v?.let { ((it - 1.0) / 4.0).toFloat().coerceIn(0.06f, 1f) } ?: 0.05f
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(frac).clip(RoundedCornerShape(6.dp))
                            .background(v?.let { moodColor(it, MaterialTheme.moodColors) } ?: MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Text(
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).take(1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, moodLevel: Double?, onClick: () -> Unit) {
    val hasMood = moodLevel != null
    val fill = if (hasMood) moodColor(moodLevel!!, MaterialTheme.moodColors) else MaterialTheme.colorScheme.surfaceVariant
    val isToday = date == LocalDate.now()
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onClick() }
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(shape)
                .background(fill)
                .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = if (hasMood) Color.White else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    PaperSurface(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.tertiary,
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
                color = MaterialTheme.colorScheme.tertiary,
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

private fun moodColor(level: Double, colors: com.daymark.app.ui.theme.MoodColors): Color {
    val lower = level.toInt().coerceIn(1, 5)
    val upper = (lower + 1).coerceAtMost(5)
    val t = (level - lower).toFloat().coerceIn(0f, 1f)
    val a = colors.forLevel(lower)
    val b = colors.forLevel(upper)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}
