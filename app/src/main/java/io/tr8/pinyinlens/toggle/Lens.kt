package io.tr8.pinyinlens.toggle

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.tr8.pinyinlens.ui.ProcessTextActivity

/**
 * On/off state for the lens.
 *
 * The component-enabled flag *is* the state — there is no separate preference
 * to drift out of sync. Disabling [ProcessTextActivity] removes "Pinyin" from
 * the text-selection toolbar everywhere; enabling it puts it back.
 */
object Lens {

    private fun component(context: Context) =
        ComponentName(context.applicationContext, ProcessTextActivity::class.java)

    fun isEnabled(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context))
        // The manifest declares it enabled, so DEFAULT means on.
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.packageManager.setComponentEnabledSetting(
            component(app),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        NotificationController.refresh(app)
        PinyinTileService.requestUpdate(app)
    }

    fun toggle(context: Context): Boolean {
        val next = !isEnabled(context)
        setEnabled(context, next)
        return next
    }
}
