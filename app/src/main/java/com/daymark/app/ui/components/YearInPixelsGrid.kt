package com.daymark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.theme.moodColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A whole year as a grid of mood-coloured squares: one row per month, one cell per day.
 * Days without an entry are shown as faint cells; impossible days (e.g. Feb 30) are blank pads.
 */
@Composable
fun YearInPixelsGrid(
    year: Int,
    dayMoods: Map<LocalDate, Double>,
    modifier: Modifier = Modifier,
) {
    val moods = MaterialTheme.moodColors
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (month in 1..12) {
            val length = YearMonth.of(year, month).lengthOfMonth()
            val label = java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.width(30.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (day in 1..31) {
                        if (day <= length) {
                            val avg = dayMoods[LocalDate.of(year, month, day)]
                            val color = if (avg != null) {
                                moods.forLevel(avg.roundToInt().coerceIn(1, 5))
                            } else {
                                empty
                            }
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(color),
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        MoodLegendRow(modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun MoodLegendRow(modifier: Modifier = Modifier) {
    val moods = MaterialTheme.moodColors
    val labels = listOf("Awful", "Bad", "Meh", "Good", "Rad")
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
                    text = " ${labels[index]}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
