package com.daymark.app.ui.sky

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.daymark.app.sky.SkyColours
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyOptions
import com.daymark.app.sky.SkyShaders
import com.daymark.app.sky.SkyWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Making a background for the phone's lock or home screen from the sky (#534, `docs/SKY.md` §6.4).
 *
 * The person chooses **Space only** or **With my stars**, frames it by dragging (and pinching, with
 * stars) or with **Another view**, and sets it through Android's own wallpaper picker, which is
 * where they choose the lock screen, the home screen or both. The picture is what the preview
 * shows, taken from it as drawn, at the screen's own size. Nothing needs the network.
 *
 * What it keeps true:
 * - Space only is drawn from the seed and the view alone ([SkyWallpaper]): no star is in it, and
 *   no layout, date or count is handed to that branch.
 * - With my stars draws only the stars and their gas, as they are today: no constellation names,
 *   no card, and nothing selected, since a lock screen is seen by others.
 * - Before the first background with stars, [SkyWallpaper.STARS_NOTE] is shown once.
 * - The picture is written to this app's cache and handed to the picker by a content link that
 *   grants it to that one app for reading; it never goes to shared storage or a gallery.
 */
@Composable
internal fun SkyWallpaperScreen(
    layout: SkyLayout,
    positions: Array<FloatArray>,
    look: SkyColours.Look,
    seed: Long,
    nebulae: List<com.daymark.app.sky.SkyNebula.Nebula>,
    todayEpochDay: Long,
    noteSeen: Boolean,
    onNoteSeen: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable {
        mutableStateOf(if (layout.shownCount > 0) SkyWallpaper.Mode.WITH_STARS else SkyWallpaper.Mode.SPACE_ONLY)
    }
    var view by rememberSaveable { mutableIntStateOf(0) }
    var noting by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()
    val camera = remember { SkyCamera() }
    val still = remember { SkyOpeningState(playing = false) }

    fun setIt() {
        if (working) return
        working = true
        scope.launch {
            val uri = runCatching {
                val picture = layer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.IO) { writePicture(context, picture) }
            }.getOrNull()
            working = false
            if (uri == null || !openPicker(context, uri)) refused = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(SkyNightBg)) {
        // The picture: exactly this box, as drawn.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                // Inside the recording, so the picture has the night behind it and no clear pixel.
                .background(SkyNightBg),
        ) {
            when (mode) {
                SkyWallpaper.Mode.SPACE_ONLY -> SkySpaceOnly(look = look, seed = seed, view = view)
                SkyWallpaper.Mode.WITH_STARS -> {
                    LaunchedEffect(view, camera.ready) {
                        if (!camera.ready) return@LaunchedEffect
                        val star = SkyWallpaper.starView(layout, seed, view)
                        if (view == 0 || star < 0) {
                            camera.fitAll(motion = false)
                        } else {
                            camera.snapTo(
                                positions[0][star],
                                positions[1][star],
                                SkyWallpaper.spaceView(seed, view).across * 2f,
                            )
                        }
                    }
                    SkySurface(
                        layout = layout,
                        positions = positions,
                        camera = camera,
                        opening = still,
                        options = SkyOptions(motionEnabled = false, highContrast = false),
                        todayEpochDay = todayEpochDay,
                        description = "Your sky, framed for a background",
                        selectedStar = NO_STAR,
                        constellations = emptyList(),
                        picked = emptyList(),
                        look = look,
                        seed = seed,
                        nebulae = nebulae,
                        trackers = emptyList(),
                        pulsars = emptyList(),
                        planets = emptyList(),
                        showerFrom = NO_STAR,
                        event = null,
                        onStarTapped = {},
                        onTrackerTapped = {},
                        onNebulaTapped = {},
                        onPlanetTapped = {},
                        onPulsarTapped = {},
                        onSkipOpening = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // The controls sit over the preview and are never in the picture.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Make a background", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Drag to frame it. Your phone then asks where it goes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    FilterChip(
                        selected = mode == SkyWallpaper.Mode.SPACE_ONLY,
                        onClick = { mode = SkyWallpaper.Mode.SPACE_ONLY },
                        label = { Text("Space only") },
                    )
                    if (layout.shownCount > 0) {
                        FilterChip(
                            selected = mode == SkyWallpaper.Mode.WITH_STARS,
                            onClick = { mode = SkyWallpaper.Mode.WITH_STARS },
                            label = { Text("With my stars") },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    TextButton(onClick = onClose) { Text("Back") }
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { view++ }) { Text("Another view") }
                    Button(
                        onClick = {
                            if (mode == SkyWallpaper.Mode.WITH_STARS && !noteSeen) noting = true else setIt()
                        },
                        enabled = !working,
                    ) { Text("Set") }
                }
            }
        }
    }

    if (noting) {
        AlertDialog(
            onDismissRequest = { noting = false },
            title = { Text("Before you set it") },
            text = { Text(SkyWallpaper.STARS_NOTE) },
            confirmButton = {
                TextButton(onClick = {
                    noting = false
                    onNoteSeen()
                    setIt()
                }) { Text("Set it") }
            },
            dismissButton = { TextButton(onClick = { noting = false }) { Text("Back") } },
        )
    }

    if (refused) {
        AlertDialog(
            onDismissRequest = { refused = false },
            title = { Text("Not set") },
            text = { Text("This phone did not open a way to set a background, so nothing was changed.") },
            confirmButton = { TextButton(onClick = { refused = false }) { Text("OK") } },
        )
    }
}

private const val NO_STAR = -1

/**
 * Space alone, from the seed and the view number only ([SkyWallpaper.spaceView],
 * [SkyWallpaper.spaceLights]): the sky's own space shader where the device can run it, and its
 * glows where it cannot, lit by a few points the seed places, which stand for nothing.
 */
@Composable
private fun SkySpaceOnly(look: SkyColours.Look, seed: Long, view: Int) {
    val shading = remember { SkyShading.create() }
    val glows = remember(seed) { SkyColours.glows(seed, 2f) }
    val spaceSeed = remember(seed) { SkyShaders.spaceSeed(seed) }
    val frame = remember(seed, view) { SkyWallpaper.spaceView(seed, view) }
    val placed = remember(seed, view) { FloatArray(SkyShaders.LIGHTS * 4) }
    val tints = remember(seed, view) { IntArray(SkyShaders.LIGHTS) }
    val count = remember(seed, view) { SkyWallpaper.spaceLights(seed, view, placed, tints) }
    // Dragging moves the view over the space and its points together.
    var dragX by remember(seed, view) { mutableFloatStateOf(0f) }
    var dragY by remember(seed, view) { mutableFloatStateOf(0f) }
    val lights = remember { FloatArray(SkyShaders.LIGHTS * 4) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(seed, view) {
                detectDragGestures { change, amount ->
                    change.consume()
                    dragX += amount.x
                    dragY += amount.y
                }
            }
            .semantics { this.contentDescription = "Your sky's space, with none of your stars, framed for a background" },
    ) {
        val w = this.size.width
        val h = this.size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val scale = w * frame.across
        val centreX = frame.centreX - dragX / scale
        val centreY = frame.centreY - dragY / scale
        // Each point, where it sits on the screen at this frame.
        fun at(k: Int): Offset = Offset(placed[k * 4] * w + dragX, placed[k * 4 + 1] * h + dragY)
        if (shading != null) {
            // At the screen's full size: a still picture can afford what the moving sky draws at a
            // quarter of it.
            for (k in 0 until count) {
                val p = at(k)
                lights[k * 4] = p.x
                lights[k * 4 + 1] = p.y
                lights[k * 4 + 2] = placed[k * 4 + 2] * w
                lights[k * 4 + 3] = placed[k * 4 + 3]
            }
            shading.drawSpace(
                scope = this,
                centreX = centreX,
                centreY = centreY,
                scale = scale,
                across = frame.across,
                seed = spaceSeed,
                space = look.space,
                gas = look.nebulae[0],
                seconds = 0f,
                show = 1f,
                lights = lights,
                lightTints = tints,
                lightCount = count,
            )
        } else {
            drawSpace(look, glows, Offset.Zero, scale, Offset(w / 2f - centreX * scale, h / 2f - centreY * scale))
        }
        for (k in 0 until count) {
            val p = at(k)
            val colour = skyColor(tints[k])
            drawGlow(p, 10.dp.toPx() * placed[k * 4 + 3], colour, 0.5f * placed[k * 4 + 3])
            drawCircle(colour, 1.6.dp.toPx(), p)
        }
    }
}

/** Writes [picture] to this app's cache as a PNG and returns a content link the picker can read. */
private fun writePicture(context: Context, picture: Bitmap): Uri {
    val software = if (picture.config == Bitmap.Config.HARDWARE) picture.copy(Bitmap.Config.ARGB_8888, false) else picture
    val folder = File(context.cacheDir, WALLPAPER_FOLDER).apply { mkdirs() }
    // One file, overwritten each time, so no old background is left behind in the cache.
    val file = File(folder, "background.png")
    file.outputStream().use { software.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, context.packageName + WALLPAPER_AUTHORITY_SUFFIX, file)
}

/**
 * Hands [uri] to Android's own wallpaper picker, or where a phone has none, to its "Set as"
 * chooser. False when neither opens, so the person is told nothing was changed.
 */
private fun openPicker(context: Context, uri: Uri): Boolean {
    val crop = runCatching { WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(uri) }.getOrNull()
    val intent = crop ?: Intent.createChooser(
        Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "image/png").putExtra("mimeType", "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Set as background",
    )
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** The cache folder the picture is written to, and the content provider that serves only it. */
internal const val WALLPAPER_FOLDER = "background"
internal const val WALLPAPER_AUTHORITY_SUFFIX = ".background"
