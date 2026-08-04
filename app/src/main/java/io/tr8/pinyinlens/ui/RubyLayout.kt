package io.tr8.pinyinlens.ui

import android.graphics.Canvas
import android.graphics.Paint
import io.tr8.pinyinlens.pinyin.RubyToken
import io.tr8.pinyinlens.pinyin.Tones
import kotlin.math.max

/** One glyph box: a character, and the syllable centred over it. */
internal data class RubyCell(
    val text: String,
    val ruby: String?,
    /** Width of the glyph box, excluding [leadingGap]. */
    val contentWidth: Float,
    /** Extra space before this cell, dropped when it starts a line. */
    val leadingGap: Float,
    val textOffset: Float,
    val rubyOffset: Float,
    val forcesBreak: Boolean,
    val startsWord: Boolean,
) {
    fun advance(firstOnLine: Boolean) =
        if (firstOnLine) contentWidth else contentWidth + leadingGap
}

/**
 * Lays out and draws ruby text.
 *
 * Extracted from [RubyTextView] so the whole-screen overlay can render the same
 * way without needing a View per text block — it draws many measured blocks
 * onto a single canvas.
 */
class RubyLayout(
    private val basePaint: Paint,
    private val rubyPaint: Paint,
    private val cellGap: Float,
    private val wordGap: Float,
    private val rubyGap: Float,
) {

    /** An immutable measured block, safe to hold while measuring others. */
    class Measured internal constructor(
        internal val lines: List<List<RubyCell>>,
        val width: Float,
        val height: Float,
        internal val lineHeight: Float,
    )

    val lineHeight: Float
        get() {
            val base = basePaint.fontMetrics
            val ruby = rubyPaint.fontMetrics
            return (base.descent - base.ascent) + (ruby.descent - ruby.ascent) + rubyGap
        }

    fun measure(tokens: List<RubyToken>, availableWidth: Float): Measured {
        val lines = breakLines(buildCells(tokens), availableWidth)
        val width = lines.maxOfOrNull { line ->
            var total = 0f
            line.forEachIndexed { index, cell -> total += cell.advance(index == 0) }
            total
        } ?: 0f
        return Measured(lines, width, lines.size * lineHeight, lineHeight)
    }

    fun draw(
        canvas: Canvas,
        measured: Measured,
        left: Float,
        top: Float,
        rubyColor: Int,
        toneColorOf: ((String) -> Int)? = null,
    ) {
        val rubyMetrics = rubyPaint.fontMetrics
        val baseMetrics = basePaint.fontMetrics
        val rubyBlockHeight = rubyMetrics.descent - rubyMetrics.ascent

        var lineTop = top
        for (line in measured.lines) {
            val rubyBaseline = lineTop - rubyMetrics.ascent
            val baseBaseline = lineTop + rubyBlockHeight + rubyGap - baseMetrics.ascent
            var x = left

            line.forEachIndexed { index, cell ->
                if (index > 0) x += cell.leadingGap
                if (cell.ruby != null) {
                    rubyPaint.color = toneColorOf?.invoke(cell.ruby) ?: rubyColor
                    canvas.drawText(cell.ruby, x + cell.rubyOffset, rubyBaseline, rubyPaint)
                }
                if (cell.text.isNotEmpty()) {
                    canvas.drawText(cell.text, x + cell.textOffset, baseBaseline, basePaint)
                }
                x += cell.contentWidth
            }
            lineTop += measured.lineHeight
        }
    }

    // --- internals --------------------------------------------------------

    private fun buildCells(tokens: List<RubyToken>): List<RubyCell> {
        val out = ArrayList<RubyCell>()
        for (token in tokens) {
            if (token.annotation != null) {
                out += cell(token.base, token.annotation, token.startsWord)
            } else {
                // Unannotated text still has to wrap sensibly, so break it into
                // whitespace-delimited chunks and keep hard newlines as breaks.
                for (chunk in CHUNK_RE.findAll(token.base).map { it.value }) {
                    if (chunk == "\n") {
                        out += RubyCell("", null, 0f, 0f, 0f, 0f, true, startsWord = true)
                    } else {
                        out += cell(chunk, null, startsWord = true)
                    }
                }
            }
        }
        return out
    }

    private fun cell(text: String, ruby: String?, startsWord: Boolean): RubyCell {
        val textWidth = basePaint.measureText(text)
        val rubyWidth = if (ruby != null) rubyPaint.measureText(ruby) else 0f
        val content = max(textWidth, rubyWidth) + if (ruby != null) cellGap else 0f
        return RubyCell(
            text = text,
            ruby = ruby,
            contentWidth = content,
            // Only annotated cells get word spacing; plain text already carries
            // its own spaces as cells.
            leadingGap = if (ruby != null && startsWord) wordGap else 0f,
            textOffset = (content - textWidth) / 2f,
            rubyOffset = (content - rubyWidth) / 2f,
            forcesBreak = false,
            startsWord = startsWord,
        )
    }

    private fun breakLines(cells: List<RubyCell>, availableWidth: Float): List<List<RubyCell>> {
        val result = ArrayList<List<RubyCell>>()
        var current = ArrayList<RubyCell>()

        fun widthOf(list: List<RubyCell>): Float {
            var total = 0f
            list.forEachIndexed { index, cell -> total += cell.advance(index == 0) }
            return total
        }

        for (cell in cells) {
            if (cell.forcesBreak) {
                result += current
                current = ArrayList()
                continue
            }
            // A leading space on a wrapped line reads as a stray indent.
            if (current.isEmpty() && cell.ruby == null && cell.text.isBlank()) continue

            if (widthOf(current) + cell.advance(current.isEmpty()) > availableWidth &&
                current.isNotEmpty()
            ) {
                // Prefer to break at a word boundary: walk back to the start of
                // the word in progress and carry it down. If that word already
                // starts the line it can't be helped, so break here.
                var breakAt = current.size
                if (!cell.startsWord) {
                    var idx = current.size - 1
                    while (idx > 0 && !current[idx].startsWord) idx--
                    if (idx > 0) breakAt = idx
                }
                val carried = ArrayList(current.subList(breakAt, current.size))
                result += ArrayList(current.subList(0, breakAt))
                current = carried
            }
            current += cell
        }
        result += current
        return result
    }

    companion object {
        val CHUNK_RE = Regex("\n|[^\\s]+|[^\\S\n]+")

        fun toneColorProvider(palette: IntArray): (String) -> Int =
            { syllable -> palette[Tones.toneOf(syllable) - 1] }
    }
}
