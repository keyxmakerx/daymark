package com.daymark.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightPaperColors = lightColorScheme(
    primary = InkAccent,
    onPrimary = PaperSheet,
    primaryContainer = Hairline,
    onPrimaryContainer = InkText,
    secondary = InkSoft,
    onSecondary = PaperSheet,
    secondaryContainer = Hairline,
    onSecondaryContainer = InkText,
    tertiary = InkFaint,
    onTertiary = InkText,
    background = PaperBg,
    onBackground = InkText,
    surface = PaperSheet,
    onSurface = InkText,
    surfaceVariant = Hairline,
    onSurfaceVariant = InkSoft,
    surfaceTint = Color.Transparent,
    outline = Hairline,
    outlineVariant = Hairline,
    error = MoodAwful,
    onError = PaperSheet,
    errorContainer = MoodAwfulWash,
    onErrorContainer = InkText,
    inverseSurface = InkText,
    inverseOnSurface = PaperBg,
)

private val DarkPaperColors = darkColorScheme(
    primary = InkAccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = HairlineDark,
    onPrimaryContainer = InkTextDark,
    secondary = InkSoftDark,
    onSecondary = PaperBgDark,
    secondaryContainer = HairlineDark,
    onSecondaryContainer = InkTextDark,
    tertiary = InkFaintDark,
    onTertiary = InkTextDark,
    background = PaperBgDark,
    onBackground = InkTextDark,
    surface = PaperSheetDark,
    onSurface = InkTextDark,
    surfaceVariant = HairlineDark,
    onSurfaceVariant = InkSoftDark,
    surfaceTint = Color.Transparent,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = ErrorDark,
    onError = PaperBgDark,
    errorContainer = MoodAwfulWashDark,
    onErrorContainer = InkTextDark,
    inverseSurface = InkTextDark,
    inverseOnSurface = PaperBgDark,
)

@Composable
fun DaymarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // The paper identity is the point of the app, so dynamic colour is OFF by default.
    dynamicColor: Boolean = false,
    // User overrides for mood colours (level -> ARGB) and labels (level -> text).
    moodColorOverrides: Map<Int, Int> = emptyMap(),
    moodLabelOverrides: Map<Int, String> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkPaperColors
        else -> LightPaperColors
    }
    // Mood colours never recolour from the wallpaper, even when dynamic colour is opted in;
    // user overrides take precedence over the built-in palette.
    val moods = (if (darkTheme) DarkMoodColors else LightMoodColors)
        .withOverrides(moodColorOverrides, darkTheme)

    CompositionLocalProvider(
        LocalMoodColors provides moods,
        LocalMoodLabels provides MoodLabels(moodLabelOverrides),
        LocalDaymarkTextStyles provides DefaultDaymarkTextStyles,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DaymarkTypography,
            shapes = DaymarkShapes,
            content = content,
        )
    }
}
