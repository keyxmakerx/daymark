package com.daymark.app.ui.sky

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyConstellation
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKey
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyListItem
import com.daymark.app.sky.SkyOpening
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyPalette
import com.daymark.app.sky.SkyTwinkle
import com.daymark.app.ui.theme.moodColors
import com.daymark.app.ui.theme.moodLabels
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

/**
 * "Your sky" — the surface, its two presentations, and its controls (`DECISIONS.md` §D11).
 *
 * The picture and the list are **peers** (§7.5), not a view and a fallback. Same data, same
 * actions, one toggle between them, and the toggle is in the top bar rather than hidden in a menu.
 * The honest part of that claim is written in the design and is worth repeating here: they are equal
 * in information and they are not the same experience, because nobody sits and looks at a list. The
 * project has no better answer than giving both the same design attention and shipping them
 * together.
 *
 * ## The opening
 *
 * Each time the sky opens, its stars twinkle in from a trickle to a burst, then the camera flies to
 * the newest one; a newest star the person has not watched arrive is born in front of them first.
 * Any tap skips all of it, and with motion off there is none: the sky opens still, on today.
 *
 * ## What this screen refuses to say
 *
 * There is no total, no count of days, no coverage figure, no streak, no "since you last opened",
 * no superlative and no comparison of any period against any other. The numbers on it are the item
 * count on a month heading in the list, which is there because a list without list semantics cannot
 * be navigated (§6.3), and how many stars a constellation the person drew joins.
 *
 * The empty state is the case worth reading twice. A new install draws the night ground, one line,
 * and nothing else: no error, no fabricated sky, no illustration of a sky someone else made, and
 * above all no invitation to start logging. `docs/DECISIONS.md` is unambiguous that nothing here
 * shames, and "you haven't written anything yet" is a scoreboard with one entry on it.
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

    var motionEnabled by rememberSaveable { mutableStateOf(!platformReducedMotion) }
    var highContrast by rememberSaveable { mutableStateOf(platformHighContrast) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(NO_SELECTION) }
    var opened by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(SkySheet.NONE) }
    var cardId by rememberSaveable { mutableStateOf(NO_CONSTELLATION) }
    var photoId by rememberSaveable { mutableStateOf(NO_CONSTELLATION) }
    var removingId by rememberSaveable { mutableStateOf(NO_CONSTELLATION) }
    var drawing by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Int>() }
    var naming by remember { mutableStateOf(false) }
    var bornJustNow by remember { mutableStateOf(NO_SELECTION) }

    val options = SkyOptions(motionEnabled = motionEnabled, highContrast = highContrast)
    val camera = remember { SkyCamera() }
    val opening = remember { SkyOpeningState(playing = !opened && motionEnabled) }

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

    // The renderer's clock read. `sky/` is import-free and has no clock — `SkyAge` takes an age in
    // years and never a date — so the one call to `LocalDate.now()` on this surface is here.
    // Re-read whenever the layout changes rather than once ever: a phone left open across midnight
    // would otherwise keep drawing yesterday's ages, and a new record is what usually wakes it.
    val todayEpochDay = remember(state.layout) { LocalDate.now().toEpochDay() }

    val layout = state.layout
    val positions = remember(layout, todayEpochDay) { layout.positionsOn(todayEpochDay) }
    val moodLabel: (Int) -> String = { labels.forLevel(it) }

    // A star is addressed by its index into the packed arrays, and a new read produces new arrays —
    // so a selection held across a change would point at a different star and put someone else's
    // Tuesday in the detail strip. Cleared rather than remapped: a person who was looking at a
    // record while it changed underneath them is better served by the sky than by a guess. Stars
    // being joined into a constellation go the same way, for the same reason.
    LaunchedEffect(layout) {
        if (opened) selected = NO_SELECTION
        picked.clear()
    }

    fun activate(index: Int) {
        val kind = layout.kindAt(index)
        when {
            SkyPresentation.opensRecord(kind) -> onOpenRecord(kind, layout.recordIds[layout.idStart[index]])
            kind == SkyKind.LIFE_EVENT -> onOpenLifeEvents()
        }
    }

    fun newestIdentity(): Long {
        val n = layout.newest
        return Sky.identityOf(layout.kindAt(n), SkyTwinkle.identityIdAt(layout, n))
    }

    /** The newest star, close enough to be a place: where the opening ends, and Today. */
    fun goToday(motion: Boolean) {
        val n = layout.newest
        if (n < 0 || !camera.ready) return
        val zoom = SkyPresentation.zoomForSpan(SkyPresentation.TODAY_SPAN, camera.fitPx, camera.viewportWidth)
        camera.flyTo(positions[0][n], positions[1][n], zoom, motion)
    }

    fun skipOpening() {
        if (opened) return
        opening.finish()
        if (layout.newest >= 0) {
            goToday(motion = false)
            viewModel.markBorn(newestIdentity())
            selected = layout.newest
        }
        opened = true
    }

    // The opening. Keyed on `opened`, so skipping it cancels this where it stands.
    LaunchedEffect(state.loaded, opened, camera.ready) {
        if (!state.loaded || opened || !camera.ready) return@LaunchedEffect
        val newest = layout.newest
        if (newest < 0) {
            opening.finish()
            opened = true
            return@LaunchedEffect
        }
        val identity = newestIdentity()
        if (!opening.playing) {
            // With motion off there is no opening at all: the sky opens still, on today.
            goToday(motion = false)
            viewModel.markBorn(identity)
            selected = newest
            opened = true
            return@LaunchedEffect
        }
        val birth = identity != state.bornIdentity
        if (birth) {
            opening.bornIndex = newest
            opening.birth = 0f
        }
        val start = withFrameMillis { it }
        while (true) {
            val seconds = withFrameMillis { (it - start) / 1000f }
            opening.reveal = SkyOpening.revealAt(seconds)
            if (seconds >= SkyOpening.FLY_AT) break
        }
        goToday(motion = true)
        while (camera.isFlying) withFrameMillis { }
        if (birth) {
            delay(BIRTH_PAUSE_MILLIS)
            val from = withFrameMillis { it }
            while (opening.birth < 1f) {
                withFrameMillis {
                    opening.birth = ((it - from).toFloat() / SkyOpening.BIRTH_MILLIS).coerceAtMost(1f)
                }
            }
            bornJustNow = newest
        }
        viewModel.markBorn(identity)
        opening.finish()
        selected = newest
        opened = true
    }

    // Every journey across the sky, a frame at a time, for as long as it lasts and no longer.
    LaunchedEffect(camera.journey) {
        while (camera.isFlying) withFrameMillis { camera.step(it) }
    }

    fun startDrawing() {
        sheet = SkySheet.NONE
        selected = NO_SELECTION
        cardId = NO_CONSTELLATION
        picked.clear()
        drawing = true
        // Stars can only be told apart close in, so drawing starts somewhere they can.
        if (camera.screenWidthsAcross < DRAW_FROM_WIDTHS) goToday(motionEnabled)
    }

    fun stopDrawing() {
        drawing = false
        naming = false
        picked.clear()
    }

    fun showConstellation(c: SkyViewModel.ShownConstellation) {
        sheet = SkySheet.NONE
        selected = NO_SELECTION
        cardId = c.id
        val kept = c.resolved.filter { it >= 0 }
        if (kept.isEmpty() || !camera.ready) return
        val left = kept.minOf { positions[0][it] }
        val right = kept.maxOf { positions[0][it] }
        val top = kept.minOf { positions[1][it] }
        val bottom = kept.maxOf { positions[1][it] }
        val zoom = SkyPresentation.zoomToFrame(
            right - left,
            bottom - top,
            camera.fitPx,
            camera.viewportWidth,
            camera.viewportHeight,
        )
        camera.flyTo((left + right) / 2f, (top + bottom) / 2f, zoom, motionEnabled)
    }

    fun tapStar(index: Int) {
        if (drawing) {
            // A star joins once in a row; tapping the last one again, or empty sky, adds nothing.
            val fresh = index >= 0 && picked.lastOrNull() != index
            if (fresh && picked.size < SkyConstellation.MAX_POINTS) picked.add(index)
            return
        }
        if (index != bornJustNow) bornJustNow = NO_SELECTION
        selected = index
        cardId = NO_CONSTELLATION
        sheet = SkySheet.NONE
    }

    val card = state.constellations.firstOrNull { it.id == cardId }
    val photo = state.constellations.firstOrNull { it.id == photoId }

    BackHandler(enabled = photo != null || sheet != SkySheet.NONE || drawing) {
        when {
            photo != null -> photoId = NO_CONSTELLATION
            sheet != SkySheet.NONE -> sheet = SkySheet.NONE
            else -> stopDrawing()
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
                    TextButton(
                        onClick = {
                            skipOpening()
                            stopDrawing()
                            showList = !showList
                        },
                    ) {
                        Text(if (showList) "Sky" else "List")
                    }
                },
            )
        },
        bottomBar = {
            if (drawing && !showList) {
                SkyDrawBar(
                    joined = picked.size,
                    onCancel = { stopDrawing() },
                    onUndo = { if (picked.isNotEmpty()) picked.removeAt(picked.lastIndex) },
                    onName = { naming = true },
                )
            } else {
                SkyControls(
                    showingList = showList,
                    hasStars = layout.starCount > 0,
                    motionEnabled = motionEnabled,
                    highContrast = highContrast,
                    onConstellations = {
                        skipOpening()
                        sheet = if (sheet == SkySheet.CONSTELLATIONS) SkySheet.NONE else SkySheet.CONSTELLATIONS
                    },
                    onToday = {
                        skipOpening()
                        sheet = SkySheet.NONE
                        cardId = NO_CONSTELLATION
                        goToday(motionEnabled)
                        selected = layout.newest
                    },
                    onKey = {
                        skipOpening()
                        sheet = if (sheet == SkySheet.KEY) SkySheet.NONE else SkySheet.KEY
                    },
                    onMotion = { motionEnabled = it },
                    // One tap, because §7.1 asks for one: the low-vision presentation is every
                    // glyph at maximum contrast with no halos.
                    onQuietSky = { highContrast = it },
                    onLifeEvents = onOpenLifeEvents,
                )
            }
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
                        positions = positions,
                        camera = camera,
                        opening = opening,
                        options = options,
                        todayEpochDay = todayEpochDay,
                        description = SkyPresentation.canvasDescription(layout, locale),
                        selectedStar = if (drawing) NO_SELECTION else selected,
                        constellations = state.constellations,
                        picked = picked,
                        onStarTapped = { index -> tapStar(index) },
                        onSkipOpening = { skipOpening() },
                        modifier = Modifier.fillMaxSize(),
                    )

                    when (layout.emptiness) {
                        SkyLayout.Emptiness.NO_RECORDS -> Text(
                            text = SkyLayout.EMPTY_LINE,
                            style = MaterialTheme.typography.bodyLarge,
                            color = SkyNightInk,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        )
                        // The whole of the Sky's onboarding: the person's first mark, named once.
                        // No walkthrough, no carousel, no "3 of 5", and no congratulation — naming,
                        // not praise (§5.1). Its detail says the same, so it waits until none is
                        // open.
                        SkyLayout.Emptiness.FIRST_LIGHT -> if (selected == NO_SELECTION && !opening.playing) {
                            Text(
                                text = layout.kindAt(0).introduction,
                                style = MaterialTheme.typography.bodyLarge,
                                color = SkyNightInk,
                                modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
                            )
                        }
                        // A populated sky is labelled with nothing at all. No part of the sky is a
                        // date, so any label here would be a claim that is not true. Dates live in
                        // a star's detail and in the list.
                        SkyLayout.Emptiness.POPULATED -> Unit
                    }

                    when {
                        drawing -> Unit
                        card != null -> SkyConstellationCard(
                            name = card.name,
                            summary = SkyPresentation.constellationSummary(
                                card.madeEpochDay,
                                card.resolved.filter { it >= 0 }.map { layout.epochDay[it] },
                                locale,
                            ),
                            onPhoto = { photoId = card.id },
                            onRemove = { removingId = card.id },
                            onDismiss = { cardId = NO_CONSTELLATION },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                        selected in 0 until layout.starCount -> SkyStarDetail(
                            note = if (selected == bornJustNow) "Born just now" else null,
                            text = SkyPresentation.starDescription(layout, selected, moodLabel, locale),
                            kind = layout.kindAt(selected),
                            moodLevel = layout.moodLevel[selected],
                            equalisedRamp = ramp,
                            onOpen = { activate(selected) },
                            onDismiss = { selected = NO_SELECTION },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }

                    when (sheet) {
                        SkySheet.NONE -> Unit
                        SkySheet.KEY -> SkySheetFrame(title = "Key", onClose = { sheet = SkySheet.NONE }) {
                            for (entry in SkyKey.entries(layout, state.constellations.size)) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                                )
                                Text(text = entry.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        SkySheet.CONSTELLATIONS -> SkySheetFrame(
                            title = "Your constellations",
                            onClose = { sheet = SkySheet.NONE },
                        ) {
                            Button(onClick = { startDrawing() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Draw a new constellation")
                            }
                            for (c in state.constellations) {
                                SkyConstellationRow(
                                    constellation = c,
                                    detail = SkyPresentation.constellationRow(
                                        c.madeEpochDay,
                                        c.resolved.count { it >= 0 },
                                        locale,
                                    ),
                                    onClick = { showConstellation(c) },
                                )
                            }
                            Text(
                                text = CONSTELLATION_NOTE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }

            if (photo != null && !showList) {
                SkyPhotoOverlay(
                    layout = layout,
                    constellation = photo,
                    options = options,
                    dateLabel = SkyPresentation.dateLabel(photo.madeEpochDay, locale),
                    onClose = { photoId = NO_CONSTELLATION },
                )
            }
        }
    }

    if (naming) {
        SkyNameDialog(
            onSave = { name ->
                val points = picked.filter { it in 0 until layout.starCount }.map { index ->
                    SkyConstellation.Point(
                        kind = layout.kindAt(index),
                        recordId = SkyTwinkle.identityIdAt(layout, index),
                        x = positions[0][index],
                        y = positions[1][index],
                    )
                }
                viewModel.saveConstellation(name, todayEpochDay, points)
                stopDrawing()
            },
            onBack = { naming = false },
        )
    }

    val removing = state.constellations.firstOrNull { it.id == removingId }
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { removingId = NO_CONSTELLATION },
            title = { Text("Remove this constellation?") },
            text = { Text("Its stars stay in your sky. Only its lines and its name go.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeConstellation(removing.id)
                        removingId = NO_CONSTELLATION
                        cardId = NO_CONSTELLATION
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removingId = NO_CONSTELLATION }) { Text("Keep it") }
            },
        )
    }
}

private const val NO_SELECTION = -1
private const val NO_CONSTELLATION = -1L

/** How many screen widths across the sky must be before stars can be joined one by one. */
private const val DRAW_FROM_WIDTHS = 6f

/** The pause between the camera arriving and a new star starting to be born. */
private const val BIRTH_PAUSE_MILLIS = 300L

private const val CONSTELLATION_NOTE =
    "Tap your own stars one after another, then give it a name. Over the years its stars drift " +
        "apart and it falls out of the sky, but See it as you drew it always shows it as it was."

private enum class SkySheet { NONE, CONSTELLATIONS, KEY }

/**
 * The sky's own controls, on an ordinary surface rather than on the night ground.
 *
 * They sit on the app's normal background on purpose: chips drawn straight onto the night ground
 * inherit the light theme's foreground colours and land somewhere around 2:1, which would put the
 * controls a low-vision reader needs most out of reach on the screen built for them.
 */
@Composable
private fun SkyControls(
    showingList: Boolean,
    hasStars: Boolean,
    motionEnabled: Boolean,
    highContrast: Boolean,
    onConstellations: () -> Unit,
    onToday: () -> Unit,
    onKey: () -> Unit,
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
            // The sky's own controls are absent while the list is showing: a control that is
            // visible and does nothing teaches someone that the controls on this screen are
            // unreliable, which costs more than the row of chips is worth. Likewise in a sky with
            // nothing in it yet, where there is no today to go to and nothing to join.
            if (!showingList) {
                if (hasStars) {
                    TextButton(onClick = onConstellations) { Text("Constellations") }
                    TextButton(onClick = onToday) { Text("Today") }
                    TextButton(onClick = onKey) { Text("Key") }
                }
                FilterChip(
                    selected = motionEnabled,
                    onClick = { onMotion(!motionEnabled) },
                    label = { Text("Motion") },
                )
                FilterChip(
                    selected = highContrast,
                    onClick = { onQuietSky(!highContrast) },
                    label = { Text("Quiet sky") },
                )
            }
            // §2.2 puts the life-event affordance on the Sky, and this is it. It is a plain
            // control that opens the screen where a person writes their own mark — it never
            // proposes one, and nothing anywhere in the app asks whether something happened.
            TextButton(onClick = onLifeEvents) { Text("Life events") }
        }
    }
}

/** While a constellation is being drawn: what has been joined so far, and the three ways on. */
@Composable
private fun SkyDrawBar(joined: Int, onCancel: () -> Unit, onUndo: () -> Unit, onName: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(SkyPresentation.drawingPrompt(joined), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onUndo, enabled = joined > 0) { Text("Undo") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onName, enabled = joined >= SkyConstellation.MIN_POINTS) { Text("Name it") }
            }
        }
    }
}

/** Naming a constellation. Left blank, it is kept as [SkyConstellation.UNTITLED]. */
@Composable
private fun SkyNameDialog(onSave: (String) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Name it") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= SkyConstellation.NAME_MAX) name = it },
                placeholder = { Text("A name for these stars") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}

/**
 * A sheet over the bottom of the sky, on an ordinary surface: a title, a way to close it, and what
 * it holds. Tapping the dimmed sky above it closes it too.
 */
@Composable
private fun SkySheetFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close",
                    onClick = onClose,
                ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/** One constellation in the list: its shape as drawn, its name, and when. */
@Composable
private fun SkyConstellationRow(
    constellation: SkyViewModel.ShownConstellation,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkyConstellationThumb(
            constellation = constellation,
            modifier = Modifier
                .size(width = 84.dp, height = 58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SkyNightBg)
                .clearAndSetSemantics {},
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = constellation.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A constellation's shape as it was drawn, small: its lines and a dot for each star. */
@Composable
private fun SkyConstellationThumb(constellation: SkyViewModel.ShownConstellation, modifier: Modifier) {
    val line = skyColor(SkyPalette.CONSTELLATION_LINE)
    Canvas(modifier = modifier) {
        val points = constellation.points
        val kept = points.indices.filter { constellation.resolved[it] >= 0 }
        if (kept.isEmpty()) return@Canvas
        val pad = 8.dp.toPx()
        val left = kept.minOf { points[it].x }
        val top = kept.minOf { points[it].y }
        val spanX = (kept.maxOf { points[it].x } - left).coerceAtLeast(0.01f)
        val spanY = (kept.maxOf { points[it].y } - top).coerceAtLeast(0.01f)
        val scale = minOf((size.width - 2f * pad) / spanX, (size.height - 2f * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2f
        val offY = (size.height - spanY * scale) / 2f
        fun at(i: Int) = Offset(offX + (points[i].x - left) * scale, offY + (points[i].y - top) * scale)
        for (i in 0 until points.size - 1) {
            if (constellation.resolved[i] < 0 || constellation.resolved[i + 1] < 0) continue
            drawLine(line, at(i), at(i + 1), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round, alpha = 0.7f)
        }
        for (i in kept) drawCircle(SkyNightInk, radius = 2.4.dp.toPx(), center = at(i))
    }
}

/** A constellation, picked from the list or the sky: what it is, and the photo of the day it was drawn. */
@Composable
private fun SkyConstellationCard(
    name: String,
    summary: String,
    onPhoto: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPhoto) { Text("See it as you drew it") }
                TextButton(onClick = onRemove) { Text("Remove this constellation") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

/**
 * "See it as you drew it": the photo, full screen, with its name and the day it was drawn. Nothing
 * in it opens, and a tap anywhere goes back to the sky.
 */
@Composable
private fun SkyPhotoOverlay(
    layout: SkyLayout,
    constellation: SkyViewModel.ShownConstellation,
    options: SkyOptions,
    dateLabel: String,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SkyNightBg)
            .clickable(onClickLabel = "Back to your sky", onClick = onClose),
    ) {
        SkyConstellationPhoto(layout = layout, constellation = constellation, options = options)
        Column(modifier = Modifier.align(Alignment.TopStart).padding(24.dp)) {
            Text(
                text = constellation.name,
                style = MaterialTheme.typography.titleLarge,
                color = SkyNightInk,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "As you drew it, $dateLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = SkyNightInk,
            )
        }
        Text(
            text = "Tap anywhere to go back to your sky",
            style = MaterialTheme.typography.bodySmall,
            color = SkyNightInk,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}

/**
 * A star's detail, as a strip over the bottom of the sky rather than a sheet that covers it.
 *
 * §4 is firm that coming close to something is not the same as leaving: the sky stays visible at the
 * edges, so there is nothing to go "back" from. The strip carries what the star was, when, and its
 * mood if it had one — and an action that hands off to the feature owning the content. It carries no
 * prose, because [SkyLayout] has none to give it.
 *
 * ## The mood dot, which is where the mood ramp went
 *
 * A star's colour is its age (`docs/SKY.md` §3.2), and §3.2 is explicit about where the person's
 * own mood colour lives instead: *"a dot beside the mood word in a star's detail"*. This is that
 * detail (§4.1). The dot is drawn from the **equalised** ramp and never from the raw one — a raw
 * dot is exactly where the ranking-by-visibility `SkyPalette` exists to remove would reappear, at
 * 4.08:1 for the hardest mood against 8.32:1 for an ordinary one, moved off the sky and into the
 * strip beside it.
 *
 * The word is already in [text]; the dot is beside it and never instead of it.
 */
@Composable
private fun SkyStarDetail(
    /** A line above the star's own, for the star that was just born in front of the person. */
    note: String?,
    text: String,
    kind: SkyKind,
    moodLevel: Int,
    /** The person's mood ramp, already through [SkyPalette.equalisedRamp]. Levels 1..5. */
    equalisedRamp: IntArray,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val moodIndex = moodLevel - SkyGlyph.MOOD_MIN
            if (moodIndex in equalisedRamp.indices) {
                // Decorative: the mood is already a word in the text beside it, so a screen reader
                // that also announced the dot would say it twice. Four kinds carry no mood at all
                // and get no dot rather than a grey one — an uncoloured star is not a lesser star.
                Spacer(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(skyColor(equalisedRamp[moodIndex]))
                        .clearAndSetSemantics {},
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
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
 * This is the app's only reduced-motion check (`docs/SKY.md` §7.4), deliberately local to the one
 * surface that needs it rather than installed as an app-wide utility this change has no mandate to
 * introduce.
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
