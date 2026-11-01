package com.uminocelo.candle.dlna

import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream
import com.uminocelo.candle.media.MediaType

/**
 * Browses a UPnP media server. Paths are ContentDirectory object ids ("0" is the
 * root); files open as the HTTP URL the server gives, which the player streams itself.
 */
class DlnaMediaSource(
    val server: DlnaServer,
    private val browse: (DlnaServer, String) -> List<DidlEntry> = DlnaClient::browse,
) : MediaSource {
    /** Item id → stream URL, filled by [list], so [open] needs no extra request. */
    private val urls = HashMap<String, String>()

    override val id: String get() = SOURCE_PREFIX + server.udn
    override val displayName: String get() = server.name

    /** Folders, and items that have a stream URL. Order is the server's. */
    override fun list(path: String): List<MediaItem> =
        browse(server, path.ifEmpty { ROOT_ID }).mapNotNull { entry ->
            when {
                entry.type == MediaType.FOLDER -> MediaItem.folder(entry.title, entry.id)
                entry.url == null -> null
                else -> {
                    synchronized(urls) { urls[entry.id] = entry.url }
                    MediaItem(entry.title, entry.id, entry.type, entry.size)
                }
            }
        }

    override fun open(item: MediaItem): MediaStream {
        require(!item.isFolder) { "cannot open a folder: ${item.path}" }
        val url = synchronized(urls) { urls[item.path] } ?: throw IllegalArgumentException("not listed yet: ${item.path}")
        return MediaStream.Location(url)
    }

    companion object {
        const val SOURCE_PREFIX = "dlna:"
        const val ROOT_ID = "0"
    }
}
