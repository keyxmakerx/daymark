package com.daymark.app.ui.sleep

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.data.SleepProfileStore
import com.daymark.app.ui.components.PaperSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepProfileScreen(
    onBack: () -> Unit,
    viewModel: SleepProfileViewModel = hiltViewModel(),
) {
    val initial = remember { viewModel.load() }
    var sharesBed by remember { mutableStateOf(initial.sharesBed) }
    var pets by remember { mutableStateOf(initial.petsNearBed) }
    var noise by remember { mutableStateOf(initial.noiseSource) }
    var placement by remember { mutableStateOf(initial.placement) }
    var position by remember { mutableStateOf(initial.position) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A few questions about your sleep setup. This just helps us read any future sleep " +
                    "signals honestly — for example, a shared bed or a pet means we can't be sure a " +
                    "snore or movement is yours.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("Do you share the bed with a partner?", sharesBed) { sharesBed = it }
            ToggleRow("Do pets sleep on or near the bed?", pets) { pets = it }
            ToggleRow("Is an AC, fan, or white-noise machine usually on?", noise) { noise = it }

            ChoiceRow("Where does your phone sit at night?", SleepProfileStore.PLACEMENTS, placement) { placement = it }
            ChoiceRow("How do you usually sleep?", SleepProfileStore.POSITIONS, position) { position = it }

            Button(
                onClick = {
                    viewModel.save(
                        SleepProfileStore.Profile(sharesBed, pets, placement, noise, position),
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        label = { Text(option) },
                    )
                }
            }
        }
    }
}
