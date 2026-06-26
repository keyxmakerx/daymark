package com.daymark.app.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.ui.components.PaperSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenerScreen(
    screenerKey: String,
    onDone: () -> Unit,
    viewModel: ScreenerViewModel = hiltViewModel(),
) {
    val screener = remember(screenerKey) { SleepScreeners.byKey(screenerKey) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screener?.title ?: "Self-check") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (screener == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Unknown self-check.")
            }
            return@Scaffold
        }

        val answers = remember(screener) {
            mutableStateListOf<Int>().apply { repeat(screener.questions.size) { add(-1) } }
        }
        var showResult by remember(screener) { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!showResult) {
                Text(
                    screener.intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                screener.questions.forEachIndexed { qi, q ->
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(q.text, style = MaterialTheme.typography.titleSmall)
                            q.options.forEachIndexed { oi, opt ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { answers[qi] = oi }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = answers[qi] == oi, onClick = { answers[qi] = oi })
                                    Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                val allAnswered = answers.none { it < 0 }
                Button(
                    onClick = {
                        val score = screener.questions.indices.sumOf { qi -> screener.questions[qi].options[answers[qi]].points }
                        val band = screener.bandFor(score)
                        viewModel.save(screener.key, band.label)
                        showResult = true
                    },
                    enabled = allAnswered,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (allAnswered) "See result" else "Answer all questions")
                }
            } else {
                val score = screener.questions.indices.sumOf { qi -> screener.questions[qi].options[answers[qi]].points }
                val band = screener.bandFor(score)
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(band.label, style = MaterialTheme.typography.headlineSmall)
                        Text(band.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Not a diagnosis. Daymark is a general-wellness tool, not a medical device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                TextButton(onClick = { showResult = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Review answers")
                }
            }
        }
    }
}
