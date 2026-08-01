package com.daymark.app.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.model.Mood
import com.daymark.app.stats.Greeting
import com.daymark.app.stats.Signals
import com.daymark.app.ui.components.CompactEntryRow
import com.daymark.app.ui.components.MoodFaceIcon
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.components.SwipeToDeleteRow
import com.daymark.app.ui.foryou.SelfRenderedSignalKinds
import com.daymark.app.ui.insights.SignalCards
import com.daymark.app.ui.insights.SignalDismissalSaver
import com.daymark.app.ui.insights.SignalsViewModel
import com.daymark.app.ui.insights.visibleSignalCount
import com.daymark.app.ui.theme.LocalMoodColors
import com.daymark.app.ui.theme.moodLabels
import com.daymark.app.util.DateUtils
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Home — the daily loop.
 *
 * This screen used to be the whole archive: every entry ever logged, grouped by day, scrolling
 * forever. That made the first thing you saw each day a wall of the past. Home now answers one
 * question ("how are you, right now?"), shows the day you're actually in, and points at the two
 * places worth going next — the rest of the suggestions, and the full history.
 *
 * Top to bottom: greeting and date · a one-tap check-in row · a small glance · at most **one**
 * suggestion card · today's entries · a link to everything else.
 */
@Composable
fun HomeScreen(
    onEntryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onQuickCheckIn: (Int) -> Unit = {},
    onSignalAction: (Signals.Action) -> Unit = {},
    onOpenForYou: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onDeleteEntry: (EntryWithActivities) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    signalsViewModel: SignalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val signals by signalsViewModel.signals.collectAsStateWithLifecycle()

    // Hoisted so the card slot is only emitted when something is actually visible — dismissing the
    // card then drops the slot cleanly instead of leaving a stray gap.
    var dismissed by rememberSaveable(stateSaver = SignalDismissalSaver) {
        mutableStateOf(emptySet<String>())
    }

    val today = LocalDate.now()
    // Only the top-ranked card lands here; "For you" holds the rest.
    val topCardVisible = visibleSignalCount(
        signals, Signals.Surface.Feed, dismissed, max = 1, exclude = SelfRenderedSignalKinds,
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Bottom padding clears the extended "Entry" FAB so it never sits on the exit links.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "greeting") {
            Column(Modifier.padding(bottom = 2.dp)) {
                Text(
                    text = Greeting.forHour(LocalTime.now().hour),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = DateUtils.formatWeekdayAndDay(today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        item(key = "check-in") {
            CheckInCard(onQuickCheckIn = onQuickCheckIn, modifier = Modifier.animateItem())
        }

        if (state.totalEntries == 0) {
            if (!state.loading) {
                item(key = "first-entry-hint") {
                    Text(
                        text = "Tap a face to log your first entry. Everything stays on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            return@LazyColumn
        }

        item(key = "glance") {
            GlanceRow(
                currentStreak = state.currentStreak,
                totalEntries = state.totalEntries,
                week = state.week,
                daysLogged = state.daysLoggedThisWeek,
                modifier = Modifier.animateItem(),
            )
        }

        if (topCardVisible > 0) {
            item(key = "signals") {
                SignalCards(
                    signals = signals,
                    onAction = onSignalAction,
                    dismissed = dismissed,
                    onDismiss = { dismissed = dismissed + it },
                    surface = Signals.Surface.Feed,
                    max = 1,
                    exclude = SelfRenderedSignalKinds,
                    header = null,
                    onControl = signalsViewModel::applyControl,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "today") {
            TodaySheet(
                entries = state.today,
                onEntryClick = onEntryClick,
                onDelete = onDeleteEntry,
                modifier = Modifier.animateItem(),
            )
        }

        // The two ways off Home, always in the same place so they're never a surprise: the rest of
        // the suggestions, and the whole archive.
        item(key = "exits") {
            Row(
                modifier = Modifier.fillMaxWidth().animateItem(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietLink(text = "More for you", onClick = onOpenForYou, modifier = Modifier.weight(1f))
                QuietLink(text = "All entries", onClick = onOpenHistory, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CheckInCard(
    onQuickCheckIn: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(
                text = "HOW ARE YOU, RIGHT NOW?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mood.ascending.forEach { mood ->
                    val label = MaterialTheme.moodLabels.forLevel(mood.level)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onQuickCheckIn(mood.level)
                            }
                            .semantics { contentDescription = "Check in: $label" }
                            .padding(6.dp),
                    ) {
                        MoodFaceIcon(level = mood.level, size = 44.dp)
                    }
                }
            }
        }
    }
}

/**
 * The glance: one number worth knowing, and the shape of the last week. Both are plain counts of
 * the person's own entries — nothing inferred, nothing interpreted.
 */
@Composable
private fun GlanceRow(
    currentStreak: Int,
    totalEntries: Int,
    week: List<Double?>,
    daysLogged: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlancePill(
            text = when {
                currentStreak > 1 -> "$currentStreak-day streak"
                currentStreak == 1 -> "1-day streak"
                totalEntries == 1 -> "1 entry"
                else -> "$totalEntries entries"
            },
            modifier = Modifier.weight(1f),
        )
        WeekGlance(week = week, daysLogged = daysLogged, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GlancePill(text: String, modifier: Modifier = Modifier) {
    PaperSurface(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 10.dp),
        )
    }
}

/**
 * The last seven days as seven small bars, oldest on the left. Bar height tracks that day's mean
 * mood and its colour comes from the person's own mood palette; an unlogged day is a faint stub,
 * not a gap — a week is allowed to have holes in it.
 */
@Composable
private fun WeekGlance(
    week: List<Double?>,
    daysLogged: Int,
    modifier: Modifier = Modifier,
) {
    val moodColors = LocalMoodColors.current
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val description = if (daysLogged == 1) {
        "Last 7 days: 1 day logged"
    } else {
        "Last 7 days: $daysLogged days logged"
    }
    PaperSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(WeekGlanceHeight + 20.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            week.forEach { mean ->
                // 1..5 maps onto a quarter..full bar, so a rough day still reads as a logged day.
                val fraction = mean?.let { (0.25f + ((it - 1.0) / 4.0).toFloat() * 0.75f) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (fraction == null) 3.dp else WeekGlanceHeight * fraction)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            if (mean == null) emptyColor else moodColors.forLevel(mean.roundToInt()),
                        ),
                )
            }
        }
    }
}

private val WeekGlanceHeight = 30.dp

/**
 * Today's entries — or a single quiet line when there aren't any yet. Kept as its own sheet so the
 * day you're in always has a place, even before you've logged.
 */
@Composable
private fun TodaySheet(
    entries: List<EntryWithActivities>,
    onEntryClick: (Long) -> Unit,
    onDelete: (EntryWithActivities) -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(
                text = "TODAY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    text = "Nothing logged today yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    key(entry.entry.id) {
                        SwipeToDeleteRow(
                            onDelete = { onDelete(entry) },
                            confirmTitle = "Delete this entry?",
                            confirmBody = "This removes today's entry. You'll get a moment to undo it.",
                            swipeLabel = "Delete entry",
                        ) {
                            CompactEntryRow(
                                entry = entry,
                                onClick = { onEntryClick(entry.entry.id) },
                            )
                        }
                    }
                    if (index < entries.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A low-key, full-width "go here next" row — the two exits off Home. */
@Composable
private fun QuietLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$text  ›",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
