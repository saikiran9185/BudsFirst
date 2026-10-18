package dev.saikiran.budsfirst

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: turns the guard on or off, e.g. to hand the sound to a lower device for a while. */
class GuardTile : TileService() {
    override fun onStartListening() {
        GuardClient.refresh()
        render()
    }

    override fun onClick() {
        GuardClient.setEnabled(!GuardClient.state.value.enabled)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = GuardClient.state.value
        tile.state = when {
            state.setup != Setup.Ready -> Tile.STATE_UNAVAILABLE
            state.enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "BudsFirst"
        tile.subtitle = when {
            state.setup != Setup.Ready -> "Start Shizuku"
            !state.enabled -> "Off"
            else -> state.priority.firstOrNull()?.let { first ->
                state.paired.firstOrNull { it.address == first }?.name
            } ?: "On"
        }
        tile.updateTile()
    }
}
