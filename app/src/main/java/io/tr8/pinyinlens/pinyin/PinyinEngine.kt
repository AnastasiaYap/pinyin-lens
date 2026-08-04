package io.tr8.pinyinlens.pinyin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One unit of annotated text: [base] is what the user wrote, [annotation] is
 * the pinyin above it (null for anything that isn't a Han character).
 *
 * [startsWord] marks the first character of a segmented word. The renderer uses
 * it to group words visually and to avoid breaking a line mid-word — neither of
 * which a font with the reading baked into the glyph can do, because by then
 * the word boundaries are gone.
 */
data class RubyToken(
    val base: String,
    val annotation: String?,
    val startsWord: Boolean = true,
)

/**
 * Turns Chinese text into ruby tokens.
 *
 * Polyphones are resolved by forward maximum matching against a word list, so
 * 银行 comes out `yín háng` rather than `yín xíng`. Characters not covered by
 * any word fall back to their single most common reading.
 */
object PinyinEngine {

    private const val MAX_WORD_LEN = 6

    private val loadLock = Mutex()

    @Volatile
    private var chars: SortedTable? = null

    @Volatile
    private var words: SortedTable? = null

    val isLoaded: Boolean get() = chars != null && words != null

    suspend fun load(context: Context) {
        if (isLoaded) return
        loadLock.withLock {
            if (isLoaded) return
            withContext(Dispatchers.IO) {
                val assets = context.applicationContext.assets
                chars = SortedTable.read(assets.open("chars.txt"))
                words = SortedTable.read(assets.open("words.txt"))
            }
        }
    }

    suspend fun annotate(
        context: Context,
        text: String,
        thirdToneSandhi: Boolean = false,
    ): List<RubyToken> {
        load(context)
        return annotateLoaded(text, thirdToneSandhi)
    }

    /** Synchronous variant for callers that have already awaited [load]. */
    fun annotateLoaded(text: String, thirdToneSandhi: Boolean = false): List<RubyToken> {
        val charTable = chars ?: return listOf(RubyToken(text, null))
        val wordTable = words ?: return listOf(RubyToken(text, null))

        val out = ArrayList<RubyToken>()
        val plain = StringBuilder()
        val hanRun = ArrayList<String>()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += RubyToken(plain.toString(), null)
                plain.setLength(0)
            }
        }

        fun flushHan() {
            if (hanRun.isNotEmpty()) {
                out += annotateHanRun(hanRun, charTable, wordTable)
                hanRun.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val piece = text.substring(i, i + width)
            if (isHan(cp)) {
                flushPlain()
                hanRun += piece
            } else {
                flushHan()
                plain.append(piece)
            }
            i += width
        }
        flushHan()
        flushPlain()
        // Sandhi runs over the finished sequence: the rules look across word
        // boundaries, so they cannot be applied while segmenting.
        return Sandhi.apply(out, thirdToneSandhi) { base -> charTable.value(base) }
    }

    private fun annotateHanRun(
        run: List<String>,
        charTable: SortedTable,
        wordTable: SortedTable,
    ): List<RubyToken> {
        val out = ArrayList<RubyToken>(run.size)
        var i = 0
        while (i < run.size) {
            var matched = false
            val longest = minOf(MAX_WORD_LEN, run.size - i)
            for (len in longest downTo 2) {
                val candidate = run.subList(i, i + len).joinToString("")
                val line = wordTable.indexOf(candidate)
                if (line < 0) continue

                // Most entries carry no reading: the word exists only to mark a
                // boundary, because its pronunciation is just the per-character
                // default. Those still group and still resist mid-word breaks.
                val syllables = wordTable.valueAt(line)?.split(' ')
                if (syllables != null && syllables.size != len) continue

                for (k in 0 until len) {
                    val syllable = syllables?.get(k) ?: charTable.value(run[i + k])
                    out += RubyToken(run[i + k], syllable, startsWord = k == 0)
                }
                i += len
                matched = true
                break
            }
            if (!matched) {
                out += RubyToken(run[i], charTable.value(run[i]))
                i++
            }
        }
        return out
    }

    private fun isHan(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN -> true
        else -> false
    }
}
