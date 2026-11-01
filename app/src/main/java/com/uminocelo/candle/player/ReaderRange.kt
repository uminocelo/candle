package com.uminocelo.candle.player

import com.uminocelo.candle.media.MediaStream
import java.io.EOFException
import java.io.IOException

/**
 * Reads one byte range of a [MediaStream.Reader] front to back: the core of
 * [ReaderDataSource], kept free of Media3 and Android types so it can be unit tested.
 *
 * The player asks for a few KB at a time; over SMB every reader call is a network
 * round-trip, so the range reads ahead in blocks of [blockSize] bytes.
 */
class ReaderRange(
    private val reader: MediaStream.Reader,
    start: Long,
    /** Bytes wanted, or null for "to the end of the file". */
    length: Long?,
    blockSize: Int = DEFAULT_BLOCK_SIZE,
) {
    /** Next byte of the file to fetch from the reader. */
    private var fetchPosition = start
    private val block = ByteArray(blockSize)
    private var blockStart = 0
    private var blockEnd = 0
    private var readerDone = false

    /** Bytes left in the range, or null when the reader does not know its length. */
    var remaining: Long? = resolveLength(start, length)
        private set

    private fun resolveLength(start: Long, length: Long?): Long? {
        val total = reader.length
        if (total != null && start > total) throw EOFException("position $start is past the end ($total bytes)")
        return length ?: total?.let { it - start }
    }

    /**
     * Copies up to [length] bytes into [buffer]. Returns the count, or [END] once the
     * range or the file is done. A reader failure (dropped connection) is rethrown.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val left = remaining
        if (left == 0L) return END
        if (blockStart == blockEnd && !fill(left)) {
            if (left != null) throw EOFException("file ended $left bytes early")
            return END
        }
        val count = minOf(length, blockEnd - blockStart)
        System.arraycopy(block, blockStart, buffer, offset, count)
        blockStart += count
        remaining = left?.minus(count)
        return count
    }

    /** Fetches the next block, never past the range. Returns false at the end of the file. */
    private fun fill(left: Long?): Boolean {
        if (readerDone) return false
        val wanted = if (left == null) block.size else minOf(block.size.toLong(), left).toInt()
        val count = reader.read(fetchPosition, block, 0, wanted)
        if (count == END) {
            readerDone = true
            return false
        }
        if (count <= 0) throw IOException("reader returned $count")
        fetchPosition += count
        blockStart = 0
        blockEnd = count
        return true
    }

    companion object {
        const val END = -1
        const val DEFAULT_BLOCK_SIZE = 512 * 1024
    }
}
