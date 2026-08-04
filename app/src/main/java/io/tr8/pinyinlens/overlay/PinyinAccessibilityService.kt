package io.tr8.pinyinlens.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.tr8.pinyinlens.pinyin.PinyinEngine
import io.tr8.pinyinlens.pinyin.RubyToken
import io.tr8.pinyinlens.toggle.Overlay
import io.tr8.pinyinlens.toggle.Prefs.overlayScalePercent
import io.tr8.pinyinlens.toggle.Prefs.toneColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads the text on screen and drives [OverlayView].
 *
 * Screen content changes constantly, so events are debounced and each pass
 * re-reads the active window from scratch. Annotation runs off the main thread;
 * the traversal itself has to be on it, because node access is main-thread only.
 */
class PinyinAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var refreshJob: Job? = null

    private var overlay: OverlayView? = null
    private var windowManager: WindowManager? = null

    /** Annotating the same string twice is pure waste while scrolling. */
    private val cache = LruCache<String, List<RubyToken>>(512)

    private val refresh = Runnable { scheduleRefresh() }
    private var lastRefreshAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        if (Overlay.isEnabled(this)) attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val view = overlay ?: return
        // Whatever is drawn now describes the previous frame. Hide it at once
        // rather than leaving annotations over content that has scrolled away,
        // then redraw when the screen settles.
        view.markStale()
        handler.removeCallbacks(refresh)

        // A screen that never stops emitting events would reset the debounce
        // forever and the cards would never come back. Force a pass through if
        // it has been too long since the last one.
        val now = SystemClock.uptimeMillis()
        if (now - lastRefreshAt >= FORCE_REFRESH_MS) {
            handler.post(refresh)
        } else {
            handler.postDelayed(refresh, DEBOUNCE_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        detach()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // --- overlay window ---------------------------------------------------

    fun attach() {
        if (overlay != null) return
        val view = OverlayView(this).apply {
            toneColors = this@PinyinAccessibilityService.toneColors
            sizeScale = currentSizeScale()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take input: the layer is purely visual and everything
            // underneath must stay usable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager?.addView(view, params) }
            .onSuccess { overlay = view; scheduleRefresh() }
    }

    fun detach() {
        val view = overlay ?: return
        overlay = null
        refreshJob?.cancel()
        handler.removeCallbacks(refresh)
        runCatching { windowManager?.removeView(view) }
    }

    fun onToneColorsChanged() {
        overlay?.toneColors = toneColors
    }

    fun onTextSizeChanged() {
        overlay?.sizeScale = currentSizeScale()
    }

    private fun currentSizeScale() = overlayScalePercent / 100f

    // --- reading the screen -----------------------------------------------

    private fun scheduleRefresh() {
        lastRefreshAt = SystemClock.uptimeMillis()
        val view = overlay ?: return
        val root = rootInActiveWindow ?: run { view.clear(); return }

        // Node access must happen on the main thread, so collect the raw text
        // and bounds here and hand only plain data to the worker.
        val found = ArrayList<Pair<Rect, String>>()
        runCatching { collect(root, found) }
        root.recycle()

        if (found.isEmpty()) {
            view.clear()
            return
        }

        refreshJob?.cancel()
        refreshJob = scope.launch {
            val blocks = withContext(Dispatchers.Default) {
                found.mapNotNull { (bounds, text) ->
                    val tokens = cache.get(text) ?: PinyinEngine.annotate(
                        this@PinyinAccessibilityService, text,
                    ).also { cache.put(text, it) }
                    if (tokens.none { it.annotation != null }) null
                    else OverlayView.Block(bounds, tokens)
                }
            }
            overlay?.setBlocks(blocks)
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Pair<Rect, String>>) {
        if (out.size >= MAX_BLOCKS) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && containsHan(text) && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) out += bounds to text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out)
            child.recycle()
        }
    }

    private fun containsHan(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true
            i += Character.charCount(cp)
        }
        return false
    }

    companion object {
        /** Long enough to coalesce a scroll, short enough not to feel stuck. */
        private const val DEBOUNCE_MS = 180L

        /** Upper bound on how long cards may stay hidden during constant churn. */
        private const val FORCE_REFRESH_MS = 700L

        /** A hard stop so a pathological tree can't stall the main thread. */
        private const val MAX_BLOCKS = 120

        @Volatile
        var instance: PinyinAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
