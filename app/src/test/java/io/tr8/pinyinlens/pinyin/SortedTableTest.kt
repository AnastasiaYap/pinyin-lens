package io.tr8.pinyinlens.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SortedTableTest {

    private fun table(vararg lines: String) =
        SortedTable.read(lines.joinToString("\n", postfix = "\n").byteInputStream())

    @Test
    fun `finds first middle and last entries`() {
        val t = table("aa", "bb", "cc", "dd", "ee")
        assertEquals(5, t.size)
        assertTrue(t.contains("aa"))
        assertTrue(t.contains("cc"))
        assertTrue(t.contains("ee"))
    }

    @Test
    fun `reports absent keys`() {
        val t = table("aa", "cc", "ee")
        assertFalse(t.contains("bb"))
        assertFalse(t.contains("zz"))
        assertFalse(t.contains("a"))   // before the first entry
        assertFalse(t.contains(""))
    }

    @Test
    fun `distinguishes a key from its own prefix`() {
        // The comparison walks characters without materialising the stored key,
        // so prefix relationships are where it would most easily go wrong.
        val t = table("ab", "abc", "abcd")
        assertTrue(t.contains("ab"))
        assertTrue(t.contains("abc"))
        assertTrue(t.contains("abcd"))
        assertFalse(t.contains("abcde"))
        assertFalse(t.contains("a"))
    }

    @Test
    fun `key-only lines have no value`() {
        val t = table("aa", "bb\tvalue", "cc")
        assertNull(t.value("aa"))
        assertEquals("value", t.value("bb"))
        assertNull(t.value("cc"))
    }

    @Test
    fun `values may contain spaces and survive at the last line`() {
        val t = table("aa\tone", "bb\tyín háng")
        assertEquals("yín háng", t.value("bb"))
    }

    @Test
    fun `tolerates a missing trailing newline`() {
        val t = SortedTable.read("aa\tone\nbb\ttwo".byteInputStream())
        assertEquals(2, t.size)
        assertEquals("two", t.value("bb"))
    }

    @Test
    fun `handles an empty table`() {
        val t = SortedTable.read("".byteInputStream())
        assertEquals(0, t.size)
        assertFalse(t.contains("anything"))
    }

    @Test
    fun `handles a single-entry table`() {
        val t = table("only\tvalue")
        assertEquals("value", t.value("only"))
        assertFalse(t.contains("other"))
    }

    @Test
    fun `searches CJK keys in UTF-16 order`() {
        // Sorted the way build_dict.py writes them: by UTF-16 code unit. The
        // supplementary character (surrogate pair, U+20000) sorts BEFORE the
        // BMP compatibility ideograph U+F900, which is the opposite of code
        // point order - the exact trap this ordering exists to avoid.
        val supplementary = String(Character.toChars(0x20000))
        val compat = "豈"
        val t = table("一", "银行\tyín háng", supplementary, compat)

        assertTrue(t.contains("一"))
        assertEquals("yín háng", t.value("银行"))
        assertTrue(t.contains(supplementary))
        assertTrue(t.contains(compat))
    }

    @Test
    fun `every entry in a large table is findable`() {
        val keys = (0 until 5_000).map { "key%05d".format(it) }.sorted()
        val t = SortedTable.read(
            keys.joinToString("\n", postfix = "\n") { "$it\tv$it" }.byteInputStream()
        )
        for (key in keys) assertEquals("v$key", t.value(key))
        assertFalse(t.contains("key99999"))
    }
}
