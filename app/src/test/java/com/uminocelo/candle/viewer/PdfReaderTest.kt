package com.uminocelo.candle.viewer

import android.view.KeyEvent
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.player.PlayerKeys
import com.uminocelo.candle.player.ReaderAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A4 portrait pages (595×842 points) on a 1280×720 screen. */
private fun viewport(pages: Int = 3, start: Int = 0) = PdfViewport(pages, 1280, 720, start) { 595 to 842 }

class PdfViewportTest {
    @Test
    fun `opens at fit width, scrolls down the page, then turns to the next`() {
        val view = viewport()
        assertEquals(Zoom.WIDTH, view.zoom)
        assertEquals(1280.0, view.contentWidth, 0.01)
        var steps = 0
        while (view.page == 0) {
            assertTrue(view.down())
            steps++
        }
        assertEquals("a fit-width A4 page is about 2.5 screens tall", 3, steps)
        assertEquals(0.0, view.scrollY, 0.0)
    }

    @Test
    fun `the first and last pages are limits`() {
        val view = viewport(pages = 2)
        assertFalse("nothing above the first page", view.up())
        assertFalse(view.previousPage())
        assertTrue(view.nextPage())
        assertEquals(1, view.page)
        assertFalse("no page after the last", view.nextPage())
        while (view.down()) Unit
        assertEquals(1, view.page)
        assertEquals("stopped at the bottom of the last page", view.contentHeight - 720, view.scrollY, 0.01)
    }

    @Test
    fun `going up from the top of a page lands at the bottom of the previous one`() {
        val view = viewport(start = 1)
        assertTrue(view.up())
        assertEquals(0, view.page)
        assertEquals(view.contentHeight - 720, view.scrollY, 0.01)
    }

    @Test
    fun `left and right turn pages at fit width and pan when zoomed in`() {
        val view = viewport()
        assertTrue(view.right())
        assertEquals("nothing to pan: right is the next page", 1, view.page)
        view.nextZoom()
        assertEquals(Zoom.WIDTH_150, view.zoom)
        assertEquals("zoom keeps the middle in view", (view.contentWidth - 1280) / 2, view.scrollX, 0.01)
        assertTrue(view.right())
        assertEquals("panned, same page", 1, view.page)
        while (view.scrollX < view.contentWidth - 1280) view.right()
        assertTrue(view.right())
        assertEquals(2, view.page)
    }

    @Test
    fun `zoom steps from fit width to 200 percent, then the whole page, then back`() {
        val view = viewport()
        val seen = (1..4).map { view.nextZoom(); view.zoom }
        assertEquals(listOf(Zoom.WIDTH_150, Zoom.WIDTH_200, Zoom.PAGE, Zoom.WIDTH), seen)
        view.nextZoom(); view.nextZoom(); view.nextZoom()
        assertEquals(Zoom.PAGE, view.zoom)
        assertEquals("the whole page fits the screen's height", 720.0, view.contentHeight, 0.01)
        assertTrue("and is centred", view.offsetX > 0)
        assertFalse("the whole first page cannot scroll up", view.up())
    }

    @Test
    fun `a start outside the document is kept inside`() {
        assertEquals(2, viewport(pages = 3, start = 7).page)
        assertEquals(0, viewport(pages = 3, start = -2).page)
    }
}

class ReadingPositionsTest {
    private val values = HashMap<String, String>()
    private val positions = ReadingPositions(
        object : ReadingPositions.PageStore {
            override fun all(): Map<String, String> = HashMap(values)

            override fun put(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        },
        limit = 2,
    )

    @Test
    fun `a document reopens on its saved page`() {
        assertEquals("a new document starts at the first page", 0, positions.startPage("dlna:g", "/book.pdf", 300))
        positions.save("dlna:g", "/book.pdf", 41)
        assertEquals(41, positions.startPage("dlna:g", "/book.pdf", 300))
        assertEquals("another source's file of the same name is separate", 0, positions.startPage("usb:1", "/book.pdf", 300))
    }

    @Test
    fun `a saved page past the end of a shorter copy opens the last page`() {
        positions.save("s", "/b.pdf", 99)
        assertEquals(9, positions.startPage("s", "/b.pdf", 10))
    }

    @Test
    fun `only the most recent books are remembered`() {
        positions.save("s", "/1.pdf", 1, now = 1)
        positions.save("s", "/2.pdf", 2, now = 2)
        positions.save("s", "/3.pdf", 3, now = 3)
        assertEquals(0, positions.startPage("s", "/1.pdf", 10))
        assertEquals(3, positions.startPage("s", "/3.pdf", 10))
    }
}

class ReaderKeysTest {
    @Test
    fun `the remote's arrows move, OK zooms, media keys turn pages`() {
        assertEquals(ReaderAction.DOWN, PlayerKeys.readerAction(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ReaderAction.ZOOM, PlayerKeys.readerAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(ReaderAction.NEXT_PAGE, PlayerKeys.readerAction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(null, PlayerKeys.readerAction(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `only PDFs open in the reader`() {
        assertTrue(PdfReaderActivity.canRead(MediaItem.file("Book.PDF", "/Book.PDF")))
        assertFalse(PdfReaderActivity.canRead(MediaItem.file("Book.epub", "/Book.epub")))
    }
}
