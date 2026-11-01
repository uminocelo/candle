package com.uminocelo.candle.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer

/**
 * Decodes a photo from any source at screen size. `ImageDecoder` applies the camera's
 * rotation (EXIF) and reads JPEG, PNG, WEBP, GIF, BMP and, where the chip can, HEIC.
 * Network photos are read into memory first: a phone photo is a few megabytes.
 */
class PhotoLoader(private val context: Context, private val screenWidth: Int, private val screenHeight: Int) {
    fun decode(source: MediaSource, item: MediaItem): Bitmap {
        val decoderSource = when (val stream = source.open(item)) {
            is MediaStream.Location -> fromUri(stream.uri)
            is MediaStream.Reader -> stream.use { ImageDecoder.createSource(ByteBuffer.wrap(readAll(it))) }
        }
        return ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
            val (width, height) = fitSize(info.size.width, info.size.height, screenWidth, screenHeight)
            decoder.setTargetSize(width, height)
        }
    }

    private fun fromUri(uri: String): ImageDecoder.Source = when (URI(uri).scheme) {
        "file" -> ImageDecoder.createSource(File(URI(uri)))
        "content" -> ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
        "http", "https" -> ImageDecoder.createSource(ByteBuffer.wrap(download(uri)))
        else -> throw IOException("cannot open $uri")
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) throw IOException("photo too large")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(if (length > 0) length.toInt() else 1 shl 20)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("photo too large")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readAll(reader: MediaStream.Reader): ByteArray {
        val length = reader.length ?: throw IOException("unknown size")
        if (length > MAX_BYTES) throw IOException("photo too large")
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = reader.read(read.toLong(), bytes, read, bytes.size - read)
            if (n < 0) throw IOException("file ended early")
            read += n
        }
        return bytes
    }

    private companion object {
        const val TIMEOUT_MS = 10_000

        /** Bigger than any camera photo; a guard against opening a huge file as a picture. */
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
