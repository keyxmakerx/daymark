package com.daylie.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Modern paper" palette — warm stationery surfaces, ink text, a single dark ink accent.
 * Mood colours are muted and earthy. These are raw tokens; [DaylieTheme] maps them onto
 * the Material 3 colour roles and the custom [MoodColors] holder.
 */

// ---- Light ("paper") ----
val PaperBg = Color(0xFFF4EFE6) // app background / "paper"
val PaperSheet = Color(0xFFFCFAF5) // cards, sheets, surfaces
val InkText = Color(0xFF2A2722) // primary text
val InkSoft = Color(0xFF6B655B) // secondary text / icons
val InkFaint = Color(0xFFA49C8E) // tertiary / placeholders / disabled
val Hairline = Color(0xFFE7DFD1) // 1dp borders & dividers
val InkAccent = Color(0xFF33302A) // primary buttons / selected (dark ink)

// ---- Dark ("night paper") ----
val PaperBgDark = Color(0xFF1B1A17)
val PaperSheetDark = Color(0xFF24221D)
val InkTextDark = Color(0xFFEBE5D8)
val InkSoftDark = Color(0xFFB7AF9E)
val InkFaintDark = Color(0xFF7C7568)
val HairlineDark = Color(0xFF34312A)
val InkAccentDark = Color(0xFFEBE5D8) // accent inverts to light ink in dark
val OnAccentDark = Color(0xFF1B1A17) // dark text on the light accent

// ---- Mood scale (awful → rad), canonical ----
val MoodAwful = Color(0xFFAE5747)
val MoodBad = Color(0xFFC27C46)
val MoodMeh = Color(0xFFC6A24E)
val MoodGood = Color(0xFF8FA268)
val MoodRad = Color(0xFF5E8A66)

// Light "wash" tints (calendar cells / chip fills) — bundled, not runtime alpha.
val MoodAwfulWash = Color(0xFFF0DAD4)
val MoodBadWash = Color(0xFFF1E2D2)
val MoodMehWash = Color(0xFFF1E8D0)
val MoodGoodWash = Color(0xFFE3E8D6)
val MoodRadWash = Color(0xFFD9E6DC)

// Dark washes (mood hue over the dark sheet).
val MoodAwfulWashDark = Color(0xFF3A2C28)
val MoodBadWashDark = Color(0xFF3A3128)
val MoodMehWashDark = Color(0xFF3A3528)
val MoodGoodWashDark = Color(0xFF2F362A)
val MoodRadWashDark = Color(0xFF2A3530)

// Error tone reuses the earthy red so nothing breaks the paper palette.
val ErrorDark = Color(0xFFD08A7C)
