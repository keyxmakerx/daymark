package com.daymark.app.ui.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.CatalogActivity
import com.daymark.app.ui.icon.ActivityIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityLibraryScreen(
    onBack: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    viewModel: ActivityLibraryViewModel = hiltViewModel(),
) {
    val existing by viewModel.existingNames.collectAsStateWithLifecycle()
    // Selected suggestions, keyed by lower-cased name so it survives recomposition.
    val selected = remember { mutableStateMapOf<String, CatalogActivity>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Button(
                        onClick = {
                            val count = selected.size
                            viewModel.add(selected.values.toList())
                            selected.clear()
                            onShowMessage(if (count == 1) "Added 1 activity" else "Added $count activities")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text("Add ${selected.size}")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    "Tap to pick activities, then add them all at once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            viewModel.categories.forEach { category ->
                item(key = "h_${category.title}") {
                    Text(
                        category.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                item(key = "g_${category.title}") {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        category.items.forEach { activity ->
                            val keyL = activity.name.trim().lowercase()
                            val alreadyAdded = keyL in existing
                            val isSelected = keyL in selected
                            FilterChip(
                                selected = isSelected || alreadyAdded,
                                enabled = !alreadyAdded,
                                onClick = {
                                    if (isSelected) selected.remove(keyL) else selected[keyL] = activity
                                },
                                label = { Text(activity.name) },
                                leadingIcon = {
                                    Icon(
                                        painter = if (alreadyAdded) {
                                            androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Check)
                                        } else {
                                            painterResource(ActivityIcons.forKey(activity.iconKey))
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
