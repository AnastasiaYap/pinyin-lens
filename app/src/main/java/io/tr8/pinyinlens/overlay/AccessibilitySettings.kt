package io.tr8.pinyinlens.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Opens the system page where the accessibility service is granted.
 *
 * Nothing here can grant it — no app may enable its own accessibility service,
 * by design, since that is precisely the capability malware would want. The
 * most that is possible is to land the user on the right screen.
 */
object AccessibilitySettings {

    fun open(context: Context) {
        val component = ComponentName(context, PinyinAccessibilityService::class.java)

        // Android 12 can deep-link to this app's own accessibility page, which
        // turns "find Pinyin Lens in a long list" into a single toggle. The
        // action is not exposed as a constant in the SDK, hence the literal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val direct = Intent(ACTION_ACCESSIBILITY_DETAILS).apply {
                putExtra(EXTRA_COMPONENT_NAME, component.flattenToString())
                // Some OEM settings apps read the fragment-argument form instead.
                putExtra(
                    ":settings:show_fragment_args",
                    Bundle().apply {
                        putString(EXTRA_COMPONENT_NAME, component.flattenToString())
                    },
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(direct) }.isSuccess) return
        }

        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private const val ACTION_ACCESSIBILITY_DETAILS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"
}
