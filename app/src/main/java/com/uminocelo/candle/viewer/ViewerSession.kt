package com.uminocelo.candle.viewer

import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource

/**
 * What a browser hands to a viewer: the source, the files (a folder's photos, or the
 * one document) and which to start with. Sources hold open connections and, for DLNA,
 * the stream URLs of what they listed, so the same object is passed in memory rather
 * than rebuilt from ids.
 */
class ViewerSession(val source: MediaSource, val items: List<MediaItem>, val start: Int) {
    companion object {
        // Kept, not cleared on use, so a viewer survives being recreated; the next file opened replaces it.
        private var latest: ViewerSession? = null

        @Synchronized
        fun put(session: ViewerSession) {
            latest = session
        }

        @Synchronized
        fun current(): ViewerSession? = latest
    }
}
