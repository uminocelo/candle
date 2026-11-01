package com.uminocelo.candle.viewer

import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Local copies of documents from the network: Android's PDF renderer needs a file it
 * can seek in. Files on the projector (USB) are used where they are. The [keep] most
 * recently opened copies stay, so going back to a book does not copy it again.
 */
class DocumentCache(private val dir: File, private val keep: Int = 3) {
    /** The document as a local file; [progress] gets bytes copied and the total, when known. */
    fun local(source: MediaSource, item: MediaItem, progress: (Long, Long?) -> Unit = { _, _ -> }): File {
        val stream = source.open(item)
        if (stream is MediaStream.Location && URI(stream.uri).scheme == "file") return File(URI(stream.uri))
        val copy = File(dir, key(source, item) + "." + item.name.substringAfterLast('.', "bin").lowercase())
        if (copy.isFile && copy.length() > 0) {
            (stream as? MediaStream.Reader)?.close()
            copy.setLastModified(System.currentTimeMillis())
            return copy
        }
        dir.mkdirs()
        val part = File(dir, copy.name + ".part")
        try {
            when (stream) {
                is MediaStream.Location -> download(stream.uri, part, progress)
                is MediaStream.Reader -> stream.use { copyReader(it, part, progress) }
            }
            if (!part.renameTo(copy)) throw IOException("cannot keep $copy")
        } finally {
            part.delete()
        }
        trim()
        return copy
    }

    private fun download(url: String, to: File, progress: (Long, Long?) -> Unit) {
        val scheme = URI(url).scheme
        if (scheme != "http" && scheme != "https") throw IOException("cannot open $url")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it >= 0 }
            connection.inputStream.use { copyStream(it, to, total, progress) }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyReader(reader: MediaStream.Reader, to: File, progress: (Long, Long?) -> Unit) {
        val total = reader.length
        val buffer = ByteArray(BUFFER)
        var position = 0L
        to.outputStream().use { out ->
            while (true) {
                val n = reader.read(position, buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
                position += n
                progress(position, total)
            }
        }
        if (total != null && position != total) throw IOException("copied $position of $total bytes")
    }

    private fun copyStream(input: InputStream, to: File, total: Long?, progress: (Long, Long?) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        to.outputStream().use { out ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                copied += n
                progress(copied, total)
            }
        }
        if (total != null && copied != total) throw IOException("copied $copied of $total bytes")
    }

    private fun trim() {
        dir.listFiles { file -> !file.name.endsWith(".part") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    private fun key(source: MediaSource, item: MediaItem): String =
        MessageDigest.getInstance("SHA-1").digest("${source.id}|${item.path}|${item.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val BUFFER = 256 * 1024
    }
}
