package io.tr8.pinyinlens.pinyin

/**
 * Tone sandhi — the rules by which a written tone is not the tone spoken.
 *
 * A dictionary stores citation forms, so 不是 is listed `bù shì` even though
 * nobody says that. For a reading aid the spoken form is the useful one, and
 * showing the citation form teaches the wrong pronunciation.
 *
 * The 不 and 一 rules are applied always: they are deterministic and every
 * textbook marks them. Third-tone sandhi is left to a setting, because
 * dictionaries and most teaching materials deliberately leave it unmarked —
 * 你好 is written `nǐ hǎo` and said `ní hǎo`.
 */
object Sandhi {

    private const val BU = "不"
    private const val YI = "一"
    private const val ORDINAL_PREFIX = "第"

    /**
     * 一 only changes tone when it means "one of something". Next to these it
     * is a numeral being read out, and keeps its citation tone: 一月 is January,
     * not "one month", and 一二三 is counting aloud.
     */
    private val DATE_UNITS = setOf("月", "日", "号", "號")
    private val DIGITS = setOf(
        "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
        "两", "兩", "零", "百", "千", "万", "萬", "亿", "億",
    )

    /**
     * [citationReading] gives a character's dictionary reading, used when the
     * next syllable is neutral. 一个 is stored as `yī ge` because 个 reduces in
     * speech, which hides the fourth tone that triggers the 一 rule — so the
     * sandhi would silently not fire on one of the commonest phrases there is.
     */
    fun apply(
        tokens: List<RubyToken>,
        thirdTone: Boolean,
        citationReading: (String) -> String? = { null },
    ): List<RubyToken> {
        if (tokens.isEmpty()) return tokens
        val out = ArrayList(tokens)

        for (i in out.indices) {
            val token = out[i]
            val reading = token.annotation ?: continue
            val nextTone = nextAnnotatedTone(out, i, citationReading) ?: continue

            when (token.base) {
                // bù -> bú before a fourth tone.
                BU -> if (nextTone == 4) {
                    out[i] = token.copy(annotation = Tones.withTone(reading, 2))
                }
                // yī -> yí before a fourth tone, yì before the others, but
                // only where 一 is counting something rather than being a
                // numeral in its own right.
                YI -> if (yiTakesSandhi(out, i)) {
                    val tone = if (nextTone == 4) 2 else if (nextTone in 1..3) 4 else 0
                    if (tone != 0) out[i] = token.copy(annotation = Tones.withTone(reading, tone))
                }
            }
        }

        if (thirdTone) applyThirdTone(out)
        return out
    }

    private fun yiTakesSandhi(tokens: List<RubyToken>, at: Int): Boolean {
        val previous = tokens.getOrNull(at - 1)?.base
        // 第一 — an ordinal, so no change.
        if (previous == ORDINAL_PREFIX) return false
        // 十一月, 二十一 — 一 is a digit inside a larger number.
        if (previous in DIGITS) return false

        val next = tokens.getOrNull(at + 1)?.base ?: return false
        // 一月, 一号 — a date, not a count.
        if (next in DATE_UNITS) return false
        // 一二三 — reading digits aloud.
        if (next in DIGITS) return false
        return true
    }

    /**
     * A third tone before another third tone is said as a second. Applied only
     * inside a segmented word, since across a word boundary it depends on
     * phrasing that the text alone does not tell us.
     */
    private fun applyThirdTone(tokens: MutableList<RubyToken>) {
        for (i in 0 until tokens.size - 1) {
            val current = tokens[i].annotation ?: continue
            val next = tokens[i + 1]
            val following = next.annotation ?: continue
            // startsWord false means `next` continues the same word as `current`.
            if (next.startsWord) continue
            if (Tones.toneOf(current) == 3 && Tones.toneOf(following) == 3) {
                tokens[i] = tokens[i].copy(annotation = Tones.withTone(current, 2))
            }
        }
    }

    private fun nextAnnotatedTone(
        tokens: List<RubyToken>,
        from: Int,
        citationReading: (String) -> String?,
    ): Int? {
        val next = tokens.getOrNull(from + 1) ?: return null
        val reading = next.annotation ?: return null
        val tone = Tones.toneOf(reading)
        if (tone != 5) return tone
        // Neutral here may be a reduction of an underlying tone; the rules key
        // off the underlying one.
        return citationReading(next.base)?.let { Tones.toneOf(it) } ?: tone
    }
}
