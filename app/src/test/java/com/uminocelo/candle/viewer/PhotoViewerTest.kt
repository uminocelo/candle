package com.uminocelo.candle.viewer

import android.view.KeyEvent
import com.uminocelo.candle.browse.PLAYABLE
import com.uminocelo.candle.browse.browsable
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.player.PhotoAction
import com.uminocelo.candle.player.PlayerKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPagerTest {
    @Test
    fun `pages forward and back, wrapping around at both ends`() {
        val pager = PhotoPager(3, start = 2)
        assertEquals(0, pager.next())
        assertEquals(1, pager.next())
        assertEquals(0, pager.previous())
        assertEquals(2, pager.previous())
        assertEquals(0, pager.upcoming)
    }

    @Test
    fun `the start is kept inside the folder`() {
        assertEquals(4, PhotoPager(5, start = 9).index)
        assertEquals(0, PhotoPager(5, start = -1).index)
    }

    @Test
    fun `OK starts and stops the slideshow, which needs two photos`() {
        val pager = PhotoPager(4, 0)
        assertTrue(pager.toggleSlideshow())
        assertTrue(pager.slideshow)
        assertFalse(pager.toggleSlideshow())
        val single = PhotoPager(1, 0)
        assertFalse(single.toggleSlideshow())
        assertEquals("one photo wraps onto itself", 0, single.next())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty folder has no pager`() {
        PhotoPager(0, 0)
    }
}

class FitSizeTest {
    @Test
    fun `a 12 MP photo decodes at screen size, landscape or portrait`() {
        assertEquals(960 to 720, fitSize(4032, 3024, 1280, 720))
        assertEquals(720 to 960, fitSize(3024, 4032, 1280, 720))
        assertEquals("a panorama is limited by its long side", 1280 to 320, fitSize(8000, 2000, 1280, 720))
    }

    @Test
    fun `small photos keep their size`() {
        assertEquals(640 to 480, fitSize(640, 480, 1280, 720))
        assertEquals(1 to 1, fitSize(1, 1, 1280, 720))
    }
}

class PhotoKeysTest {
    @Test
    fun `left and right page, OK toggles the slideshow, Back is left to the system`() {
        assertEquals(PhotoAction.PREVIOUS, PlayerKeys.photoAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PhotoAction.NEXT, PlayerKeys.photoAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PhotoAction.TOGGLE_SLIDESHOW, PlayerKeys.photoAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PhotoAction.TOGGLE_SLIDESHOW, PlayerKeys.photoAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(null, PlayerKeys.photoAction(KeyEvent.KEYCODE_BACK))
    }
}

class BrowsableTest {
    private val folder = listOf(
        MediaItem.file("b.jpg", "/b.jpg"), MediaItem.file("a10.jpg", "/a10.jpg"), MediaItem.file("a2.jpg", "/a2.jpg"),
        MediaItem.file("book.pdf", "/book.pdf"), MediaItem.file("film.mkv", "/film.mkv"), MediaItem.file("x.srt", "/x.srt"),
        MediaItem.folder("Album", "/Album"),
    )

    @Test
    fun `the projector shows photos and documents in folder order`() {
        assertEquals(listOf("Album", "a2.jpg", "a10.jpg", "b.jpg", "book.pdf", "film.mkv"), folder.browsable().map { it.name })
    }

    @Test
    fun `the phone page lists only what it can play`() {
        assertEquals(listOf("Album", "film.mkv"), folder.browsable(PLAYABLE).map { it.name })
    }
}
