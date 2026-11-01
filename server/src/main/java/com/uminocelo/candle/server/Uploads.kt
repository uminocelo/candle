package com.uminocelo.candle.server

import android.annotation.SuppressLint
import com.uminocelo.candle.http.HttpError
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Writes uploaded files into a shared folder, or [dir] ("Candle Uploads") when none is
 * given. The body goes to a hidden
 * temporary file first and is renamed only once complete, so an interrupted upload
 * leaves nothing behind and the index never shows half a film.
 */
@SuppressLint("UsableSpace") // see freeBytes
class Uploads(
    private val dir: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val keepFreeBytes: Long = KEEP_FREE_BYTES,
    /** Free bytes on the storage. Cached data Android could clear is not counted: the margin is generous anyway. */
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    /** Saves [length] bytes of [body] as [name] (made safe and unique) in [into] and returns the file. */
    fun save(name: String, length: Long, body: InputStream, into: File = dir): File {
        val clean = safeName(name) ?: throw HttpError(400, "not a usable file name")
        if (length <= 0) throw HttpError(411, "the upload needs a Content-Length")
        if (length > maxFileBytes) throw HttpError(413, "files can be at most ${maxFileBytes / GB} GB")
        if (!into.isDirectory && !into.mkdirs()) throw IOException("cannot create $into")
        if (freeBytes(into) - length < keepFreeBytes) throw HttpError(507, "not enough space on the phone")

        val temp = File.createTempFile(".upload-", ".part", into)
        try {
            var written = 0L
            temp.outputStream().buffered(BUFFER).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                }
            }
            if (written != length) throw IOException("upload cut off after $written of $length bytes")
            synchronized(this) {
                val target = unique(into, clean)
                if (!temp.renameTo(target)) throw IOException("cannot save $target")
                return target
            }
        } finally {
            temp.delete() // after a successful rename there is nothing left to delete
        }
    }

    /** [name], or `name (1).ext`, `name (2).ext`… when taken. */
    private fun unique(dir: File, name: String): File {
        val base = name.substringBeforeLast('.').takeIf { '.' in name } ?: name
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }

    companion object {
        const val FOLDER = "Candle Uploads"
        private const val GB = 1024L * 1024 * 1024
        const val MAX_FILE_BYTES = 8 * GB
        const val KEEP_FREE_BYTES = 2 * GB
        private const val BUFFER = 256 * 1024

        /**
         * The last path segment of [name], without control characters or a leading dot
         * (hidden files would never be shared); null when nothing usable is left.
         */
        fun safeName(name: String): String? {
            val last = name.replace('\\', '/').substringAfterLast('/')
            val clean = last.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }.trim().trimStart('.').trim()
            return clean.takeIf { it.isNotEmpty() && it.length <= 200 }
        }
    }
}
