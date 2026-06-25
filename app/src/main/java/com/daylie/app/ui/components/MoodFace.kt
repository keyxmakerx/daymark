package com.daylie.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daylie.app.model.Mood
import com.daylie.app.ui.theme.LightMoodColors

/**
 * Hand-drawn "paper" mood face: a stroked circle, two dot eyes and a mouth curve that varies
 * with the mood level. Rendered with [Canvas] so it tints cleanly and needs no bitmap assets.
 *
 * - [selected] = false → outline face in the mood colour.
 * - [selected] = true  → filled disc in the mood colour with light strokes.
 */
@Composable
fun MoodFaceIcon(
    level: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val moodColor = LightMoodColors.forLevel(level)
    val clickable = if (onClick != null) modifier.clickable { onClick() } else modifier
    Canvas(modifier = clickable.size(size)) {
        drawMoodFace(level = level, moodColor = moodColor, selected = selected)
    }
}

/** Backwards-compatible wrapper kept so existing call sites keep working. */
@Composable
fun MoodFace(
    mood: Mood,
    modifier: Modifier = Modifier,
    size: Int = 56,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) = MoodFaceIcon(
    level = mood.level,
    modifier = modifier,
    size = size.dp,
    selected = selected,
    onClick = onClick,
)

private fun DrawScope.drawMoodFace(level: Int, moodColor: Color, selected: Boolean) {
    val w = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = w * 0.42f
    val stroke = (w * 0.055f).coerceAtLeast(2f)
    val faceColor = if (selected) Color(0xFFFCFAF5) else moodColor

    // Face circle
    if (selected) {
        drawCircle(color = moodColor, radius = radius, center = Offset(cx, cy))
    } else {
        drawCircle(color = moodColor, radius = radius, center = Offset(cx, cy), style = Stroke(width = stroke))
    }

    // Eyes
    val eyeDx = w * 0.16f
    val eyeY = cy - w * 0.09f
    val eyeR = w * 0.05f
    drawCircle(faceColor, radius = eyeR, center = Offset(cx - eyeDx, eyeY))
    drawCircle(faceColor, radius = eyeR, center = Offset(cx + eyeDx, eyeY))

    // Mouth — control-point offset (negative = frown, positive = smile)
    val mouthDx = w * 0.17f
    val xL = cx - mouthDx
    val xR = cx + mouthDx
    val curve = when (level) {
        1 -> -w * 0.16f // awful: deep frown
        2 -> -w * 0.08f // bad: slight frown
        3 -> 0f // meh: flat
        4 -> w * 0.13f // good: smile
        else -> w * 0.20f // rad: big smile
    }
    val baseY = cy + w * 0.11f
    val mouthStroke = Stroke(width = stroke, cap = StrokeCap.Round)
    if (curve == 0f) {
        drawLine(faceColor, Offset(xL, baseY), Offset(xR, baseY), strokeWidth = stroke, cap = StrokeCap.Round)
    } else {
        val path = Path().apply {
            moveTo(xL, baseY)
            quadraticTo(cx, baseY + curve, xR, baseY)
        }
        drawPath(path, faceColor, style = mouthStroke)
    }
}
