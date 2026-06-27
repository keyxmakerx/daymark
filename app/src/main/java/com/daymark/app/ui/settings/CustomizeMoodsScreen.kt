package com.daymark.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface

/** A curated palette offered in the colour picker — earthy tones that fit the paper theme. */
private val PRESET_COLORS = listOf(
    0xFFAE5747, 0xFFB5654D, 0xFFC27C46, 0xFFCB8E3E, 0xFFC6A24E, 0xFFB7A95B,
    0xFF8FA268, 0xFF6F9A6A, 0xFF5E8A66, 0xFF4F8A7B, 0xFF5C7C99, 0xFF6E6CA8,
    0xFF9A6FA0, 0xFFB06A86, 0xFF8C7B6B, 0xFF6B655B,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeMoodsScreen(
    onBack: () -> Unit,
    viewModel: CustomizeMoodsViewModel = hiltViewModel(),
) {
    val levels by viewModel.levels.collectAsStateWithLifecycle()
    var pickerFor by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize moods") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::resetAll) { Text("Reset all") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Rename the five moods and recolour them to your liking. Your existing entries " +
                        "keep their place on the scale — this only changes how each level looks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(levels, key = { it.level }) { lvl ->
                MoodLevelRow(
                    ui = lvl,
                    onLabel = { viewModel.setLabel(lvl.level, it) },
                    onPickColor = { pickerFor = lvl.level },
                    onReset = { viewModel.resetLevel(lvl.level) },
                )
            }
        }
    }

    pickerFor?.let { level ->
        ColorPickerDialog(
            onDismiss = { pickerFor = null },
            onPick = { argb ->
                viewModel.setColor(level, argb)
                pickerFor = null
            },
        )
    }
}

@Composable
private fun MoodLevelRow(
    ui: MoodLevelUi,
    onLabel: (String) -> Unit,
    onPickColor: () -> Unit,
    onReset: () -> Unit,
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MoodFaceIcon(level = ui.level, size = 40.dp)
            OutlinedTextField(
                value = ui.label,
                onValueChange = onLabel,
                singleLine = true,
                modifier = Modifier.weight(1f),
                label = { Text("Level ${ui.level}") },
            )
            // Colour swatch — tap to pick.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPickColor() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(ui.colorArgb)))
            }
            if (ui.labelOverridden || ui.colorOverridden) {
                TextButton(onClick = onReset) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PRESET_COLORS.chunked(4).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowColors.forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                    .clickable { onPick(argb) },
                            )
                        }
                    }
                }
            }
        },
    )
}
