package com.daylie.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daylie.app.model.Mood
import com.daylie.app.util.DateUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A calendar cell is either a blank leading pad or an actual day. */
private sealed interface Cell {
    data object Empty : Cell
    data class Day(val date: LocalDate, val moodLevel: Double?) : Cell
}

@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        // Month header with navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = viewModel::previousMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = DateUtils.formatMonthYear(state.month.atDay(1)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = viewModel::nextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        // Weekday headers (Mon..Sun)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            DayOfWeek.entries.forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val cells = buildCells(state)
        LazyVerticalGrid(columns = GridCells.Fixed(7)) {
            items(cells.size) { index ->
                when (val cell = cells[index]) {
                    is Cell.Empty -> Box(Modifier.aspectRatio(1f))
                    is Cell.Day -> DayCell(cell)
                }
            }
        }

        MoodLegend(modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun DayCell(cell: Cell.Day) {
    val hasMood = cell.moodLevel != null
    val fill = if (hasMood) moodColor(cell.moodLevel!!) else MaterialTheme.colorScheme.surfaceVariant
    val isToday = cell.date == LocalDate.now()
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(fill)
                .then(
                    if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = if (hasMood) Color.White else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun MoodLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Mood.ascending.forEach { mood ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(mood.color),
                )
                Text(
                    text = " ${mood.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Linearly interpolate between adjacent mood colors for fractional averages. */
private fun moodColor(level: Double): Color {
    val lower = level.toInt().coerceIn(1, 5)
    val upper = (lower + 1).coerceAtMost(5)
    val t = (level - lower).toFloat().coerceIn(0f, 1f)
    val a = Mood.fromLevel(lower).color
    val b = Mood.fromLevel(upper).color
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}

private fun buildCells(state: CalendarUiState): List<Cell> {
    val firstOfMonth = state.month.atDay(1)
    // Monday-first grid: DayOfWeek.MONDAY.value == 1
    val leadingPad = firstOfMonth.dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = mutableListOf<Cell>()
    repeat(leadingPad) { cells.add(Cell.Empty) }
    for (day in 1..state.month.lengthOfMonth()) {
        val date = state.month.atDay(day)
        cells.add(Cell.Day(date, state.dayMoods[date]))
    }
    return cells
}
