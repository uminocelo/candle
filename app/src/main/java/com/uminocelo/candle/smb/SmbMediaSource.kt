package com.uminocelo.candle.smb

import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream

/**
 * Browses a saved SMB server. Paths are share-relative (`Films/a.mkv`); files open
 * as [MediaStream.Reader]s because the player cannot open `smb://` itself.
 */
class SmbMediaSource(
    val server: SmbServer,
    private val connect: () -> SmbShare,
) : MediaSource {
    override val id: String get() = server.sourceId
    override val displayName: String get() = server.displayName

    /** Skips hidden and system entries and dot-files. Throws [java.io.IOException] when the share cannot be read. */
    override fun list(path: String): List<MediaItem> =
        connect().list(path)
            .filterNot { it.hidden || it.name.startsWith(".") }
            .map { entry ->
                val childPath = SmbPaths.child(path, entry.name)
                if (entry.isFolder) MediaItem.folder(entry.name, childPath) else MediaItem.file(entry.name, childPath, entry.size)
            }

    override fun open(item: MediaItem): MediaStream {
        require(!item.isFolder) { "cannot open a folder: ${item.path}" }
        return SmbReader(connect().open(item.path))
    }
}

private class SmbReader(private val file: SmbFile) : MediaStream.Reader {
    override val length: Long get() = file.length

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        file.read(position, buffer, offset, length)

    override fun close() = file.close()
}
