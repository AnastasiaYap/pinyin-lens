package io.tr8.pinyinlens.toggle

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.tr8.pinyinlens.R
import io.tr8.pinyinlens.ui.ProcessTextActivity

/**
 * Opens the sheet on whatever was last copied.
 *
 * This exists because the selection toolbar is not ours to control: an app can
 * make its text unselectable, replace the toolbar with its own, or simply never
 * declare the package-visibility entry that lets it see third-party handlers.
 * Copy survives all three, so the clipboard is the one route into those apps
 * that does not depend on their cooperation.
 *
 * The tile cannot read the clipboard itself — since Android 10 that needs
 * window focus — so it launches the sheet, which reads it once focused.
 */
class ClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.clipboard_tile)
            updateTile()
        }
    }

    // Deprecated below API 34 but there is no alternative there, and minSdk
    // is 26. The modern overload is used wherever it exists.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ProcessTextActivity::class.java)
            .setAction(ProcessTextActivity.ACTION_READ_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // The PendingIntent overload only exists from API 34; below that the
        // Intent form is the only one available, deprecated though it now is.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
