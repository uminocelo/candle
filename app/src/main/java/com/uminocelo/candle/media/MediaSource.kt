package com.uminocelo.candle.media

/**
 * A place media can be browsed and opened from: a USB drive, an SMB share, a DLNA server.
 *
 * Both calls may touch disk or network, so call them off the main thread.
 */
interface MediaSource {
    /** Stable id, used to remember resume positions and saved sources. */
    val id: String

    /** Name shown in the UI, e.g. the drive label or server name. */
    val displayName: String

    /** Lists the folder at [path]; [ROOT] is the top level. Order is unspecified; see [sortedForBrowsing]. */
    fun list(path: String = ROOT): List<MediaItem>

    /** Opens a file returned by [list]. Throws [IllegalArgumentException] for folders. */
    fun open(item: MediaItem): MediaStream

    companion object {
        const val ROOT = ""
    }
}
