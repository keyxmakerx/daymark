package com.daymark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import java.time.LocalDate

/**
 * GitHub-style consistency grid: the last [weeks] weeks as columns of 7 days, each square shaded
 * by how many entries were logged that day. Distinct from the mood-tinted Year-in-Pixels — this is
 * purely about showing up, in a single accent hue.
 */
@Composable
fun ConsistencyHeatmap(
    entriesByDay: Map<LocalDate, Int>,
    modifier: Modifier = Modifier,
    weeks: Int = 16,
    today: LocalDate = LocalDate.now(),
) {
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    // Start from the Monday of the oldest shown week so columns line up by weekday.
    val end = today
    val start = end.minusWeeks((weeks - 1).toLong())
        .minusDays((end.minusWeeks((weeks - 1).toLong()).dayOfWeek.value - 1).toLong())
    val maxCount = (entriesByDay.values.maxOrNull() ?: 1).coerceAtLeast(1)

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        var col = start
        while (!col.isAfter(end)) {
            val weekStart = col
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in 0 until 7) {
                    val day = weekStart.plusDays(d.toLong())
                    val count = if (day.isAfter(end)) 0 else entriesByDay[day] ?: 0
                    val alpha = if (count == 0) 0f else (0.30f + 0.70f * (count.toFloat() / maxCount)).coerceAtMost(1f)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (alpha == 0f) empty else accent.copy(alpha = alpha)),
                    )
                }
            }
            col = col.plusWeeks(1)
        }
    }
}
