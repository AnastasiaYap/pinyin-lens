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
        for (length in segment(run, wordTable)) {
            val word = if (length == 1) null else run.subList(i, i + length).joinToString("")
            val syllables = word?.let { candidate ->
                val line = wordTable.indexOf(candidate)
                if (line < 0) null else wordTable.valueAt(line)?.split(' ')
            }
            for (k in 0 until length) {
                val syllable = syllables?.getOrNull(k) ?: charTable.value(run[i + k])
                out += RubyToken(run[i + k], syllable, startsWord = k == 0)
            }
            i += length
        }
        return out
    }

    /**
     * Splits a run of Han characters into word lengths.
     *
     * Greedy longest-match commits to the longest word at each position and
     * cannot back out, which mis-segments 北京大学生 as 北京大学 / 生. Running the
     * same greedy match from the right as well and keeping the better parse
     * fixes that case and 研究生命科学, at the cost of one extra pass.
     */
    private fun segment(run: List<String>, wordTable: SortedTable): List<Int> {
        val forward = greedy(run, wordTable, fromLeft = true)
        val backward = greedy(run, wordTable, fromLeft = false)
        if (forward.size != backward.size) {
            return if (forward.size < backward.size) forward else backward
        }
        // Same word count: prefer the parse that strands fewer lone characters.
        val forwardSingles = forward.count { it == 1 }
        val backwardSingles = backward.count { it == 1 }
        return if (backwardSingles < forwardSingles) backward else forward
    }

    private fun greedy(
        run: List<String>,
        wordTable: SortedTable,
        fromLeft: Boolean,
    ): List<Int> {
        val lengths = ArrayList<Int>()
        var cursor = if (fromLeft) 0 else run.size

        while (if (fromLeft) cursor < run.size else cursor > 0) {
            val remaining = if (fromLeft) run.size - cursor else cursor
            var taken = 1
            for (len in minOf(MAX_WORD_LEN, remaining) downTo 2) {
                val from = if (fromLeft) cursor else cursor - len
                val candidate = run.subList(from, from + len).joinToString("")
                val line = wordTable.indexOf(candidate)
                if (line < 0) continue
                // Reject a word whose stored reading does not line up with its
                // characters; it would annotate the wrong syllables.
                val syllables = wordTable.valueAt(line)?.split(' ')
                if (syllables != null && syllables.size != len) continue
                taken = len
                break
            }
            if (fromLeft) {
                lengths += taken
                cursor += taken
            } else {
                lengths.add(0, taken)
                cursor -= taken
            }
        }
        return lengths
    }

    private fun isHan(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN -> true
        else -> false
    }
}
