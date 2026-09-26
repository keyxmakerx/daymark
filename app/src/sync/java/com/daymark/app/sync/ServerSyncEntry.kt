package com.daymark.app.sync

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.daymark.app.ui.settings.ServerSyncDoor

/** The `sync` flavour's door into Settings (#432): the row, and the screen it opens. */
object ServerSyncEntry : ServerSyncDoor {

    @Composable
    override fun SettingsRow(onOpen: () -> Unit) {
        ListItem(
            headlineContent = { Text("Sync with your server") },
            supportingContent = { Text("Send an encrypted copy of your journal to your own Companion server") },
            modifier = Modifier.clickable { onOpen() },
        )
    }

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ServerSyncScreen(onBack = onBack)
    }
}
