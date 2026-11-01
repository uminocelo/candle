package com.uminocelo.candle.server

import java.io.File
import java.util.UUID

/** Where settings live: SharedPreferences on the phone, a map in tests. */
interface SettingsStore {
    fun string(key: String): String?

    fun stringSet(key: String): Set<String>?

    fun boolean(key: String): Boolean?

    fun putString(key: String, value: String)

    fun putStringSet(key: String, value: Set<String>)

    fun putBoolean(key: String, value: Boolean)
}

/** A top-level folder of the phone's storage, shared or offered for sharing. */
data class SharedFolder(val name: String, val dir: File, val shared: Boolean) {
    val exists: Boolean get() = dir.isDirectory
}

/**
 * The server's settings: on/off, its name on the network, and which top-level folders
 * of [root] (the phone's storage) it shares. Read on every use, so changes apply at once.
 */
class ServerSettings(private val store: SettingsStore, private val root: File, private val defaultName: String) {
    var enabled: Boolean
        get() = store.boolean(KEY_ENABLED) ?: true
        set(value) = store.putBoolean(KEY_ENABLED, value)

    /** The name phones and players see; blank goes back to the phone's own name. */
    var name: String
        get() = store.string(KEY_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName
        set(value) = store.putString(KEY_NAME, value.trim())

    /** Whether players on the Wi-Fi (Candle, VLC, TVs) can browse and play; they cannot send a password. */
    var shareWithPlayers: Boolean
        get() = store.boolean(KEY_PLAYERS) ?: true
        set(value) = store.putBoolean(KEY_PLAYERS, value)

    /** The server's UPnP id, made once: players remember a server by it. */
    val uuid: String
        get() = store.string(KEY_UUID) ?: UUID.randomUUID().toString().also { store.putString(KEY_UUID, it) }

    /** Names of the shared folders, sorted; a folder that was deleted stays listed until unshared. */
    val sharedNames: List<String>
        get() = (store.stringSet(KEY_FOLDERS) ?: DEFAULT_FOLDERS.toSet()).sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** The shared folders that exist right now: what the server serves. */
    fun sharedFolders(): List<SharedFolder> = sharedNames.map { folder(it, shared = true) }.filter { it.exists }

    /** Every top-level folder of the storage plus the shared ones, for the settings screen; never apps' private data. */
    fun allFolders(): List<SharedFolder> {
        val shared = sharedNames.toSet()
        val onDisk = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != APP_DATA }?.map { it.name }.orEmpty()
        return (onDisk + shared).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).map { folder(it, it in shared) }
    }

    fun share(name: String) {
        require(isFolderName(name)) { "not a top-level folder name: $name" }
        store.putStringSet(KEY_FOLDERS, sharedNames.toSet() + name)
    }

    fun unshare(name: String) {
        store.putStringSet(KEY_FOLDERS, sharedNames.toSet() - name)
    }

    private fun folder(name: String, shared: Boolean) = SharedFolder(name, File(root, name), shared)

    companion object {
        /** Where phones keep films, songs, camera photos, downloads and documents. */
        val DEFAULT_FOLDERS = listOf("DCIM", "Documents", "Download", "Movies", "Music", "Pictures")

        /** Used when the phone reports neither a device name nor a model. */
        const val FALLBACK_NAME = "Candle Server"

        /** The phone's own name ([deviceName], set by the user), else its [model], else [FALLBACK_NAME]. */
        fun defaultName(deviceName: String?, model: String?): String =
            listOf(deviceName, model).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: FALLBACK_NAME

        private const val APP_DATA = "Android"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NAME = "name"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_PLAYERS = "share_with_players"
        private const val KEY_UUID = "uuid"

        /** One path segment: no separators, no `.` or `..`. */
        fun isFolderName(name: String) = name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".."
    }
}
