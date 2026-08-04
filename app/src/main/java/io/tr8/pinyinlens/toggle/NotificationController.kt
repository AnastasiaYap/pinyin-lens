package io.tr8.pinyinlens.toggle

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.tr8.pinyinlens.MainActivity
import io.tr8.pinyinlens.R
import io.tr8.pinyinlens.toggle.Prefs.notificationEnabled

/**
 * The persistent notification toggle.
 *
 * Unlike a VPN's notification this one isn't keeping anything alive — the lens
 * is a component flag, not a running process. The notification survives on its
 * own, and tapping an action wakes us via [ToggleReceiver]. That means no
 * foreground service, and nothing for ColorOS's battery manager to kill.
 */
object NotificationController {

    private const val CHANNEL_ID = "lens_toggle"
    private const val NOTIFICATION_ID = 1
    private const val REGRANT_ID = 2

    fun refresh(context: Context) {
        if (context.notificationEnabled) post(context) else cancel(context)
    }

    // canPost() is the permission guard, but it splits on SDK_INT so lint can't
    // follow it back to the notify() call below.
    @SuppressLint("MissingPermission")
    fun post(context: Context) {
        if (!canPost(context)) return
        ensureChannel(context)

        val selection = Lens.isEnabled(context)
        val overlay = Overlay.isEnabled(context) && Overlay.isServiceGranted(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText(context, selection, overlay))
            .setContentIntent(activityIntent(context))
            .addAction(
                0,
                context.getString(R.string.action_selection) + if (selection) " ✓" else "",
                broadcast(context, ToggleReceiver.ACTION_TOGGLE, 1),
            )
            .addAction(
                0,
                context.getString(R.string.action_overlay) + if (overlay) " ✓" else "",
                broadcast(context, ToggleReceiver.ACTION_TOGGLE_OVERLAY, 4),
            )
            .addAction(
                0,
                context.getString(R.string.action_hide),
                broadcast(context, ToggleReceiver.ACTION_HIDE, 2),
            )
            // Android 14 lets users swipe away even an ongoing notification.
            // Treat that as "hide", so the switch in the app doesn't go on
            // claiming a notification that isn't there.
            .setDeleteIntent(broadcast(context, ToggleReceiver.ACTION_HIDE, 3))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Posted when an update has cleared the accessibility grant. Without it the
     * overlay simply stops working and nothing says why.
     */
    @SuppressLint("MissingPermission")
    fun postRegrantNeeded(context: Context) {
        if (!canPost(context)) return
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REGRANT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 5, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.regrant_title))
            .setContentText(context.getString(R.string.regrant_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.regrant_text))
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(REGRANT_ID, notification)
    }

    fun cancelRegrantNeeded(context: Context) {
        NotificationManagerCompat.from(context).cancel(REGRANT_ID)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun canPost(context: Context): Boolean {
        // POST_NOTIFICATIONS only became a runtime permission in Tiramisu; below
        // that the honest question is whether the user has muted us instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun statusText(context: Context, selection: Boolean, overlay: Boolean): String =
        when {
            overlay -> context.getString(R.string.overlay_on)
            selection -> context.getString(R.string.notification_on)
            else -> context.getString(R.string.notification_off)
        }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_toggle),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_toggle_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ToggleReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun activityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
