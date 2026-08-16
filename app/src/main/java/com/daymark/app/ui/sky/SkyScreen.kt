package com.daymark.app.ui.sky

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyListItem
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyPalette
import com.daymark.app.ui.theme.moodColors
import com.daymark.app.ui.theme.moodLabels
import java.util.Locale

/**
 * "Your sky" — the surface, its two presentations, and its controls.
 *
 * The picture and the list are **peers** (§7.5), not a view and a fallback. Same data, same
 * actions, one toggle between them, and the toggle is in the top bar rather than hidden in a menu.
 * The honest part of that claim is written in the design and is worth repeating here: they are equal
 * in information and they are not the same experience, because nobody sits and looks at a list. The
 * project has no better answer than giving both the same design attention and shipping them
 * together.
 *
 * ## What this screen refuses to say
 *
 * There is no total, no count of days, no coverage figure, no streak, no "since you last opened",
 * no superlative and no comparison of any period against any other. The only number anywhere on it
 * is the item count on a month heading in the list, which is there because a list without list
 * semantics cannot be navigated (§6.3).
 *
 * The empty state is the case worth reading twice. A new install draws the field, one line, and
 * nothing else: no error, no fabricated sky, no illustration of a sky someone else made, and above
 * all no invitation to start logging. `docs/DECISIONS_2026-08.md` is unambiguous that nothing here
 * shames, and "you haven't written anything yet" is a scoreboard with one entry on it. The sky is
 * a sky that nothing of theirs is in yet, which is true and is allowed to be said plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyScreen(
    onBack: () -> Unit,
    onOpenRecord: (SkyKind, Long) -> Unit,
    onOpenLifeEvents: () -> Unit,
    viewModel: SkyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale: Locale = Locale.getDefault()

    // The platform preferences are read once as *defaults*. §7.1 is explicit that they must stay
    // independently toggleable, because the platform signal is coarse — "I turn animations off to
    // save battery" and "motion makes me ill" arrive here as the same bit.
    val platformReducedMotion = remember(context) { prefersReducedMotion(context) }
    val platformHighContrast = remember(context) { prefersHighContrast(context) }

    var fieldEnabled by rememberSaveable { mutableStateOf(!platformHighContrast) }
    var motionEnabled by rememberSaveable { mutableStateOf(!platformReducedMotion) }
    var highContrast by rememberSaveable { mutableStateOf(platformHighContrast) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(NO_SELECTION) }
    var topMonth by remember { mutableStateOf<Int?>(null) }

    val options = SkyOptions(
        fieldEnabled = fieldEnabled,
        motionEnabled = motionEnabled,
        highContrast = highContrast,
    )

    val moods = MaterialTheme.moodColors
    val labels = MaterialTheme.moodLabels
    // The person's live palette, equalised — never a hardcoded hue, which is why nothing in `sky/`
    // contains one (§3.2). Equalisation and not lifting-to-the-floor: the raw ramp draws the worst
    // mood faintest and the middle of the ramp loudest, which is a verdict about a person's months
    // arrived at by way of a palette. See SkyPalette.
    val ramp = remember(moods, highContrast) {
        val target =
            if (highContrast) SkyPresentation.HIGH_CONTRAST_TARGET else SkyPalette.STAR_CONTRAST_TARGET
        SkyPalette.equalisedRamp(
            IntArray(5) { moods.forLevel(it + 1).toArgb() and 0xFFFFFF },
            target = target,
        )
    }

    val layout = state.layout
    val moodLabel: (Int) -> String = { labels.forLevel(it) }

    // A star is addressed by its index into the packed arrays, and a new read produces new arrays —
    // so a selection held across a change would point at a different star and put someone else's
    // Tuesday in the detail strip. Cleared rather than remapped: a person who was looking at a
    // record while it changed underneath them is better served by the sky than by a guess.
    LaunchedEffect(layout) { selected = NO_SELECTION }

    fun activate(index: Int) {
        val kind = layout.kindAt(index)
        when {
            SkyPresentation.opensRecord(kind) -> onOpenRecord(kind, layout.recordIds[layout.idStart[index]])
            kind == SkyKind.LIFE_EVENT -> onOpenLifeEvents()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your sky") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // A word rather than an icon: this toggle swaps between two presentations of
                    // the same thing, and there is no glyph for that anyone reads correctly.
                    TextButton(onClick = { showList = !showList }) {
                        Text(if (showList) "Sky" else "List")
                    }
                },
            )
        },
        bottomBar = {
            SkyControls(
                showingList = showList,
                fieldEnabled = fieldEnabled,
                motionEnabled = motionEnabled,
                highContrast = highContrast,
                onField = { fieldEnabled = it },
                onMotion = { motionEnabled = it },
                onQuietSky = {
                    // One tap, because §7.1 asks for one: the low-vision presentation is the field
                    // off and every glyph at maximum contrast, and someone who needs it should not
                    // have to assemble it from two switches. Both halves stay independently
                    // toggleable above.
                    highContrast = it
                    fieldEnabled = !it
                },
                onLifeEvents = onOpenLifeEvents,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(if (showList) MaterialTheme.colorScheme.background else SkyNightBg),
        ) {
            when {
                // Night ground and nothing else until the first read comes back. Not a spinner:
                // this surface has one job on opening, which is to be calm.
                !state.loaded -> Unit

                showList -> SkyList(
                    layout = layout,
                    moodLabel = moodLabel,
                    locale = locale,
                    onActivate = { index -> activate(index) },
                )

                else -> {
                    SkySurface(
                        layout = layout,
                        fieldSeed = state.fieldSeed,
                        options = options,
                        equalisedRamp = ramp,
                        description = SkyPresentation.canvasDescription(layout, locale),
                        selectedStar = selected,
                        onStarTapped = { selected = it },
                        onTopMonthChange = { topMonth = it },
                        modifier = Modifier.fillMaxSize(),
                    )

                    when (layout.emptiness) {
                        SkyLayout.Emptiness.NO_RECORDS -> Text(
                            text = SkyLayout.EMPTY_LINE,
                            style = MaterialTheme.typography.bodyLarge,
                            color = SkyNightInk,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        )
                        SkyLayout.Emptiness.FIRST_LIGHT -> Text(
                            // The whole of the Sky's onboarding: the person's first mark, named
                            // once. No walkthrough, no carousel, no "3 of 5", and no
                            // congratulation — naming, not praise (§5.1).
                            text = layout.kindAt(0).introduction,
                            style = MaterialTheme.typography.bodyLarge,
                            color = SkyNightInk,
                            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
                        )
                        SkyLayout.Emptiness.POPULATED -> Text(
                            text = SkyPresentation.monthLabel(
                                topMonth ?: layout.firstEpochMonth,
                                locale,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = SkyNightFaint,
                            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                        )
                    }

                    if (selected in 0 until layout.starCount) {
                        SkyStarDetail(
                            text = SkyPresentation.starDescription(
                                layout,
                                selected,
                                moodLabel,
                                locale,
                            ),
                            kind = layout.kindAt(selected),
                            onOpen = { activate(selected) },
                            onDismiss = { selected = NO_SELECTION },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

private const val NO_SELECTION = -1

/**
 * The sky's own controls, on an ordinary surface rather than on the night ground.
 *
 * They sit on the app's normal background on purpose: chips drawn straight onto `#16150F` inherit
 * the light theme's foreground colours and land somewhere around 2:1, which would put the controls
 * a low-vision reader needs most out of reach on the screen built for them.
 */
@Composable
private fun SkyControls(
    showingList: Boolean,
    fieldEnabled: Boolean,
    motionEnabled: Boolean,
    highContrast: Boolean,
    onField: (Boolean) -> Unit,
    onMotion: (Boolean) -> Unit,
    onQuietSky: (Boolean) -> Unit,
    onLifeEvents: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The three that change how the sky is drawn are absent while the list is showing:
            // a control that is visible and does nothing teaches someone that the controls on this
            // screen are unreliable, which costs more than the row of chips is worth.
            if (!showingList) {
                FilterChip(
                    selected = highContrast,
                    onClick = { onQuietSky(!highContrast) },
                    label = { Text("Quiet sky") },
                )
                FilterChip(
                    selected = fieldEnabled,
                    onClick = { onField(!fieldEnabled) },
                    label = { Text("Field") },
                )
                FilterChip(
                    selected = motionEnabled,
                    onClick = { onMotion(!motionEnabled) },
                    label = { Text("Motion") },
                )
            }
            // §2.2 puts the life-event affordance on the Sky, and this is it. It is a plain
            // control that opens the screen where a person writes their own mark — it never
            // proposes one, and nothing anywhere in the app asks whether something happened.
            TextButton(onClick = onLifeEvents) { Text("Life events") }
        }
    }
}

/**
 * A star's detail, as a strip over the bottom of the sky rather than a sheet that covers it.
 *
 * §4 is firm that coming close to something is not the same as leaving: the sky stays visible at the
 * edges, so there is nothing to go "back" from. The strip carries what the star was, when, and its
 * mood if it had one — and an action that hands off to the feature owning the content. It carries no
 * prose, because [SkyLayout] has none to give it.
 */
@Composable
private fun SkyStarDetail(
    text: String,
    kind: SkyKind,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // A project step is the one kind that goes nowhere, and it gets no button rather than a
            // disabled one: a control that is present and refuses is worse than one that was never
            // offered. See SkyPresentation.hasAction for why a life event still counts as going
            // somewhere.
            if (SkyPresentation.hasAction(kind)) {
                TextButton(onClick = onOpen) { Text("Open it") }
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

/**
 * The text equivalent (§7.5).
 *
 * Month headings in time order with real heading semantics, one row per star, the same activation
 * as a tap on the sky. **Only months that have stars get a heading** — that rule lives in
 * [Sky.list], which this renders without filtering, so the list skips from March to July without
 * comment exactly as the sky does. Absence is not remarked on in any modality.
 *
 * No summary, no overview sentence, no "your year in words". The list is the data.
 */
@Composable
private fun SkyList(
    layout: SkyLayout,
    moodLabel: (Int) -> String,
    locale: Locale,
    onActivate: (Int) -> Unit,
) {
    if (layout.starCount == 0) {
        Text(
            text = SkyLayout.EMPTY_LINE,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    // Named `rows` and not `items`: `items` is also LazyListScope's own function, and a local
    // shadowing it inside the builder reads as a bug even when it is not one.
    val rows = remember(layout) { Sky.list(layout) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows.size) { position ->
            when (val item = rows[position]) {
                is SkyListItem.MonthHeading -> Text(
                    text = SkyPresentation.monthHeading(item, locale),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                is SkyListItem.Star -> {
                    val openable = SkyPresentation.hasAction(layout.kindAt(item.index))
                    Text(
                        text = SkyPresentation.starDescription(layout, item.index, moodLabel, locale),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (openable) Modifier.clickable { onActivate(item.index) }
                                else Modifier,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * The Android equivalent of `prefers-reduced-motion`.
 *
 * There is no reduced-motion helper anywhere in this app — `docs/SKY.md` §12 records that as a
 * prerequisite nobody had built — so this is it, deliberately local to the one surface that needs
 * it rather than installed as an app-wide utility this change has no mandate to introduce.
 *
 * `ANIMATOR_DURATION_SCALE` at zero is what the platform's own "Remove animations" setting sets,
 * and it is the signal Android's accessibility guidance points at. It is coarse — someone may have
 * turned animations off to save battery — which is exactly why it is read as a **default** for a
 * switch the person can move, and not as a lock.
 */
private fun prefersReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

/**
 * The platform's high-contrast preference, read as the default for "quiet sky".
 *
 * The key is a string rather than a constant because the platform does not expose one publicly.
 * `getInt` with a default cannot throw on an unknown key, so an Android version that renames or
 * drops it degrades to "off" — the ordinary sky — rather than to a crash on a surface someone may
 * be opening on a bad day.
 */
private fun prefersHighContrast(context: Context): Boolean =
    Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
