package com.daymark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.navigation.Routes
import com.daymark.app.ui.navigation.TopLevelDestination
import com.daymark.app.ui.theme.HairlineWidth

/**
 * A paper-styled bottom navigation bar with the centre destination (Home) raised into a
 * circular button that floats slightly above the bar.
 *
 * Every item is a tab in the semantics tree (role + selected state), so a screen reader announces
 * "Home, tab, selected" rather than a bare button. The selected cue is never colour alone: the
 * four flat items carry a short bar under the icon, and the raised disc is filled with the accent
 * only while Home is the current tab.
 */
@Composable
fun RaisedCenterNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // Top hairline rule.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route ||
                    (currentRoute == null && dest.route == Routes.HOME)
                if (dest.route == Routes.HOME) {
                    RaisedHomeItem(dest, selected, onClick = { onNavigate(dest.route) }, modifier = Modifier.weight(1f))
                } else {
                    NavItem(dest, selected, onClick = { onNavigate(dest.route) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    dest: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // The visible label names the tab; a description on the icon would read it twice.
        Icon(painterResource(dest.icon), contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
        // Selected cue that is not colour alone. It keeps its size when unselected (drawn
        // transparent) so nothing shifts as the current tab changes.
        Box(
            Modifier
                .width(16.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Text(dest.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun RaisedHomeItem(
    dest: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Paper surfaces carry at most a whisper of shadow, and none in dark mode (docs/DESIGN.md).
    val elevation = if (isSystemInDarkTheme()) 0.dp else 4.dp
    // The accent disc means "you are here", not "press me": while another tab is current the disc
    // goes quiet — the surface colour behind a hairline, with the icon in the muted ink.
    val disc = if (selected) colors.primary else colors.surface
    val iconTint = if (selected) colors.onPrimary else colors.onSurfaceVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .offset(y = (-16).dp)
                .size(58.dp)
                .shadow(elevation, CircleShape)
                .clip(CircleShape)
                .background(disc)
                .then(if (selected) Modifier else Modifier.border(HairlineWidth, colors.outlineVariant, CircleShape))
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(dest.icon),
                contentDescription = dest.label,
                tint = iconTint,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}
