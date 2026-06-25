package com.daylie.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Restrained, paper-like radii. */
val DaylieShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), // chips, small toggles
    small = RoundedCornerShape(12.dp), // inputs, badges
    medium = RoundedCornerShape(15.dp), // buttons
    large = RoundedCornerShape(16.dp), // cards / sheets
    extraLarge = RoundedCornerShape(22.dp), // hero containers / bottom sheets
)

val CardShape = RoundedCornerShape(16.dp)
val ButtonShape = RoundedCornerShape(15.dp)
