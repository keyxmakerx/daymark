package com.daymark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.navigation.Routes
import com.daymark.app.ui.navigation.TopLevelDestination

/**
 * A paper-styled bottom navigation bar with the centre destination (Home) raised into a
 * circular accent button that floats slightly above the bar.
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
                    RaisedHomeItem(dest, onClick = { onNavigate(dest.route) }, modifier = Modifier.weight(1f))
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
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(painterResource(dest.icon), contentDescription = dest.label, tint = tint, modifier = Modifier.size(23.dp))
        Text(dest.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun RaisedHomeItem(
    dest: TopLevelDestination,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .offset(y = (-16).dp)
                .size(58.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(dest.icon),
                contentDescription = dest.label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}
