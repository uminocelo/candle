package com.uminocelo.candle.smb

import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.media.sortedForBrowsing
import com.uminocelo.candle.player.PlaybackTarget
import com.uminocelo.candle.player.ReaderRange
import com.uminocelo.candle.resume.ResumeEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException

/**
 * In-memory share: folders are paths ending in "/", files map to their bytes.
 * [dropAfterBytes] makes reads fail once that many bytes were served, like a dropped connection.
 */
class FakeSmbShare(
    private val files: Map<String, ByteArray>,
    private val folders: Set<String> = emptySet(),
    private val hidden: Set<String> = emptySet(),
    var dropAfterBytes: Long = Long.MAX_VALUE,
) : SmbShare {
    var reads = 0
    var served = 0L

    override fun list(path: String): List<SmbEntry> {
        if (path.isNotEmpty() && path !in folders) throw IOException("STATUS_OBJECT_NAME_NOT_FOUND: $path")
        val children = (files.keys + folders).filter { SmbPaths.parent(it) == path }
        return children.map { child ->
            SmbEntry(SmbPaths.name(child), child in folders, files[child]?.size?.toLong() ?: 0, hidden = child in hidden)
        }
    }

    override fun open(path: String): SmbFile {
        val bytes = files[path] ?: throw IOException("STATUS_OBJECT_NAME_NOT_FOUND: $path")
        return object : SmbFile {
            override val length = bytes.size.toLong()

            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (served >= dropAfterBytes) throw IOException("connection reset")
                if (position >= bytes.size) return -1
                val count = minOf(length.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, count)
                served += count
                return count
            }

            override fun close() = Unit
        }
    }
}

private val SERVER = SmbServer("srv1", "nas", "Media", "alex")
private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

/** Reads a whole range with small player-sized reads. */
private fun ReaderRange.readAll(chunk: Int = 1000): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(chunk)
    while (true) {
        val count = read(buffer, 0, chunk)
        if (count == ReaderRange.END) return out.toByteArray()
        out.write(buffer, 0, count)
    }
}

class SmbMediaSourceTest {
    private val share = FakeSmbShare(
        files = mapOf("Films/a.mkv" to bytes(10), "Films/.DS_Store" to bytes(1), "song.mp3" to bytes(3), "desktop.ini" to bytes(1)),
        folders = setOf("Films", "Films/Kids", "\$RECYCLE.BIN"),
        hidden = setOf("desktop.ini", "\$RECYCLE.BIN"),
    )
    private val source = SmbMediaSource(SERVER) { share }

    @Test
    fun `lists the share root, skipping hidden and system entries`() {
        val items = source.list(MediaSource.ROOT).sortedForBrowsing()
        assertEquals(listOf("Films", "song.mp3"), items.map { it.name })
        assertEquals(MediaItem.folder("Films", "Films"), items[0])
        assertEquals(MediaItem.file("song.mp3", "song.mp3", 3), items[1])
    }

    @Test
    fun `subfolders use share-relative paths and skip dot-files`() {
        val films = source.list("Films").sortedForBrowsing()
        assertEquals(listOf("Kids" to "Films/Kids", "a.mkv" to "Films/a.mkv"), films.map { it.name to it.path })
        assertEquals(MediaType.VIDEO, films[1].type)
    }

    @Test
    fun `a missing folder is an IO error`() {
        assertThrows(IOException::class.java) { source.list("Nope") }
    }

    @Test
    fun `files open as readers with their length`() {
        val reader = source.open(MediaItem.file("a.mkv", "Films/a.mkv")) as MediaStream.Reader
        assertEquals(10L, reader.length)
        assertArrayEquals(bytes(10), ReaderRange(reader, 0, null).readAll())
        assertThrows(IllegalArgumentException::class.java) { source.open(MediaItem.folder("Films", "Films")) }
    }

    @Test
    fun `source id and name come from the server`() {
        assertEquals("smb:srv1", source.id)
        assertEquals("Media on nas", source.displayName)
    }
}

class ReaderRangeTest {
    private fun reader(size: Int, dropAfter: Long = Long.MAX_VALUE): Pair<FakeSmbShare, MediaStream.Reader> {
        val share = FakeSmbShare(mapOf("f" to bytes(size)), dropAfterBytes = dropAfter)
        return share to (SmbMediaSource(SERVER) { share }.open(MediaItem.file("f", "f")) as MediaStream.Reader)
    }

    @Test
    fun `reads a range from the middle`() {
        val (_, r) = reader(10_000)
        val range = ReaderRange(r, start = 2_000, length = 3_000, blockSize = 1_024)
        assertEquals(3_000L, range.remaining)
        assertArrayEquals(bytes(10_000).copyOfRange(2_000, 5_000), range.readAll(chunk = 700))
        assertEquals(0L, range.remaining)
    }

    @Test
    fun `an open-ended range runs to the end of the file, then reports END`() {
        val (_, r) = reader(5_000)
        val range = ReaderRange(r, start = 4_000, length = null, blockSize = 1_024)
        assertEquals(1_000L, range.remaining)
        assertEquals(1_000, range.readAll().size)
        assertEquals(ReaderRange.END, range.read(ByteArray(10), 0, 10))
    }

    @Test
    fun `seeking to the very end gives an empty range, past it is an error`() {
        val (_, r) = reader(100)
        assertEquals(ReaderRange.END, ReaderRange(r, 100, null).read(ByteArray(1), 0, 1))
        assertThrows(EOFException::class.java) { ReaderRange(r, 101, null) }
    }

    @Test
    fun `a file shorter than the requested range is an error, not silent truncation`() {
        val (_, r) = reader(100)
        val range = ReaderRange(r, start = 50, length = 80)
        assertThrows(EOFException::class.java) { range.readAll() }
    }

    @Test
    fun `a dropped connection surfaces as an IO error`() {
        val (_, r) = reader(10_000, dropAfter = 3_000)
        val range = ReaderRange(r, 0, null, blockSize = 1_024)
        val error = assertThrows(IOException::class.java) { range.readAll() }
        assertEquals("connection reset", error.message)
    }

    @Test
    fun `small player reads are served from read-ahead blocks`() {
        val (share, r) = reader(100_000)
        ReaderRange(r, 0, null, blockSize = 32 * 1024).readAll(chunk = 1_000)
        // 100 player reads, but only ceil(100000 / 32768) = 4 trips to the share.
        assertEquals(4, share.reads)
    }

    @Test
    fun `a zero-length read returns 0 without touching the share`() {
        val (share, r) = reader(10)
        assertEquals(0, ReaderRange(r, 0, null).read(ByteArray(1), 0, 0))
        assertEquals(0, share.reads)
    }
}

class SmbServerTest {
    @Test
    fun `accepts the ways people type a share`() {
        val expected = SmbServer("id", "nas", "Media", "me")
        assertEquals(expected, SmbServer.fromInput("id", "nas", "Media", "me"))
        assertEquals(expected, SmbServer.fromInput("id", " \\\\nas\\Media ", "", " me "))
        assertEquals(expected, SmbServer.fromInput("id", "smb://nas/Media/Films", "", "me"))
        assertEquals(expected, SmbServer.fromInput("id", "nas", "/Media/", "me"))
        assertEquals(SmbServer("id", "192.0.2.10", "Media", ""), SmbServer.fromInput("id", "192.0.2.10", "Media", ""))
    }

    @Test
    fun `host and share are required`() {
        assertNull(SmbServer.fromInput("id", "", "Media", "me"))
        assertNull(SmbServer.fromInput("id", "nas", "", "me"))
    }

    @Test
    fun `source ids round-trip`() {
        assertEquals("smb:srv1", SERVER.sourceId)
        assertEquals("srv1", SmbServer.idFromSourceId("smb:srv1"))
        assertNull(SmbServer.idFromSourceId("file"))
        assertNull(SmbServer.idFromSourceId("smb:"))
    }

    @Test
    fun `smb playback targets carry an encoded smb URI and resume through the server id`() {
        val target = PlaybackTarget.smb("srv1", "Films/Kids/My Movie #1.mkv")
        assertEquals("smb:srv1", target.sourceId)
        assertEquals("Films/Kids/My Movie #1.mkv", target.path)
        assertEquals("My Movie #1", target.title)
        assertEquals("smb://srv1/Films/Kids/My%20Movie%20%231.mkv", target.uri)

        val entry = ResumeEntry("smb:srv1", "Films/Kids/My Movie #1.mkv", "Saved title", 60_000, 600_000, 1)
        assertEquals(target.copy(title = "Saved title"), PlaybackTarget.forEntry(entry))
        assertTrue(PlaybackTarget.forEntry(entry.copy(sourceId = "ftp:x")) == null)
    }
}
