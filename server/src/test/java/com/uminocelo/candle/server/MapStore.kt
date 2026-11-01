package com.uminocelo.candle.server

/** Settings kept in a map, for tests. */
internal class MapStore : SettingsStore {
    val values = mutableMapOf<String, Any>()

    override fun string(key: String) = values[key] as String?

    @Suppress("UNCHECKED_CAST")
    override fun stringSet(key: String) = values[key] as Set<String>?

    override fun boolean(key: String) = values[key] as Boolean?

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun putStringSet(key: String, value: Set<String>) {
        values[key] = value
    }

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
}
