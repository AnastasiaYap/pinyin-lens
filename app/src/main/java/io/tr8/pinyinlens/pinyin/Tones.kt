package io.tr8.pinyinlens.pinyin

/**
 * Reads the tone back off a tone-marked syllable.
 *
 * The colours themselves live in resources so they can differ between light and
 * dark themes; see `R.color.tone_*`.
 */
object Tones {

    private val MARKS = mapOf(
        'ā' to 1, 'ō' to 1, 'ē' to 1, 'ī' to 1, 'ū' to 1, 'ǖ' to 1,
        'á' to 2, 'ó' to 2, 'é' to 2, 'í' to 2, 'ú' to 2, 'ǘ' to 2,
        'ǎ' to 3, 'ǒ' to 3, 'ě' to 3, 'ǐ' to 3, 'ǔ' to 3, 'ǚ' to 3,
        'à' to 4, 'ò' to 4, 'è' to 4, 'ì' to 4, 'ù' to 4, 'ǜ' to 4,
    )

    /** 1-4, or 5 for the neutral tone. */
    fun toneOf(syllable: String): Int {
        for (ch in syllable) MARKS[ch]?.let { return it }
        return 5
    }
}
