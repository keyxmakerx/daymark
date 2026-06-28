package com.daymark.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.theme.moodColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// Fixed "night paper" palette — this card is always dark, even in the light app theme (matches the
// approved Year-in-Stars mockup). Mood colours come from the theme so custom palettes carry over.
private val NightBg = Color(0xFF16150F)
private val NightInk = Color(0xFFEBE5D8)
private val NightFaint = Color(0xFF8E887A)

/**
 * A whole year as a night sky: one row per month, each logged day a star whose size & brightness
 * track that day's mood and whose colour is the mood colour. Ordinary days are soft glowing dots;
 * the good days (mood 4–5) get a brighter glint with cross-rays. Unlogged days are faint specks, so
 * the *amount of twinkle* itself reads as how a stretch of life went. Purely a reflection lens —
 * the [YearInPixelsGrid] grid view remains for dense analysis.
 */
@Composable
fun YearInStarsGrid(
    year: Int,
    dayMoods: Map<LocalDate, Double>,
    modifier: Modifier = Modifier,
) {
    val moods = MaterialTheme.moodColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightBg)
            .padding(vertical = 12.dp, horizontal = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (month in 1..12) {
                val length = YearMonth.of(year, month).lengthOfMonth()
                val label = java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = NightFaint,
                        modifier = Modifier.width(30.dp),
                    )
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp),
                    ) {
                        for (day in 1..length) {
                            val cx = (day - 0.5f) / 31f * size.width
                            // Deterministic vertical drift so the sky looks organic, not gridded.
                            val jitter = (hash(month, day) - 0.5f) * size.height * 0.55f
                            val cy = size.height / 2f + jitter
                            val avg = dayMoods[LocalDate.of(year, month, day)]
                            if (avg == null) {
                                drawCircle(NightInk.copy(alpha = 0.12f), radius = 1.2.dp.toPx(), center = Offset(cx, cy))
                            } else {
                                val level = avg.roundToInt().coerceIn(1, 5)
                                star(cx, cy, level, moods.forLevel(level))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Draws one star: a soft glowing dot for ordinary days, a brighter cross-ray glint for the best. */
private fun DrawScope.star(cx: Float, cy: Float, level: Int, moodColor: Color) {
    val center = Offset(cx, cy)
    // Lighten the mood colour a touch so it reads as luminous against the night background.
    val color = lerp(moodColor, Color.White, 0.18f)
    val r = when (level) {
        1 -> 1.7f; 2 -> 2.1f; 3 -> 2.7f; 4 -> 3.3f; else -> 3.9f
    }.dp.toPx()

    if (level >= 4) {
        // Glow (two faint discs) + cross-ray glint + bright core.
        drawCircle(color.copy(alpha = 0.16f), radius = r * 2.6f, center = center)
        drawCircle(color.copy(alpha = 0.30f), radius = r * 1.5f, center = center)
        val ray = r * 2.7f
        val w = r * 0.5f
        drawLine(color, Offset(cx, cy - ray), Offset(cx, cy + ray), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx - ray, cy), Offset(cx + ray, cy), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(color, radius = r * 0.85f, center = center)
    } else {
        // Soft dot: a faint halo plus a small solid core.
        drawCircle(color.copy(alpha = 0.22f), radius = r * 1.8f, center = center)
        drawCircle(color, radius = r * 0.75f, center = center)
    }
}

/** Stable pseudo-random in [0,1) from a month/day pair (no Random — keeps the sky stable). */
private fun hash(month: Int, day: Int): Float {
    var h = month * 73856093 xor day * 19349663
    h = h xor (h ushr 13)
    h *= 1274126177
    return ((h ushr 8) and 0xFFFF) / 65536f
}

/** Legend for the stars view, on the dark card. */
@Composable
fun StarsLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "a soft dot most days · a glint on the bright ones",
            style = MaterialTheme.typography.labelSmall,
            color = NightFaint,
        )
    }
}
