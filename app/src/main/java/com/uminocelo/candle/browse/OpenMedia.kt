package com.uminocelo.candle.browse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.uminocelo.candle.R
import com.uminocelo.candle.dlna.DlnaMediaSource
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.media.UsbMediaSource
import com.uminocelo.candle.player.NowPlayingActivity
import com.uminocelo.candle.player.PlaybackTarget
import com.uminocelo.candle.player.VideoPlayerActivity
import com.uminocelo.candle.smb.SmbMediaSource
import com.uminocelo.candle.viewer.PdfReaderActivity
import com.uminocelo.candle.viewer.PhotoViewerActivity
import java.io.File

/**
 * The player screen for a file picked in a browser (on the projector or the phone).
 * Songs on a USB drive queue their folder in Now Playing; everything else opens in the
 * video player, since the music queue only reads local folders. Null for a source
 * Candle cannot play from. DLNA items must have been listed by the same [source].
 */
fun playIntent(context: Context, source: MediaSource, item: MediaItem): Intent? = when (source) {
    is UsbMediaSource ->
        if (item.type == MediaType.AUDIO) {
            NowPlayingActivity.intent(context, File(item.path).parent ?: source.drive.mountPath, item.path)
        } else {
            VideoPlayerActivity.intent(context, PlaybackTarget.localFile(item.path))
        }
    is SmbMediaSource -> VideoPlayerActivity.intent(context, PlaybackTarget.smb(source.server.id, item.path))
    is DlnaMediaSource -> {
        val url = (source.open(item) as MediaStream.Location).uri
        VideoPlayerActivity.intent(context, PlaybackTarget.dlna(source.server.udn, url, item.name))
    }
    else -> null
}

/**
 * Opens a file picked in a projector browser: photos in the viewer with the rest of
 * their [folder], documents in the reader, videos and music in their players.
 */
fun Activity.openFromBrowser(source: MediaSource, item: MediaItem, folder: List<MediaItem>) {
    val intent = when (item.type) {
        MediaType.IMAGE -> PhotoViewerActivity.intent(this, source, folder.filter { it.type == MediaType.IMAGE }, item)
        MediaType.DOCUMENT -> if (PdfReaderActivity.canRead(item)) {
            PdfReaderActivity.intent(this, source, item)
        } else {
            val server = documentServerName(source)
            val text = if (server != null) getString(R.string.document_elsewhere_from, server) else getString(R.string.document_elsewhere)
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            null
        }
        else -> playIntent(this, source, item)
    }
    intent?.let(::startActivity)
}

/**
 * The media server to name when Candle cannot read a document (a phone can open it from
 * there, e.g. from Candle Server's web page); null for other sources and unnamed servers.
 */
internal fun documentServerName(source: MediaSource): String? {
    val server = (source as? DlnaMediaSource)?.server ?: return null
    return server.name.trim().takeIf { it.isNotEmpty() && it != server.udn }
}
