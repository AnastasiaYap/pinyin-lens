package io.tr8.pinyinlens.toggle

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.tr8.pinyinlens.R

/** Quick Settings tile mirroring the same state as the notification toggle. */
class PinyinTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (Overlay.isServiceGranted(this)) Overlay.toggle(this) else Lens.toggle(this)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val granted = Overlay.isServiceGranted(this)
        val enabled = if (granted) Overlay.isEnabled(this) else Lens.isEnabled(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(
            if (enabled) R.string.notification_on else R.string.notification_off
        )
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, PinyinTileService::class.java),
                )
            }
        }
    }
}
