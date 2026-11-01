package com.uminocelo.candle.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import com.uminocelo.candle.media.NaturalOrder
import com.uminocelo.candle.media.UsbDrive
import com.uminocelo.candle.media.isUnder
import java.io.File

/** The parts of a `StorageVolume` Candle uses, so the filtering can be unit tested. */
data class VolumeInfo(
    val removable: Boolean,
    val primary: Boolean,
    val state: String,
    val uuid: String?,
    val description: String?,
    val directory: String?,
)

/** A drive was mounted, or is going away ([mounted] false), at [mountPath]. */
data class DriveEvent(val mountPath: String, val mounted: Boolean) {
    fun affects(path: String): Boolean = isUnder(path, mountPath)
}

object UsbVolumes {
    private val MOUNTED_STATES = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
    private val REMOVED_ACTIONS = setOf(
        Intent.ACTION_MEDIA_EJECT,
        Intent.ACTION_MEDIA_UNMOUNTED,
        Intent.ACTION_MEDIA_BAD_REMOVAL,
        Intent.ACTION_MEDIA_REMOVED,
    )
    val ACTIONS = REMOVED_ACTIONS + Intent.ACTION_MEDIA_MOUNTED

    /** Mounted removable drives, sorted by label. Internal storage is left out. */
    fun drives(volumes: List<VolumeInfo>): List<UsbDrive> = volumes
        .filter { it.removable && !it.primary && it.state in MOUNTED_STATES && it.directory != null }
        .map { volume ->
            val path = volume.directory!!
            val label = volume.description?.takeIf { it.isNotBlank() } ?: volume.uuid ?: File(path).name
            UsbDrive(volume.uuid, label, path, readOnly = volume.state == Environment.MEDIA_MOUNTED_READ_ONLY)
        }
        .sortedWith(compareBy(NaturalOrder) { it.label })

    /** Turns a storage broadcast into a [DriveEvent], or null when it is not one. */
    fun eventFor(action: String?, path: String?): DriveEvent? = when {
        path == null -> null
        action == Intent.ACTION_MEDIA_MOUNTED -> DriveEvent(path, mounted = true)
        action in REMOVED_ACTIONS -> DriveEvent(path, mounted = false)
        else -> null
    }
}

/** Drives mounted right now. Talks to the storage service, so call it off the main thread. */
fun Context.mountedDrives(): List<UsbDrive> {
    val volumes = getSystemService(StorageManager::class.java).storageVolumes.map {
        VolumeInfo(it.isRemovable, it.isPrimary, it.state, it.uuid, it.getDescription(this), it.directory?.path)
    }
    return UsbVolumes.drives(volumes)
}

/** Calls [onEvent] on the main thread when a drive is mounted or removed, until the result is closed. */
fun Context.watchDrives(onEvent: (DriveEvent) -> Unit): AutoCloseable {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            UsbVolumes.eventFor(intent.action, intent.data?.path)?.let(onEvent)
        }
    }
    // Storage broadcasts carry the mount point as a file:// URI and only match filters with that scheme.
    val filter = IntentFilter().apply {
        UsbVolumes.ACTIONS.forEach(::addAction)
        addDataScheme("file")
    }
    registerReceiver(receiver, filter)
    return AutoCloseable { unregisterReceiver(receiver) }
}
