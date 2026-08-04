package io.tr8.pinyinlens.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import io.tr8.pinyinlens.R
import io.tr8.pinyinlens.pinyin.RubyToken
import io.tr8.pinyinlens.ui.RubyLayout
import io.tr8.pinyinlens.ui.RubyTextView

/**
 * The whole-screen annotation layer.
 *
 * An accessibility service can read a text node's string and its bounding box,
 * but not glyph positions, font, size, or colour. So in-place annotation is
 * impossible: the only option is to cover each text block and re-render it.
 * That is what this does — opaque cards, one per block, drawn over the original
 * text.
 *
 * Consequences worth knowing, rather than discovering:
 *  - Cards are visibly cards. They do not blend into the host app.
 *  - Ruby text is roughly 1.6x the height of plain text, so a card is taller
 *    than the text it covers and can reach into whatever sits below.
 *  - Blocks whose cards would collide are dropped, newest-lowest first, so a
 *    dense screen annotates fewer blocks than it contains.
 *  - Anything not exposed as a text node — canvas drawing, games, video
 *    subtitles, text inside images — is invisible here and stays unannotated.
 */
class OverlayView(context: Context) : View(context) {

    /** A text block to annotate, in screen coordinates. */
    data class Block(val bounds: Rect, val tokens: List<RubyToken>)

    private class Placed(
        val card: RectF,
        val measured: RubyLayout.Measured,
        val textLeft: Float,
        val textTop: Float,
        /** Paints are shared and mutable, so each block records its own size. */
        val textSizePx: Float,
    )

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rubyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val palette = IntArray(RubyTextView.TONE_COLORS.size) {
        ContextCompat.getColor(context, RubyTextView.TONE_COLORS[it])
    }
    private val toneColorOf = RubyLayout.toneColorProvider(palette)

    private val engine = RubyLayout(
        basePaint = basePaint,
        rubyPaint = rubyPaint,
        cellGap = dp(1f),
        wordGap = dp(3f),
        rubyGap = dp(0.5f),
    )

    private val cardPadding = dp(6f)
    private val cardRadius = dp(8f)

    private var placed: List<Placed> = emptyList()
    private var pending: List<Block> = emptyList()
    private val screenOffset = IntArray(2)

    var toneColors: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Multiplier on the size derived from each block, driven by the Text size
     * slider. Card text still tracks the host's apparent size — this scales
     * that up or down rather than replacing it, so a caption stays smaller
     * than body text.
     */
    var sizeScale: Float = 1f
        set(value) {
            field = value
            relayout()
        }

    /**
     * Set the moment the screen changes. Cards drawn for the previous frame are
     * now in the wrong place, and showing them over content that has moved is
     * worse than showing nothing, so drawing stops until the next refresh.
     */
    private var stale = false

    init {
        basePaint.color = ContextCompat.getColor(context, R.color.text_primary)
        rubyPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        cardPaint.color = ContextCompat.getColor(context, R.color.overlay_card)
        cardStroke.color = ContextCompat.getColor(context, R.color.overlay_card_border)
    }

    fun setBlocks(blocks: List<Block>) {
        pending = blocks
        stale = false
        relayout()
    }

    fun markStale() {
        if (stale) return
        stale = true
        invalidate()
    }

    fun clear() {
        pending = emptyList()
        placed = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        // Node bounds are absolute screen coordinates, but this window does not
        // necessarily start at (0,0) — it is inset by the status bar. Without
        // this correction every card lands low by the inset height and covers
        // whatever sits below its text instead of the text itself.
        getLocationOnScreen(screenOffset)
        placed = layout(pending)
        invalidate()
    }

    /**
     * Sizes a card per block and drops any that would overlap one already
     * placed. Taller blocks are laid out first so the substantial text on a
     * screen wins over incidental labels.
     */
    private fun layout(blocks: List<Block>): List<Placed> {
        val out = ArrayList<Placed>()
        val ordered = blocks.sortedByDescending { it.bounds.height() * it.bounds.width() }

        for (block in ordered) {
            val bounds = block.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            // Match the host's apparent text size from the block's line height,
            // guessing line count from how tall the box is. It is a guess: the
            // node gives no font metrics.
            val estimatedLines = maxOf(1, Math.round(bounds.height() / dp(22f)))
            val perLine = (bounds.height().toFloat() / estimatedLines).coerceIn(dp(11f), dp(26f))
            // Scale after clamping, so the slider can still reach genuinely
            // small text; the floor only stops it collapsing to nothing.
            val sizePx = (perLine * sizeScale).coerceAtLeast(dp(6f))
            // Padding scales too, or small text ends up swimming in a card
            // sized for large text.
            val padding = (cardPadding * sizeScale).coerceAtLeast(dp(2f))
            applyTextSize(sizePx)

            val available = bounds.width() - 2 * padding
            if (available <= 0) continue
            val measured = engine.measure(block.tokens, available)

            val left = bounds.left - screenOffset[0]
            val top = bounds.top - screenOffset[1]
            val card = RectF(
                left.toFloat(),
                top.toFloat(),
                left + measured.width + 2 * padding,
                top + measured.height + 2 * padding,
            )
            if (card.right > width) card.right = width.toFloat()

            if (out.any { RectF.intersects(it.card, card) }) continue

            out += Placed(
                card = card,
                measured = measured,
                textLeft = card.left + padding,
                textTop = card.top + padding,
                textSizePx = sizePx,
            )
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        if (stale) return
        for (item in placed) {
            val radius = cardRadius * sizeScale
            canvas.drawRoundRect(item.card, radius, radius, cardPaint)
            canvas.drawRoundRect(item.card, radius, radius, cardStroke)
        }
        // Each block was measured at its own text size, so restore that size
        // before drawing its lines — the paints are shared.
        val ruby = ContextCompat.getColor(context, R.color.text_secondary)
        for (item in placed) {
            applyTextSize(item.textSizePx)
            engine.draw(
                canvas = canvas,
                measured = item.measured,
                left = item.textLeft,
                top = item.textTop,
                rubyColor = ruby,
                toneColorOf = if (toneColors) toneColorOf else null,
            )
        }
    }

    private fun applyTextSize(sizePx: Float) {
        basePaint.textSize = sizePx
        rubyPaint.textSize = sizePx * RubyTextView.RUBY_RATIO
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
