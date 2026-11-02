package com.daymark.app.ui.support

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.daymark.app.calm.CalmShelf
import com.daymark.app.calm.ShakeDetector
import com.daymark.app.ui.theme.CardShape
import com.daymark.app.util.Haptics
import kotlin.math.roundToInt

/**
 * "Tidy a little shelf": books go anywhere on two shelves, in any order, and a firm shake of the
 * phone, or the Shake button, tumbles them loose again (#170). While a book is held over a shelf,
 * the books there already make room for it, so the person sees where it will stand before letting go.
 *
 * Every rule lives in `calm/CalmShelf.kt`: there is no right order and no "done", and books move
 * only when the person drags or taps one, shakes the phone or presses Shake. Nothing animates, so
 * there is nothing for reduced motion to change. The accelerometer is listened to only while this
 * screen is in front, and [ShakeDetector] needs a deliberate shake, so a jolt in a pocket or a bag
 * moves nothing.
 */
@Composable
fun CalmShelfScreen(onBackToMenu: () -> Unit) {
    val context = LocalContext.current
    var shelf by remember { mutableStateOf(CalmShelf.start(System.nanoTime().toInt())) }
    val tumble = {
        shelf = shelf.tumble(System.nanoTime().toInt())
        Haptics.doublePulse(context)
    }
    val onShake by rememberUpdatedState(tumble)

    LifecycleResumeEffect(Unit) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val detector = ShakeDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (detector.onReading(event.timestamp / 1_000_000L, v[0], v[1], v[2])) onShake()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensors != null && accelerometer != null) sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onPauseOrDispose { sensors?.unregisterListener(listener) }
    }

    CalmActivityFrame(title = "Tidy a little shelf", onBackToMenu = onBackToMenu) {
        Text(
            "Put the books anywhere on the shelves. Shake the phone to tumble them loose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Room(shelf, onChange = { next -> if (next != shelf) { shelf = next; Haptics.pulse(context, 30, 50) } })
        OutlinedButton(onClick = tumble, modifier = Modifier.fillMaxWidth()) { Text("Shake") }
    }
}

/** The wall, two shelves and the floor, in shelf units: the room is [ROOM_UNITS] units across. */
@Composable
private fun Room(shelf: CalmShelf, onChange: (CalmShelf) -> Unit) {
    // The drag handlers below outlive a recomposition, so they read the board through these and
    // never through a captured copy of a board that has since changed.
    val board by rememberUpdatedState(shelf)
    val change by rememberUpdatedState(onChange)
    val shades = CalmInk.shades
    val wall = CalmInk.ground
    val wood = CalmInk.shelf
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(380.dp).clip(CardShape).background(wall)
            .semantics { contentDescription = "Two shelves and some books. Tap a loose book to put it on a shelf." },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val unit = widthPx / ROOM_UNITS
        val pad = unit * 3
        val shelfY = listOf(heightPx * 0.32f, heightPx * 0.62f)
        val floorY = heightPx - unit * 2
        val floorSpan = widthPx - 2 * pad

        /** Top-left and size, in pixels, of where [id] rests on [on]. */
        fun rest(id: Int, on: CalmShelf = board): Pair<Offset, Pair<Float, Float>> {
            val book = CalmShelf.BOOKS[id]
            on.shelves.forEachIndexed { s, row ->
                row.firstOrNull { it.id == id }?.let { standing ->
                    return Offset(pad + standing.at * unit, shelfY[s] - book.height * unit) to (book.width * unit to book.height * unit)
                }
            }
            val loose = on.loose.getValue(id)
            val w = (if (loose.lying) book.height else book.width) * unit
            val h = (if (loose.lying) book.width else book.height) * unit
            return Offset(pad + loose.x * floorSpan, floorY - h) to (w to h)
        }

        /** Where [id] would go if let go now, [moved] from where it rests: a shelf and a left edge, or null for the floor. */
        fun target(id: Int, moved: Offset): Pair<Int, Float>? {
            val (start, dims) = rest(id)
            val left = start.x + moved.x
            val bottom = start.y + moved.y + dims.second
            val shelf = shelfY.indexOfFirst { y -> bottom in (y - unit * 8)..(y + unit * 4) }
            if (shelf < 0 || !board.hasRoomFor(id, shelf)) return null
            // A book carried on its side stands up on the shelf, centred where it was held.
            val width = CalmShelf.BOOKS[id].width * unit
            return shelf to (left + dims.first / 2 - width / 2 - pad) / unit
        }

        // While a book is held over a shelf, the others already stand where they will make room.
        val held = dragging
        val shown = held?.let { id -> target(id, drag)?.let { (s, at) -> board.place(id, s, at) } } ?: board

        shelfY.forEach { y ->
            Box(
                Modifier.offset { IntOffset((pad - unit).roundToInt(), y.roundToInt()) }
                    .size(with(density) { (widthPx - 2 * pad + 2 * unit).toDp() }, 6.dp)
                    .background(wood, RoundedCornerShape(2.dp)),
            )
        }

        CalmShelf.BOOKS.forEach { book ->
            val moving = dragging == book.id
            val (at, size) = rest(book.id, if (moving) board else shown)
            val drawnAt = if (moving) at + drag else at
            Box(
                Modifier
                    .zIndex(if (moving) 1f else 0f)
                    .offset { IntOffset(drawnAt.x.roundToInt(), drawnAt.y.roundToInt()) }
                    .size(with(density) { size.first.toDp() }, with(density) { size.second.toDp() })
                    .background(shades[book.shade], RoundedCornerShape(3.dp))
                    .clickable {
                        // The one-tap way, for anyone who cannot drag: a loose book stands at the end
                        // of the first shelf with room. A book already up stays where it is.
                        if (book.id in board.loose) {
                            board.shelfWithRoomFor(book.id)?.let { s -> change(board.place(book.id, s, board.endOf(s))) }
                        }
                    }
                    .pointerInput(book.id, widthPx, heightPx) {
                        detectDragGestures(
                            onDragStart = { dragging = book.id; drag = Offset.Zero },
                            onDrag = { change, amount -> change.consume(); drag += amount },
                            onDragCancel = { dragging = null; drag = Offset.Zero },
                            onDragEnd = {
                                val now = board
                                val left = rest(book.id).first.x + drag.x
                                // Over a shelf with room it stands there; anywhere else it lies where it was let go.
                                val next = target(book.id, drag)?.let { (s, at) -> now.place(book.id, s, at) }
                                    ?: now.putDown(book.id, (left - pad) / floorSpan)
                                dragging = null
                                drag = Offset.Zero
                                change(next)
                            },
                        )
                    },
            )
        }
    }
}

private const val ROOM_UNITS = 40
