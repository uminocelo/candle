package com.uminocelo.candle.server

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Log
import android.util.Size
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Android's own photo and video previews (the ones the Gallery shows), made on first
 * request and kept as JPEGs in [dir], named after the file's path, size and date so
 * an edited file gets a new one. One at a time: decoding a 4K video frame is heavy.
 */
class AndroidThumbnails(private val dir: File) : Thumbnails {
    @Synchronized
    override fun jpeg(item: IndexEntry.Item): File? {
        val cached = File(dir, key(item) + ".jpg")
        if (cached.isFile) return cached
        val bitmap = try {
            when (item.type.kind) {
                MediaKind.IMAGE -> ThumbnailUtils.createImageThumbnail(item.file, SIZE, null)
                MediaKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(item.file, SIZE, null)
                else -> return null
            }
        } catch (e: IOException) {
            Log.i(TAG, "no thumbnail for ${item.id}: ${e.message}")
            return null
        }
        dir.mkdirs()
        val temp = File(dir, cached.name + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        return if (temp.renameTo(cached)) cached else null
    }

    private fun key(item: IndexEntry.Item): String =
        MessageDigest.getInstance("SHA-1").digest("${item.file.path}|${item.size}|${item.modified}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CandleServer"
        val SIZE = Size(320, 320)
        const val QUALITY = 85
    }
}
