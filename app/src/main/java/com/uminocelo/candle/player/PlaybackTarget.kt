package com.uminocelo.candle.player

import com.uminocelo.candle.dlna.DlnaMediaSource
import com.uminocelo.candle.resume.ResumeEntry
import com.uminocelo.candle.smb.SmbServer
import com.uminocelo.candle.smb.SmbPaths
import java.io.File

/** One file to play: where it lives (for resume positions) and the URI the player opens. */
data class PlaybackTarget(
    val sourceId: String,
    val path: String,
    val title: String,
    val uri: String,
) {
    val isLocalFile: Boolean get() = sourceId == LOCAL_SOURCE

    /** Sent from a phone app; its URL usually stops working once the app closes, so it is never resumed. */
    val isCast: Boolean get() = sourceId == CAST_SOURCE

    companion object {
        /** Files on the projector's own storage or a mounted USB drive. */
        const val LOCAL_SOURCE = "file"

        /** Anything else opened by URI (e.g. `content://` from another app). */
        const val URI_SOURCE = "uri"

        /** A URL a phone app sent to the DLNA player. */
        const val CAST_SOURCE = "cast"

        fun localFile(path: String): PlaybackTarget {
            val file = File(path)
            return PlaybackTarget(LOCAL_SOURCE, file.path, file.nameWithoutExtension, file.toURI().toString())
        }

        fun uri(uri: String, title: String) = PlaybackTarget(URI_SOURCE, uri, title, uri)

        fun cast(url: String, title: String) = PlaybackTarget(CAST_SOURCE, url, title, url)

        /** A file on a saved SMB server; [path] is share-relative. */
        fun smb(serverId: String, path: String): PlaybackTarget = PlaybackTarget(
            SmbServer.SOURCE_PREFIX + serverId,
            path,
            SmbPaths.name(path).substringBeforeLast('.'),
            SmbUris.of(serverId, path),
        )

        /**
         * An item on a DLNA server, kept by its stream URL: object ids are not stable on
         * every server, and the URL is what the player needs to resume.
         */
        fun dlna(udn: String, url: String, title: String) =
            PlaybackTarget(DlnaMediaSource.SOURCE_PREFIX + udn, url, title, url)

        /** Rebuilds the target for a "Continue watching" entry, or null when its source is not supported yet. */
        fun forEntry(entry: ResumeEntry): PlaybackTarget? {
            if (entry.sourceId == LOCAL_SOURCE) return localFile(entry.path).copy(title = entry.title)
            if (entry.sourceId == URI_SOURCE) return uri(entry.path, entry.title)
            if (entry.sourceId.startsWith(DlnaMediaSource.SOURCE_PREFIX)) {
                return dlna(entry.sourceId.removePrefix(DlnaMediaSource.SOURCE_PREFIX), entry.path, entry.title)
            }
            return SmbServer.idFromSourceId(entry.sourceId)?.let { smb(it, entry.path).copy(title = entry.title) }
        }
    }
}
