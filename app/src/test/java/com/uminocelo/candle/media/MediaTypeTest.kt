package com.uminocelo.candle.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypeTest {
    @Test
    fun `classifies common media extensions`() {
        assertEquals(MediaType.VIDEO, MediaType.fromFileName("movie.mp4"))
        assertEquals(MediaType.VIDEO, MediaType.fromFileName("movie.mkv"))
        assertEquals(MediaType.VIDEO, MediaType.fromFileName("movie.avi"))
        assertEquals(MediaType.AUDIO, MediaType.fromFileName("song.mp3"))
        assertEquals(MediaType.AUDIO, MediaType.fromFileName("song.flac"))
        assertEquals(MediaType.SUBTITLE, MediaType.fromFileName("movie.srt"))
    }

    @Test
    fun `classifies photos and documents`() {
        for (name in listOf("a.jpg", "a.jpeg", "a.png", "a.webp", "a.heic", "a.heif", "a.gif", "a.bmp")) {
            assertEquals(name, MediaType.IMAGE, MediaType.fromFileName(name))
        }
        for (name in listOf("a.pdf", "a.epub", "a.mobi", "a.azw3", "a.cbz", "a.cbr", "a.djvu")) {
            assertEquals(name, MediaType.DOCUMENT, MediaType.fromFileName(name))
        }
        assertEquals(MediaType.IMAGE, MediaType.fromFileName("IMG_20261029_101500.JPG"))
    }

    @Test
    fun `ignores extension case`() {
        assertEquals(MediaType.VIDEO, MediaType.fromFileName("MOVIE.MKV"))
        assertEquals(MediaType.AUDIO, MediaType.fromFileName("Song.Mp3"))
    }

    @Test
    fun `uses only the last extension`() {
        assertEquals(MediaType.SUBTITLE, MediaType.fromFileName("movie.en.srt"))
        assertEquals(MediaType.VIDEO, MediaType.fromFileName("show.s01e02.1080p.mkv"))
    }

    @Test
    fun `unknown or missing extensions are OTHER`() {
        assertEquals(MediaType.OTHER, MediaType.fromFileName("notes.txt"))
        assertEquals(MediaType.OTHER, MediaType.fromFileName("README"))
        assertEquals(MediaType.OTHER, MediaType.fromFileName("trailing."))
        assertEquals(MediaType.OTHER, MediaType.fromFileName(".mkv"))
        assertEquals(MediaType.OTHER, MediaType.fromFileName(""))
    }
}
