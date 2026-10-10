package com.daymark.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one on/off switch every screen uses (#436).
 *
 * Material's own colours draw an off switch with a track the colour of the page and a thumb and
 * outline on the hairline, 1.0 to 1.2:1 against what they sit on, so off and on were hard to tell
 * apart. Here, off is a hairline track with the soft ink for its thumb and its outline; on is the
 * accent ink track with a paper thumb. Every colour is a role of the scheme, so both themes and
 * dynamic colour take it. `ColorSchemeSourceTest` holds the off colours to 3:1 against the track and
 * the grounds a switch sits on, and `ui/` to using this and no bare `Switch(`.
 */
@Composable
fun DaymarkSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
