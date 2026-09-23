package com.daymark.app.ui.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.BuildConfig
import com.daymark.app.stats.PhrasePool
import com.daymark.app.stats.RuleReadout
import com.daymark.app.stats.TimingGrid
import com.daymark.app.ui.theme.CardShape
import com.daymark.app.ui.theme.HairlineWidth
import com.daymark.app.ui.theme.ScreenPadding
import com.daymark.app.ui.theme.Spacing

/**
 * **"Why it asks" — the timing layer, shown to itself. Debug builds only.**
 *
 * `docs/FEATURES.md` §13.4: per feature, the rule, what it reads, its current values, whether it
 * would fire now and if not why, the offers made and how they were answered, how much the gate is
 * holding back, plus the hour × weekday grid and the phrase pool.
 *
 * ## Three gates, and why there are three
 *
 * `BuildConfig.DEBUG` is checked **here**, at the top of this composable, and again around the route
 * in `DaymarkAppScaffold` and around the entry point in `SettingsScreen`. Any one of them removed
 * and the screen is still unreachable in a release build. Compose has no route-level access control
 * to lean on, and this screen is the one place in the app where the decision engine's whole state is
 * laid out in one scroll, so it is worth spending three `if`s on rather than one.
 *
 * ## What it says, and what it will never say
 *
 * Everything drawn below is the **app's own behaviour**: how often it may speak, when it last spoke,
 * what became of that, which hours of the week it has been answered in. There is no mood, no trend,
 * no entry, no screener and no goal on this screen, and the "why not" is one of
 * [RuleReadout.Hold]'s five fixed sentences — a closed list, because a reason the engine could
 * compose is a reason that could one day be about the person.
 *
 * It follows that an unanswered hour is drawn as **an hour with no answer in it, and nothing else**.
 * Asleep, at work, out of battery and having a terrible week are indistinguishable to the engine on
 * purpose (`docs/DECISIONS.md` §D1a), so they are indistinguishable here: no red, no
 * warning, no empty-state sentence suggesting the person ought to have been there. A gap in
 * someone's data is never drawn as a failure.
 *
 * Nothing on this screen leaves the phone. `docs/FEATURES.md` §13.2 is explicit that the reception
 * ledger and the timing grid are never shared with a clinician, and the footer says so where a
 * person reading it will see it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTimingScreen(
    onBack: () -> Unit,
    viewModel: DebugTimingViewModel = hiltViewModel(),
) {
    // The second of the three gates. See the header: each one alone is enough, which is why there
    // are three.
    if (!BuildConfig.DEBUG) return

    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Why it asks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Re-read") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Everything the app uses to decide whether to speak, and what each feature " +
                    "would do right now. This describes the rules — it does not describe you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.md),
            )
            Text(
                text = "Read at " + clockLabel(state.hour) + " on " + weekdayName(state.weekday) +
                    " · " + state.zoneId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )

            Spacer(Modifier.height(Spacing.md))

            state.features.forEach { row ->
                FeatureCard(row)
                Spacer(Modifier.height(Spacing.md))
            }

            if (state.loaded) {
                ReadsCard(state.reads)
                Spacer(Modifier.height(Spacing.md))
                OpenersCard(state.band, state.openers)
                Spacer(Modifier.height(Spacing.md))
            }

            Text(
                text = "This stays on the phone. When you answer the app is the app's business " +
                    "with you — the ledger and the grid are never shared with a clinician, and " +
                    "nothing here goes into a backup, an export or a report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.xl),
            )
        }
    }
}

@Composable
private fun FeatureCard(row: DebugFeature) {
    val feature = row.feature
    PanelCard {
        Text(feature.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))

        // "Whether it would speak now and, if not, why" (`docs/FEATURES.md` §13.4). The second
        // line is always one of RuleReadout.Hold's five, never a sentence composed here: a "why
        // not" this screen could assemble is one that could one day be about the person, which is
        // why that list is closed and why nothing in this file adds a sixth reason to it.
        Text(
            text = if (feature.wouldAskNow) "It would ask now" else "It would not ask now",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = feature.hold.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = feature.rule,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.md))
        feature.values.forEach { value -> ValueRow(value) }

        if (row.declaredIsDefault) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "There is no setting for this one, so “your setting” above is the " +
                    "starting point the app uses until you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(Spacing.lg))
        SubHeading("Hours it may use")
        HoursStrip(feature.placement)
        Spacer(Modifier.height(Spacing.sm))
        // The strip and the grid below it share three fills but answer different questions, so each
        // says in words what its own fills mean. A colour that has to be remembered from another
        // section is a colour that states nothing.
        HoursStripLegend()

        Spacer(Modifier.height(Spacing.lg))
        SubHeading("Asks by hour and weekday")
        WeekGrid(feature.grid)
        Spacer(Modifier.height(Spacing.sm))
        GridLegend()
    }
}

@Composable
private fun ValueRow(value: RuleReadout.Value) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = value.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The 24 hours of the day, marked with where this feature's asks are allowed to land.
 *
 * A window, not a schedule — [TimingGrid.Placement.hours] rations nothing, the minimum gap does, so
 * a wide strip here never means more asks.
 */
@Composable
private fun HoursStrip(placement: TimingGrid.Placement) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Spacer(Modifier.width(WEEKDAY_LABEL_WIDTH))
            placement.readings.forEach { reading ->
                val standing = when {
                    reading.placed -> "an hour it may ask in"
                    reading.candidate -> "an hour it could use and is not using"
                    else -> "an hour it does not consider"
                }
                val fill = when {
                    reading.placed -> MaterialTheme.colorScheme.primary
                    reading.candidate -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
                Cell(fill = fill, description = clockLabel(reading.hour) + ", " + standing)
            }
        }
        HourRuler()
    }
}

/**
 * The hour × weekday grid: seven rows of twenty-four, every slot present whether or not anything
 * happened in it.
 *
 * A count of the app's asks and of how many were answered. Never a count of people, never a rate,
 * and there is no total anywhere on it — a number that went up or down would be a score, and a
 * score is the thing this product does not have.
 */
@Composable
private fun WeekGrid(grid: TimingGrid.Grid) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for (weekday in 1..TimingGrid.DAYS_IN_WEEK) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = weekdayName(weekday).take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(WEEKDAY_LABEL_WIDTH),
                )
                for (hour in 0 until TimingGrid.HOURS_IN_DAY) {
                    val cell = grid.cell(weekday, hour)
                    val asks = cell?.asks ?: 0
                    val answered = cell?.answered ?: 0
                    val fill = when {
                        asks == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        answered > 0 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                    }
                    val description = weekdayName(weekday) + " " + clockLabel(hour) + ", " +
                        askCountLabel(asks) + ", " + answered + " answered"
                    Cell(fill = fill, description = description)
                }
            }
        }
        HourRuler()
    }
}

/** The three fills the hours strip uses, said in words. */
@Composable
private fun HoursStripLegend() {
    Column {
        LegendRow(MaterialTheme.colorScheme.primary, "An hour this feature may ask in")
        LegendRow(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f),
            "An hour it could use and is not using",
        )
        LegendRow(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            "An hour it does not consider at all",
        )
    }
}

/** The three fills the grid uses, said in words, because a colour on its own states nothing. */
@Composable
private fun GridLegend() {
    Column {
        LegendRow(MaterialTheme.colorScheme.primary, "Asked here, and an answer came back")
        LegendRow(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f),
            "Asked here, no answer came back",
        )
        LegendRow(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            "Never asked here",
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "An hour with no answer in it is just that. The app moves its asking elsewhere " +
                "and never records a reason, because it has none: asleep, busy and a hard week " +
                "look the same from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun LegendRow(fill: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = Spacing.xxs),
    ) {
        Box(
            modifier = Modifier
                .size(CELL_SIZE)
                .background(fill, CELL_SHAPE)
                .border(HairlineWidth, MaterialTheme.colorScheme.outline, CELL_SHAPE),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadsCard(reads: List<String>) {
    PanelCard {
        Text("What the rule reads", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "All of it. If something is not on this list, the rule does not see it — there " +
                "is no mood, no entry, no goal and no screener anywhere in this decision.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        reads.forEach { line ->
            Text(
                text = "· " + line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.xxs),
            )
        }
    }
}

@Composable
private fun OpenersCard(band: PhrasePool.Band, openers: List<String>) {
    PanelCard {
        Text("The openers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        val why = when (band) {
            PhrasePool.Band.Morning -> "the day is still ahead, so these."
            PhrasePool.Band.Evening -> "the day is behind, so these."
        }
        Text(
            text = "Fixed lines, written by a person, rotated in order. Which pool is decided by " +
                "the clock alone — " + why +
                " Nothing about how you seemed reaches this choice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        openers.forEach { line ->
            Text(
                text = "“" + line + "”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.xxs),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Small pieces.
// -------------------------------------------------------------------------------------------

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) { content() }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
}

@Composable
private fun Cell(fill: Color, description: String) {
    Box(
        modifier = Modifier
            .padding(CELL_GAP)
            .size(CELL_SIZE)
            .background(fill, CELL_SHAPE)
            .semantics { contentDescription = description },
    )
}

/** 00, 06, 12, 18 under the columns they belong to, so a cell can be found without counting. */
@Composable
private fun HourRuler() {
    Row(modifier = Modifier.padding(top = Spacing.xxs)) {
        Spacer(Modifier.width(WEEKDAY_LABEL_WIDTH))
        for (hour in 0 until TimingGrid.HOURS_IN_DAY) {
            Box(
                modifier = Modifier
                    .padding(horizontal = CELL_GAP)
                    .width(CELL_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                if (hour % 6 == 0) {
                    Text(
                        text = twoDigits(hour),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

private val CELL_SIZE = 11.dp
private val CELL_GAP = 1.dp
private val CELL_SHAPE = RoundedCornerShape(2.dp)
private val WEEKDAY_LABEL_WIDTH = 30.dp

private fun twoDigits(value: Int): String = (if (value < 10) "0" else "") + value

private fun clockLabel(hour: Int): String = twoDigits(hour) + ":00"

private fun askCountLabel(asks: Int): String =
    if (asks == 1) "1 ask" else asks.toString() + " asks"

/**
 * Monday is 1 — `java.time.DayOfWeek.value`, which is what [TimingGrid] uses.
 *
 * Spelled out rather than taken from `DayOfWeek.getDisplayName`, which is locale-dependent and
 * would make the grid's row order and its labels disagree on a device whose week starts on Sunday.
 */
private fun weekdayName(weekday: Int): String = when (weekday) {
    1 -> "Monday"
    2 -> "Tuesday"
    3 -> "Wednesday"
    4 -> "Thursday"
    5 -> "Friday"
    6 -> "Saturday"
    7 -> "Sunday"
    else -> "—"
}
