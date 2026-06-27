package com.daymark.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.model.Mood
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import java.util.Locale

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.totalEntries == 0) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Log a few entries to see your stats.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Entries", state.totalEntries.toString(), Modifier.weight(1f))
                StatCard(
                    "Avg mood",
                    state.averageMood?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Current streak", "${state.currentStreak}d", Modifier.weight(1f))
                StatCard("Longest streak", "${state.longestStreak}d", Modifier.weight(1f))
            }
        }
        item { SectionCard("Mood over last 30 days") { TrendChart(state.trend) } }
        item { SectionCard("Mood distribution") { MoodDistribution(state.moodCounts) } }
        if (state.topActivities.isNotEmpty()) {
            item {
                SectionCard("Average mood by activity") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.topActivities.forEach { stat ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${stat.name} (${stat.count})")
                                Text(String.format(Locale.getDefault(), "%.1f", stat.averageMood))
                            }
                        }
                    }
                }
            }
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
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        val w = size.width
        val h = size.height
        // Mood levels 1..5 mapped vertically (5 at top).
        fun y(level: Double) = h - ((level - 1.0) / 4.0).toFloat() * h

        // Horizontal gridlines for each mood level.
        for (level in 1..5) {
            val gy = y(level.toDouble())
            drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }

        val points = trend.mapIndexedNotNull { index, value ->
            value?.let { Offset(index / 29f * w, y(it)) }
        }
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
            val barColor = com.daymark.app.ui.theme.LocalMoodColors.current.forLevel(mood.level)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.daymark.app.ui.components.MoodFaceIcon(level = mood.level, size = 22.dp)
                Box(modifier = Modifier.weight(1f).height(18.dp)) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
                        val barWidth = size.width * (count.toFloat() / max)
                        drawRoundRect(
                            color = barColor,
                            size = androidx.compose.ui.geometry.Size(barWidth.coerceAtLeast(2f), size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                        )
                    }
                }
                Text(count.toString(), color = Color.Unspecified)
            }
        }
    }
}
