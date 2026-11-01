package com.uminocelo.candle.media

/**
 * One entry in a [MediaSource] listing.
 *
 * [path] is opaque to everyone but the source that produced it; pass it back to
 * [MediaSource.list] (folders) or [MediaSource.open] (files).
 */
data class MediaItem(
    val name: String,
    val path: String,
    val type: MediaType,
    /** Size in bytes, or null when the source does not know it (folders, some DLNA items). */
    val size: Long? = null,
) {
    val isFolder: Boolean get() = type == MediaType.FOLDER

    companion object {
        fun folder(name: String, path: String) = MediaItem(name, path, MediaType.FOLDER)

        fun file(name: String, path: String, size: Long? = null) =
            MediaItem(name, path, MediaType.fromFileName(name), size)
    }
}
