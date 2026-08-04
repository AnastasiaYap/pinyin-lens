package io.tr8.pinyinlens.toggle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tr8.pinyinlens.toggle.Prefs.notificationEnabled

/** Handles the notification's action buttons. */
class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> Lens.toggle(context)
            ACTION_TOGGLE_OVERLAY -> Overlay.toggle(context)
            ACTION_HIDE -> {
                context.notificationEnabled = false
                NotificationController.cancel(context)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "io.tr8.pinyinlens.TOGGLE"
        const val ACTION_TOGGLE_OVERLAY = "io.tr8.pinyinlens.TOGGLE_OVERLAY"
        const val ACTION_HIDE = "io.tr8.pinyinlens.HIDE"
    }
}
