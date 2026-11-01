package com.uminocelo.candle.smb

import java.io.Closeable

/** One entry of a share folder listing. */
data class SmbEntry(val name: String, val isFolder: Boolean, val size: Long, val hidden: Boolean = false)

/** An open file on a share, read by offset. */
interface SmbFile : Closeable {
    val length: Long

    /** Reads up to [length] bytes at [position]; returns the count, or -1 at the end of the file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * A connected SMB share. Paths are share-relative with `/` separators; "" is the
 * share root. [SmbjShare] talks to a real server, tests use an in-memory fake.
 * Every call does network I/O and throws [java.io.IOException] when it fails.
 */
interface SmbShare {
    fun list(path: String): List<SmbEntry>

    fun open(path: String): SmbFile
}

object SmbPaths {
    /** "Films" + "a.mkv" → "Films/a.mkv"; the root is "". */
    fun child(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    /** smbj wants backslashes and no leading separator. */
    fun toSmb(path: String): String = path.trim('/').replace('/', '\\')

    /** The folder that holds [path] ("" for the share root). */
    fun parent(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun name(path: String): String = path.substringAfterLast('/')
}
