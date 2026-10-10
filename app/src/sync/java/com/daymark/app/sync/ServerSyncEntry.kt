package com.daymark.app.sync

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        // The Clinicians screens (#174) and the inbox (#177) open from inside this one, so they share
        // its door and its route.
        var page by rememberSaveable { mutableStateOf(SYNC) }
        when (page) {
            CLINICIANS -> CliniciansScreen(onBack = { page = SYNC })
            INBOX -> InboxScreen(onBack = { page = SYNC })
            else -> ServerSyncScreen(onBack = onBack, onClinicians = { page = CLINICIANS }, onInbox = { page = INBOX })
        }
    }

    private const val SYNC = "sync"
    private const val CLINICIANS = "clinicians"
    private const val INBOX = "inbox"
}
