package io.tr8.pinyinlens.toggle

import android.content.Context
import androidx.core.content.edit

object Prefs {

    private const val FILE = "pinyin_lens"
    private const val KEY_NOTIFICATION = "notification_enabled"
    private const val KEY_TONE_COLORS = "tone_colors"
    private const val KEY_TEXT_SIZE = "text_size_sp"
    private const val KEY_OVERLAY_SCALE = "overlay_scale_percent"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_ONBOARDED = "onboarded"

    const val TEXT_SIZE_MIN = 12f
    const val TEXT_SIZE_MAX = 48f
    const val TEXT_SIZE_DEFAULT = 22f
    const val TEXT_SIZE_STEP = 2f

    // The overlay cannot use an absolute size: each card starts from the size
    // the host app appears to be using, so this scales that rather than
    // replacing it. Percent keeps the slider on integer steps.
    const val OVERLAY_SCALE_MIN = 40f
    const val OVERLAY_SCALE_MAX = 200f
    const val OVERLAY_SCALE_DEFAULT = 100f
    const val OVERLAY_SCALE_STEP = 10f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.notificationEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_NOTIFICATION, true)
        set(value) = prefs(this).edit { putBoolean(KEY_NOTIFICATION, value) }

    var Context.toneColors: Boolean
        get() = prefs(this).getBoolean(KEY_TONE_COLORS, true)
        set(value) = prefs(this).edit { putBoolean(KEY_TONE_COLORS, value) }

    /** Whether the how-to-use dialog has been shown once already. */
    var Context.onboarded: Boolean
        get() = prefs(this).getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs(this).edit { putBoolean(KEY_ONBOARDED, value) }

    /** Whole-screen overlay: on only once the accessibility service is granted. */
    var Context.overlayEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_OVERLAY, false)
        set(value) = prefs(this).edit { putBoolean(KEY_OVERLAY, value) }

    /** Character size in sp for the highlight sheet; pinyin scales with it. */
    var Context.textSizeSp: Float
        get() = prefs(this).getFloat(KEY_TEXT_SIZE, TEXT_SIZE_DEFAULT)
            .coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)
        set(value) = prefs(this).edit { putFloat(KEY_TEXT_SIZE, value) }

    /** Whole-screen card size, as a percentage of the host app's own size. */
    var Context.overlayScalePercent: Float
        get() = prefs(this).getFloat(KEY_OVERLAY_SCALE, OVERLAY_SCALE_DEFAULT)
            .coerceIn(OVERLAY_SCALE_MIN, OVERLAY_SCALE_MAX)
        set(value) = prefs(this).edit { putFloat(KEY_OVERLAY_SCALE, value) }
}
