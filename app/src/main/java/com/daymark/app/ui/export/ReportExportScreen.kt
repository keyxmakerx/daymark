package com.daymark.app.ui.export

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.export.PdfExportOptions
import com.daymark.app.ui.components.PaperSurface

/**
 * The PDF report, from "what goes in it" to the saved file, as one navigation destination.
 *
 * Settings' dialog chooses the range and the switches, then hands over here. Two steps follow:
 *
 *  1. **Your writing** ([JournalPickerScreen]), only when the person asked to include journal
 *     writing. Entries are picked one at a time or the whole range is taken, and the picker's
 *     [JournalSelection.Outcome] is copied onto the options by [withJournalChoice], the one place
 *     those three fields are set (#303).
 *  2. **The report itself** ([ReportPreviewViewModel]), page by page, as the clinician will see it,
 *     with Save (#198). Back from here returns to the picker, or to Settings, so what goes in can be
 *     changed before anything is written.
 *
 * Both view models are scoped to this destination, so leaving the flow forgets the ticks and
 * deletes the previewed file: the next report starts from nothing chosen, which is what
 * [JournalPickerScreen]'s KDoc asks of whoever hosts it.
 */
@Composable
fun ReportExportScreen(
    options: PdfExportOptions,
    pickWriting: Boolean,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    // Null until the picker is answered. Without the picker step the report carries no writing,
    // which is the options' own default.
    var writing by remember { mutableStateOf<JournalSelection.Outcome?>(null) }

    if (pickWriting && writing == null) {
        JournalPickerScreen(
            fromMillis = options.fromMillis,
            toMillis = options.toMillis,
            rangeLabel = options.rangeLabel,
            onBack = onBack,
            onConfirm = { writing = it },
        )
        return
    }

    val chosen = writing?.let { options.withJournalChoice(it) } ?: options
    ReportPreview(
        options = chosen,
        onBack = { if (pickWriting) writing = null else onBack() },
        onSaved = onSaved,
        onShowMessage = onShowMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportPreview(
    options: PdfExportOptions,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    viewModel: ReportPreviewViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(options) { viewModel.preview(options) }
    LaunchedEffect(Unit) {
        viewModel.saves.collect { if (it.saved) onSaved(it.message) else onShowMessage(it.message) }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let(viewModel::saveTo) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check your report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            // Opaque, for the same reason as the picker's bar: the pages must not scroll through it.
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    Button(
                        onClick = {
                            viewModel.prepareForFilePicker()
                            saveLauncher.launch("daymark-report.pdf")
                        },
                        enabled = state is ReportPreviewState.Ready,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    ) { Text("Save PDF") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "These are the pages exactly as they will be saved. Nothing is saved " +
                        "until you choose Save, and you can go back to change what goes in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (val s = state) {
                ReportPreviewState.Building -> item {
                    Text(
                        "Making the report…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReportPreviewState.Failed -> item {
                    Text(
                        "The report could not be made, so there is nothing to save. Go back and " +
                            "try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ReportPreviewState.Ready -> items(s.pageCount) { index ->
                    ReportPage(
                        index = index,
                        pageCount = s.pageCount,
                        aspect = options.paperSize.widthPt.toFloat() / options.paperSize.heightPt,
                        load = viewModel::page,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportPage(
    index: Int,
    pageCount: Int,
    aspect: Float,
    load: suspend (Int, Int) -> Bitmap?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(index, widthPx) { bitmap = load(index, widthPx) }
        val description = "Page ${index + 1} of $pageCount"
        val shown = bitmap
        if (shown == null) {
            // The sheet's own shape while it is drawn, so the list does not jump when it arrives.
            PaperSurface(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(description, style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
