package com.enlace.app.modules.mic

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The quick-settings button: pull the shade down, tap, the PC has a microphone. */
class MicTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (MicService.state == MicService.State.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        if (MicService.state != MicService.State.OFF) {
            MicService.stop(this)
            return
        }
        // Android only lets a microphone service start from something on
        // screen, so the tile opens an invisible activity that starts it.
        val intent = Intent(this, MicStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
