package io.tr8.pinyinlens

import android.app.Application
import io.tr8.pinyinlens.pinyin.PinyinEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The selection-menu entry usually starts a cold process, so the ~64k lines of
 * dictionary would otherwise be read while the user is already looking at a
 * spinner. Start that read here instead, in parallel with inflating the sheet.
 */
class PinyinLensApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch { PinyinEngine.load(this@PinyinLensApp) }
    }
}
