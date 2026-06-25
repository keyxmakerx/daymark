package com.daymark.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.daymark.app.ui.theme.CardShape
import com.daymark.app.ui.theme.HairlineWidth

/**
 * The paper "sheet" container primitive: a flat surface with a hairline border (no heavy M3
 * tonal elevation), used for cards, timeline sheets, settings lists, stat cards, etc.
 */
@Composable
fun PaperSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    border: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = if (border) BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline) else null,
    ) {
        Box { content() }
    }
}
