package com.daymark.app.ui.assessments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.AssessmentResult
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentScreen(
    assessmentKey: String,
    onDone: () -> Unit,
    onOpenSupport: () -> Unit,
    viewModel: AssessmentViewModel = hiltViewModel(),
) {
    val screener = remember(assessmentKey) { Assessments.byKey(assessmentKey) }
    val history by remember(assessmentKey) { viewModel.history(assessmentKey) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

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
        val maxScore = remember(screener) {
            screener.questions.sumOf { q -> q.options.maxOf { it.points } }
        }

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
                        viewModel.save(screener.key, score, screener.bandFor(score).label)
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
                // PHQ-9 self-harm item: surface support gently, never a risk verdict.
                val flagSelfHarm = screener.key == Assessments.PHQ9_KEY &&
                    answers.getOrElse(Assessments.PHQ9_SELF_HARM_INDEX) { 0 } > 0
                if (flagSelfHarm) {
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("You're not alone", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "You noted some thoughts of being better off dead or hurting yourself. " +
                                    "That can be really hard to sit with. If things feel heavy, support is available.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = onOpenSupport, modifier = Modifier.fillMaxWidth()) {
                                Text("See support options")
                            }
                        }
                    }
                }
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(band.label, style = MaterialTheme.typography.headlineSmall)
                        Text("Score: ${Assessments.displayScore(screener.key, score)}", style = MaterialTheme.typography.titleMedium)
                        Text(band.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (history.size >= 2) {
                    PaperSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Your trend", style = MaterialTheme.typography.titleSmall)
                            ScoreTrend(history.takeLast(12), maxScore)
                        }
                    }
                }
                if (screener.citation.isNotBlank()) {
                    Text(
                        screener.citation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/** Tiny bar chart of recent scores (oldest → newest), normalized to the questionnaire max. */
@Composable
private fun ScoreTrend(results: List<AssessmentResult>, maxScore: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        results.forEach { r ->
            val frac = if (maxScore > 0) (r.score.toFloat() / maxScore).coerceIn(0.04f, 1f) else 0.04f
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(frac).clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    DateUtils.formatDate(r.dateTime).takeLast(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
