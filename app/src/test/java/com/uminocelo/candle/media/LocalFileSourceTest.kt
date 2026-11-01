package com.uminocelo.candle.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LocalFileSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lists folders and files, skipping hidden ones`() {
        tmp.newFolder("Albums")
        tmp.newFile("song.mp3").writeBytes(ByteArray(3))
        tmp.newFile(".hidden.mp3")
        val source = LocalFileSource(tmp.root.path)

        val items = source.list(MediaSource.ROOT).sortedForBrowsing()
        assertEquals(listOf("Albums", "song.mp3"), items.map { it.name })
        assertEquals(MediaType.FOLDER, items[0].type)
        assertEquals(3L, items[1].size)
    }

    @Test
    fun `opens files as file URIs and rejects folders`() {
        val song = tmp.newFile("a b.mp3")
        val source = LocalFileSource(tmp.root.path)
        val item = source.list(tmp.root.path).single()

        assertEquals(MediaStream.Location(song.toURI().toString()), source.open(item))
        assertThrows(IllegalArgumentException::class.java) { source.open(MediaItem.folder("x", tmp.root.path)) }
    }

    @Test
    fun `missing folder is an IO error`() {
        assertThrows(IOException::class.java) { LocalFileSource(tmp.root.path).list(tmp.root.path + "/nope") }
    }
}
