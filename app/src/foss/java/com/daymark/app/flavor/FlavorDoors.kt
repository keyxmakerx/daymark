package com.daymark.app.flavor

import com.daymark.app.ui.settings.ServerSyncDoor

/**
 * The `foss` flavour's doors to what only the `sync` flavour builds: none. The offline app has no
 * server to sync with, so Settings shows no such row and the navigation graph has no such route
 * (docs/COMPANION_PHONE.md §0). The `sync` flavour's file of the same name opens the door.
 */
object FlavorDoors {
    val serverSync: ServerSyncDoor? = null
}
