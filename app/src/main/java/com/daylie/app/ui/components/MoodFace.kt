package com.daylie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daylie.app.model.Mood

/** A circular mood "face" using the mood's emoji and color, optionally selectable. */
@Composable
fun MoodFace(
    mood: Mood,
    modifier: Modifier = Modifier,
    size: Int = 56,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(mood.color.copy(alpha = if (selected) 0.9f else 0.18f))
        .then(
            if (selected) Modifier.border(2.dp, mood.color, CircleShape) else Modifier,
        )
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    Box(modifier = modifier.then(base), contentAlignment = Alignment.Center) {
        Text(text = mood.emoji, fontSize = (size * 0.45).sp, style = MaterialTheme.typography.titleLarge)
    }
}
