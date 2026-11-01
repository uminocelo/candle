package com.uminocelo.candle.resume

import android.content.SharedPreferences

class PrefsResumeStore(
    private val prefs: SharedPreferences,
    private val limit: Int = 20,
) : ResumeStore {
    override fun entries(): List<ResumeEntry> =
        prefs.getStringSet(KEY, null).orEmpty()
            .mapNotNull(ResumeCodec::decode)
            .sortedByDescending { it.updatedAt }

    override fun save(entry: ResumeEntry) = write(entries().upsert(entry, limit))

    override fun remove(sourceId: String, path: String) =
        write(entries().filterNot { it.sourceId == sourceId && it.path == path })

    private fun write(entries: List<ResumeEntry>) {
        prefs.edit().putStringSet(KEY, entries.map(ResumeCodec::encode).toSet()).apply()
    }

    private companion object {
        const val KEY = "entries"
    }
}
