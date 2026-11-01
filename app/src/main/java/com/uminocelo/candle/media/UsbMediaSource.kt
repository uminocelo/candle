package com.uminocelo.candle.media

/** A mounted USB drive. [mountPath] is where Android mounted it, e.g. `/storage/1234-ABCD`. */
data class UsbDrive(
    /** Filesystem UUID; null for filesystems that have none. */
    val uuid: String?,
    val label: String,
    val mountPath: String,
    val readOnly: Boolean = false,
) {
    /** True when [path] is this drive's mount point or anything below it. */
    fun contains(path: String): Boolean = isUnder(path, mountPath)

    /** The drive label followed by [path] relative to the drive, e.g. "Films/Kids". */
    fun breadcrumb(path: String): String = label + path.removePrefix(mountPath.trimEnd('/'))
}

/** True when [path] is [root] or inside it. */
fun isUnder(path: String, root: String): Boolean {
    val base = root.trimEnd('/')
    return path == base || path.startsWith("$base/")
}

/**
 * Browses a USB drive. Paths are absolute file paths, so the player and resume
 * positions treat drive files like any other local file.
 */
class UsbMediaSource(val drive: UsbDrive) : MediaSource {
    private val files = LocalFileSource(drive.mountPath)

    override val id: String = "usb:${drive.uuid ?: drive.mountPath}"
    override val displayName: String get() = drive.label

    /**
     * Skips hidden files and the folders Windows and Android leave on drives.
     * Throws [java.io.IOException] when the folder cannot be read.
     */
    override fun list(path: String): List<MediaItem> =
        files.list(path).filterNot { it.isFolder && it.name.lowercase() in SYSTEM_FOLDERS }

    override fun open(item: MediaItem): MediaStream = files.open(item)

    private companion object {
        val SYSTEM_FOLDERS = setOf("system volume information", "\$recycle.bin", "recycler", "lost.dir", "android")
    }
}
