package io.tr8.pinyinlens.toggle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android clears posted notifications on reboot, so a toggle that lives in one
 * would quietly disappear until the app was opened again. Re-post it.
 *
 * The lens state itself needs no restoring — it's a PackageManager component
 * flag, which is already persistent.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                NotificationController.refresh(context)
                // An update can clear the accessibility grant. Say so rather
                // than letting the overlay quietly stop working.
                if (Overlay.needsRegrant(context)) {
                    NotificationController.postRegrantNeeded(context)
                }
            }
        }
    }
}
