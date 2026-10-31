package com.daymark.app.ui.trackers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * One tracker's check-in settings and nothing else, as its object in the Sky opens them (#539).
 *
 * The same card as on the tracker's own screen, so every change here is that same setting and is
 * undone the same way. No log, no average, and no count of answers or misses is on this screen:
 * from the Sky it shows settings only (SKY.md §6.3). The bar names no tracker, because this is
 * reached from the most identifying surface in the app (§6.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerCheckInsScreen(
    onBack: () -> Unit,
    viewModel: TrackerDetailViewModel = hiltViewModel(),
) {
    val tracker by viewModel.tracker.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check-in settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            tracker?.let { TrackerCheckInsCard(it, onChange = viewModel::update) }
        }
    }
}
