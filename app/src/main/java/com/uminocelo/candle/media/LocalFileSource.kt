package com.uminocelo.candle.media

import java.io.File

/** Files on the projector's storage or a mounted drive. Paths are absolute file paths. */
class LocalFileSource(
    private val rootPath: String,
    override val id: String = "file",
    override val displayName: String = File(rootPath).name,
) : MediaSource {
    override fun list(path: String): List<MediaItem> {
        val folder = File(path.ifEmpty { rootPath })
        val children = folder.listFiles() ?: throw java.io.IOException("cannot read folder ${folder.path}")
        return children
            .filterNot { it.isHidden }
            .map { if (it.isDirectory) MediaItem.folder(it.name, it.path) else MediaItem.file(it.name, it.path, it.length()) }
    }

    override fun open(item: MediaItem): MediaStream {
        require(!item.isFolder) { "cannot open a folder: ${item.path}" }
        return MediaStream.Location(File(item.path).toURI().toString())
    }
}
