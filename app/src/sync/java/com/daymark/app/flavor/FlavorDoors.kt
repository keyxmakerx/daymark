package com.daymark.app.flavor

import com.daymark.app.sync.ServerSyncEntry
import com.daymark.app.ui.settings.ServerSyncDoor

/**
 * The `sync` flavour's doors: Settings → Sync with your server (#432). The `foss` flavour's file of the
 * same name holds none, so the offline build never names anything here.
 */
object FlavorDoors {
    val serverSync: ServerSyncDoor? = ServerSyncEntry
}
