package com.uminocelo.candle.remote

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.uminocelo.candle.browse.playIntent
import com.uminocelo.candle.dlna.DlnaClient
import com.uminocelo.candle.dlna.DlnaMediaSource
import com.uminocelo.candle.dlna.DlnaServer
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.UsbMediaSource
import com.uminocelo.candle.smb.SmbServers
import com.uminocelo.candle.usb.mountedDrives
import java.util.concurrent.ConcurrentHashMap

/**
 * USB drives, saved SMB servers and DLNA servers for the phone. Source objects are kept
 * by id between calls: a DLNA source remembers the stream URLs of the items it listed.
 */
class AndroidRemoteLibrary(private val context: Context) : RemoteLibrary {
    private val known = ConcurrentHashMap<String, MediaSource>()

    @Volatile
    private var dlna: List<DlnaServer> = emptyList()

    @Volatile
    private var dlnaSearchedAt = 0L

    override fun sources(): List<MediaSource> {
        val smb = SmbServers(context)
        val current = context.mountedDrives().map(::UsbMediaSource) +
            smb.all().map(smb::source) +
            dlnaServers().map(::DlnaMediaSource)
        // Keep the source objects the phone is already browsing.
        val sources = current.map { known[it.id] ?: it }
        known.keys.retainAll(sources.map { it.id }.toSet())
        sources.forEach { known.putIfAbsent(it.id, it) }
        return sources
    }

    override fun source(id: String): MediaSource? = known[id] ?: sources().firstOrNull { it.id == id }

    override fun play(source: MediaSource, item: MediaItem): Boolean {
        val intent = playIntent(context, source, item) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Searches at most once a minute; a search takes about 3 s. */
    private fun dlnaServers(): List<DlnaServer> {
        val now = SystemClock.elapsedRealtime()
        if (dlnaSearchedAt == 0L || now - dlnaSearchedAt > DLNA_SEARCH_INTERVAL_MS) {
            runCatching { DlnaClient.discover() }
                .onSuccess { dlna = it }
                .onFailure { Log.w(TAG, "DLNA discovery failed", it) }
            dlnaSearchedAt = now
        }
        return dlna
    }

    private companion object {
        const val TAG = "CandleRemote"
        const val DLNA_SEARCH_INTERVAL_MS = 60_000L
    }
}
