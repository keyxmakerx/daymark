package com.daylie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daylie.app.data.entity.ActivityEntity
import com.daylie.app.ui.icon.ActivityIcons

/** Selectable chip used in the entry editor's activity grid. */
@Composable
fun ActivityChip(
    activity: ActivityEntity,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(activity.name) },
        leadingIcon = {
            Icon(
                imageVector = ActivityIcons.forKey(activity.iconKey),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        modifier = modifier,
    )
}

/** Small read-only activity badge for history/calendar detail rows. */
@Composable
fun ActivityBadge(activity: ActivityEntity, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = ActivityIcons.forKey(activity.iconKey),
                contentDescription = activity.name,
                modifier = Modifier.size(18.dp),
            )
            Text(text = activity.name, textAlign = TextAlign.Center)
        }
    }
}
