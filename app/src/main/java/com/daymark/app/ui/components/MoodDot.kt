package com.daymark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.theme.moodColors

/**
 * One entry's mood as a dot: a disc in that entry's own mood colour, as the person has it, inside a
 * thin ring of the soft ink (#412).
 *
 * The ring is what keeps the dot visible. A mood colour is the value the person logged, and they can
 * make it any colour, so a dot on its own can all but vanish into the sheet: two of the built-in
 * colours measure 2.32:1 (Meh) and 2.67:1 (Good) on the light sheet, under the 3:1 a small mark
 * needs. The ring is `onSurfaceVariant` whatever the mood: 5.53:1 on the sheet and 5.04:1 on the
 * paper in the light theme, 7.29:1 and 7.99:1 in the dark. With dynamic colour on, the system
 * supplies the role, which Material makes for words on the surface. The ring sits outside the colour,
 * so the colour keeps its whole [MOOD_DOT_DP] and the mark is [MOOD_DOT_MARK_DP] across. The mood
 * colours themselves are never changed.
 *
 * The month, Insights → Week and Home's week strip draw each entry with this and with nothing else;
 * `WeekDaysSourceTest` holds them to it and measures the ring. A dot says nothing to a screen reader:
 * the day it sits in says what its dots show.
 */
@Composable
fun MoodDot(level: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(MOOD_DOT_MARK_DP.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant)
            .padding(MOOD_DOT_RING_DP.dp)
            .clip(CircleShape)
            .background(MaterialTheme.moodColors.forLevel(level)),
    )
}

/** The colour's own width: large enough to tell at a glance, and three fit in the narrowest month cell. */
const val MOOD_DOT_DP = 6

/** The ring's width, outside the colour. */
const val MOOD_DOT_RING_DP = 1

/** The whole mark across, ring and all. */
const val MOOD_DOT_MARK_DP = MOOD_DOT_DP + 2 * MOOD_DOT_RING_DP
