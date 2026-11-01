package com.uminocelo.candle.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourceTest {
    private val movie = MediaItem.file("Movie.mkv", "/Movies/Movie.mkv", size = 1_000)
    private val source = FakeMediaSource(
        mapOf(
            MediaSource.ROOT to listOf(MediaItem.folder("Movies", "/Movies")),
            "/Movies" to listOf(movie),
        ),
    )

    @Test
    fun `lists the root by default and folders by path`() {
        assertEquals(listOf("Movies"), source.list().map { it.name })
        assertEquals(listOf(movie), source.list("/Movies"))
    }

    @Test
    fun `factory functions set the type from the name`() {
        assertTrue(MediaItem.folder("Movies", "/Movies").isFolder)
        assertEquals(MediaType.VIDEO, movie.type)
        assertEquals(1_000L, movie.size)
    }

    @Test
    fun `opening a file returns a stream and opening a folder is rejected`() {
        assertEquals(MediaStream.Location("fake:///Movies/Movie.mkv"), source.open(movie))
        assertThrows(IllegalArgumentException::class.java) {
            source.open(MediaItem.folder("Movies", "/Movies"))
        }
    }
}
