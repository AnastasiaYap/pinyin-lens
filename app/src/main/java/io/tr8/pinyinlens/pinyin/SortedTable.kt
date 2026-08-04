package io.tr8.pinyinlens.pinyin

import java.io.InputStream

/**
 * A sorted `key[\tvalue]` table, searched in place.
 *
 * The word list is ~180k entries. As a `HashMap<String, String>` that would
 * cost an estimated 25-30 MB of heap — a lot to pay in a process that spawns
 * fresh every time the user taps a selection-menu item. Holding each file as
 * one string plus an index of line offsets costs a measured 3.2 MB for both
 * tables together (words 2.5 MB, chars 0.7 MB), and a binary search over 180k
 * lines is ~18 comparisons.
 *
 * Assumes the asset is sorted by UTF-16 code unit, which is what
 * `String.compareTo` uses — `tools/build_dict.py` sorts on the UTF-16 bytes
 * for exactly this reason.
 */
class SortedTable private constructor(
    private val data: String,
    private val lineStarts: IntArray,
) {

    val size: Int get() = lineStarts.size

    /** Index of the line whose key is [key], or -1 if there is no such line. */
    fun indexOf(key: String): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareKeyAt(mid, key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The value on line [index], or null when the line carries only a key. */
    fun valueAt(index: Int): String? {
        var i = lineStarts[index]
        while (i < data.length && data[i] != '\n') {
            if (data[i] == '\t') {
                var end = i + 1
                while (end < data.length && data[end] != '\n') end++
                return data.substring(i + 1, end)
            }
            i++
        }
        return null
    }

    fun value(key: String): String? {
        val index = indexOf(key)
        return if (index < 0) null else valueAt(index)
    }

    fun contains(key: String): Boolean = indexOf(key) >= 0

    /** Sign of `storedKey.compareTo(key)`, without materialising the stored key. */
    private fun compareKeyAt(index: Int, key: String): Int {
        var i = lineStarts[index]
        var j = 0
        while (i < data.length) {
            val stored = data[i]
            if (stored == '\t' || stored == '\n') break
            if (j == key.length) return 1  // stored key is longer
            val diff = stored.compareTo(key[j])
            if (diff != 0) return diff
            i++
            j++
        }
        return if (j == key.length) 0 else -1  // stored key is shorter
    }

    companion object {
        fun read(input: InputStream): SortedTable {
            val data = input.bufferedReader().use { it.readText() }

            var lines = 0
            for (i in data.indices) if (data[i] == '\n') lines++
            // Tolerate a file that doesn't end in a newline.
            if (data.isNotEmpty() && data[data.length - 1] != '\n') lines++

            val starts = IntArray(lines)
            var line = 1
            for (i in data.indices) {
                if (data[i] == '\n' && line < lines) starts[line++] = i + 1
            }
            return SortedTable(data, starts)
        }
    }
}
