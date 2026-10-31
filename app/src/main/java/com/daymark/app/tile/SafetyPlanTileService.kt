package com.daymark.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.daymark.app.MainActivity

/**
 * The optional Quick Settings tile (#522). Tapping it opens the safety plan, through the same doors
 * as opening Daymark any other way: the phone's own unlock first, then the app's PIN when one is
 * set, because the activity starts fresh and so starts locked (MainActivity's UnlockState).
 *
 * What it must keep true:
 * - Its label is the app's name and nothing else, and it never sets a subtitle or a state
 *   description: the tile is drawn on the lock screen, where "safety plan" would tell whoever
 *   holds the phone something private. `SafetyPlanTileSourceTest` holds the manifest and this file
 *   to that.
 * - Daymark never adds it. It is there only once the person drags it into Quick Settings, so this
 *   file never calls `requestAddTileService`.
 * - It is a button, not a switch: always shown as inactive, so it reads as neither on nor off.
 */
class SafetyPlanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openPlan() } else openPlan()
    }

    private fun openPlan() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SAFETY_PLAN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST = 522
    }
}
