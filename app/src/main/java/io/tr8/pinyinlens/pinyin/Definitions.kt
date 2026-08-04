package io.tr8.pinyinlens.pinyin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * English glosses, for tap-to-define.
 *
 * Loaded lazily and separately from the readings: this table is several times
 * their size, the whole-screen overlay never needs it, and the sheet only needs
 * it once something is actually tapped.
 */
object Definitions {

    private val loadLock = Mutex()

    @Volatile
    private var table: SortedTable? = null

    suspend fun lookup(context: Context, word: String): String? {
        load(context)
        return table?.value(word)
    }

    /**
     * Longest match ending at or covering the tapped word: if the exact word is
     * absent, fall back to its first character, which usually still says
     * something useful.
     */
    suspend fun lookupOrChar(context: Context, word: String): Pair<String, String>? {
        load(context)
        val t = table ?: return null
        t.value(word)?.let { return word to it }
        if (word.isEmpty()) return null
        val first = word.substring(0, Character.charCount(word.codePointAt(0)))
        return t.value(first)?.let { first to it }
    }

    private suspend fun load(context: Context) {
        if (table != null) return
        loadLock.withLock {
            if (table != null) return
            withContext(Dispatchers.IO) {
                table = SortedTable.read(context.applicationContext.assets.open("defs.txt"))
            }
        }
    }
}
