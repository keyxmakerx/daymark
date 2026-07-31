package com.daymark.app.ui.foryou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.model.Mood
import com.daymark.app.stats.Signals
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.insights.SignalCards
import com.daymark.app.ui.insights.SignalDismissalSaver
import com.daymark.app.ui.insights.SignalsViewModel
import com.daymark.app.ui.insights.visibleSignalCount
import com.daymark.app.ui.theme.LocalDaymarkTextStyles
import com.daymark.app.util.DateUtils
import java.time.LocalDate

/**
 * "For you" — the full set of gentle, rules-based suggestions, plus the "on this day" memories
 * that used to crowd Home.
 *
 * Home carries at most one of these cards so the first screen stays quiet; this is where the rest
 * live, reached on purpose rather than pushed at you. Nothing here is generated: every card is a
 * fixed template from [Signals], and every one can be waved away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    onBack: () -> Unit,
    onSignalAction: (Signals.Action) -> Unit,
    onEntryClick: (Long) -> Unit,
    signalsViewModel: SignalsViewModel = hiltViewModel(),
    memoriesViewModel: MemoriesViewModel = hiltViewModel(),
) {
    val signals by signalsViewModel.signals.collectAsStateWithLifecycle()
    val memories by memoriesViewModel.memories.collectAsStateWithLifecycle()

    var dismissed by rememberSaveable(stateSaver = SignalDismissalSaver) {
        mutableStateOf(emptySet<String>())
    }

    val visible = visibleSignalCount(
        signals, Signals.Surface.Feed, dismissed, max = FeedWindow, exclude = SelfRenderedSignalKinds,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("For you", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Gentle suggestions — never nags",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (visible == 0 && memories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing to suggest right now. That's a fine place to be.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (visible > 0) {
                item(key = "signals") {
                    SignalCards(
                        signals = signals,
                        onAction = onSignalAction,
                        dismissed = dismissed,
                        onDismiss = { dismissed = dismissed + it },
                        surface = Signals.Surface.Feed,
                        max = FeedWindow,
                        exclude = SelfRenderedSignalKinds,
                        header = null,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (memories.isNotEmpty()) {
                item(key = "on-this-day") {
                    OnThisDayCard(memories, onEntryClick, modifier = Modifier.animateItem())
                }
            }
            item(key = "control-note") {
                Text(
                    text = "You're always in control — dismiss any card and it steps back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).animateItem(),
                )
            }
        }
    }
}

/** How many ranked feed cards this screen will show at once. */
private const val FeedWindow = 6

/**
 * Feed signals that Home and "For you" render in their own, richer form rather than as a generic
 * [SignalCards] card — so the same thing never appears twice on one screen.
 *
 *  - `prompt_log_today` **is** Home's check-in row ("How are you, right now?").
 *  - `on_this_day` is the memories card below, which shows several memories instead of one line.
 */
val SelfRenderedSignalKinds = setOf("prompt_log_today", "on_this_day")

@Composable
private fun OnThisDayCard(
    memories: List<EntryWithActivities>,
    onEntryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thisYear = LocalDate.now().year
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "ON THIS DAY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
            )
            memories.take(4).forEach { m ->
                val mood = Mood.fromLevel(m.entry.moodLevel)
                val yearsAgo = thisYear - DateUtils.toLocalDate(m.entry.dateTime).year
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEntryClick(m.entry.id) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    MoodFaceIcon(level = mood.level, size = 34.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (m.entry.note.isNotBlank()) {
                            Text(
                                "“${m.entry.note}”",
                                style = LocalDaymarkTextStyles.current.diaryNote,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
