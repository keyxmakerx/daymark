package com.daymark.app.ui.settings

import androidx.compose.runtime.Composable

/**
 * The one way the app's shared code reaches the Companion sync screen (#432). The `sync` flavour
 * provides it; the `foss` flavour provides none (`com.daymark.app.flavor.FlavorDoors`, one file in each
 * flavour's source set), so the offline build has no row, no route and nothing behind either that could
 * reach a network (docs/COMPANION_PHONE.md §0). Nothing here names a class of the `sync` flavour: the
 * `foss` build compiles against this interface alone.
 */
interface ServerSyncDoor {

    /** The row in Settings that opens the screen. */
    @Composable
    fun SettingsRow(onOpen: () -> Unit)

    /** The screen: pairing, the passphrase, and sending a copy. */
    @Composable
    fun Screen(onBack: () -> Unit)
}
