package com.uminocelo.candle.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaStream
import com.uminocelo.candle.smb.SmbServer
import com.uminocelo.candle.smb.SmbServers

/** Plays a [MediaStream.Reader] (an SMB file), with seeking: each seek opens a new [ReaderRange]. */
@OptIn(UnstableApi::class)
class ReaderDataSource(private val openReader: (Uri) -> MediaStream.Reader) : BaseDataSource(true) {
    private var reader: MediaStream.Reader? = null
    private var range: ReaderRange? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val opened = openReader(dataSpec.uri).also { reader = it }
        val wanted = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        val newRange = ReaderRange(opened, dataSpec.position, wanted).also { range = it }
        transferStarted(dataSpec)
        return newRange.remaining ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = checkNotNull(range) { "not opened" }.read(buffer, offset, length)
        if (count == ReaderRange.END) return C.RESULT_END_OF_INPUT
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val wasOpen = range != null
        range = null
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (wasOpen) transferEnded()
        }
    }
}

/** Sends `smb://` URIs to [ReaderDataSource] and everything else to Media3's default sources. */
@OptIn(UnstableApi::class)
private class RoutingDataSource(private val default: DataSource, private val smb: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SmbUris.SCHEME) smb else default
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(current).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}

/** `smb://<server id>/<share path>`: how SMB files travel through [PlaybackTarget] and Media3. */
object SmbUris {
    const val SCHEME = "smb"

    /** Plain Java, so [PlaybackTarget] stays unit-testable. Spaces and the like are percent-encoded. */
    fun of(serverId: String, path: String): String =
        java.net.URI(SCHEME, serverId, "/" + path.trimStart('/'), null, null).toASCIIString()

    fun openReader(context: Context, uri: Uri): MediaStream.Reader {
        val servers = SmbServers(context)
        val server: SmbServer = uri.authority?.let(servers::get) ?: throw java.io.IOException("unknown SMB server in $uri")
        val path = uri.path.orEmpty().trimStart('/')
        return servers.source(server).open(MediaItem.file(path.substringAfterLast('/'), path)) as MediaStream.Reader
    }
}

/** The player's data sources: local files, content and HTTP as usual, plus `smb://`. */
@OptIn(UnstableApi::class)
fun candleDataSourceFactory(context: Context): DataSource.Factory {
    val appContext = context.applicationContext
    val defaults = DefaultDataSource.Factory(appContext)
    return DataSource.Factory {
        RoutingDataSource(defaults.createDataSource(), ReaderDataSource { SmbUris.openReader(appContext, it) })
    }
}
