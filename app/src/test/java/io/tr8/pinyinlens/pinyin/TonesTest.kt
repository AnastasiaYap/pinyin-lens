package io.tr8.pinyinlens.pinyin

import org.junit.Assert.assertEquals
import org.junit.Test

class TonesTest {

    @Test
    fun `reads the tone off a syllable`() {
        assertEquals(1, Tones.toneOf("zhōng"))
        assertEquals(2, Tones.toneOf("háng"))
        assertEquals(3, Tones.toneOf("wǒ"))
        assertEquals(4, Tones.toneOf("shì"))
        assertEquals(5, Tones.toneOf("de"))
    }

    @Test
    fun `strips the mark`() {
        assertEquals("zhong", Tones.stripTone("zhōng"))
        assertEquals("lü", Tones.stripTone("lǚ"))
        assertEquals("de", Tones.stripTone("de"))
    }

    @Test
    fun `rewrites the tone in place`() {
        assertEquals("bú", Tones.withTone("bù", 2))
        assertEquals("yì", Tones.withTone("yī", 4))
        assertEquals("ní", Tones.withTone("nǐ", 2))
    }

    @Test
    fun `places the mark by the standard rule`() {
        // a wins over everything
        assertEquals("hǎo", Tones.withTone("hao", 3))
        // otherwise the last vowel, which is what makes iu and ui correct
        assertEquals("liù", Tones.withTone("liu", 4))
        assertEquals("duì", Tones.withTone("dui", 4))
        assertEquals("guó", Tones.withTone("guo", 2))
    }

    @Test
    fun `neutral tone drops the mark`() {
        assertEquals("le", Tones.withTone("lè", 5))
    }

    @Test
    fun `round trips through every tone`() {
        for (tone in 1..5) {
            val marked = Tones.withTone("hao", tone)
            assertEquals(tone, Tones.toneOf(marked))
            assertEquals("hao", Tones.stripTone(marked))
        }
    }
}
