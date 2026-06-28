package com.daymark.app.ui.achievements

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.PaperSurface
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    onBack: () -> Unit,
    viewModel: AchievementsViewModel = hiltViewModel(),
) {
    val items by viewModel.uiState.collectAsStateWithLifecycle()
    val unlocked = items.count { it.unlockedAt != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Achievements") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "$unlocked of ${items.size} earned — small markers for showing up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.achievement.id }) { ui ->
                    BadgeCard(ui)
                }
            }
        }
    }
}

@Composable
private fun BadgeCard(ui: AchievementUi) {
    val earned = ui.unlockedAt != null
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BadgeArt(earned = earned, modifier = Modifier.size(56.dp))
            Text(
                ui.achievement.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = if (earned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                ui.achievement.description,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Original hand-drawn badge: a star inside a ring, filled when earned, outlined when not. */
@Composable
private fun BadgeArt(earned: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier) {
        val color = if (earned) accent else muted
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ring = size.minDimension * 0.46f
        val stroke = size.minDimension * 0.06f
        if (earned) {
            drawCircle(color = color.copy(alpha = 0.15f), radius = ring, center = Offset(cx, cy))
        }
        drawCircle(color = color, radius = ring, center = Offset(cx, cy), style = Stroke(width = stroke))
        // Five-point star.
        val rOuter = ring * 0.62f
        val rInner = rOuter * 0.42f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) rOuter else rInner
            val angle = Math.toRadians((-90 + i * 36).toDouble())
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (earned) {
            drawPath(path, color = color)
        } else {
            drawPath(path, color = color, style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round))
        }
    }
}
