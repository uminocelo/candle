package com.uminocelo.candle.viewer

import android.content.SharedPreferences

/** Where each document was left, so it reopens there ("Continue reading"). */
class ReadingPositions(private val store: PageStore, private val limit: Int = 50) {
    interface PageStore {
        fun all(): Map<String, String>

        fun put(key: String, value: String?)
    }

    /** The page to open [path] of [sourceId] at: the saved one, kept inside the document. */
    fun startPage(sourceId: String, path: String, pageCount: Int): Int =
        (entry(key(sourceId, path))?.first ?: 0).coerceIn(0, maxOf(0, pageCount - 1))

    fun save(sourceId: String, path: String, page: Int, now: Long = System.currentTimeMillis()) {
        store.put(key(sourceId, path), "$page,$now")
        // The oldest go once there are more than [limit] books.
        store.all().keys.mapNotNull { key -> entry(key)?.let { key to it.second } }
            .sortedByDescending { it.second }
            .drop(limit)
            .forEach { store.put(it.first, null) }
    }

    private fun entry(key: String): Pair<Int, Long>? {
        val parts = store.all()[key]?.split(',') ?: return null
        val page = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return page to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun key(sourceId: String, path: String) = "$sourceId|$path"
}

class PrefsPageStore(private val prefs: SharedPreferences) : ReadingPositions.PageStore {
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
