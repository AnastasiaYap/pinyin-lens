package io.tr8.pinyinlens.pinyin

import org.junit.Assert.assertEquals
import org.junit.Test

class SandhiTest {

    private fun tokens(vararg pairs: Pair<String, String?>, wordStarts: Set<Int> = emptySet()) =
        pairs.mapIndexed { i, (base, reading) ->
            RubyToken(base, reading, startsWord = wordStarts.isEmpty() || i in wordStarts)
        }

    private fun readings(list: List<RubyToken>) =
        list.mapNotNull { it.annotation }.joinToString(" ")

    @Test
    fun `bu becomes second tone before a fourth`() {
        val out = Sandhi.apply(tokens("不" to "bù", "是" to "shì"), thirdTone = false)
        assertEquals("bú shì", readings(out))
    }

    @Test
    fun `bu is unchanged before other tones`() {
        assertEquals("bù hǎo", readings(Sandhi.apply(tokens("不" to "bù", "好" to "hǎo"), false)))
        assertEquals("bù néng", readings(Sandhi.apply(tokens("不" to "bù", "能" to "néng"), false)))
    }

    @Test
    fun `yi becomes second tone before a fourth`() {
        assertEquals("yí gè", readings(Sandhi.apply(tokens("一" to "yī", "个" to "gè"), false)))
    }

    @Test
    fun `yi becomes fourth tone before first second and third`() {
        assertEquals("yì tiān", readings(Sandhi.apply(tokens("一" to "yī", "天" to "tiān"), false)))
        assertEquals("yì nián", readings(Sandhi.apply(tokens("一" to "yī", "年" to "nián"), false)))
        assertEquals("yì qǐ", readings(Sandhi.apply(tokens("一" to "yī", "起" to "qǐ"), false)))
    }

    @Test
    fun `yi keeps its citation form in an ordinal`() {
        val out = Sandhi.apply(tokens("第" to "dì", "一" to "yī", "课" to "kè"), false)
        assertEquals("dì yī kè", readings(out))
    }

    @Test
    fun `yi is unchanged with nothing following`() {
        assertEquals("yī", readings(Sandhi.apply(tokens("一" to "yī"), false)))
    }

    @Test
    fun `third tone sandhi is off by default`() {
        val within = tokens("你" to "nǐ", "好" to "hǎo", wordStarts = setOf(0))
        assertEquals("nǐ hǎo", readings(Sandhi.apply(within, thirdTone = false)))
    }

    @Test
    fun `third tone sandhi applies inside a word when enabled`() {
        val within = tokens("你" to "nǐ", "好" to "hǎo", wordStarts = setOf(0))
        assertEquals("ní hǎo", readings(Sandhi.apply(within, thirdTone = true)))
    }

    @Test
    fun `third tone sandhi does not cross a word boundary`() {
        // Both start words, so they are separate words and phrasing is unknown.
        val across = tokens("很" to "hěn", "好" to "hǎo", wordStarts = setOf(0, 1))
        assertEquals("hěn hǎo", readings(Sandhi.apply(across, thirdTone = true)))
    }

    @Test
    fun `yi fires through a neutralised following syllable`() {
        // 一个 is stored as "yī ge": 个 reduces in speech, hiding the fourth
        // tone that triggers the rule. The citation reading restores it.
        val out = Sandhi.apply(
            tokens("一" to "yī", "个" to "ge"),
            thirdTone = false,
            citationReading = { base -> if (base == "个") "gè" else null },
        )
        assertEquals("yí ge", readings(out))
    }

    @Test
    fun `a genuinely neutral syllable does not trigger sandhi`() {
        val out = Sandhi.apply(
            tokens("不" to "bù", "了" to "le"),
            thirdTone = false,
            citationReading = { base -> if (base == "了") "le" else null },
        )
        assertEquals("bù le", readings(out))
    }

    @Test
    fun `unannotated tokens are left alone`() {
        val out = Sandhi.apply(tokens("不" to null, "!" to null), false)
        assertEquals("", readings(out))
    }
}
