package io.tr8.pinyinlens.pinyin

/**
 * Reads the tone off a tone-marked syllable, and writes a different one back.
 *
 * The colours themselves live in resources so they can differ between light and
 * dark themes; see `R.color.tone_*`.
 */
object Tones {

    /** Marked vowel -> tone number. */
    private val MARKS = mapOf(
        'ā' to 1, 'ō' to 1, 'ē' to 1, 'ī' to 1, 'ū' to 1, 'ǖ' to 1,
        'á' to 2, 'ó' to 2, 'é' to 2, 'í' to 2, 'ú' to 2, 'ǘ' to 2,
        'ǎ' to 3, 'ǒ' to 3, 'ě' to 3, 'ǐ' to 3, 'ǔ' to 3, 'ǚ' to 3,
        'à' to 4, 'ò' to 4, 'è' to 4, 'ì' to 4, 'ù' to 4, 'ǜ' to 4,
    )

    /** Marked vowel -> its plain form. */
    private val PLAIN = mapOf(
        'ā' to 'a', 'á' to 'a', 'ǎ' to 'a', 'à' to 'a',
        'ō' to 'o', 'ó' to 'o', 'ǒ' to 'o', 'ò' to 'o',
        'ē' to 'e', 'é' to 'e', 'ě' to 'e', 'è' to 'e',
        'ī' to 'i', 'í' to 'i', 'ǐ' to 'i', 'ì' to 'i',
        'ū' to 'u', 'ú' to 'u', 'ǔ' to 'u', 'ù' to 'u',
        'ǖ' to 'ü', 'ǘ' to 'ü', 'ǚ' to 'ü', 'ǜ' to 'ü',
    )

    /** Plain vowel -> its four marked forms, tone 1 to 4. */
    private val ROWS = mapOf(
        'a' to "āáǎà", 'o' to "ōóǒò", 'e' to "ēéěè",
        'i' to "īíǐì", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
    )

    /** 1-4, or 5 for the neutral tone. */
    fun toneOf(syllable: String): Int {
        for (ch in syllable) MARKS[ch]?.let { return it }
        return 5
    }

    fun stripTone(syllable: String): String = buildString(syllable.length) {
        for (ch in syllable) append(PLAIN[ch] ?: ch)
    }

    /**
     * Re-marks [syllable] with [tone]. Placement follows the standard rule —
     * `a` wins, then `o`, then `e`, otherwise the last vowel — which is what
     * gets `iu` marked as `iù` and `ui` as `uì`.
     */
    fun withTone(syllable: String, tone: Int): String {
        val plain = stripTone(syllable)
        if (tone == 5) return plain

        var index = -1
        for (vowel in charArrayOf('a', 'o', 'e')) {
            val at = plain.indexOf(vowel)
            if (at >= 0) { index = at; break }
        }
        if (index < 0) index = plain.indexOfLast { ROWS.containsKey(it) }
        if (index < 0) return plain

        val row = ROWS[plain[index]] ?: return plain
        return plain.substring(0, index) + row[tone - 1] + plain.substring(index + 1)
    }
}
