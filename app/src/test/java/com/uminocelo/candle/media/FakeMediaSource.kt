package com.uminocelo.candle.media

/** In-memory [MediaSource] for tests: folder path → children. */
class FakeMediaSource(
    private val tree: Map<String, List<MediaItem>>,
    override val id: String = "fake",
    override val displayName: String = "Fake",
) : MediaSource {
    override fun list(path: String): List<MediaItem> =
        tree[path] ?: throw NoSuchElementException("no folder at '$path'")

    override fun open(item: MediaItem): MediaStream {
        require(!item.isFolder) { "cannot open a folder: ${item.path}" }
        return MediaStream.Location("fake://${item.path}")
    }
}
