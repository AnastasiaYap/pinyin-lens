package io.tr8.pinyinlens.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import io.tr8.pinyinlens.R
import io.tr8.pinyinlens.pinyin.RubyToken
import kotlin.math.ceil

/**
 * Draws text with pinyin set above each character.
 *
 * Rather than a font with the reading baked into the glyph, each character is
 * measured and its syllable centred over it. That costs a custom measure/draw
 * pass but keeps two things the font approach can't: context-correct
 * polyphones, and word grouping.
 *
 * The layout itself lives in [RubyLayout], shared with the whole-screen overlay.
 */
class RubyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(22f) }
    private val rubyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(10f) }

    private val palette = IntArray(TONE_COLORS.size) {
        ContextCompat.getColor(context, TONE_COLORS[it])
    }
    private val toneColorOf = RubyLayout.toneColorProvider(palette)

    private var layout = newLayout()
    private var measured: RubyLayout.Measured? = null

    private fun newLayout() = RubyLayout(
        basePaint = basePaint,
        rubyPaint = rubyPaint,
        cellGap = dp(2f),
        wordGap = dp(5f),
        rubyGap = dp(1f),
    )

    var tokens: List<RubyToken> = emptyList()
        set(value) {
            field = value
            // The annotations are drawn, not laid out as text, so a screen
            // reader would otherwise find an empty view here.
            contentDescription = value.joinToString("") { it.base }
            requestLayout()
            invalidate()
        }

    var baseTextColor: Int = 0xFF1A1A1A.toInt()
        set(value) {
            field = value
            basePaint.color = value
            invalidate()
        }

    var rubyTextColor: Int = 0xFF6B6B70.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** When true each syllable is drawn in its tone's colour. */
    var toneColors: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var baseTextSizeSp: Float = 22f
        set(value) {
            field = value
            basePaint.textSize = sp(value)
            rubyPaint.textSize = sp(value * RUBY_RATIO)
            layout = newLayout()
            requestLayout()
            invalidate()
        }

    init {
        basePaint.color = baseTextColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val available = when (widthMode) {
            MeasureSpec.UNSPECIFIED -> Float.MAX_VALUE
            else -> (widthSize - paddingLeft - paddingRight).toFloat()
        }
        val result = layout.measure(tokens, available)
        measured = result

        setMeasuredDimension(
            resolveSize(ceil(result.width).toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(ceil(result.height).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val result = measured ?: return
        layout.draw(
            canvas = canvas,
            measured = result,
            left = paddingLeft.toFloat(),
            top = paddingTop.toFloat(),
            rubyColor = rubyTextColor,
            toneColorOf = if (toneColors) toneColorOf else null,
        )
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        const val RUBY_RATIO = 10f / 22f

        /** Indexed by tone 1-4 then neutral. */
        val TONE_COLORS = intArrayOf(
            R.color.tone_1, R.color.tone_2, R.color.tone_3, R.color.tone_4, R.color.tone_neutral,
        )
    }
}
