package io.tr8.pinyinlens.toggle

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import io.tr8.pinyinlens.overlay.PinyinAccessibilityService
import io.tr8.pinyinlens.toggle.Prefs.overlayEnabled

/**
 * Whole-screen overlay state.
 *
 * Two independent things have to be true for anything to appear: the user has
 * granted the accessibility service, and the overlay is switched on. The grant
 * can only be given in system settings, and ColorOS may revoke it on reboot or
 * when its battery manager intervenes — so it is read back from the system
 * every time rather than cached.
 */
object Overlay {

    /** Whether the user has granted the accessibility service. */
    fun isServiceGranted(context: Context): Boolean {
        val expected = ComponentName(context, PinyinAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    /** Whether the user wants the overlay on. Meaningless without the grant. */
    fun isEnabled(context: Context): Boolean = context.overlayEnabled

    fun isActive(context: Context): Boolean =
        isEnabled(context) && isServiceGranted(context) && PinyinAccessibilityService.isRunning

    fun setEnabled(context: Context, enabled: Boolean) {
        context.overlayEnabled = enabled
        val service = PinyinAccessibilityService.instance
        if (enabled) service?.attach() else service?.detach()
        NotificationController.refresh(context)
        PinyinTileService.requestUpdate(context)
    }

    fun toggle(context: Context): Boolean {
        val next = !isEnabled(context)
        setEnabled(context, next)
        return next
    }
}
