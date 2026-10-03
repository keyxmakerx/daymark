package com.daymark.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paper typography: a serif for the wordmark / display / titles (journal feel) and the
 * platform default sans for UI, labels and numbers.
 *
 * The sans is the platform's own for good (#251): every non-serif style follows the font the device
 * uses, and no font is ever fetched from a network. The serif is the platform serif until Fraunces
 * is bundled in the APK for the serif roles: #363.
 */
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

val DaymarkTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Extra, non-M3 text styles used by the paper UI. */
@androidx.compose.runtime.Immutable
data class DaymarkTextStyles(
    val diaryNote: TextStyle,
    val wordmark: TextStyle,
)

val DefaultDaymarkTextStyles = DaymarkTextStyles(
    diaryNote = TextStyle(
        fontFamily = Serif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    wordmark = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
)

val LocalDaymarkTextStyles = staticCompositionLocalOf { DefaultDaymarkTextStyles }

// Backwards-compatible alias for existing references to `Typography`.
val Typography = DaymarkTypography
