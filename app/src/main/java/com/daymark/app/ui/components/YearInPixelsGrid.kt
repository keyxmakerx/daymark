package com.daymark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daymark.app.stats.YearGrid
import com.daymark.app.ui.theme.moodColors
import com.daymark.app.ui.theme.moodLabels
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * A whole year as twelve small month calendars, three to a row, Monday first, so every day sits
 * under its weekday ([YearGrid]). A day is never averaged: a day of one mood is a whole square in
 * that mood's colour, and a day of more than one is split, its highest mood on the top half and its
 * lowest on the bottom. A day with no entry is a faint square, the same at every point in the year,
 * and a day still to come is left blank.
 */
@Composable
fun YearInPixelsGrid(
    year: Int,
    dayLevels: Map<LocalDate, List<Int>>,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val months = remember(year, dayLevels, today) { YearGrid.months(year, dayLevels, today) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        months.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { month -> MonthCalendar(month, Modifier.weight(1f)) }
            }
        }
        MoodLegendRow(modifier = Modifier.padding(top = 2.dp))
        Text(
            text = "A day of more than one mood shows its highest above its lowest.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MonthCalendar(month: YearGrid.Month, modifier: Modifier = Modifier) {
    val name = java.time.Month.of(month.month).getDisplayName(TextStyle.FULL, Locale.getDefault())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DayOfWeek.entries.forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val places = month.days + List((7 - month.days.size % 7) % 7) { YearGrid.Day.Pad }
        places.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day -> DaySquare(day, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DaySquare(day: YearGrid.Day, modifier: Modifier = Modifier) {
    val moods = MaterialTheme.moodColors
    val square = modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
    when (day) {
        YearGrid.Day.Pad, YearGrid.Day.ToCome -> Spacer(modifier.aspectRatio(1f))
        YearGrid.Day.Nothing -> Spacer(square.background(MaterialTheme.colorScheme.surfaceVariant))
        is YearGrid.Day.Moods -> if (day.isSplit) {
            Column(square) {
                Box(Modifier.weight(1f).fillMaxWidth().background(moods.forLevel(day.high)))
                Box(Modifier.weight(1f).fillMaxWidth().background(moods.forLevel(day.low)))
            }
        } else {
            Box(square.background(moods.forLevel(day.high)))
        }
    }
}

@Composable
private fun MoodLegendRow(modifier: Modifier = Modifier) {
    val moods = MaterialTheme.moodColors
    val labels = MaterialTheme.moodLabels
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        moods.ascending.forEachIndexed { index, color ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    modifier = Modifier
                        .width(11.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
                Text(
                    text = " ${labels.forLevel(index + 1)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
